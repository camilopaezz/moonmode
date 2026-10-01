#!/usr/bin/env python3
"""Check a visible Android app/widget track against the system Material You palette.

Requires adb and Pillow. Keep the target visible in the requested appearance.
Choose a point inside the track background, away from icons and selected cells.

Examples:
    python3 scripts/check-material-you.py --serial SERIAL --appearance dark --point 86 2220
    python3 scripts/check-material-you.py --serial SERIAL --appearance dark \
        --package com.miui.home --point X Y
"""

import argparse
import io
import subprocess
import sys

from PIL import Image


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--appearance', choices=('light', 'dark'), required=True)
    parser.add_argument('--package', default='dev.camilo.st2mode')
    parser.add_argument('--point', type=int, nargs=2, metavar=('X', 'Y'), required=True)
    parser.add_argument('--image', help='Use a fresh device-tool screenshot for secondary displays')
    args = parser.parse_args()
    adb = ['adb', '-s', args.serial]

    def read(*command):
        return subprocess.check_output(adb + list(command), text=True, timeout=15).strip()

    windows = read('shell', 'dumpsys', 'window')
    focuses = [line for line in windows.splitlines() if 'mCurrentFocus=' in line]
    eligible = focuses if args.image else focuses[:1]
    if not any(args.package + '/' in line for line in eligible):
        sys.exit('CHECK BLOCKED: target is not focused on the capture display; use --image for a device-tool capture')

    sdk = int(read('shell', 'getprop', 'ro.build.version.sdk'))
    if sdk >= 34:
        resource = 'android:color/system_surface_variant_' + args.appearance
    else:
        tone = '700' if args.appearance == 'dark' else '100'
        resource = 'android:color/system_neutral2_' + tone
    color = read('shell', 'cmd', 'overlay', 'lookup', 'android', resource)
    expected = tuple(int(color[-6:][i:i + 2], 16) for i in (0, 2, 4))
    source = args.image if args.image else io.BytesIO(subprocess.check_output(
        adb + ['exec-out', 'screencap', '-p'], timeout=15,
    ))
    image = Image.open(source).convert('RGB')
    point = tuple(args.point)
    if not (0 <= point[0] < image.width and 0 <= point[1] < image.height):
        sys.exit('CHECK BLOCKED: the point is outside the screenshot')
    actual = image.getpixel(point)

    def hex_color(value):
        return '#' + ''.join(f'{channel:02x}' for channel in value)

    print(f'Control track at {point}: {hex_color(actual)}')
    print(f'Material You surface variant: {hex_color(expected)}')
    passed = actual == expected
    print('PASS: controls use the system palette' if passed else 'FAIL: controls do not use the system palette')
    return 0 if passed else 1


if __name__ == '__main__':
    sys.exit(main())
