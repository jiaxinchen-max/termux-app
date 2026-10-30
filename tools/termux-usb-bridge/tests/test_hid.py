# SPDX-License-Identifier: MIT
import ctypes as C
from pathlib import Path
import sys
import unittest

LIBRARY = sys.argv.pop(1)
DESCRIPTOR = bytes.fromhex("05 01 09 05 a1 01 05 09 19 01 29 08 15 00 25 01 75 01 95 08 81 02 05 01 09 30 09 31 15 81 25 7f 75 08 95 02 81 02 09 39 15 00 25 07 75 04 95 01 81 42 75 04 95 01 81 03 c0")


class Field(C.Structure):
    _fields_ = [("offset",C.c_uint32),("minimum",C.c_int32),("maximum",C.c_int32),
                ("report",C.c_uint16),("bits",C.c_uint16),("kind",C.c_uint16),("index",C.c_uint16)]
class Descriptor(C.Structure):
    _fields_ = [("count",C.c_size_t),("report_ids",C.c_int),("fields",Field*128)]
class State(C.Structure):
    _fields_ = [("sequence",C.c_uint32),("connected",C.c_uint32),("buttons",C.c_uint32),("axes",C.c_int16*8),("hat",C.c_uint32)]


class HIDTests(unittest.TestCase):
    def setUp(self):
        self.lib=C.CDLL(LIBRARY,use_errno=True)
        self.lib.tusb_hid_parse.argtypes=[C.c_void_p,C.c_size_t,C.POINTER(Descriptor)]
        self.lib.tusb_hid_report.argtypes=[C.POINTER(Descriptor),C.c_void_p,C.c_size_t,C.POINTER(State)]

    def test_axes_buttons_hat_and_null(self):
        descriptor=Descriptor();state=State()
        self.assertEqual(self.lib.tusb_hid_parse(DESCRIPTOR,len(DESCRIPTOR),C.byref(descriptor)),0)
        report=bytes.fromhex("03 81 7f 02")
        self.assertEqual(self.lib.tusb_hid_report(C.byref(descriptor),report,len(report),C.byref(state)),0)
        self.assertEqual((state.buttons,state.axes[0],state.axes[1],state.hat),(3,-32768,32767,2))
        report=bytes.fromhex("00 00 00 0f")
        self.assertEqual(self.lib.tusb_hid_report(C.byref(descriptor),report,len(report),C.byref(state)),0)
        self.assertEqual((state.buttons,state.hat),(0,0))

    def test_report_id_and_truncated_atomicity(self):
        data=DESCRIPTOR[:6]+bytes.fromhex("85 07")+DESCRIPTOR[6:]
        descriptor=Descriptor();state=State()
        self.assertEqual(self.lib.tusb_hid_parse(data,len(data),C.byref(descriptor)),0)
        report=bytes.fromhex("07 01 81 7f 00")
        self.assertEqual(self.lib.tusb_hid_report(C.byref(descriptor),report,len(report),C.byref(state)),0)
        before=bytes(state)
        self.assertEqual(self.lib.tusb_hid_report(C.byref(descriptor),report,len(report)-2,C.byref(state)),-1)
        self.assertEqual(bytes(state),before)

    def test_reject_malformed_and_non_gamepad(self):
        for data in (b"",b"\x75",b"\xb4",DESCRIPTOR[:-1],DESCRIPTOR.replace(b"\x09\x05",b"\x09\x06",1)):
            descriptor=Descriptor();self.assertEqual(self.lib.tusb_hid_parse(data,len(data),C.byref(descriptor)),-1)


if __name__ == "__main__": unittest.main(verbosity=2)
