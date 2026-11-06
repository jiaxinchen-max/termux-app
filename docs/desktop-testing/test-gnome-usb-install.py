#!/usr/bin/env python3
"""Execute the real install script with isolated package/network commands.

No device, network, host package manager, native compilation or source rewriting.
"""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'termux-ui/src/main/assets/local-games'

MOCK = r'''
import json, os, pathlib, re, sys
case=pathlib.Path(os.environ['INSTALL_FIXTURE'])
state=json.loads((case/'fixture.json').read_text())
command=pathlib.Path(sys.argv[0]).name
args=sys.argv[1:]
with (case/'calls.jsonl').open('a') as out: out.write(json.dumps([command,*args])+'\n')
if command=='uname': print('aarch64')
elif command=='curl':
    if state.get('curl_error'): sys.exit(state['curl_error'])
    pathlib.Path(args[args.index('-o')+1]).write_text('mocked downloaded payload')
elif command=='sha256sum':
    target=pathlib.Path(args[0]); digest=state['digests'].get(target.name)
    if digest is None:
        digest=next((d for p,f,d in state['host_files'] if target==case/'usr'/f),None)
    if digest is None: sys.exit(92)
    if target.name==state.get('bad_digest'): digest='0'*64
    print(digest+'  '+str(target))
elif command=='pkg':
    if state.get('pkg_error'): sys.exit(state['pkg_error'])
elif command=='apt':
    if state.get('apt_error'):
        print('E: fixture dependency failure',file=sys.stderr);sys.exit(state['apt_error'])
    if 'pulseaudio' in args:
        for name in ('pulseaudio','pactl'):
            target=case/'usr/bin'/name
            target.write_text('#!/bin/sh\nexit 0\n');target.chmod(0o700)
elif command=='dpkg-query':
    package=args[-1]
    if package not in state['versions']: sys.exit(1)
    value=state['versions'][package]
    # -f=${Status} is a single argument, rather than a separate format token.
    if any('${Status}' in arg for arg in args):
        value=state.get('package_statuses',{}).get(package,'install ok installed')
    print(value,end='')
elif command=='dpkg':
    if args[0]=='--audit':
        if state.get('audit_output'): print(state['audit_output'])
        sys.exit(state.get('audit_error',0))
    if args[0]=='--verify':
        if args[1]==state.get('corrupt_package'): print('??5?????? changed');sys.exit(1)
    if args[0]=='--compare-versions':
        left,op,right=args[1:]
        version=lambda s:tuple(map(int,re.findall(r'\d+',s)))
        sys.exit(0 if (version(left)>=version(right) if op=='ge' else version(left)<version(right)) else 1)
elif command=='apt-mark':
    if state.get('hold_error'): sys.exit(state['hold_error'])
    if args and args[0]=='hold':
        statuses=state.setdefault('package_statuses',{})
        for package in args[1:]: statuses[package]=state.get('status_after_hold','hold ok installed')
        (case/'fixture.json').write_text(json.dumps(state))
elif command=='python3':
    # proot-distro module probe is covered separately by test-desktop-restore-prerequisites.sh.
    if len(args)>2 and args[:3]==['-I','-B','-c']: sys.exit(0)
    if len(args)>2 and pathlib.Path(args[2]).name=='desktop_install_mesa.py': sys.exit(state.get('mesa_helper_error',0))
    sys.exit(93)
elif command=='proot-distro':
    if args==['install']: print('-n, --name');sys.exit(64)
    if args and args[0]=='install':
        alias=args[args.index('-n')+1];release=args[-1].split(':')[-1]
        root=case/'usr/var/lib/proot-distro/containers'/alias/'rootfs'
        (root/'etc').mkdir(parents=True,exist_ok=True)
        (root/'etc/os-release').write_text('ID='+state.get('actual_distro_id','debian')+'\nVERSION_CODENAME='+state.get('actual_codename',release)+'\n')
    elif args and args[0]=='login': sys.exit(state.get('container_apt_error',0))
elif command=='tar':
    # No real archive is extracted by this fixture.
    destination=pathlib.Path(args[args.index('-C')+1])
    destination.mkdir(parents=True,exist_ok=True)
'''


