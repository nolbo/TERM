import ctypes, json, os, signal
signal.alarm(20)
c = ctypes.CDLL(None, use_errno=True)
c.proot_cost_count.argtypes = [ctypes.c_uint, ctypes.c_uint]
c.proot_cost_count.restype = ctypes.c_ulong
c.proot_cost_reset.restype = None
c.proot_fastfd_single_safe.restype = ctypes.c_uint
names = {29:'ioctl',56:'openat',57:'close',78:'readlinkat',79:'fstatat',80:'fstat',100:'fstatfs',135:'rt_sigprocmask',172:'getpid',270:'process_vm_readv',271:'process_vm_writev'}
path='/tmp/cost-file'
with open(path,'wb') as f: f.write(b'x'*448)
# Establish the mapping and libc bindings before the observation window.
f=open(path,'rb'); assert f.read()==b'x'*448; f.close()
rows=[]
def snapshot(label):
    result={'operation':label,'single_safe':c.proot_fastfd_single_safe()}
    for kind, label in enumerate(('ordinary_dso','trampoline_dso')):
        counts={names.get(n,str(n)):c.proot_cost_count(kind,n) for n in range(512)}
        result[label]={n:v for n,v in counts.items() if v}
    rows.append(result)
c.proot_cost_reset(); f=open(path,'rb'); snapshot('python-open')
c.proot_cost_reset(); assert f.read()==b'x'*448; snapshot('python-read')
c.proot_cost_reset(); f.close(); snapshot('python-close')
c.proot_cost_reset(); fd=os.open(path,os.O_RDONLY); snapshot('os-open')
c.proot_cost_reset(); assert os.fstat(fd).st_size==448; snapshot('os-fstat')
c.proot_cost_reset(); os.close(fd); snapshot('os-close')
print(json.dumps(rows))
