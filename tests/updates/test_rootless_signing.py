import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest


FAKE_GH = '''#!/usr/bin/env python3
import json, os, shutil, sys
from pathlib import Path
root = Path(os.environ['SIGNING_TEST_STATE'])
args = sys.argv[1:]
with (root / 'calls').open('a') as output:
    output.write(json.dumps(args) + '\\n')
name = args[1]
counter = root / name
count = int(counter.read_text()) + 1 if counter.exists() else 1
counter.write_text(str(count))
if name == 'view':
    if count == 1:
        sys.exit(1)  # Tag push precedes draft creation.
    print('true')
elif name == 'download':
    if count == 1:
        sys.exit(1)  # The draft exists but the request has not arrived.
    shutil.copyfile(root / 'input.tar.gz', 'signing-input.tar.gz')
else:
    raise SystemExit('Unexpected publication operation')
'''


class RootlessSigningTest(unittest.TestCase):
    def test_tag_run_waits_for_draft_and_request_then_checks_source_before_signing(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            gh = root / 'gh'
            gh.write_text(FAKE_GH)
            gh.chmod(0o700)
            sleep = root / 'sleep'
            sleep.write_text('#!/bin/sh\nexit 0\n')
            sleep.chmod(0o700)
            request = json.dumps(dict(commit='1' * 40, tag='v0.1.0-rootless.2', versionCode=1000073)).encode()
            with tarfile.open(root / 'input.tar.gz', 'w:gz') as archive:
                for name, data in (('unsigned.apk', b'fixture'), ('request.json', request)):
                    member = tarfile.TarInfo(name)
                    member.size = len(data)
                    archive.addfile(member, io.BytesIO(data))
            env = dict(os.environ, PATH=f"{root}:{os.environ['PATH']}", SIGNING_TEST_STATE=str(root),
                       GITHUB_REF_NAME='v0.1.0-rootless.2', GITHUB_SHA='2' * 40,
                       GITHUB_RUN_NUMBER='3', TERMINAL_VERSION_CODE_OFFSET='70')
            script = Path(__file__).resolve().parents[2] / 'tools/updates/sign-rootless-prerelease.sh'
            result = subprocess.run(['bash', str(script)], env=env, capture_output=True, text=True, timeout=10)
            self.assertNotEqual(result.returncode, 0)  # Wrong source must never be signed.
            calls = [json.loads(line) for line in (root / 'calls').read_text().splitlines()]
            self.assertEqual([call[1] for call in calls], ['view', 'view', 'download', 'view', 'download', 'view'])
            self.assertFalse(any(call[1] in ('upload', 'edit') for call in calls))
