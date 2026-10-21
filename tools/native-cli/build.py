#!/usr/bin/env python3
"""Pinned Android/Bionic CLI build. No downloaded on-device executable code."""
import hashlib
import fcntl
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request

REPO = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
BUILD = Path(sys.argv[1]).resolve()
NDK = Path(sys.argv[2]).resolve()
JOBS = os.environ.get('TERMINAL_NATIVE_JOBS', '4')
BUILD.mkdir(parents=True, exist_ok=True)
BUILD_LOCK = (BUILD / '.build.lock').open('w')
fcntl.flock(BUILD_LOCK, fcntl.LOCK_EX)
PREFIX = BUILD / 'prefix'
JNI = BUILD / 'jniLibs/arm64-v8a'
ASSETS = BUILD / 'assets/native-cli'
for directory in [PREFIX, JNI, ASSETS, BUILD / 'downloads', BUILD / 'logs']:
    directory.mkdir(parents=True, exist_ok=True)

ENV = os.environ.copy()
ENV.update(TERMINAL_ANDROID_NDK=str(NDK), LC_ALL='C', TZ='UTC', SOURCE_DATE_EPOCH='1781913600')
if os.uname().machine != 'aarch64':
    # CI uses the matching NDK tools, without depending on host LLVM symlinks.
    ENV['PATH'] = str(NDK / 'toolchains/llvm/prebuilt/linux-x86_64/bin') + os.pathsep + ENV['PATH']
ENV.update(CC=str(REPO / 'tools/iphone/android-clang.sh'), AR='llvm-ar', RANLIB='llvm-ranlib')
ENV['CFLAGS'] = '-Os -fPIC -ffunction-sections -fdata-sections -fstack-protector-strong -D_FORTIFY_SOURCE=2 ' + f'-ffile-prefix-map={BUILD}=/native-build'
ENV['LDFLAGS'] = '-Wl,--gc-sections,-z,relro,-z,now,-z,noexecstack,-z,max-page-size=16384,-z,common-page-size=16384'
ENV['CPPFLAGS'] = f'-I{PREFIX}/include'
ENV['LIBS'] = ''


def run(args, cwd, env=ENV):
    subprocess.run([str(a) for a in args], cwd=cwd, env=env, check=True)


def configure(src, *args, extra=None):
    env = ENV | (extra or {})
    state = hashlib.sha256((src / 'configure').read_bytes() + json.dumps(
        [args, {k: env.get(k) for k in sorted(set(['CC', 'AR', 'RANLIB', 'CFLAGS', 'CPPFLAGS', 'LDFLAGS']) | set(extra or {}))}],
        sort_keys=True).encode()).hexdigest()
    stamp = src / '.term-configure'
    if stamp.exists() and stamp.read_text() == state:
        return
    run(['sh', 'configure', '--host=aarch64-linux-android', f'--prefix={PREFIX}', *args], src, env)
    stamp.write_text(state)


def make(src, *args, env=ENV):
    run(['make', '-j' + JOBS, *args], src, env)


def publish(src, name):
    target = JNI / ('lib' + name + '.so')
    shutil.copyfile(src, target)
    run(['llvm-strip', '--strip-unneeded', target], BUILD)
    target.chmod(0o755)


