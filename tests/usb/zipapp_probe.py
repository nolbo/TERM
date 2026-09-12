"""Run via the generated terminal-usb zipapp to verify the single-file API bind."""
from terminal_usb import RawInterface, SerialPort, pyusb_backend
from usb_profiles import validate
from usb_mtp import MtpClient
from usb_relays import adb_header, serial_pty
from usb_storage import run_storage

assert RawInterface and SerialPort and MtpClient and callable(pyusb_backend)
assert callable(adb_header) and callable(serial_pty) and callable(run_storage)
assert validate(dict(baud=9600, bits=8, stops=0, parity=0, dtr=False, rts=False, port=None))
print("zipapp-api-ok")
