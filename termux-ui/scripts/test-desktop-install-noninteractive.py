"""Run desktop install entry points against a real, isolated dpkg conffile conflict."""

import json
import os
from pathlib import Path
import pty
import shutil
import signal
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "termux-ui/src/main/assets/local-games"


@unittest.skipUnless(shutil.which("dpkg") and shutil.which("dpkg-deb"),
                     "requires Debian packaging tools")
class DesktopInstallNoninteractiveTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="desktop-conffile-")
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name)
        self.prefix = self.base / "usr"
        (self.prefix / "bin").mkdir(parents=True)
        (self.prefix / "tmp").mkdir()
        self.dpkg_root = self.base / "dpkg-root"
        self.dpkg_root.mkdir()
        self.dpkg = [shutil.which("dpkg"), "--force-not-root", "--root=" + str(self.dpkg_root),
                     "--log=" + str(self.base / "dpkg.log")]
        self.packages = []
        for version in ("1", "2"):
            source = self.base / ("package-" + version)
            (source / "DEBIAN").mkdir(parents=True)
            (source / "DEBIAN/control").write_text(
                "Package: desktop-conffile-fixture\nVersion: " + version + "\n"
                "Architecture: all\nMaintainer: Test <test@example.invalid>\n"
                "Description: Isolated desktop configuration conflict\n")
            (source / "DEBIAN/conffiles").write_text("/etc/tls/openssl.cnf\n")
            config = source / "etc/tls/openssl.cnf"
            config.parent.mkdir(parents=True)
            config.write_text("packaged-config-" + version + "\n")
            package = self.base / (version + ".deb")
            subprocess.run(["dpkg-deb", "--build", "--root-owner-group", str(source), str(package)],
                           check=True, capture_output=True)
            self.packages.append(package)
        subprocess.run(self.dpkg + ["--install", str(self.packages[0])],
                       check=True, capture_output=True)
        self.config = self.dpkg_root / "etc/tls/openssl.cnf"
        self.config.write_text("local-desktop-config\n")
        self.trace = self.base / "calls.jsonl"
        self.env = dict(os.environ, PREFIX=str(self.prefix), HOME=str(self.base / "home"),
                        TMPDIR=str(self.prefix / "tmp"), LC_ALL="C",
                        FIXTURE_TRACE=str(self.trace), FIXTURE_TARGET="apt", FIXTURE_STAGE="install",
                        FIXTURE_DPKG=json.dumps(self.dpkg), FIXTURE_DEB=str(self.packages[1]))
        # These wrappers replace network/package selection only. The conflict itself uses dpkg.
        wrapper = "#!/usr/bin/python3\n" + r'''
import json, os, pathlib, subprocess, sys
name = pathlib.Path(sys.argv[0]).name
args = sys.argv[1:]
with open(os.environ['FIXTURE_TRACE'], 'a') as trace:
    trace.write(json.dumps({'name':name, 'args':args,
                            'frontend':os.environ.get('DEBIAN_FRONTEND')})+'\n')
if name == 'uname': print('aarch64')
elif name == 'dpkg-query': sys.exit(1)
elif name == 'curl': pathlib.Path(args[args.index('-o')+1]).write_text('fixture download')
elif name == 'proot-distro': sys.exit(91)
elif name == os.environ['FIXTURE_TARGET'] and args[0] == os.environ['FIXTURE_STAGE']:
    flags = [arg.split('=', 1)[1] for arg in args if arg.startswith('Dpkg::Options::=')]
    code = subprocess.call(json.loads(os.environ['FIXTURE_DPKG']) + flags
                           + ['--install', os.environ['FIXTURE_DEB']])
    if code: sys.exit(code)
    print('CONFFILE_HANDLED', flush=True)
    # Stop before unrelated desktop provisioning; the installer must retain this failure code.
    sys.exit(37)
'''
        for name in ("uname", "dpkg-query", "curl", "pkg", "apt", "proot-distro"):
            command = self.prefix / "bin" / name
            command.write_text(wrapper)
            command.chmod(0o755)

    def without_input(self, command, timeout=3):
        master, slave = pty.openpty()
        process = subprocess.Popen(command, env=self.env, stdin=slave,
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   start_new_session=True)
        os.close(slave)
        blocked = False
        try:
            try:
                output = process.communicate(timeout=timeout)[0]
            except subprocess.TimeoutExpired:
                blocked = True
                os.killpg(process.pid, signal.SIGKILL)
                output = process.communicate()[0]
            return process.returncode, output.decode(errors="replace"), blocked
        finally:
            os.close(master)

    def run_install(self, mode="native", de="xfce4", operation="install", target="apt", stage="install"):
        self.env["FIXTURE_TARGET"] = target
        self.env["FIXTURE_STAGE"] = stage
        spec = self.base / "spec"
        values = dict(DESKTOP_MODE=mode, DESKTOP_DE=de,
                      DESKTOP_SPEC_VERSION="3", DESKTOP_PROOT_DISTRO="fixture",
                      DESKTOP_HOST_GL_MODE="llvmpipe" if mode == "proot" and de == "kwin_plasma" else "system",
                      DESKTOP_GUEST_GL_MODE="none" if mode == "native" else "llvmpipe" if de == "kwin_plasma" else "default",
                      DESKTOP_USB_STORAGE="off", DESKTOP_REFRESH_RATE="60",
                      DESKTOP_GNOME_BACKEND="android", DESKTOP_GNOME_RENDERER="system",
                      DESKTOP_LOG_FILE=str(self.base / "install.log"),
                      DESKTOP_PID_FILE=str(self.base / "pid"),
                      DESKTOP_STATUS_FILE=str(self.base / "status"),
                      DESKTOP_RELEASE_TAG="termux-hwaccel-6.6-r28")
        spec.write_text("".join(key + "='" + value + "'\n" for key, value in values.items()))
        command = ["sh", str(ASSETS / "desktop_install.sh"), str(spec), "--operation=" + operation]
        if operation == "host-prerequisites":
            command += ["--task-id=conflict", "--result-file=" + str(self.base / "result")]
        code, output, blocked = self.without_input(command)
        self.assertFalse(blocked, output)
        self.assertEqual(37, code, output)
        self.assertIn("CONFFILE_HANDLED", output)
        self.assertEqual("local-desktop-config\n", self.config.read_text())
        calls = [json.loads(line) for line in self.trace.read_text().splitlines()]
        installs = [call for call in calls if call["name"] in ("apt", "pkg")
                    and call["args"][0] in ("install", "update")]
        self.assertTrue(installs)
        self.assertTrue(all(call["frontend"] == "noninteractive" for call in installs), installs)
        self.assertFalse(any(call["name"] == "proot-distro" for call in calls), calls)
        if operation == "install":
            self.assertEqual("exit:37\n", (self.base / "status").read_text())
        else:
            result = (self.base / "result").read_text()
            self.assertIn("state=failed\nexitCode=37\n", result)

    def test_real_dpkg_without_strategy_waits_for_user(self):
        _, output, blocked = self.without_input(self.dpkg + ["--install", str(self.packages[1])], timeout=1)
        self.assertTrue(blocked, output)
        self.assertIn("*** openssl.cnf", output)

    def test_repository_setup_handles_conflict_without_input(self):
        self.run_install(target="pkg")

    def test_pkg_update_upgrade_handles_conflict_without_input(self):
        self.run_install(target="pkg", stage="update")

    def test_native_xfce_handles_conflict_and_preserves_failure(self):
        self.run_install()

    def test_native_plasma_handles_conflict_and_preserves_failure(self):
        self.run_install(de="kwin_plasma")

    def test_native_gnome_handles_conflict_and_preserves_failure(self):
        self.run_install(de="gnome")

    def test_proot_host_install_handles_conflict_without_creating_container(self):
        self.run_install(mode="proot")

    def test_restore_prerequisites_handles_conflict_and_reports_failure(self):
        self.run_install(mode="proot", operation="host-prerequisites")

    def test_restore_repository_setup_handles_conflict_and_reports_failure(self):
        self.run_install(mode="proot", operation="host-prerequisites", target="pkg")

    def test_restore_pkg_update_upgrade_handles_conflict_and_reports_failure(self):
        self.run_install(mode="proot", operation="host-prerequisites", target="pkg", stage="update")


if __name__ == "__main__":
    unittest.main(verbosity=2)