class InstallContract(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='gnome-install-contract-', dir='/tmp')
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        (self.base/'usr/bin').mkdir(parents=True)
        (self.base/'usr/tmp').mkdir()
        (self.base/'state').mkdir()
        (self.base/'home').mkdir()
        source = (ASSETS/'desktop_install.sh').read_text()
        digests = dict(re.findall(r'([A-Za-z0-9._+~-]+(?:\.deb|\.tar\.gz)) ([a-f0-9]{64})', source))
        host_block = re.search(r"^HOST_FILES='(.*?)'", source, re.M | re.S)
        host_files = [line.split() for line in host_block[1].splitlines() if line.strip()]
        versions = {'proot':'5.1.107', 'proot-distro':'5.10.3'}
        for name in digests:
            if not name.endswith('.deb'): continue
            package, version, abi = name.split('_')
            versions[package] = version
        self.state = dict(digests=digests, versions=versions, host_files=host_files)
        for path in ASSETS.glob('desktop_*'):
            if path.is_file(): shutil.copy2(path, self.base/path.name)
        for name in ('uname','curl','sha256sum','pkg','apt','dpkg','dpkg-query','apt-mark','proot','proot-distro','python3','tar','pulseaudio','pactl'):
            target = self.base/'usr/bin'/name
            target.write_text('#!'+sys.executable+'\n'+MOCK)
            target.chmod(0o700)
        for package, relative, digest in host_files:
            target = self.base/'usr'/relative
            target.parent.mkdir(parents=True, exist_ok=True)
            if not target.exists(): target.touch()

    def run_install(self, *, de='gnome', mode='native', renderer='system', usb='off', rate='60',
                    overrides=None, operation='install', **state):
        self.state.update(state)
        (self.base/'fixture.json').write_text(json.dumps(self.state))
        (self.base/'calls.jsonl').write_text('')
        values = dict(DESKTOP_ID='fixture', DESKTOP_MODE=mode, DESKTOP_DE=de,
            DESKTOP_GL_MODE=renderer, DESKTOP_USB_STORAGE=usb, DESKTOP_REFRESH_RATE=rate,
            DESKTOP_GNOME_BACKEND='android' if mode=='native' else 'x11',
            DESKTOP_GNOME_RENDERER=renderer, DESKTOP_CONTAINER_CODENAME='trixie',
            DESKTOP_PROOT_DISTRO='debian-'+de.replace('_','-')+'-1',
            DESKTOP_LOG_FILE=str(self.base/'install.log'), DESKTOP_PID_FILE=str(self.base/'state/pid'),
            DESKTOP_STATUS_FILE=str(self.base/'state/status'), DESKTOP_RELEASE_TAG='termux-hwaccel-6.6-r28')
        if de=='gnome' and mode=='proot':
            values['DESKTOP_GNOME_DISPLAY_GL_MODE']='zink' if renderer=='zink' else 'system'
            values['DESKTOP_GNOME_DISPLAY_BUFFER_MODE']='dmabuf' if renderer=='zink' else 'native'
        for key,value in (overrides or {}).items():
            if value is None: values.pop(key,None)
            else: values[key]=value
        (self.base/'spec').write_text(''.join(k+'='+v+'\n' for k,v in values.items()))
        command = ['sh',str(self.base/'desktop_install.sh'),str(self.base/'spec'),'--operation='+operation]
        if operation=='host-prerequisites':
            command += ['--task-id=fixture','--result-file='+str(self.base/'result')]
        env = dict(os.environ,PREFIX=str(self.base/'usr'),HOME=str(self.base/'home'),
            TMPDIR=str(self.base/'usr/tmp'),INSTALL_FIXTURE=str(self.base))
        result = subprocess.run(command,env=env,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=25)
        calls = [json.loads(line) for line in (self.base/'calls.jsonl').read_text().splitlines()]
        return result,calls

    def rejected_before_package_work(self, **kwargs):
        result,calls = self.run_install(**kwargs)
        self.assertEqual(64,result.returncode,result.stdout)
        self.assertFalse(calls,calls)
        self.assertEqual('exit:64\n',(self.base/'state/status').read_text())

    def test_missing_invalid_or_mismatched_explicit_gnome_modes_are_rejected(self):
        for override in ({'DESKTOP_GNOME_BACKEND':None}, {'DESKTOP_GNOME_RENDERER':None},
                         {'DESKTOP_GNOME_BACKEND':'x11'}, {'DESKTOP_GNOME_RENDERER':'zink'},
                         {'DESKTOP_GL_MODE':'auto'}, {'DESKTOP_MODE':'auto'}):
            with self.subTest(override=override): self.rejected_before_package_work(overrides=override)

    def test_native_gnome_rejects_every_unsupported_frequency(self):
        for rate in ('30','45','90','120'):
            with self.subTest(rate=rate): self.rejected_before_package_work(rate=rate)

    def test_proot_gnome_requires_trixie_and_software_x11(self):
        for override in ({'DESKTOP_CONTAINER_CODENAME':'forky'}, {'DESKTOP_CONTAINER_CODENAME':None},
                         {'DESKTOP_GNOME_BACKEND':'android'}, {'DESKTOP_GL_MODE':'system'},
                         {'DESKTOP_GNOME_RENDERER':'system'}):
            with self.subTest(override=override):
                self.rejected_before_package_work(mode='proot',renderer='llvmpipe',overrides=override)

    def test_proot_gnome_requires_explicit_system_egl_for_the_host_display(self):
        for display in (None,'','auto','llvmpipe','zink','unknown'):
            with self.subTest(display=display):
                self.rejected_before_package_work(mode='proot',renderer='llvmpipe',
                    overrides={'DESKTOP_GNOME_DISPLAY_GL_MODE':display})

    def test_proot_gnome_display_buffer_mode_must_match_the_renderer(self):
        for renderer,good in [('llvmpipe','native'),('zink','dmabuf')]:
            for bad in (None,'','auto','unknown','native' if good=='dmabuf' else 'dmabuf'):
                with self.subTest(renderer=renderer,buffer=bad):
                    self.rejected_before_package_work(mode='proot',renderer=renderer,
                        overrides={'DESKTOP_GNOME_DISPLAY_BUFFER_MODE':bad})
        self.rejected_before_package_work(overrides={'DESKTOP_GNOME_DISPLAY_BUFFER_MODE':'native'})

    def test_proot_gnome_zink_requires_its_explicit_host_zink_display(self):
        for bad in (None,'','auto','system','llvmpipe'):
            with self.subTest(display=bad):
                self.rejected_before_package_work(mode='proot',renderer='zink',
                    overrides={'DESKTOP_GNOME_DISPLAY_GL_MODE':bad})

    def test_native_gnome_rejects_x11_display_mode_parameters(self):
        for display in ('system','llvmpipe','zink','auto'):
            with self.subTest(display=display):
                self.rejected_before_package_work(overrides={'DESKTOP_GNOME_DISPLAY_GL_MODE':display})

    def test_usb_mode_is_required_and_plasma_read_only_is_rejected(self):
        for usb in ('','auto','read-write'):
            with self.subTest(usb=usb): self.rejected_before_package_work(usb=usb)
        self.rejected_before_package_work(overrides={'DESKTOP_USB_STORAGE':None})
        self.rejected_before_package_work(de='kwin_plasma',renderer='zink',usb='read-only')

    def test_proot_plasma_system_renderer_is_rejected(self):
        self.rejected_before_package_work(de='kwin_plasma',mode='proot',renderer='system')

    def test_native_gnome_installs_pinned_runtime_and_copies_launcher(self):
        result,calls = self.run_install()
        self.assertEqual(0,result.returncode,result.stdout)
        apt = next(call for call in calls if call[0]=='apt')
        self.assertTrue(any(Path(arg).name=='mutter_48.1-24_aarch64.deb' for arg in apt),apt)
        self.assertTrue(any(Path(arg).name=='gnome-shell_48.7-1_aarch64.deb' for arg in apt),apt)
        self.assertIn('python',apt)
        self.assertFalse(any('termux-usb-bridge_' in arg for call in calls for arg in call),calls)
        self.assertEqual((ASSETS/'desktop_gnome_launch.py').read_bytes(),
            (self.base/'usr/libexec/termux-gnome/launch.py').read_bytes())
        self.assertEqual('exit:0\n',(self.base/'state/status').read_text())

    def test_native_xfce_and_plasma_keep_their_original_package_families(self):
        for de,package in [('xfce4','xfce4'),('kwin_plasma','plasma-desktop')]:
            with self.subTest(de=de):
                result,calls = self.run_install(de=de,renderer='zink')
                self.assertEqual(0,result.returncode,result.stdout)
                apt = next(call for call in calls if call[0]=='apt')
                self.assertIn(package,apt)
                self.assertFalse(any('mutter_' in arg or 'gnome-shell_' in arg for arg in apt),apt)

    def test_container_release_selection_stays_trixie_for_xfce_gnome_and_forky_for_plasma(self):
        for de,renderer,release in [('xfce4','system','trixie'),('gnome','llvmpipe','trixie'),('kwin_plasma','llvmpipe','forky')]:
            with self.subTest(de=de):
                result,calls = self.run_install(de=de,mode='proot',renderer=renderer)
                self.assertEqual(0,result.returncode,result.stdout)
                installs = [call for call in calls if call[:2]==['proot-distro','install'] and '-n' in call]
                self.assertEqual('debian:'+release,installs[0][-1])
                logins = [call for call in calls if call[:2]==['proot-distro','login']]
                self.assertTrue(logins)
                self.assertIn('gnome-shell' if de=='gnome' else 'xfce4' if de=='xfce4' else 'plasma-desktop',logins[0])
                if de=='gnome': self.assertIn('--no-install-recommends',logins[0])
                else: self.assertNotIn('--no-install-recommends',logins[0])

    def test_plasma_installs_its_default_file_manager_and_terminal(self):
        for mode in ('native', 'proot'):
            with self.subTest(mode=mode):
                result,calls = self.run_install(de='kwin_plasma',mode=mode,renderer='llvmpipe')
                self.assertEqual(0,result.returncode,result.stdout)
                install = next(call for call in calls if call[0]=='apt') if mode=='native' else next(
                    call for call in calls if call[:2]==['proot-distro','login'])
                for package in ('dolphin','konsole'):
                    self.assertIn(package,install)

    def test_proot_gnome_does_not_download_or_verify_native_gnome_packages(self):
        result,calls=self.run_install(mode='proot',renderer='llvmpipe')
        self.assertEqual(0,result.returncode,result.stdout)
        host_apt=next(call for call in calls if call[0]=='apt')
        self.assertFalse(any('mutter_' in arg or 'gnome-shell_' in arg for arg in host_apt),host_apt)
        self.assertFalse(any('gnome-usb-20261030-r1' in arg for call in calls for arg in call),calls)
        self.assertFalse(any(call[0] in ('dpkg','dpkg-query') and call[-1] in ('mutter','gnome-shell','gjs','gnome-session') for call in calls))
        self.assertIn('python',host_apt)

    def test_proot_gnome_zink_installs_verified_kgsl_mesa_and_dependencies(self):
        result,calls=self.run_install(mode='proot',renderer='zink')
        self.assertEqual(0,result.returncode,result.stdout)
        host_apt=next(call for call in calls if call[0]=='apt')
        self.assertIn('mesa-vulkan-icd-freedreno',host_apt)
        guest_apt=next(call for call in calls if call[:2]==['proot-distro','login'])
        for package in ('libllvm19','libvulkan1','vulkan-tools','mesa-vulkan-drivers'):
            self.assertIn(package,guest_apt)
        mesa_calls=[call for call in calls if call[0]=='python3' and len(call)>3 and Path(call[3]).name=='desktop_install_mesa.py']
        self.assertEqual(1,len(mesa_calls),calls)
        self.assertEqual(['--codename','trixie','--gl-mode','zink'],mesa_calls[0][-4:])
        self.assertTrue(any(call[0]=='curl' and 'mesa-for-android-container' in call[-1] for call in calls))
        self.assertTrue((self.base/'usr/var/lib/proot-distro/containers/debian-gnome-1/.desktop-de-gnome').is_file())

    def test_bad_turnip_digest_stops_before_mesa_install_and_ready_marker(self):
        result,calls=self.run_install(mode='proot',renderer='zink',
            bad_digest='mesa-for-android-container_27.0.0-devel-20261025_debian_trixie_arm64.tar.gz')
        self.assertNotEqual(0,result.returncode,result.stdout)
        self.assertIn('checksum mismatch',(self.base/'install.log').read_text())
        self.assertFalse(any(call[0]=='python3' and any(Path(arg).name=='desktop_install_mesa.py' for arg in call) for call in calls))
        self.assertFalse((self.base/'usr/var/lib/proot-distro/containers/debian-gnome-1/.desktop-de-gnome').exists())

    def test_turnip_installer_failure_is_preserved_and_reinstall_clears_old_marker(self):
        result,_=self.run_install(mode='proot',renderer='llvmpipe')
        self.assertEqual(0,result.returncode,result.stdout)
        marker=self.base/'usr/var/lib/proot-distro/containers/debian-gnome-1/.desktop-de-gnome'
        self.assertTrue(marker.exists())
        result,calls=self.run_install(mode='proot',renderer='zink',mesa_helper_error=77)
        self.assertEqual(77,result.returncode,result.stdout)
        self.assertFalse(marker.exists())
        self.assertFalse(any(call[:4]==['proot-distro','login','debian-gnome-1','--shared-tmp'] and 'apt-mark' in call for call in calls))
        self.assertNotIn('Desktop install finished.',result.stdout)

    def test_existing_wrong_release_is_not_silently_used(self):
        result,calls = self.run_install(mode='proot',renderer='llvmpipe',actual_codename='forky')
        self.assertNotEqual(0,result.returncode,result.stdout)
        self.assertFalse(any(call[:2]==['proot-distro','login'] for call in calls),calls)
        self.assertFalse((self.base/'usr/var/lib/proot-distro/containers/debian-gnome-1/.desktop-de-gnome').exists())

    def test_matching_codename_cannot_disguise_a_non_debian_container(self):
        result,calls = self.run_install(mode='proot',renderer='llvmpipe',actual_distro_id='ubuntu')
        self.assertNotEqual(0,result.returncode,result.stdout)
        self.assertFalse(any(call[:2]==['proot-distro','login'] for call in calls),calls)
        self.assertFalse((self.base/'usr/var/lib/proot-distro/containers/debian-gnome-1/.desktop-de-gnome').exists())

    def test_bad_gnome_deb_checksum_stops_before_apt_and_launcher_publication(self):
        result,calls = self.run_install(bad_digest='mutter_48.1-24_aarch64.deb')
        self.assertNotEqual(0,result.returncode,result.stdout)
        self.assertIn('checksum mismatch',(self.base/'install.log').read_text())
        self.assertFalse(any(call[0]=='apt' for call in calls),calls)
        self.assertFalse((self.base/'usr/libexec/termux-gnome/launch.py').exists())

    def test_read_only_xfce_installs_the_pinned_usb_bridge(self):
        result,calls = self.run_install(de='xfce4',usb='read-only')
        self.assertEqual(0,result.returncode,result.stdout)
        apt = next(call for call in calls if call[0]=='apt')
        self.assertTrue(any(Path(arg).name=='termux-usb-bridge_0.4.1_aarch64.deb' for arg in apt),apt)

    def test_bad_usb_deb_checksum_stops_before_apt(self):
        result,calls = self.run_install(de='xfce4',usb='read-only',bad_digest='termux-usb-bridge_0.4.1_aarch64.deb')
        self.assertNotEqual(0,result.returncode,result.stdout)
        self.assertFalse(any(call[0]=='apt' for call in calls),calls)

    def test_apt_failure_preserves_exit_code_and_skips_success_steps(self):
        result,calls = self.run_install(apt_error=100)
        self.assertEqual(100,result.returncode,result.stdout)
        self.assertIn('E: fixture dependency failure',result.stdout)
        self.assertEqual('exit:100\n',(self.base/'state/status').read_text())
        self.assertFalse(any(call[0]=='apt-mark' or call[:2]==['dpkg','--audit'] for call in calls),calls)

    def test_audit_command_failure_cannot_be_reported_as_success(self):
        result,calls = self.run_install(audit_error=71)
        self.assertNotEqual(0,result.returncode,result.stdout)
        self.assertNotEqual('exit:0\n',(self.base/'state/status').read_text())
        self.assertNotIn('Desktop install finished.',result.stdout)

    def test_same_version_alternate_mutter_payload_fails_final_sha_verification(self):
        result,calls = self.run_install(bad_digest='libmutter-android-system-egl.so')
        self.assertNotEqual(0,result.returncode,result.stdout)
        self.assertIn('installed package verification failed: mutter',result.stdout)
        self.assertTrue(any(call[:2]==['dpkg','--verify'] and call[-1]=='mutter' for call in calls),calls)
        self.assertNotIn('Desktop install finished.',result.stdout)

    def test_dpkg_file_verification_failure_is_not_hidden_by_successful_apt(self):
        result,calls = self.run_install(corrupt_package='gnome-shell')
        self.assertNotEqual(0,result.returncode,result.stdout)
        self.assertIn('installed package verification failed: gnome-shell',result.stdout)
        self.assertNotIn('Desktop install finished.',result.stdout)

    def test_gnome_restore_prerequisites_are_explicitly_unsupported(self):
        result,calls = self.run_install(mode='proot',renderer='llvmpipe',operation='host-prerequisites')
        self.assertEqual(64,result.returncode,result.stdout)
        self.assertFalse(calls,calls)
        self.assertIn('state=failed',(self.base/'result').read_text())

    def test_restore_installs_missing_host_audio_support(self):
        (self.base/'usr/bin/pactl').unlink()
        result,calls = self.run_install(de='xfce4',mode='proot',renderer='system',
            operation='host-prerequisites')
        self.assertEqual(0,result.returncode,result.stdout)
        self.assertTrue(any(call[0]=='apt' and 'pulseaudio' in call for call in calls),calls)
        self.assertTrue((self.base/'usr/bin/pactl').is_file())
        self.assertIn('state=succeeded',(self.base/'result').read_text())

    def test_held_xorg_remains_valid_during_final_package_verification(self):
        result,calls = self.run_install(de='xfce4')
        self.assertEqual(0,result.returncode,result.stdout)
        state=json.loads((self.base/'fixture.json').read_text())
        self.assertEqual('hold ok installed',state['package_statuses']['xorg-server'])
        held=calls.index(['apt-mark','hold','xorg-server'])
        queries=[call for call in calls[held+1:] if call[0]=='dpkg-query' and call[-1]=='xorg-server']
        self.assertTrue(any('-f=${Status}' in call for call in queries),queries)
        self.assertTrue(any('-f=${Version}' in call for call in queries),queries)
        self.assertIn(['dpkg','--verify','xorg-server'],calls[held+1:])
        self.assertEqual('exit:0\n',(self.base/'state/status').read_text())

    def test_hold_does_not_accept_partial_or_reinstreq_package_states(self):
        for status in ('hold ok unpacked','hold ok half-configured','install ok half-installed',
                       'install reinstreq installed','hold reinstreq installed',
                       'deinstall ok config-files','unknown ok not-installed',
                       'install ok triggers-pending','hold ok triggers-awaited'):
            with self.subTest(status=status):
                result,calls = self.run_install(de='xfce4',status_after_hold=status)
                self.assertNotEqual(0,result.returncode,result.stdout)
                self.assertIn('installed package verification failed: xorg-server',result.stdout)
                self.assertNotIn('Desktop install finished.',result.stdout)
                held=calls.index(['apt-mark','hold','xorg-server'])
                # A rejected status cannot advance to version, dpkg-file or payload checks.
                after=calls[held+1:]
                self.assertTrue(any(call[0]=='dpkg-query' and call[-1]=='xorg-server'
                    and '-f=${Status}' in call for call in after),after)
                self.assertFalse(any(call[0]=='dpkg-query' and call[-1]=='xorg-server'
                    and '-f=${Version}' in call for call in after),after)
                self.assertNotIn(['dpkg','--verify','xorg-server'],after)

    def test_hold_failure_cannot_be_reported_as_success(self):
        result,calls = self.run_install(hold_error=73)
        self.assertEqual(73,result.returncode,result.stdout)
        self.assertEqual('exit:73\n',(self.base/'state/status').read_text())


