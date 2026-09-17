#!/usr/bin/env python3
"""Close acceleration is revoked by concurrency and restored only by exec."""
import ctypes
import errno
import os
import signal
import sys
import tempfile
import threading

signal.alarm(30)
libc = ctypes.CDLL(None, use_errno=True)
hits = libc.proot_fastclose_hits
hits.restype = ctypes.c_ulong
with tempfile.TemporaryDirectory() as directory:
    path = directory + '/file'
    fd = os.open(path, os.O_CREAT | os.O_RDWR, 0o600)
    os.write(fd, b'close data')
    before = hits()
    ctypes.set_errno(errno.EBUSY)
    assert libc.close(fd) == 0 and ctypes.get_errno() == errno.EBUSY
    if os.environ.get('PROOT_FASTCLOSE') == '1':
        assert hits() > before, 'no accelerated close before concurrency'
    assert libc.close(fd) == -1 and ctypes.get_errno() == errno.EBADF
    thread = threading.Thread(target=lambda: None)
    thread.start()
    thread.join()
    before = hits()
    fd = os.open(path, os.O_RDONLY)
    assert os.read(fd, 100) == b'close data'
    os.close(fd)
    assert hits() == before, 'close acceleration survived thread creation'
    child = os.fork()
    if child == 0:
        code = "import ctypes,os; c=ctypes.CDLL(None); n=c.proot_fastclose_hits(); f=os.open('/etc/debian_version',os.O_RDONLY); os.close(f); assert os.environ.get('PROOT_FASTCLOSE')!='1' or c.proot_fastclose_hits()>n"
        os.execl('/usr/bin/python3', 'python3', '-c', code)
    assert os.waitpid(child, 0)[1] == 0
    child = os.fork()
    if child == 0:
        code = '''import ctypes,errno,os
c=ctypes.CDLL(None,use_errno=True)
class F(ctypes.Structure):
 _fields_=[('code',ctypes.c_ushort),('jt',ctypes.c_ubyte),('jf',ctypes.c_ubyte),('k',ctypes.c_uint)]
class P(ctypes.Structure):
 _fields_=[('length',ctypes.c_ushort),('filter',ctypes.POINTER(F))]
fd=os.open('/etc/debian_version',os.O_RDONLY)
filters=(F*4)(F(0x20,0,0,0),F(0x15,0,1,57),F(0x06,0,0,0x50000|errno.EPERM),F(0x06,0,0,0x7fff0000))
p=P(4,filters)
assert c.prctl(38,1,0,0,0)==0
assert c.prctl(22,2,ctypes.byref(p),0,0)==0
before=c.proot_fastclose_hits()
assert c.close(fd)==-1 and ctypes.get_errno()==errno.EPERM
assert os.read(fd,100)
assert os.environ.get('PROOT_FASTCLOSE')!='1' or c.proot_fastclose_hits()>before
os._exit(0)
'''
        os.execl('/usr/bin/python3', 'python3', '-c', code)
    assert os.waitpid(child, 0)[1] == 0
print('PASS fastclose: actual hits, errno, EBADF, thread revocation, exec reactivation')
