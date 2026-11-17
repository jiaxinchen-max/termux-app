#!/usr/bin/env python3
"""Execute prepared GNOME/Hyprland launch boundaries with changing output geometry."""
import importlib.util
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ASSETS = Path(__file__).resolve().parents[1] / 'src/main/assets/local-games'


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ASSETS / filename)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


g = module('gnome_output', 'desktop_gnome_launch.py')
h = module('hyprland_output', 'desktop_hyprland.py')


class OutputTests(unittest.TestCase):
    def test_gnome_legacy_preparation_executes_new_geometry_without_modifying_saved_script(self):
        for mode in ('native', 'proot'):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory(prefix='output ') as temp:
                work = Path(temp)
                capture = work / 'compositor.json'
                compositor = work / 'fake compositor.py'
                compositor.write_text('import json,os\nfrom pathlib import Path\nPath(' + repr(str(capture)) +
                    ').write_text(json.dumps({k:v for k,v in os.environ.items() if k.startswith("MUTTER_ANDROID_")}))\n')
                original = '#!/bin/sh\nexec ' + shlex.join(['env', 'MUTTER_ANDROID_WIDTH=1280',
                    'MUTTER_ANDROID_HEIGHT=720', 'MUTTER_ANDROID_REFRESH_RATE=60',
                    'MUTTER_ANDROID_GL_MODE=system', sys.executable, str(compositor)]) + '\n'
                (work / 'session.sh').write_text(original)
                args = SimpleNamespace(size='2400x1080', refresh_rate=60, run_mode=mode, renderer='system')
                prepared = {'schema': 1, 'request': dict(g.session_request(args), size='1280x720')}
                self.assertTrue(g.prepared_request_matches(prepared['request'], args))
                # Execute the derived shell command, asserting what the child actually receives.
                script = g.session_output_script(work, str(work), prepared, args)
                subprocess.run(['/bin/sh', script], check=True, timeout=5)
                self.assertEqual({'MUTTER_ANDROID_WIDTH': '2400', 'MUTTER_ANDROID_HEIGHT': '1080',
                    'MUTTER_ANDROID_REFRESH_RATE': '60', 'MUTTER_ANDROID_GL_MODE': 'system'}, json.loads(capture.read_text()))
                self.assertEqual(original, (work / 'session.sh').read_text())
                args.size = '1080x2400'
                script = g.session_output_script(work, str(work), prepared, args)
                subprocess.run(['/bin/sh', script], check=True, timeout=5)
                self.assertEqual('1080', json.loads(capture.read_text())['MUTTER_ANDROID_WIDTH'])
                self.assertEqual('2400', json.loads(capture.read_text())['MUTTER_ANDROID_HEIGHT'])

    def test_non_geometry_gnome_changes_still_require_preparation(self):
        args = SimpleNamespace(size='2400x1080', refresh_rate=60, run_mode='proot',
            rootfs='/container', gl_mode='system', desktop_id='test', usb_storage='off')
        request = g.session_request(args)
        for key in ('refresh_rate', 'run_mode', 'rootfs', 'gl_mode', 'desktop_id', 'usb_storage'):
            self.assertFalse(g.prepared_request_matches(dict(request, **{key: 'different'}), args), key)
        self.assertFalse(g.prepared_request_matches(None, args))

    def test_gnome_invalid_size_or_refresh_is_rejected_before_launch(self):
        for size, refresh in [('0x0', 60), ('240x320', 60), ('1920x200', 60),
                              ('9999x1080', 60), ('1920x1080;id', 60), ('1920x1080', 90)]:
            with self.subTest(size=size, refresh=refresh), self.assertRaises(ValueError):
                g.run_prepared(SimpleNamespace(work=Path('/unused'), size=size, refresh_rate=refresh))

    def test_unrecognized_gnome_saved_script_cannot_silently_start_at_old_size(self):
        with tempfile.TemporaryDirectory() as temp:
            work = Path(temp)
            (work / 'session.sh').write_text('exec echo no-output-arguments\n')
            with self.assertRaisesRegex(RuntimeError, 'output command is invalid'):
                g.session_output_script(work, '/guest', {'request': {'size': '1280x720'}},
                    SimpleNamespace(size='2400x1080', refresh_rate=60))
            self.assertFalse((work / 'session-output.sh').exists())

    def test_hyprland_native_and_proot_accept_new_size_but_pin_every_other_setting(self):
        for mode in ('native', 'proot'):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as temp:
                work = Path(temp)
                args = SimpleNamespace(command='session', width=2400, height=1080, refresh=120,
                    run_mode=mode, desktop_id='output-test', usb_storage='off', client_gl_mode='default',
                    ready_file=work / 'ready', theme_id='m3ww-v1', runtime_id=h.VERSION)
                request = dict(h.session_request(args), width=1280, height=720)
                (work / 'prepared-session.json').write_text(json.dumps({'schema': 1, 'request': request}))
                with patch.object(h, 'work_for', return_value=work), patch.object(h, '_session_locked', return_value=0) as launch:
                    self.assertEqual(0, h.session(args))
                    self.assertEqual(2400, launch.call_args[0][0].width)
                    self.assertEqual(1080, launch.call_args[0][0].height)
                    self.assertEqual(120, launch.call_args[0][0].refresh)
                for key in ('refresh', 'theme_id', 'runtime_id', 'usb_storage', 'client_gl_mode', 'desktop_id'):
                    bad = dict(request, **{key: 'different'})
                    (work / 'prepared-session.json').write_text(json.dumps({'schema': 1, 'request': bad}))
                    with patch.object(h, 'work_for', return_value=work), self.assertRaisesRegex(RuntimeError, 'not prepared'):
                        h.session(args)
                args.width = 9999
                with self.assertRaises(ValueError):
                    h.session(args)


if __name__ == '__main__':
    unittest.main()
