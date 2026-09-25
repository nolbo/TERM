"""Exercise unchanged Python threading/concurrent.futures in a disposable root."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
from pathlib import Path
import shutil
import threading

root = Path('/tmp/python-threads')
root.mkdir()
local = threading.local()

def task(index):
    local.value = index
    data = (f'thread:{index}\n' * 32).encode()
    path = root / f'file-{index}'
    path.write_bytes(data)
    renamed = path.with_suffix('.done')
    path.rename(renamed)
    assert renamed.read_bytes() == data and renamed.stat().st_size == len(data)
    assert local.value == index
    return hashlib.sha256(data).hexdigest()

with ThreadPoolExecutor(max_workers=8) as pool:
    results = list(pool.map(task, range(64)))
assert len(results) == len(set(results)) == 64
shutil.rmtree(root)
print('PASS Python ThreadPoolExecutor, thread-local state and concurrent file work')
