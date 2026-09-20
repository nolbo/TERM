#!/usr/bin/env python3
"""Disposable dpkg install/upgrade/remove with an actual tar hardlink entry."""
import io
import os
import subprocess
import tarfile
import tempfile


os.environ["PATH"] = "/usr/sbin:/usr/bin:/sbin:/bin"

def archive(entries):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode='w:gz') as stream:
        for name, content, link in entries:
            info = tarfile.TarInfo(name)
            info.mode = 0o644
            if name.endswith("/"):
                info.type = tarfile.DIRTYPE
                info.mode = 0o755
                stream.addfile(info)
            elif link:
                info.type = tarfile.LNKTYPE
                info.linkname = link
                stream.addfile(info)
            else:
                info.size = len(content)
                stream.addfile(info, io.BytesIO(content))
    return output.getvalue()


with tempfile.TemporaryDirectory() as root:
    target = root + '/target'
    os.makedirs(target + '/var/lib/dpkg/updates')
    open(target + '/var/lib/dpkg/status', 'w').close()
    for version in (1, 2):
        control = f'Package: terminal-link-probe\nVersion: {version}\nArchitecture: all\nMaintainer: Test <test@example.invalid>\nDescription: disposable link probe\n'.encode()
        contents = str(version).encode()
        members = [('debian-binary', b'2.0\n'),
                   ('control.tar.gz', archive([('./control', control, None)])),
                   ('data.tar.gz', archive([(directory, b'', None) for directory in ('./usr/', './usr/share/', './usr/share/terminal-link-probe/')] + [('./usr/share/terminal-link-probe/a', contents, None),
                                            ('./usr/share/terminal-link-probe/b', b'', './usr/share/terminal-link-probe/a')]))]
        package = root + '/package.deb'
        with open(package, 'wb') as output:
            output.write(b'!<arch>\n')
            for name, data in members:
                output.write(f'{name + "/":<16}{0:<12}{0:<6}{0:<6}{100644:<8}{len(data):<10}`\n'.encode())
                output.write(data)
                if len(data) % 2:
                    output.write(b'\n')
        subprocess.run(['dpkg', '--root=' + target, '--log=' + root + '/dpkg.log', '--install', package], check=True)
        first = target + '/usr/share/terminal-link-probe/a'
        second = target + '/usr/share/terminal-link-probe/b'
        assert open(second, 'rb').read() == contents
        assert os.stat(first).st_ino == os.stat(second).st_ino
        assert os.stat(first).st_nlink == 2
    subprocess.run(['dpkg', '--root=' + target, '--log=' + root + '/dpkg.log', '--remove', 'terminal-link-probe'], check=True)
    assert not os.path.exists(first) and not os.path.exists(second)
print('PASS isolated dpkg install upgrade remove with emulated hardlinks')
