"""Exercise the shipped installer with a local APT repository, never host dpkg."""

import getpass
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[4]
ASSETS = ROOT / "app/src/main/assets"
DEB_NAME = "termux-x11-nightly-1.04.10-0-all.deb"


@unittest.skipUnless(all(shutil.which(tool) for tool in
                         ("apt-get", "dpkg-deb", "dpkg-scanpackages")),
                     "requires APT and Debian packaging tools")
class X11BridgeInstallTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="x11 bridge ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.home = self.root / "home"
        self.staging = self.home / ".termux/tmp"
        self.staging.mkdir(parents=True)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.trace = self.root / "apt-trace.jsonl"
        real_apt = shutil.which("apt-get")
        wrapper = self.bin / "apt-get"
        wrapper.write_text("#!/usr/bin/python3\n" + f"""
import json, os, sys
with open(os.environ['TEST_APT_TRACE'], 'a') as output:
    output.write(json.dumps(sys.argv[1:]) + '\\n')
with open(os.environ['TEST_APT_TRACE']) as trace:
    call_number = len(trace.readlines())
if (os.environ.get('TEST_APT_FAIL_STAGE') == sys.argv[1]
        or os.environ.get('TEST_APT_FAIL_CALL') == str(call_number)):
    sys.exit(int(os.environ['TEST_APT_FAIL_CODE']))
arguments = ['--simulate'] if sys.argv[1] == 'install' else []
os.execv({real_apt!r}, [{real_apt!r}] + arguments + sys.argv[1:])
""")
        wrapper.chmod(0o755)
        query = self.bin / "dpkg-query"
        query.write_text("#!/bin/sh\n"
                         'if [ "${TEST_X11_REPO_INSTALLED:-yes}" = yes ]; then\n'
                         '    echo installed\n'
                         'else\n'
                         '    exit 1\n'
                         'fi\n')
        query.chmod(0o755)
        self.environment = dict(os.environ, HOME=str(self.home),
                                PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                                TEST_APT_TRACE=str(self.trace), LC_ALL="C")

    def configure_repository(self):
        repository = self.root / "repository"
        repository.mkdir()
        for version in ("2.44", "2.46"):
            package = self.root / ("keyboard-" + version)
            control = package / "DEBIAN/control"
            control.parent.mkdir(parents=True)
            control.write_text(f"""Package: xkeyboard-config
Version: {version}
Architecture: all
Maintainer: Test <test@example.invalid>
Description: Isolated keyboard configuration fixture
""")
            subprocess.run(["dpkg-deb", "--build", "--root-owner-group", str(package),
                            str(repository / ("keyboard-" + version + ".deb"))],
                           check=True, capture_output=True)
        repository_package = self.root / "x11-repo/DEBIAN/control"
        repository_package.parent.mkdir(parents=True)
        repository_package.write_text("""Package: x11-repo
Version: 1.0
Architecture: all
Maintainer: Test <test@example.invalid>
Description: X11 source activation fixture
""")
        subprocess.run(["dpkg-deb", "--build", "--root-owner-group",
                        str(repository_package.parent.parent), str(repository / "x11-repo.deb")],
                       check=True, capture_output=True)
        index = subprocess.run(["dpkg-scanpackages", "--multiversion", "."],
                               cwd=repository, check=True, capture_output=True).stdout
        (repository / "Packages").write_bytes(index)
        apt = self.root / "apt"
        for directory in ("lists/partial", "archives/partial"):
            (apt / directory).mkdir(parents=True)
        self.status = apt / "status"
        self.status.write_text("")
        sources = apt / "sources.list"
        sources.write_text(f"deb [trusted=yes] {repository.as_uri()} ./\n")
        config = apt / "apt.conf"
        config.write_text(f"""Dir "{apt}";
Dir::State::status "{self.status}";
Dir::State::lists "{apt / 'lists'}";
Dir::Cache::archives "{apt / 'archives'}";
Dir::Etc::sourcelist "{sources}";
Dir::Etc::sourceparts "-";
Dir::Etc::main "-";
Dir::Etc::parts "-";
APT::Architecture "arm64";
APT::Architectures {{ "arm64"; }};
APT::Sandbox::User "{getpass.getuser()}";
Acquire::Languages "none";
Debug::NoLocking "true";
""")
        self.environment["APT_CONFIG"] = str(config)

    def install(self):
        shutil.copy2(ASSETS / "install", self.staging / "install")
        shutil.copy2(ASSETS / DEB_NAME, self.staging / DEB_NAME)
        result = subprocess.run(["sh", str(self.staging / "install")],
                                env=self.environment, capture_output=True, text=True,
                                timeout=30)
        self.assertFalse((self.staging / "install").exists())
        self.assertFalse((self.staging / DEB_NAME).exists())
        return result

    def calls(self):
        return [json.loads(line) for line in self.trace.read_text().splitlines()]

    def test_real_apt_resolves_latest_dependency_and_forces_local_version(self):
        self.configure_repository()
        for installed in (None, "1.03.10-0", "1.04.10-0", "1.05.10-0"):
            with self.subTest(installed=installed):
                status = """Package: xkeyboard-config
Status: install ok installed
Architecture: all
Version: 2.44
Description: Older installed keyboard fixture

"""
                if installed:
                    status += f"""Package: termux-x11-nightly
Status: install ok installed
Architecture: all
Version: {installed}
Depends: xkeyboard-config
Description: Installed bridge fixture

"""
                self.status.write_text(status)
                self.trace.unlink(missing_ok=True)
                result = self.install()
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertRegex(result.stdout, r"Inst xkeyboard-config \[2\.44\] \(2\.46 ")
                bridge_line = next(line for line in result.stdout.splitlines()
                                   if line.startswith("Inst termux-x11-nightly "))
                self.assertIn("(1.04.10-0 ", bridge_line)
                if installed:
                    self.assertIn("[" + installed + "]", bridge_line)
                self.assertEqual(["update", "install"], [call[0] for call in self.calls()])

    def test_missing_x11_source_is_installed_and_refreshed_before_bridge(self):
        self.configure_repository()
        self.environment["TEST_X11_REPO_INSTALLED"] = "no"
        result = self.install()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(["update", "install", "update", "install"],
                         [call[0] for call in self.calls()])
        self.assertEqual("x11-repo", self.calls()[1][-1])
        self.assertIn("Inst x11-repo ", result.stdout)
        self.assertIn("Inst xkeyboard-config ", result.stdout)
        self.assertIn("Inst termux-x11-nightly ", result.stdout)

    def test_source_setup_failure_stops_before_bridge_installation(self):
        self.configure_repository()
        self.environment["TEST_X11_REPO_INSTALLED"] = "no"
        for call_number in (2, 3):
            with self.subTest(failing_call=call_number):
                self.trace.unlink(missing_ok=True)
                self.environment.update(TEST_APT_FAIL_CALL=str(call_number),
                                        TEST_APT_FAIL_CODE="23")
                result = self.install()
                self.assertEqual(23, result.returncode, result.stdout + result.stderr)
                self.assertEqual(call_number, len(self.calls()))
                self.assertFalse(any(DEB_NAME in arg for call in self.calls() for arg in call))

    def test_update_failure_stops_before_installation(self):
        self.environment.update(TEST_APT_FAIL_STAGE="update", TEST_APT_FAIL_CODE="17")
        result = self.install()
        self.assertEqual(17, result.returncode, result.stdout + result.stderr)
        self.assertEqual(["update"], [call[0] for call in self.calls()])

    def test_installation_failure_is_returned(self):
        self.configure_repository()
        self.environment.update(TEST_APT_FAIL_STAGE="install", TEST_APT_FAIL_CODE="19")
        result = self.install()
        self.assertEqual(19, result.returncode, result.stdout + result.stderr)
        self.assertEqual(["update", "install"], [call[0] for call in self.calls()])

    def test_package_metadata_and_launcher_permissions(self):
        metadata = subprocess.check_output(["dpkg-deb", "--field", str(ASSETS / DEB_NAME)],
                                           text=True)
        fields = dict(line.split(": ", 1) for line in metadata.splitlines() if ": " in line)
        self.assertEqual("1.04.10-0", fields["Version"])
        self.assertEqual("xkeyboard-config", fields["Depends"])
        payload = subprocess.check_output(["dpkg-deb", "--fsys-tarfile", str(ASSETS / DEB_NAME)])
        prefix = "./data/data/com.termux/files/usr/"
        with tarfile.open(fileobj=io.BytesIO(payload)) as archive:
            for name in ("startxserver", "stopserver", "termux-x11", "termux-display",
                         "termux-x11-preference"):
                self.assertEqual(0o755, archive.getmember(prefix + "bin/" + name).mode)
            for directory in ("termux-x11", "termux-display"):
                member = archive.getmember(prefix + "libexec/" + directory + "/loader.apk")
                self.assertGreater(member.size, 0)
        controls = subprocess.check_output(["dpkg-deb", "--ctrl-tarfile", str(ASSETS / DEB_NAME)])
        with tarfile.open(fileobj=io.BytesIO(controls)) as archive:
            self.assertEqual(0o755, archive.getmember("./postinst").mode)
        self.assertEqual((ROOT / "install").read_bytes(), (ASSETS / "install").read_bytes())
        self.assertEqual([DEB_NAME], sorted(path.name for path in ASSETS.glob("*.deb")))
        self.assertEqual([], list(ASSETS.glob("*.pkg.tar.xz")))


if __name__ == "__main__":
    unittest.main(verbosity=2)
