import ctypes,json,os,signal
signal.alarm(30)
c=ctypes.CDLL(None)
f=c.proot_procdir_probe
f.argtypes=[ctypes.c_int,ctypes.c_uint,ctypes.c_uint,ctypes.POINTER(ctypes.c_ulonglong)]
f.restype=ctypes.c_int
fd=os.open('/tmp/procdir-file',os.O_CREAT|os.O_RDWR,0o600)
rows=[]
for order in ((0,1,2),(1,2,0),(2,0,1)):
 for mode in order:
  elapsed=ctypes.c_ulonglong()
  assert f(fd,mode,10000,ctypes.byref(elapsed))==0
  rows.append({'mode':mode,'count':10000,'ns':elapsed.value})
os.close(fd)
print(json.dumps(rows))
