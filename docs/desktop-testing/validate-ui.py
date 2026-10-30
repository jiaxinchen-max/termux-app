#!/usr/bin/env python3
"""Repeat UI gestures and record screenshots. Never launch a desktop through a shell."""
import argparse
import hashlib
import json
import math
import re
import shlex
import struct
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zlib
from pathlib import Path


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def png_size(raw):
    if raw[:8] != b'\x89PNG\r\n\x1a\n' or len(raw) < 33 or raw[12:16] != b'IHDR':
        raise ValueError('screencap did not return a PNG')
    if struct.unpack('>I', raw[8:12])[0] != 13:
        raise ValueError('invalid PNG header')
    size = struct.unpack('>II', raw[16:24])
    offset = 8
    while offset + 12 <= len(raw):
        length = struct.unpack('>I', raw[offset:offset + 4])[0]
        end = offset + length + 12
        if end > len(raw): break
        kind = raw[offset + 4:offset + 8]
        expected_crc = struct.unpack('>I', raw[end - 4:end])[0]
        if zlib.crc32(raw[offset + 4:end - 4]) != expected_crc:
            raise ValueError('invalid PNG chunk checksum')
        if kind == b'IEND':
            if length != 0 or end != len(raw):
                raise ValueError('screencap returned trailing output after PNG')
            return size
        offset = end
    raise ValueError('screencap returned a truncated PNG')


def transform(point, reference, target_size, target_rotation):
    width, height = reference['width'], reference['height']
    rotation = reference['rotation']
    delta = (target_rotation - rotation) % 360
    expected_landscape = width > height if delta in (0, 180) else height > width
    if width != height and (target_size[0] > target_size[1]) != expected_landscape:
        raise ValueError('actual screenshot orientation contradicts explicit target rotation')
    if width < 2 or height < 2 or rotation not in (0, 90, 180, 270):
        raise ValueError('invalid reference dimensions/rotation')
    x, y = map(float, point)
    if not math.isfinite(x + y) or not 0 <= x < width or not 0 <= y < height:
        raise ValueError('point outside reference screenshot')
    x, y = x / (width - 1), y / (height - 1)
    if delta == 90: x, y = 1 - y, x
    elif delta == 180: x, y = 1 - x, 1 - y
    elif delta == 270: x, y = y, 1 - x
    elif delta != 0: raise ValueError('rotation must be a multiple of 90')
    return round(x * (target_size[0] - 1)), round(y * (target_size[1] - 1))


def compare(baseline, actual, report, diff, tolerance, max_fraction=None):
    # Pillow is an explicit dependency; no alternate comparison implementation is selected.
    if not 0 <= tolerance <= 255: raise ValueError('channel tolerance must be 0..255')
    from PIL import Image, ImageChops
    before, after = Image.open(baseline).convert('RGB'), Image.open(actual).convert('RGB')
    result = {'baseline': str(Path(baseline).resolve()), 'actual': str(Path(actual).resolve()),
              'baseline_sha256': sha(baseline), 'actual_sha256': sha(actual),
              'baseline_size': before.size, 'actual_size': after.size,
              'channel_tolerance': tolerance, 'semantic_verdict': 'not_assessed'}
    if before.size != after.size:
        result.update(comparable=False, reason='dimension_mismatch')
    else:
        difference = ImageChops.difference(before, after)
        data = difference.tobytes()
        changed = [max(data[offset:offset + 3]) > tolerance for offset in range(0, len(data), 3)]
        total = before.width * before.height
        mask = Image.new('L', before.size)
        mask.putdata([255 if value else 0 for value in changed])
        difference.save(diff)
        fraction = sum(changed) / total
        result.update(comparable=True, changed_pixels=sum(changed), total_pixels=total,
                      changed_fraction=fraction, mean_channel_difference=sum(data) / (total * 3),
                      changed_bounds=mask.getbbox(), diff=str(Path(diff).resolve()))
        if max_fraction is not None:
            result.update(max_changed_fraction=max_fraction, pixel_threshold_met=fraction <= max_fraction)
    Path(report).write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    # An explicit pixel threshold is only a mechanical gate; never a functional verdict.
    return result


def physical_display_id(text):
    if not re.fullmatch(r'[0-9]+', text) or int(text) > (1 << 64) - 1:
        raise argparse.ArgumentTypeError('display-id must be a decimal SurfaceFlinger physical display ID')
    return text