sources = []
for line in (HERE / 'sources.lock').read_text().splitlines():
    if not line or line.startswith('#'):
        continue
    name, version, digest, url = line.split()
    archive = BUILD / 'downloads' / (name + '.archive')
    if not archive.exists():
        temporary = archive.with_suffix('.download')
        with urllib.request.urlopen(url, timeout=180) as response, temporary.open('wb') as output:
            shutil.copyfileobj(response, output)
        if hashlib.sha256(temporary.read_bytes()).hexdigest() != digest:
            raise RuntimeError('Source checksum mismatch: ' + name)
        temporary.replace(archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != digest:
        raise RuntimeError('Source checksum mismatch: ' + name)
    src = BUILD / 'sources' / name
    if not src.exists():
        src.mkdir(parents=True)
        run(['tar', 'xf', archive, '--strip-components=1', '-C', src], BUILD)
    sources.append(dict(name=name, version=version, sha256=digest, source=url))

# A recipe change invalidates all dependent outputs, with no clean Gradle build.
compiler_version = subprocess.check_output([ENV['CC'], '--version'], env=ENV, text=True).splitlines()[0]
signature = hashlib.sha256(Path(__file__).read_bytes() + (HERE / 'sources.lock').read_bytes() +
                           (REPO / 'tools/iphone/android-clang.sh').read_bytes() + compiler_version.encode() +
                           (NDK / 'source.properties').read_bytes()).hexdigest()
for item in sources:
    name = item['name']
    src = BUILD / 'sources' / name
    stamp = BUILD / (name + '.stamp')
    required = {'openssl': ['libterm-crypto.so', 'libterm-ssl.so'], 'jq': ['libjq.so'],
                'curl': ['libcurl.so'], 'openssh': ['libssh.so', 'libssh-keygen.so'],
                'rg': ['librg.so'], 'vim': ['libvim.so']}.get(name, [])
    if stamp.exists() and stamp.read_text() == signature and all((JNI / p).is_file() for p in required):
        continue
    print('Building', name, item['version'], flush=True)
    if name == 'openssl':
        # linux-aarch64 selects ELF/AArch64 assembly; the compiler wrapper supplies
        # the NDK's API-31 Bionic ABI. Avoid x86-only NDK host executables.
        run(['perl', 'Configure', 'linux-aarch64', 'no-shared', 'no-module', 'no-tests',
             'no-apps', 'no-docs', 'no-secure-memory', f'--prefix={PREFIX}', '--libdir=lib'], src)
        make(src, 'build_libs')
        make(src, 'install_dev')
        for lib, dependencies in [('crypto', ['-ldl']), ('ssl', ['-L' + str(JNI), '-lterm-crypto'])]:
            run([ENV['CC'], '-shared', *ENV['LDFLAGS'].split(), '-Wl,-soname,libterm-' + lib + '.so',
                 '-Wl,--whole-archive', PREFIX / 'lib' / ('lib' + lib + '.a'),
                 '-Wl,--no-whole-archive', *dependencies, '-o', JNI / ('libterm-' + lib + '.so')], BUILD)
            run(['llvm-strip', '--strip-unneeded', JNI / ('libterm-' + lib + '.so')], BUILD)
            # Link clients to the shared APK dependency, not duplicate static crypto.
            link = PREFIX / 'lib' / ('lib' + lib + '.so')
            link.unlink(missing_ok=True)
            link.symlink_to(JNI / ('libterm-' + lib + '.so'))
    elif name == 'ncurses':
        configure(src, '--enable-widec', '--without-shared', '--without-debug', '--without-ada',
                  '--without-cxx', '--without-cxx-binding', '--without-progs', '--without-tests',
                  '--without-manpages', '--disable-db-install', '--disable-stripping')
        make(src)
        make(src, 'install.libs', 'install.includes')
    elif name == 'jq':
        configure(src, '--disable-shared', '--disable-docs', '--disable-maintainer-mode', '--with-oniguruma=builtin')
        make(src)
        publish(src / 'jq', 'jq')
    elif name == 'curl':
        configure(src, '--disable-shared', '--enable-static', '--disable-docs', '--disable-manual',
                  '--disable-ldap', '--disable-ldaps', '--disable-rtsp', '--disable-dict', '--disable-telnet',
                  '--disable-tftp', '--disable-pop3', '--disable-imap', '--disable-smtp', '--disable-gopher',
                  '--disable-smb', '--disable-mqtt', '--without-libpsl', '--without-libidn2',
                  '--without-brotli', '--without-zstd', '--without-nghttp2', '--without-libssh2',
                  '--without-librtmp', '--with-openssl=' + str(PREFIX), '--with-zlib',
                  '--without-ca-bundle', '--with-ca-path=/system/etc/security/cacerts')
        make(src)
        publish(src / 'src/curl', 'curl')
    elif name == 'openssh':
        # Bionic's passwd home is '/' rather than this Environment's HOME.
        needle = '\tpw = pwcopy(pw);'
        replacement = needle + '\n\t/* TERM: honor the app-private Environment home. */\n\tif (getenv("HOME") != NULL && getenv("HOME")[0] == \'/\') {\n\t\tfree(pw->pw_dir);\n\t\tpw->pw_dir = xstrdup(getenv("HOME"));\n\t}'
        for filename in ['ssh.c', 'ssh-keygen.c']:
            path = src / filename
            text = path.read_text()
            if 'TERM: honor' not in text:
                assert text.count(needle) == 1
                path.write_text(text.replace(needle, replacement))
        # Bionic bzero is a function-like macro, not an addressable symbol.
        # Preserve OpenSSH's volatile-indirection zeroization with memset.
        path = src / 'openbsd-compat/explicit_bzero.c'
        text = path.read_text().replace('static void (* volatile ssh_bzero)(void *, size_t) = bzero;',
            'static void *(* volatile ssh_memset)(void *, int, size_t) = memset;')
        path.write_text(text.replace('ssh_bzero(p, n);', '(void)ssh_memset(p, 0, n);'))
        # Android exposes res_init/res_query but deliberately hides glibc's
        # mutable resolver state. Keep DNS queries on those public Bionic APIs.
        path = src / 'openbsd-compat/getrrsetbyname.c'
        text = path.read_text()
        if 'TERM: Bionic resolver' not in text:
            text = text.replace('#ifndef HAVE__RES_EXTERN', '#if !defined(HAVE__RES_EXTERN) && !defined(__ANDROID__)')
            needle = '\tstruct __res_state *_resp = _THREAD_PRIVATE(_res, _res, &_res);'
            assert needle in text
            text = text.replace(needle, '#ifndef __ANDROID__\n' + needle + '\n#endif')
            begin = text.index('\t/* initialize resolver */')
            end = text.index('\t/* make query */', begin)
            text = text[:begin] + '\t/* TERM: Bionic resolver has no public __res_state. */\n#ifdef __ANDROID__\n\tif (res_init() == -1) { result = ERRSET_FAIL; goto fail; }\n#else\n' + text[begin:end] + '#endif\n\n' + text[end:]
            path.write_text(text)
        path = src / 'misc.c'
        text = path.read_text()
        start = text.index('int\ntilde_expand(')
        end = text.index('\nchar *\ntilde_expand_filename', start)
        block = text[start:end]
        if 'TERM: tilde home' not in block:
            block = block.replace('const char *path = NULL, *user = NULL;', 'const char *path = NULL, *user = NULL, *home;')
            marker = '\t/* Make sure directory has a trailing'
            block = block.replace(marker, '\t/* TERM: tilde home follows the Environment, never Android passwd /. */\n\thome = user == NULL && getenv("HOME") != NULL && getenv("HOME")[0] == \'/\' ? getenv("HOME") : pw->pw_dir;\n\n' + marker)
            begin = block.index(marker)
            block = block[:begin] + block[begin:].replace('pw->pw_dir', 'home')
            path.write_text(text[:start] + block + text[end:])
        configure(src, '--without-pam', '--without-selinux', '--without-libedit', '--without-kerberos5', '--without-retpoline', '--disable-pkcs11',
                  '--without-ldns', '--without-security-key-builtin', '--without-zlib',
                  '--with-ssl-dir=' + str(PREFIX), '--sysconfdir=/system/etc/ssh',
                  extra={'ac_cv_func_getresuid': 'no', 'ac_cv_func_getresgid': 'no',
                         'ac_cv_func_malloc_0_nonnull': 'yes', 'ac_cv_func_realloc_0_nonnull': 'yes',
                         'ac_cv_func_calloc_0_nonnull': 'yes', 'CONFIG_SHELL': '/bin/bash',
                         'ac_cv_func_bzero': 'yes',
                         'CPPFLAGS': ENV['CPPFLAGS'] + ' -DHAVE_ATTRIBUTE__SENTINEL__=1'})
        make(src, 'ssh', 'ssh-keygen')
        publish(src / 'ssh', 'ssh')
        publish(src / 'ssh-keygen', 'ssh-keygen')
    elif name == 'rg':
        cargo = os.environ.get('TERMINAL_CARGO', str(Path.home() / '.cargo/bin/cargo'))
        env = ENV | {'CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER': ENV['CC'],
                     'CARGO_TARGET_DIR': str(BUILD / 'cargo-target'),
                     'RUSTFLAGS': '-C opt-level=z -C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384',
                     'CARGO_PROFILE_RELEASE_DEBUG': '0'}
        run([cargo, '+1.89.0', 'build', '--locked', '--release', '--target', 'aarch64-linux-android'], src, env)
        publish(BUILD / 'cargo-target/aarch64-linux-android/release/rg', 'rg')
        vendor = BUILD / 'rg-vendor'
        if not vendor.exists():
            run([cargo, '+1.89.0', 'vendor', '--locked', '--versioned-dirs', str(vendor)], src, env)
        for dependency in vendor.iterdir():
            target = BUILD / 'assets/licenses/native-cli/rg-dependencies' / dependency.name
            target.mkdir(parents=True, exist_ok=True)
            for license in list(dependency.glob('LICENSE*')) + list(dependency.glob('COPYING*')) + list(dependency.glob('UNLICENSE*')):
                if license.is_file():
                    shutil.copyfile(license, target / license.name)
        shutil.copyfile(src / 'Cargo.lock', BUILD / 'assets/licenses/native-cli/rg-Cargo.lock')
    elif name == 'vim':
        env = {k: 'yes' for k in ['vim_cv_toupper_broken', 'vim_cv_terminfo', 'vim_cv_tty_group',
                                   'vim_cv_getcwd_broken', 'vim_cv_stat_ignores_slash', 'vim_cv_memmove_handles_overlap']}
        env.update(vim_cv_toupper_broken='no', vim_cv_getcwd_broken='no', vim_cv_stat_ignores_slash='no',
                   vim_cv_tty_group='world', vim_cv_tty_mode='0620', vim_cv_bcopy_handles_overlap='yes',
                   vim_cv_memcpy_handles_overlap='no', ac_cv_small_wchar_t='no',
                   LDFLAGS=ENV['LDFLAGS'] + f' -L{PREFIX}/lib', CPPFLAGS=ENV['CPPFLAGS'] + f' -I{PREFIX}/include/ncursesw')
        configure(src, '--with-features=normal', '--enable-multibyte', '--disable-gui', '--without-x',
                  '--disable-nls', '--disable-netbeans', '--disable-channel', '--disable-terminal',
                  '--disable-python3interp', '--disable-pythoninterp', '--disable-perlinterp',
                  '--disable-rubyinterp', '--disable-luainterp', '--disable-tclinterp', '--with-tlib=ncursesw', extra=env)
        make(src)
        publish(src / 'src/vim', 'vim')
    stamp.write_text(signature)

runtime = ASSETS / 'runtime/vim'
if runtime.exists():
    shutil.rmtree(runtime)
# Keep syntax, indent, filetype plugins, autoload and colors; no test fixtures,
# runtime generators, GUI/printing resources, help or Git-only file types.
shutil.copytree(BUILD / 'sources/vim/runtime', runtime, ignore=shutil.ignore_patterns(
    'doc', 'tutor', 'spell', 'lang', 'pack', 'tools', 'testdir', 'generator',
    'bitmaps', 'print', 'git*', '*.exe', '*.dll', '*.bat', '*.info'))
# Vim 9.2's default netrwPlugin is a packadd shim: its mandatory package must
# accompany that shim, even though unrelated optional packages are omitted.
shutil.copytree(BUILD / 'sources/vim/runtime/pack/dist/opt/netrw', runtime / 'pack/dist/opt/netrw',
    ignore=shutil.ignore_patterns('doc', 'testdir', 'test*', '.github', 'git*'))
terminfo = ASSETS / 'runtime/terminfo'
if terminfo.exists():
    shutil.rmtree(terminfo)
run(['tic', '-x', '-o', terminfo, '-e', 'xterm,xterm-256color,screen,screen-256color,tmux,tmux-256color,dumb',
     BUILD / 'sources/ncurses/misc/terminfo.src'], BUILD)
shutil.copyfile(HERE / 'cacert.crt', ASSETS / 'runtime/cacert.crt')
shutil.copyfile(HERE / 'vimrc', ASSETS / 'runtime/vimrc')
licenses = BUILD / 'assets/licenses/native-cli'
licenses.mkdir(parents=True, exist_ok=True)
for item in sources:
    src = BUILD / 'sources' / item['name']
    for filename in ['COPYING', 'COPYRIGHT', 'LICENCE', 'LICENSE', 'LICENSE.txt', 'LICENSE-MIT', 'UNLICENSE', 'doc/uganda.txt', 'runtime/doc/uganda.txt', 'vendor/oniguruma/COPYING']:
        source = src / filename
        if source.is_file():
            shutil.copyfile(source, licenses / (item['name'] + '-' + filename.replace('/', '-') + '.txt'))
(licenses / 'sources.json').write_text(json.dumps(sources, indent=2) + '\n')
shutil.copyfile(HERE / 'CA-NOTICE.txt', licenses / 'CA-NOTICE.txt')
shutil.copyfile(HERE / 'MPL-2.0.txt', licenses / 'MPL-2.0.txt')
for name in ['rust-COPYRIGHT.txt', 'rust-LICENSE-APACHE.txt', 'rust-LICENSE-MIT.txt', 'RUST-NOTICE-SOURCES.txt']:
    shutil.copyfile(HERE / name, licenses / name)
shutil.copyfile(NDK / 'NOTICE', licenses / 'NDK-NOTICE.txt')
(licenses / 'build.json').write_text(json.dumps({'recipeSha256': signature,
    'ndk': (NDK / 'source.properties').read_text(), 'compiler': compiler_version,
    'rust': '1.89.0', 'api': 31, 'sourceDateEpoch': ENV['SOURCE_DATE_EPOCH']}, indent=2) + '\n')
run([sys.executable, HERE / 'verify.py', JNI, ASSETS, '--write-manifest'], BUILD)
