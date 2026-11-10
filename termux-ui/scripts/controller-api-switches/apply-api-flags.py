#!/usr/bin/env python3
"""Upgrade the existing single-controller UDP DLLs to CMod input flags."""
from pathlib import Path
import sys
root = Path(sys.argv[1])
p = root / 'dlls/dinput/gamepad.c'
s = p.read_text()
assert s.count('if (gamepad_id == 0)') == 1
s = s.replace('if (gamepad_id == 0)', 'if (gamepad_id == 0 || !(buffer[5] & 0x08))')
assert s.count('mapper_type = buffer[5];') == 1
s = s.replace('mapper_type = buffer[5];', 'mapper_type = (buffer[5] & 0x02) ? MAPPER_TYPE_XINPUT : MAPPER_TYPE_STANDARD;')
call = 'gamepad_enum_device( 0, 0, &impl->base.instance, dinput->dwVersion );'
assert s.count(call) == 1
s = s.replace(call, 'if (FAILED(hr = gamepad_enum_device( 0, 0, &impl->base.instance, dinput->dwVersion ))) goto failed;')
p.write_text(s)
p = root / 'dlls/xinput1_3/main.c'
s = p.read_text()
assert s.count('if (gamepad_id > 0)') == 1
s = s.replace('if (gamepad_id > 0)', 'if (gamepad_id > 0 && (buffer[5] & 0x04))')
assert s.count('else if (gamepad_id == 0)') == 1
s = s.replace('else if (gamepad_id == 0)', 'else')
p.write_text(s)