class Device:
    def __init__(self, args):
        self.command = [args.adb]
        if args.adb_server == 'remote':
            if not args.adb_host or args.adb_port is None:
                raise ValueError('remote ADB requires explicit --adb-host and --adb-port')
            self.command += ['-H', args.adb_host, '-P', str(args.adb_port)]
        elif args.adb_host or args.adb_port is not None:
            raise ValueError('local ADB does not accept remote host/port')
        self.command += ['-s', args.serial]
        self.events = []
        self.display_id = args.display_id

    def adb(self, *arguments, reject_warnings=False):
        started = time.time()
        result = subprocess.run(self.command + list(arguments), stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=45)
        self.events.append({'time': started, 'adb': list(arguments), 'status': result.returncode,
                            'stderr': result.stderr.decode(errors='replace')})
        if result.returncode:
            raise RuntimeError(result.stderr.decode(errors='replace') or 'ADB command failed')
        if reject_warnings and re.search(rb'\bwarning\b', result.stderr, re.IGNORECASE):
            raise RuntimeError('screencap warning: ' + result.stderr.decode(errors='replace'))
        return result.stdout

    def shell(self, *arguments):
        return self.adb('shell', shlex.join(map(str, arguments)))

    def capture(self, target):
        command = ['exec-out', 'screencap', '-p']
        if self.display_id is not None:
            command += ['-d', self.display_id]
        raw = self.adb(*command, reject_warnings=True)
        size = png_size(raw)
        Path(target).write_bytes(raw)
        return size

    def tap_text(self, text, index, xml_path):
        remote = '/sdcard/desktop-validation-window.xml'
        output = self.shell('uiautomator', 'dump', remote)
        if b'ERROR' in output or b'dumped to' not in output:
            raise RuntimeError('uiautomator dump failed; no cached UI tree is used: ' + output.decode(errors='replace'))
        raw = self.shell('cat', remote)
        Path(xml_path).write_bytes(raw)
        nodes = [n for n in ET.fromstring(raw).iter('node')
                 if text in (n.get('text'), n.get('content-desc'), n.get('resource-id'))]
        if index is None and len(nodes) != 1:
            raise ValueError('UI text must match exactly one node, or specify index: ' + text)
        node = nodes[0 if index is None else index]
        if node.get('enabled') == 'false': raise ValueError('UI node is disabled: ' + text)
        bounds = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
        if len(bounds) != 4 or bounds[2] <= bounds[0] or bounds[3] <= bounds[1]:
            raise ValueError('invalid UI node bounds')
        x, y = (bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2
        self.shell('input', 'tap', x, y)
        return {'label': text, 'bounds': bounds, 'screen_point': [x, y]}


def duration(step, default):
    value = int(step.get('duration_ms', default))
    if not 1 <= value <= 60000: raise ValueError('duration_ms must be 1..60000')
    return value


def run_plan(device, args):
    plan_path = Path(args.plan).resolve()
    plan = json.loads(plan_path.read_text())
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    actions = {'tap', 'tap_text', 'swipe', 'tap2', 'tap3', 'swipe2', 'key', 'capture'}
    for step in plan['steps']:
        if step['action'] not in actions: raise ValueError('unsupported UI action: ' + step['action'])
        if 'baseline' in step:
            from PIL import Image
            with Image.open((plan_path.parent / step['baseline']).resolve()) as baseline: baseline.verify()
        if step['action'] in ('tap2', 'tap3', 'swipe2'):
            if args.input_manager is None or not args.jar_sha256 or not re.fullmatch(r'[0-9a-f]{64}', args.jar_sha256):
                raise ValueError('multi-touch requires explicit --input-manager and --jar-sha256 before execution')
    results = {'plan': str(plan_path), 'plan_sha256': sha(plan_path), 'adb': device.command,
               'physical_display_id': device.display_id,
               'semantic_verdict': 'not_assessed', 'steps': []}
    try:
        if device.adb('get-state').strip() != b'device': raise RuntimeError('selected device is unavailable')
        for index, step in enumerate(plan['steps']):
            action = step['action']
            stem = '%03d-%s' % (index, action)
            before = output / (stem + '-before.png')
            size = device.capture(before)
            target_rotation = int(step.get('target_rotation', args.target_rotation))
            if target_rotation not in (0, 90, 180, 270): raise ValueError('invalid target rotation')
            result = {'action': action, 'before': str(before), 'target_size': size,
                      'target_rotation': target_rotation}
            point = lambda p: transform(p, plan['reference'], size, target_rotation)
            if action == 'tap':
                p = point(step['point']); device.shell('input', 'tap', *p); result['screen_point'] = p
            elif action == 'tap_text':
                result.update(device.tap_text(step['text'], step.get('index'), output / (stem + '.xml')))
            elif action == 'swipe':
                start, end = point(step['start']), point(step['end'])
                device.shell('input', 'swipe', *start, *end, duration(step, 400))
                result.update(screen_start=start, screen_end=end)
            elif action in ('tap2', 'tap3', 'swipe2'):
                if args.input_manager is None: raise ValueError('multi-touch requires explicit --input-manager global|legacy')
                if not args.jar_sha256 or not re.fullmatch(r'[0-9a-f]{64}', args.jar_sha256):
                    raise ValueError('multi-touch requires explicit lowercase --jar-sha256')
                if device.shell('sha256sum', args.device_jar).decode().split()[0] != args.jar_sha256:
                    raise ValueError('device mt.jar SHA256 differs from --jar-sha256')
                expected = 3 if action == 'tap3' else 2
                points = [point(p) for p in step['points']]
                if len(points) != expected: raise ValueError('wrong pointer count')
                arguments = [str(v) for p in points for v in p]
                if action == 'swipe2':
                    # Transform the endpoint as a point, including rotation, then subtract.
                    end = point([step['points'][0][0] + step['delta'][0], step['points'][0][1] + step['delta'][1]])
                    delta = [end[0] - points[0][0], end[1] - points[0][1]]
                    for original in step['points'][1:]: point([original[0] + step['delta'][0], original[1] + step['delta'][1]])
                    arguments += list(map(str, delta)); result['screen_delta'] = delta
                arguments += [str(duration(step, 400 if action == 'swipe2' else 60))]
                device.shell('env', 'CLASSPATH=' + args.device_jar, 'app_process', '/', 'MultiTouch',
                             '--input-manager=' + args.input_manager, action, *arguments)
                result['screen_points'] = points
            elif action == 'key':
                key = step['key']
                if key not in ('BACK', 'ENTER', 'ESCAPE', 'HOME', 'TAB', 'DPAD_UP', 'DPAD_DOWN', 'DPAD_LEFT', 'DPAD_RIGHT'):
                    raise ValueError('key not allowlisted')
                device.shell('input', 'keyevent', 'KEYCODE_' + key)
            elif action != 'capture':
                raise ValueError('unsupported UI action: ' + action)
            wait_ms = int(step.get('settle_ms', 600))
            if not 0 <= wait_ms <= 60000: raise ValueError('settle_ms must be 0..60000')
            time.sleep(wait_ms / 1000)
            actual = output / (stem + '-after.png')
            device.capture(actual)
            result['after'] = str(actual)
            if 'baseline' in step:
                baseline = (plan_path.parent / step['baseline']).resolve()
                result['comparison'] = compare(baseline, actual, output / (stem + '-comparison.json'),
                                                output / (stem + '-diff.png'), int(step.get('channel_tolerance', 0)))
            results['steps'].append(result)
        results['execution_status'] = 'completed'
    except Exception as error:
        results.update(execution_status='failed', error=str(error))
        raise
    finally:
        results['adb_events'] = device.events
        (output / 'report.json').write_text(json.dumps(results, ensure_ascii=False, indent=2) + '\n')
    print(output / 'report.json')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--adb-server', choices=['local', 'remote'])
    parser.add_argument('--adb-host'); parser.add_argument('--adb-port', type=int)
    parser.add_argument('--serial')
    parser.add_argument('--display-id', type=physical_display_id,
                        help='SurfaceFlinger physical ID for screencap -d; input remains logical display 0')
    sub = parser.add_subparsers(dest='command', required=True)
    install = sub.add_parser('install'); install.add_argument('--jar', required=True)
    install.add_argument('--device-jar', default='/data/local/tmp/desktop-validation-mt.jar')
    run = sub.add_parser('run'); run.add_argument('--plan', required=True); run.add_argument('--output', required=True)
    run.add_argument('--target-rotation', type=int, choices=[0, 90, 180, 270], required=True)
    run.add_argument('--device-jar', default='/data/local/tmp/desktop-validation-mt.jar')
    run.add_argument('--input-manager', choices=['global', 'legacy'])
    run.add_argument('--jar-sha256', help='Required for any multi-touch step; compare with install output')
    diff = sub.add_parser('compare'); diff.add_argument('--baseline', required=True); diff.add_argument('--actual', required=True)
    diff.add_argument('--report', required=True); diff.add_argument('--diff', required=True)
    diff.add_argument('--channel-tolerance', type=int, required=True); diff.add_argument('--max-changed-fraction', type=float)
    args = parser.parse_args()
    if args.command == 'compare':
        if not 0 <= args.channel_tolerance <= 255: raise ValueError('channel tolerance must be 0..255')
        if args.max_changed_fraction is not None and not 0 <= args.max_changed_fraction <= 1: raise ValueError('max fraction must be 0..1')
        result = compare(args.baseline, args.actual, args.report, args.diff, args.channel_tolerance, args.max_changed_fraction)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0 if result['comparable'] and result.get('pixel_threshold_met', True) else 1
    if not args.adb_server or not args.serial: raise ValueError('device commands require --adb-server and --serial')
    device = Device(args)
    if args.command == 'install':
        digest = sha(args.jar)
        device.adb('push', str(Path(args.jar).resolve()), args.device_jar)
        if device.shell('sha256sum', args.device_jar).decode().split()[0] != digest: raise ValueError('installed jar SHA256 mismatch')
        print(json.dumps({'device_jar': args.device_jar, 'sha256': digest, 'adb': device.command}, indent=2))
    else: run_plan(device, args)
    return 0


if __name__ == '__main__':
    try: sys.exit(main())
    except Exception as error:
        print(str(error), file=sys.stderr); sys.exit(2)
