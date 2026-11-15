#!/usr/bin/env python3
"""Execute catalog preparation with isolated package/network/cache commands."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'termux-ui/src/main/assets/local-games'


class CatalogPreparationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        (self.base / 'os-release').write_text('ID=ubuntu\n')
        script = (ASSETS / 'desktop_container_discover_catalog.sh').read_text()
        script = script.replace('export PATH=', '# isolated PATH=')
        script = script.replace('. /etc/os-release', '. "' + str(self.base / 'os-release') + '"')
        (self.base / 'prepare.sh').write_text(script)
        (self.base / 'desktop_container_statx_setup.sh').write_text(
            'run_proot_config() { shift; "$@"; }\n')
        (self.base / 'desktop_container_apt_sources.sh').write_text(
            'configure_ubuntu_apt_sources() { echo sources >> "$CALLS"; }\n')
        for name, variable in [('apt-get', 'APT_EXIT'), ('appstreamcli', 'CATALOG_EXIT')]:
            target = self.base / name
            target.write_text('#!/bin/sh\necho "' + name + ' $*" >> "$CALLS"\nexit "${' + variable + ':-0}"\n')
            target.chmod(0o700)

    def run_helper(self, *, args=('--statx-backend=proc-fdinfo',), **env):
        result = subprocess.run(['sh', str(self.base / 'prepare.sh'), *args],
            env=dict(os.environ, PATH=str(self.base) + ':/usr/bin:/bin',
                     CALLS=str(self.base / 'calls'), **env),
            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=5)
        calls = self.base / 'calls'
        return result, calls.read_text().splitlines() if calls.exists() else []

    def test_success_publishes_os_catalog_after_strict_update(self):
        result, calls = self.run_helper()
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertEqual(calls, ['sources', 'apt-get update --error-on=any',
                                 'appstreamcli refresh --force --source=os'])

    def test_update_error_does_not_publish_catalog(self):
        result, calls = self.run_helper(APT_EXIT='100')
        self.assertEqual(result.returncode, 100)
        self.assertFalse(any(c.startswith('appstreamcli') for c in calls))

    def test_catalog_error_is_returned(self):
        result, calls = self.run_helper(CATALOG_EXIT='74')
        self.assertEqual(result.returncode, 74)
        self.assertEqual(calls[-1], 'appstreamcli refresh --force --source=os')

    def test_non_ubuntu_has_no_side_effects(self):
        (self.base / 'os-release').write_text('ID=debian\n')
        result, calls = self.run_helper()
        self.assertEqual(result.returncode, 64)
        self.assertEqual(calls, [])

    def test_missing_explicit_backend_has_no_side_effects(self):
        result, calls = self.run_helper(args=())
        self.assertEqual(result.returncode, 64)
        self.assertEqual(calls, [])

    def test_missing_cli_fails_before_network(self):
        (self.base / 'appstreamcli').unlink()
        # A controlled command lookup avoids depending on the host's installed packages.
        p = self.base / 'prepare.sh'
        p.write_text(p.read_text().replace('command -v appstreamcli', 'false'))
        result, calls = self.run_helper()
        self.assertEqual(result.returncode, 69)
        self.assertEqual(calls, [])


if __name__ == '__main__':
    unittest.main()
