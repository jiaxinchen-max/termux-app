#!/usr/bin/env python3
"""Exercise actual install control flow; isolate only network/package/driver operations."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ASSETS = Path(__file__).resolve().parents[1] / 'src/main/assets/local-games'
PIN = 'b711d1d9ab5537ee9085da8896dfe2115e791b33aa317fe37538f276c967d687'

class InstallTests(unittest.TestCase):
    def run_install(self, mode='zink', failure='', de='hyprland', theme='m3ww-v1', switch=False):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary); prefix = base / 'usr'; (prefix / 'bin').mkdir(parents=True); (prefix / 'tmp').mkdir()
            assets = base / 'assets'; assets.mkdir()
            for name in ['desktop_common.sh', 'desktop_rendering.sh', 'hyprland-components.sh', 'desktop_container_plasma_apt.sh', 'desktop_container_statx_setup.sh', 'desktop_container_run_config.sh', 'desktop_container_statx_aarch64.bin']:
                shutil.copyfile(ASSETS / name, assets / name)
            container = prefix / 'var/lib/proot-distro/containers/debian-1'; rootfs = container / 'rootfs'
            (rootfs / 'etc').mkdir(parents=True); (rootfs / 'etc/os-release').write_text('ID=debian\nVERSION_CODENAME=trixie\n')
            marker = container / '.desktop-de-hyprland'; marker.write_text('old marker'); Path(str(marker)+'-zink').write_text('old marker')
            source = (ASSETS / 'desktop_install.sh').read_text()
            # Real entry validation, marker logic, shared Mesa function and error propagation run.
            # External network/apt/Python operations are recorded stand-ins, not a GPU success claim.
            mocks = r'''
run() {
    printf '%s\n' "$*" >> "$FIXTURE_TRACE"
    case "$*" in
        *backup-mesa*) [ "$FIXTURE_FAIL" != backup ] || return 41; mkdir -p "${PREFIX}/var/lib/proot-distro/containers/debian-1/.hyprland-mesa-backup" ;;
        *desktop_install_mesa.py*) [ "$FIXTURE_FAIL" != checksum ] || return 42 ;;
        *dependencies=*) [ "$FIXTURE_FAIL" != ldd ] || return 43 ;;
        *"import gi;"*) [ "$FIXTURE_FAIL" != gi ] || return 44 ;;
        *"apt-mark showhold"*) printf 'preexisting-hold\n' ;;
    esac
    return 0
}
fetch_debs() { printf '%s\n' "$3" >> "$FIXTURE_TRACE"; printf '%s' "$PREFIX/tmp/component.tar.gz"; }
ensure_container_instance() { :; }
initialize_desktop() { printf initialization-verified >> "$FIXTURE_TRACE"; [ "$FIXTURE_FAIL" != initialization ] || exit 72; }
dpkg() { :; }
'''
            if de == 'gnome':
                source = source[:source.index('\nif [ "$DESKTOP_DE" = hyprland ]; then\n    log "Installing')]+mocks+'\nensure_gnome_turnip_icd debian-1\nINSTALL_DONE=1\nexit 0\n'
            else:
                source = source.replace('\nif [ "$DESKTOP_DE" = hyprland ]; then\n    log "Installing', '\n'+mocks+'\nif [ "$DESKTOP_DE" = hyprland ]; then\n    log "Installing')
            # Architecture is an external platform identity, not part of this fixture.
            (prefix / 'bin/uname').write_text('#!/bin/sh\nprintf aarch64\n'); (prefix / 'bin/uname').chmod(0o755)
            script = assets / 'desktop_install.sh'; script.write_text(source)
            spec = base / 'spec'
            values = dict(DESKTOP_MODE='proot', DESKTOP_DE=de, DESKTOP_SPEC_VERSION='3', DESKTOP_HOST_GL_MODE='zink' if de=='gnome' else 'system',
                DESKTOP_GUEST_GL_MODE='zink' if mode=='zink' else 'default', DESKTOP_PROOT_DISTRO='debian-1',
                DESKTOP_USB_STORAGE='off', DESKTOP_HYPRLAND_BACKEND='android',
                DESKTOP_HYPRLAND_COMPONENT_SWITCH='1' if switch else '0', DESKTOP_HYPRLAND_RUNTIME_ID='0.57.0-r7', DESKTOP_HYPRLAND_THEME_ID=theme, DESKTOP_HYPRLAND_HOST_GL_MODE='system',
                DESKTOP_HYPRLAND_CLIENT_GL_MODE='zink' if mode=='zink' else 'default',
                DESKTOP_RELEASE_TAG='termux-hwaccel-6.7-r4', DESKTOP_LOG_FILE=str(base/'log'),
                DESKTOP_PID_FILE=str(base/'pid'), DESKTOP_STATUS_FILE=str(base/'status'),
                DESKTOP_GNOME_BACKEND='x11', DESKTOP_GNOME_RENDERER='zink', DESKTOP_CONTAINER_CODENAME='trixie',
                DESKTOP_GNOME_DISPLAY_GL_MODE='zink', DESKTOP_GNOME_DISPLAY_BUFFER_MODE='dmabuf')
            spec.write_text(''.join(k+'='+v+'\n' for k,v in values.items()))
            trace=base/'trace'
            result = subprocess.run(['sh',str(script),str(spec),'--operation=install'], capture_output=True, text=True,
                env=dict(os.environ, PREFIX=str(prefix), HOME=str(base/'home'), FIXTURE_TRACE=str(trace), FIXTURE_FAIL=failure), timeout=10)
            return dict(code=result.returncode, output=result.stdout+result.stderr,
                trace=trace.read_text() if trace.exists() else '', base_marker=marker.exists(),
                zink_marker=Path(str(marker)+'-zink').read_text() if Path(str(marker)+'-zink').exists() else None,
                status=(base/'status').read_text())

    def testExplicitZinkInstallsCompletePinnedMesaAndWritesIdentityLast(self):
        result = self.run_install()
        self.assertEqual(0, result['code'], result['output'])
        self.assertIn(PIN, result['trace']); self.assertIn('backup-mesa', result['trace'])
        self.assertIn('desktop_install_mesa.py', result['trace']); self.assertIn('apt-mark hold', result['trace'])
        self.assertTrue(result['base_marker']); self.assertEqual(PIN+'\n', result['zink_marker'])
        self.assertEqual('exit:0\n', result['status'])

    def testEveryFailedMesaOrDiagnosticStageLeavesNoSuccessMarker(self):
        for stage, code in [('backup',41),('checksum',42),('ldd',43),('gi',44)]:
            with self.subTest(stage=stage):
                result = self.run_install(failure=stage)
                self.assertEqual(code, result['code'], result['output'])
                self.assertFalse(result['base_marker']); self.assertIsNone(result['zink_marker'])
                self.assertEqual('exit:'+str(code)+'\n', result['status'])

    def testDefaultModeSkipsMesaOverlayAndHardwareDiagnostic(self):
        result = self.run_install(mode='system')
        self.assertEqual(0, result['code'], result['output'])
        self.assertNotIn('desktop_install_mesa.py', result['trace']); self.assertNotIn('backup-mesa',result['trace'])
        self.assertTrue(result['base_marker']); self.assertIsNone(result['zink_marker'])

    def testClassicDoesNotInstallM3wwToolsOrRedownloadVerifiedRuntime(self):
        result = self.run_install(mode='system', theme='classic-r7')
        self.assertEqual(0, result['code'], result['output'])
        self.assertIn('xfce4-panel', result['trace'])
        self.assertNotIn('eww foot fish starship', result['trace'])
        self.assertNotIn('hyprland-android-runtime-aarch64', result['trace'])
        self.assertIn('desktop_hyprland_components.py install', result['trace'])

    def testThemeSwitchPreservesContainerReceiptsOnSuccessAndFailure(self):
        for failure, code in [('', 0), ('initialization', 72)]:
            with self.subTest(failure=failure):
                result = self.run_install(theme='classic-r7', switch=True, failure=failure)
                self.assertEqual(code, result['code'], result['output'])
                self.assertTrue(result['base_marker'])
                self.assertEqual('old marker', result['zink_marker'])
                self.assertNotIn('desktop_container_plasma_apt.sh', result['trace'])
                self.assertNotIn('desktop_install_mesa.py', result['trace'])
                self.assertEqual('exit:' + str(code) + '\n', result['status'])

    def testGnomeWrapperRetainsPinnedInstallerAndNoHyprlandBackup(self):
        result = self.run_install(de='gnome')
        self.assertEqual(0, result['code'], result['output'])
        self.assertIn(PIN,result['trace']); self.assertIn('desktop_install_mesa.py',result['trace'])
        self.assertNotIn('backup-mesa',result['trace'])

if __name__ == '__main__': unittest.main()
