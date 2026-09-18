#!/usr/bin/env python3
"""Isolated nscd experiment. Bind a whole private /etc and cache dirs first.

Usage: python3 nss-cache-test.py EXTRACTED_NSCD PRIVATE_TRIAL_DIRECTORY
The fixture must contain root and probe (uid/gid 1234); no real account files.
Bind PRIVATE_TRIAL_DIRECTORY to /etc, its run to /run/nscd and cache to
/var/cache/nscd. Whole-directory binding keeps inotify parent watches valid.
"""
import grp
import json
import os
import pathlib
import pwd
import subprocess
import sys
import time

binary, trial = sys.argv[1:]
trial = pathlib.Path(trial)
assert pathlib.Path('/etc/passwd').read_bytes() == (trial / 'passwd').read_bytes()
assert pathlib.Path('/etc/group').read_bytes() == (trial / 'group').read_bytes()
# Reset only private, deliberately constructed fixtures for repeatable checks.
assert '/terminal-nss-cache-' in str(trial)
(trial / 'passwd').write_text('root:x:0:0:root:/root:/bin/sh\nprobe:x:1234:1234:probe:/tmp:/bin/sh\n')
(trial / 'group').write_text('root:x:0:\nprobe:x:1234:\n')
config = trial / 'nscd.conf'
config.write_text('threads 3\n' + ''.join(
    f'enable-cache {table} yes\npositive-time-to-live {table} 2\n'
    f'negative-time-to-live {table} 1\ncheck-files {table} yes\n'
    f'persistent {table} no\nshared {table} yes\n'
    for table in ('passwd', 'group')))
client = '''import pwd,grp
for i in range(100):
 assert pwd.getpwnam('probe').pw_uid == 1234
 assert grp.getgrnam('probe').gr_gid == 1234
maps=open('/proc/self/maps').read().splitlines()
# Nonpersistent nscd databases use unlinked run/dbXXXXXX backing files.
shared=[line for line in maps if 'r--s' in line and '/run/db' in line]
print('SHARED_DATABASES', len(shared))
'''


def observed(name):
    values = []
    for lookup, field in ((pwd.getpwnam, 'pw_uid'), (grp.getgrnam, 'gr_gid')):
        try:
            values.append(getattr(lookup(name), field))
        except KeyError:
            values.append(None)
    return values


def wait_for(name, expected):
    start = time.monotonic()
    while observed(name) != expected:
        assert time.monotonic() - start < 10, (name, observed(name), expected)
        time.sleep(0.05)
    return round((time.monotonic() - start) * 1000, 3)


def replace_fixture(name, contents):
    temporary = trial / (name + '.new')
    temporary.write_text(contents)
    temporary.replace(trial / name)


with (trial / 'daemon.log').open('w+') as log:
    daemon = subprocess.Popen([binary, '-d', '-f', str(config)], stdout=log, stderr=log)
    try:
        deadline = time.monotonic() + 10
        while not pathlib.Path('/run/nscd/socket').exists():
            if daemon.poll() is not None or time.monotonic() > deadline:
                log.seek(0)
                raise RuntimeError('nscd did not start: ' + log.read())
            time.sleep(0.05)
        result = subprocess.run([sys.executable, '-c', client], check=True,
                                capture_output=True, text=True, timeout=10)
        print(result.stdout, end='', flush=True)
        assert 'SHARED_DATABASES 2' in result.stdout
        log.seek(0)
        requests = log.read()
        # 200 name lookups need only one cache miss per database.
        assert requests.count('GETPWBYNAME (probe)') == 1
        assert requests.count('GETGRBYNAME (probe)') == 1
        print('NAME_LOOKUPS 200 DAEMON_NAME_REQUESTS 2', flush=True)
        children = []
        try:
            for _ in range(4):
                children.append(subprocess.Popen([sys.executable, '-c', client],
                                                 stdout=subprocess.DEVNULL))
            for child in children:
                assert child.wait(timeout=10) == 0
        finally:
            for child in children:
                if child.poll() is None:
                    child.kill()
                child.wait(timeout=5)
        print('CONCURRENT_CLIENTS_PASS', flush=True)
        # Change only the private fixture behind the bind, preserving its inode.
        account = trial / 'passwd'
        account.write_text(account.read_text().replace('probe:x:1234:', 'probe:x:1235:'))
        group = trial / 'group'
        group.write_text(group.read_text().replace('probe:x:1234:', 'probe:x:1235:'))
        refresh = {'in_place_ms': wait_for('probe', [1235, 1235])}
        replace_fixture('passwd', account.read_text().replace('probe:x:1235:', 'probe:x:1236:'))
        replace_fixture('group', group.read_text().replace('probe:x:1235:', 'probe:x:1236:'))
        refresh['replace_ms'] = wait_for('probe', [1236, 1236])
        assert observed('newprobe') == [None, None]
        replace_fixture('passwd', account.read_text() + 'newprobe:x:2345:2345:new:/tmp:/bin/sh\n')
        replace_fixture('group', group.read_text() + 'newprobe:x:2345:\n')
        refresh['negative_to_positive_ms'] = wait_for('newprobe', [2345, 2345])
        replace_fixture('passwd', 'root:x:0:0:root:/root:/bin/sh\n')
        replace_fixture('group', 'root:x:0:\n')
        refresh['deleted_account_ms'] = wait_for('probe', [None, None])
        wait_for('newprobe', [None, None])
        replace_fixture('passwd', account.read_text() + 'probe:x:1235:1235:probe:/tmp:/bin/sh\n')
        replace_fixture('group', group.read_text() + 'probe:x:1235:\n')
        wait_for('probe', [1235, 1235])
        print('CACHE_REFRESH_PASS ' + json.dumps(refresh), flush=True)
    finally:
        if daemon.poll() is None:
            daemon.terminate()
        try:
            daemon.wait(timeout=5)
        except subprocess.TimeoutExpired:
            daemon.kill()
            daemon.wait(timeout=5)
    subprocess.run([sys.executable, '-c',
                    "import pwd,grp; assert pwd.getpwnam('probe').pw_uid==1235; "
                    "assert grp.getgrnam('probe').gr_gid==1235"], check=True, timeout=10)
    print(json.dumps({'cache_refresh': True, 'daemon_stopped': True,
                      'fallback_after_stop': True}))
