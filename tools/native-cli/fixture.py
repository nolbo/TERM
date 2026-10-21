#!/usr/bin/env python3
"""Loopback-only TLS/SSH acceptance fixture. Temporary keys, no app payload."""
import argparse
import http.server
import os
from pathlib import Path
import pwd
import signal
import ssl
import subprocess
import tempfile
import threading
import time

parser = argparse.ArgumentParser()
parser.add_argument('--port', type=int, default=18440)
args = parser.parse_args()
with tempfile.TemporaryDirectory(prefix='term-native-fixture-') as temporary:
    root = Path(temporary)
    def run(*command):
        subprocess.run(command, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    run('ssh-keygen', '-q', '-t', 'ed25519', '-N', '', '-f', str(root / 'id_ed25519'))
    run('ssh-keygen', '-q', '-t', 'ed25519', '-N', '', '-f', str(root / 'host'))
    run('openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '2',
        '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost',
        '-keyout', str(root / 'tls.key'), '-out', str(root / 'ca.crt'))
    (root / 'known_hosts').write_text(f'[127.0.0.1]:{args.port + 2} ' + (root / 'host.pub').read_text())
    (root / 'payload').write_text('fixture-pass\n')
    user = pwd.getpwuid(os.getuid()).pw_name
    (root / 'sshd_config').write_text(f'''Port {args.port + 2}
ListenAddress 127.0.0.1
HostKey {root}/host
PidFile {root}/sshd.pid
AuthorizedKeysFile {root}/id_ed25519.pub
StrictModes no
UsePAM no
PasswordAuthentication no
KbdInteractiveAuthentication no
PubkeyAuthentication yes
AllowUsers {user}
AllowTcpForwarding local
PermitOpen 127.0.0.1:{args.port}
PermitTTY no
X11Forwarding no
PermitTunnel no
ForceCommand /bin/echo fixture-pass
''')
    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            name = self.path.removeprefix('/')
            if name not in {'ca.crt', 'id_ed25519', 'known_hosts', 'payload'}:
                self.send_error(404); return
            data = (root / name).read_bytes()
            self.send_response(200); self.send_header('Content-Length', str(len(data))); self.end_headers()
            self.wfile.write(data)
        def log_message(self, *arguments):
            pass
    servers = []
    for port in [args.port, args.port + 1]:
        server = http.server.ThreadingHTTPServer(('127.0.0.1', port), Handler)
        if port == args.port + 1:
            tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            tls.load_cert_chain(root / 'ca.crt', root / 'tls.key')
            server.socket = tls.wrap_socket(server.socket, server_side=True)
        threading.Thread(target=server.serve_forever, daemon=True).start(); servers.append(server)
    daemon = subprocess.Popen(['/usr/sbin/sshd', '-D', '-e', '-f', str(root / 'sshd_config')])
    try:
        time.sleep(0.3)
        if daemon.poll() is not None:
            raise RuntimeError('Fixture sshd failed')
        print(f'READY nativeFixturePort={args.port} nativeFixtureUser={user}', flush=True)
        signal.pause()
    finally:
        daemon.terminate(); daemon.wait(timeout=5)
        for server in servers:
            server.shutdown()
