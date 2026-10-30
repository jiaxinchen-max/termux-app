# SPDX-License-Identifier: MIT
"""Verify GNOME/Plasma test artifacts independently (requires Pillow and NumPy).

The fixture shows a 640x480 viewfinder at 1x scale. Search each desktop capture
for its matching JPEG at a 20-pixel stride. A live stream and saved JPEG alone
do not prove that the X11 preview actually rendered.
"""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageChops, ImageStat


def match_preview(screenshot, photo):
    a = Image.open(screenshot).convert('RGB')
    b = Image.open(photo).convert('RGB')
    assert b.size == (640, 480), (photo, b.size)
    a = np.asarray(a.resize((a.width // 20, a.height // 20)), dtype=np.float32)
    b = np.asarray(b.resize((32, 24)), dtype=np.float32)
    centered = b - b.mean()
    norm = np.sqrt((centered * centered).sum())
    best = {'correlation': -2.0}
    for y in range(a.shape[0] - 24 + 1):
        for x in range(a.shape[1] - 32 + 1):
            patch = a[y:y + 24, x:x + 32]
            c = patch - patch.mean()
            denominator = np.sqrt((c * c).sum()) * norm
            score = float((c * centered).sum() / denominator) if denominator else 0.0
            if score > best['correlation']:
                best = {'correlation': score, 'rgb_mae': float(np.abs(patch - b).mean()),
                        'origin': [x * 20, y * 20]}
    return best


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('directory', type=Path)
    args = ap.parse_args()
    w = args.directory
    result = json.loads((w / 'result.json').read_text())
    assert result.get('status') == 'PASS', result
    report = {'mode': result['mode'], 'status': 'FAIL', 'minimum_correlation': .85,
              'maximum_rgb_mae': 30, 'stride_pixels': 20, 'storage': [], 'cameras': []}
    passed = True
    try:
        for item in result['storage']:
            path = w / (item['format'] + '-readback.bin')
            data = path.read_bytes()
            digest = hashlib.sha256(data).hexdigest()
            assert len(data) == item['bytes'] == 131123
            assert digest == item['sha256']
            report['storage'].append({'format': item['format'], 'bytes': len(data), 'sha256': digest})
        for item in result['cameras']:
            label = item['label']
            photos = [w / ('photos-' + label) / ('cap_' + str(i)) for i in (0, 1)]
            images = [Image.open(p).convert('RGB') for p in photos]
            assert all(im.size == (640, 480) for im in images)
            change = ImageStat.Stat(ImageChops.difference(*images)).mean
            assert max(change) > 0, ('unchanged captures', label)
            matches = []
            for index, photo in enumerate(photos):
                match = match_preview(w / (label + f'-preview-{index}.png'), photo)
                match['status'] = ('PASS' if match['correlation'] >= .85 and
                                   match['rgb_mae'] <= 30 else 'FAIL')
                passed = passed and match['status'] == 'PASS'
                matches.append(match)
            report['cameras'].append({'label': label, 'jpeg_size': [640, 480],
                                      'jpeg_pair_rgb_mae': change, 'previews': matches})
        if passed:
            report['status'] = 'PASS'
    finally:
        (w / 'pixel-verification.json').write_text(json.dumps(report, indent=2) + '\n')
    assert passed, report
    print('DESKTOP_PIXEL_VERIFICATION_PASS', result['mode'])


if __name__ == '__main__':
    main()
