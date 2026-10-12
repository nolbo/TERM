"""Run with PyUSB 1.3.1 on PYTHONPATH; exercises its real core against a mock transport."""
import importlib.util
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "android-runtime/src/main/assets/usb"))
from usb_pyusb import create_backend


@unittest.skipUnless(importlib.util.find_spec("usb"), "PyUSB is optional; add official 1.3.1 source to PYTHONPATH")
class PyUsbTest(unittest.TestCase):
    def setUp(self):
        import usb.core
        import usb.util
        self.core, self.util = usb.core, usb.util
        self.ports = []
        self.current_config = 1
        self.pending = False
        self.description = dict(id="attachment", vendor=0x1234, product=1, **{"class": 0, "subclass": 0, "protocol": 0},
                                configurations=[dict(value=1, maxPower=100, selfPowered=False, remoteWakeup=False,
                                    interfaces=[dict(id=3, alternate=alt, **{"class": 255, "subclass": 0, "protocol": 0},
                                        endpoints=[dict(address=0x81, type=2, packetSize=64, interval=0),
                                                   dict(address=1, type=2, packetSize=64, interval=0)]) for alt in (0, 1)])])
        test = self
        class Broker:
            def __enter__(self): return self
            def __exit__(self, *unused): pass
            def call(self, op): return {"devices": [test.description]}
        class Raw:
            def __init__(self, device, intf, config, alternate):
                if config != test.current_config: raise RuntimeError("CONFIGURATION_CHANGE_REQUIRED")
                self.metadata = dict(configuration=config, alternate=alternate)
                self.interface, self.closed = intf, False
                self.writes = []
                test.ports.append(self)
            def __enter__(self): return self
            def __exit__(self, *unused): self.close()
            def close(self): self.closed = True
            def poll(self, endpoint, size, timeout): return None if test.pending else b"\0\xff"
            def write(self, endpoint, data, timeout): self.writes.append(bytes(data)); return len(data)
            def control(self, request_type, request, value, index, data, timeout): return b"ab" if request_type & 128 else len(data)
        self.backend = create_backend(Broker, Raw)
        self.device = self.core.find(idVendor=0x1234, backend=self.backend)

    def tearDown(self):
        self.util.dispose_resources(self.device)

    def test_real_core_enumeration_descriptors_io_and_alternate_releases(self):
        self.assertIsNotNone(self.device)
        config = self.device[0]
        self.assertEqual(1, config.bNumInterfaces)
        self.assertEqual(3, config[(0, 0)].bInterfaceNumber)
        self.assertEqual(0x81, config[(0, 1)][0].bEndpointAddress)
        self.device.set_configuration(1)
        self.assertTrue(self.ports[-1].closed)  # verification doesn't hold an implicit interface
        self.assertEqual(b"\0\xff", bytes(self.device.read(0x81, 64, timeout=100)))
        first = self.ports[-1]
        self.assertEqual(2, self.device.write(1, b"xy", timeout=100))
        self.assertEqual([b"xy"], first.writes)
        self.device.set_interface_altsetting(interface=3, alternate_setting=1)
        self.assertTrue(first.closed)
        self.assertEqual(1, self.ports[-1].metadata["alternate"])
        self.assertEqual(b"ab", bytes(self.device.ctrl_transfer(0xc1, 1, 0, 3, 2)))

    def test_wrong_hardware_configuration_is_not_reported_as_success(self):
        self.current_config = 2
        with self.assertRaisesRegex(self.core.USBError, "CONFIGURATION_CHANGE_REQUIRED"):
            self.device.set_configuration(1)

    def test_idle_timeout_is_specific_and_device_wide_control_is_denied(self):
        self.pending = True
        with self.assertRaises(self.core.USBTimeoutError): self.device.read(0x81, 64, timeout=100)
        self.pending = False
        self.assertEqual(b"\0\xff", bytes(self.device.read(0x81, 64, timeout=100)))
        with self.assertRaisesRegex(self.core.USBError, "Device-recipient"):
            self.device.ctrl_transfer(0xc0, 1, 0, 0, 2)