HELPER_MOCK = r'''
import json,os,pathlib,sys
base=pathlib.Path(os.environ['HELPER_FIXTURE']);command=pathlib.Path(sys.argv[0]).name;args=sys.argv[1:]
with (base/'calls.jsonl').open('a') as out:out.write(json.dumps([command,*args])+'\n')
state=json.loads((base/'fixture.json').read_text())
if command=='apt-get' and args[0]=='install':sys.exit(state.get('apt_error',0))
if command=='dpkg':
    print('Status: '+state.get('package_statuses',{}).get(args[-1],state.get('package_status','install ok installed')))
    sys.exit(state.get('dpkg_error',0))
if command=='apt-mark' and args[0]=='hold':
    statuses=state.setdefault('package_statuses',{})
    for package in args[1:]:statuses[package]='hold ok installed'
    (base/'fixture.json').write_text(json.dumps(state))
'''


class ContainerAptContract(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory(prefix='container-apt-contract-',dir='/tmp')
        self.addCleanup(self.temporary.cleanup)
        self.base=Path(self.temporary.name);(self.base/'bin').mkdir()
        for command in ('apt-get','apt-mark','dpkg','getent','groupadd','useradd','systemd-sysusers'):
            target=self.base/'bin'/command;target.write_text('#!'+sys.executable+'\n'+HELPER_MOCK);target.chmod(0o700)
        (self.base/'desktop_container_statx_setup.sh').write_text(
            'configure_proot_statx() { return 0; }\n'
            'run_proot_config() { shift; "$@"; }\n'
            'restore_legacy_postinsts() { printf restored > "$HELPER_FIXTURE/restored"; }\n'
            'validate_polkit_identity() { return 0; }\n')
        source=(ASSETS/'desktop_container_plasma_apt.sh').read_text()
        source=source.replace('export PATH=', 'export PATH='+str(self.base/'bin')+':',1)
        source=source.replace('/usr/sbin/policy-rc.d',str(self.base/'policy-rc.d'))
        source=source.replace('/usr/bin/systemd-sysusers',str(self.base/'bin/systemd-sysusers'))
        (self.base/'helper.sh').write_text(source)

    def run_helper(self,*args,apt_error=0,package_status='install ok installed',dpkg_error=0,preserve_state=False):
        (self.base/'calls.jsonl').write_text('')
        if not preserve_state:
            (self.base/'fixture.json').write_text(json.dumps({'apt_error':apt_error,'package_status':package_status,'dpkg_error':dpkg_error}))
        env=dict(os.environ,HELPER_FIXTURE=str(self.base))
        result=subprocess.run(['sh',str(self.base/'helper.sh'),*args],env=env,text=True,
            stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=15)
        calls=[json.loads(line) for line in (self.base/'calls.jsonl').read_text().splitlines()]
        return result,calls

    def test_explicit_no_recommends_reaches_apt_but_never_package_verification(self):
        packages=['gnome-shell','gnome-session','gvfs-backends']
        result,calls=self.run_helper('--statx-backend=proc-fdinfo','--no-install-recommends',*packages)
        self.assertEqual(0,result.returncode,result.stdout)
        installs=[call for call in calls if call[:2]==['apt-get','install']]
        self.assertEqual(1,len(installs));self.assertEqual(1,installs[0].count('--no-install-recommends'))
        self.assertEqual(packages,installs[0][-len(packages):])
        self.assertEqual([['dpkg','-s',package] for package in packages],[call for call in calls if call[0]=='dpkg'])
        self.assertTrue((self.base/'restored').exists())

    def test_zink_first_install_hold_and_reinstall_accept_held_mesa_packages(self):
        packages=['gnome-shell','gnome-settings-daemon','nautilus','gvfs-backends',
            'gnome-backgrounds','adwaita-icon-theme','dbus-x11','libgl1-mesa-dri','mesa-utils',
            'mesa-vulkan-drivers','libvulkan1','vulkan-tools','libllvm19','libdrm2']
        args=['--statx-backend=proc-fdinfo','--no-install-recommends',*packages]
        first,first_calls=self.run_helper(*args)
        self.assertEqual(0,first.returncode,first.stdout)
        self.assertEqual([['dpkg','-s',package] for package in packages],
            [call for call in first_calls if call[0]=='dpkg'])
        # Apply the actual hold set used after installing the pinned upstream Mesa overlay.
        held=['mesa-vulkan-drivers','libgbm1','libegl-mesa0','libgl1-mesa-dri','libglx-mesa0','libgles2']
        subprocess.run([str(self.base/'bin/apt-mark'),'hold',*held],
            env=dict(os.environ,HELPER_FIXTURE=str(self.base)),check=True)
        state=json.loads((self.base/'fixture.json').read_text())
        self.assertTrue(all(state['package_statuses'][package]=='hold ok installed' for package in held))
        repeat,calls=self.run_helper(*args,preserve_state=True)
        self.assertEqual(0,repeat.returncode,repeat.stdout)
        self.assertEqual([['dpkg','-s',package] for package in packages],
            [call for call in calls if call[0]=='dpkg'])
        self.assertEqual(1,len([call for call in calls if call[:2]==['apt-get','install']]))

    def test_only_exact_install_or_hold_ok_installed_statuses_are_accepted(self):
        for status in ('install ok installed','hold ok installed'):
            with self.subTest(status=status):
                result,_=self.run_helper('--statx-backend=proc-fdinfo','mesa-vulkan-drivers',package_status=status)
                self.assertEqual(0,result.returncode,result.stdout)
        for status in ('deinstall ok installed','deinstall ok config-files','install ok unpacked',
                       'hold ok unpacked','install ok half-configured','hold ok half-configured',
                       'install ok half-installed','hold reinstreq installed',
                       'install ok triggers-pending','hold ok triggers-awaited',
                       'unknown ok not-installed','install ok installed garbage'):
            with self.subTest(status=status):
                (self.base/'restored').unlink(missing_ok=True)
                result,calls=self.run_helper('--statx-backend=proc-fdinfo','mesa-vulkan-drivers',package_status=status)
                self.assertEqual(1,result.returncode,result.stdout)
                self.assertIn('not fully installed: mesa-vulkan-drivers',result.stdout)
                self.assertFalse((self.base/'restored').exists())
                self.assertEqual([['dpkg','-s','mesa-vulkan-drivers']],
                    [call for call in calls if call[0]=='dpkg'])

    def test_failed_package_query_cannot_be_hidden_by_valid_installed_status_output(self):
        for status in ('install ok installed','hold ok installed'):
            with self.subTest(status=status):
                result,calls=self.run_helper('--statx-backend=proc-fdinfo','mesa-vulkan-drivers',
                    package_status=status,dpkg_error=47)
                self.assertEqual(47,result.returncode,result.stdout)
                self.assertIn('package status query failed (exit 47): mesa-vulkan-drivers',result.stdout)
                self.assertEqual([['dpkg','-s','mesa-vulkan-drivers']],
                    [call for call in calls if call[0]=='dpkg'])
                self.assertFalse((self.base/'restored').exists())
                self.assertEqual(1,len([call for call in calls if call[:2]==['apt-get','install']]))

    def test_unflagged_xfce_and_plasma_keep_the_existing_recommends_policy(self):
        for packages in (['xfce4','xfce4-terminal','dbus-x11'],['plasma-desktop','packagekit','polkitd']):
            with self.subTest(packages=packages):
                result,calls=self.run_helper('--statx-backend=proc-fdinfo',*packages)
                self.assertEqual(0,result.returncode,result.stdout)
                installs=[call for call in calls if call[:2]==['apt-get','install']]
                self.assertEqual(1,len(installs));self.assertNotIn('--no-install-recommends',installs[0])
                self.assertEqual(packages,installs[0][-len(packages):])
                self.assertEqual([['dpkg','-s',package] for package in packages],[call for call in calls if call[0]=='dpkg'])

    def test_unknown_repeated_misordered_or_package_less_options_fail_before_apt(self):
        for args in (['--no-install-recommends','--statx-backend=proc-fdinfo','gnome-shell'],
                     ['--statx-backend=proc-fdinfo','--unknown','gnome-shell'],
                     ['--statx-backend=proc-fdinfo','--no-install-recommends','--no-install-recommends','gnome-shell'],
                     ['--statx-backend=proc-fdinfo','--no-install-recommends']):
            with self.subTest(args=args):
                result,calls=self.run_helper(*args)
                self.assertEqual(64,result.returncode,result.stdout);self.assertFalse(calls,calls)

    def test_flagged_install_failure_preserves_exit_and_skips_verification_and_repair(self):
        result,calls=self.run_helper('--statx-backend=proc-fdinfo','--no-install-recommends','gnome-shell',apt_error=100)
        self.assertEqual(100,result.returncode,result.stdout)
        self.assertEqual(1,len([call for call in calls if call[:2]==['apt-get','install']]))
        self.assertFalse(any(call[0]=='dpkg' for call in calls),calls)
        self.assertFalse((self.base/'restored').exists())


if __name__ == '__main__':
    unittest.main(verbosity=2)
