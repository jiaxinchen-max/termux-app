#!/usr/bin/env python3
import argparse
import importlib.util
import json
import tempfile
import subprocess
from unittest import mock
import unittest
from pathlib import Path
spec = importlib.util.spec_from_file_location('validation', Path(__file__).with_name('validate-ui.py'))
v = importlib.util.module_from_spec(spec); spec.loader.exec_module(v)

class ValidationTests(unittest.TestCase):
    def test_rotation_and_endpoint_mapping(self):
        ref = {'width': 2400, 'height': 1080, 'rotation': 0}
        self.assertEqual(v.transform((0, 0), ref, (1080, 2400), 90), (1079, 0))
        self.assertEqual(v.transform((2399, 1079), ref, (1080, 2400), 90), (0, 2399))
        self.assertEqual(v.transform((0, 0), ref, (2400, 1080), 180), (2399, 1079))
        self.assertEqual(v.transform((2399, 0), ref, (1080, 2400), 270), (0, 0))
        with self.assertRaises(ValueError): v.transform((2, 3), ref, (1080, 2400), 0)
        with self.assertRaises(ValueError): v.transform((2400, 3), ref, (2400, 1080), 0)

    def test_adb_server_is_exact(self):
        args = argparse.Namespace(adb='adb', adb_server='remote', adb_host='120.24.191.156', adb_port=8037, serial='phone', display_id=None)
        self.assertEqual(v.Device(args).command, ['adb', '-H', '120.24.191.156', '-P', '8037', '-s', 'phone'])
        args.adb_port = None
        with self.assertRaises(ValueError): v.Device(args)
        args.adb_server = 'local'
        with self.assertRaises(ValueError): v.Device(args)

    def test_physical_display_selection_and_warning_failure(self):
        from PIL import Image
        import io
        stream = io.BytesIO(); Image.new('RGB', (3, 2)).save(stream, format='PNG')
        png = stream.getvalue()
        args = argparse.Namespace(adb='adb', adb_server='local', adb_host=None,
                                  adb_port=None, serial='phone', display_id='4630946736638489730')
        with tempfile.TemporaryDirectory() as temp:
            target = Path(temp)/'capture.png'
            for selected in ('4630946736638489730', None):
                args.display_id = selected
                device = v.Device(args)
                with mock.patch.object(v.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, png, b'')) as execute:
                    self.assertEqual(device.capture(target), (3, 2))
                    expected = ['adb', '-s', 'phone', 'exec-out', 'screencap', '-p']
                    if selected is not None: expected += ['-d', selected]
                    self.assertEqual(execute.call_args.args[0], expected)
                    self.assertEqual(execute.call_count, 1)
                target.unlink()
            for stdout, stderr, error in ((b'Warning: multiple displays\n' + png, b'', ValueError),
                                           (png + b'\nWarning: multiple displays\n', b'', ValueError),
                                           (png, b'Warning: multiple displays\n', RuntimeError)):
                with mock.patch.object(v.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, stdout, stderr)) as execute:
                    with self.assertRaises(error): v.Device(args).capture(target)
                    self.assertEqual(execute.call_count, 1)
                    self.assertFalse(target.exists())
        for invalid in ('-1', 'abc', '1; echo x', str(1 << 64)):
            with self.assertRaises(argparse.ArgumentTypeError): v.physical_display_id(invalid)

    def test_ui_dump_failure_never_uses_old_tree(self):
        class BrokenDevice:
            def shell(self, *args):
                self.call = args
                return b'ERROR: could not get idle state'
        device = BrokenDevice()
        with tempfile.TemporaryDirectory() as temp:
            stale = Path(temp)/'window.xml'; stale.write_text('<hierarchy><node text="Launch" bounds="[1,1][5,5]"/></hierarchy>')
            with self.assertRaises(RuntimeError): v.Device.tap_text(device, 'Launch', None, stale)
            self.assertEqual(device.call[0], 'uiautomator')

    def test_pixel_report_is_not_semantic_pass(self):
        from PIL import Image
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); a = root/'a.png'; b = root/'b.png'
            Image.new('RGB', (3, 2)).save(a)
            image = Image.new('RGB', (3, 2)); image.putpixel((1, 1), (10, 0, 0)); image.save(b)
            report = root/'report.json'; diff = root/'diff.png'
            result = v.compare(a, b, report, diff, 9, 0.0)
            self.assertEqual(result['changed_pixels'], 1)
            self.assertEqual(result['changed_bounds'], (1, 1, 2, 2))
            self.assertFalse(result['pixel_threshold_met'])
            self.assertEqual(result['semantic_verdict'], 'not_assessed')
            self.assertEqual(v.compare(a, b, report, diff, 10)['changed_pixels'], 0)
            Image.new('RGB', (4, 2)).save(b)
            self.assertFalse(v.compare(a, b, report, diff, 0)['comparable'])
            self.assertEqual(json.loads(report.read_text())['semantic_verdict'], 'not_assessed')

if __name__ == '__main__': unittest.main()
