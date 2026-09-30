#!/usr/bin/env python3
"""Deterministic offline inputs; never downloads or copies an untracked tree."""
import gzip
import io
import json
import hashlib
from email.parser import Parser
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import subprocess
import tarfile
import tempfile

from common import SCHEMA, CheckFailure, child_environment, digest, file_digest, require, write_json


GIT = ["git", "-c", "core.hooksPath=/dev/null", "-c", "core.autocrlf=false",
       "-c", "core.fsmonitor=false", "-c", "core.untrackedCache=false",
       "-c", "gc.auto=0", "-c", "maintenance.auto=false"]
NEEDLE = "ROOTLESS_BENCH_NEEDLE"
PACKAGE = "terminal-workload-fixture"
PACKAGE_TIME = 1700000000


def command(argv, cwd, env=None):
    return subprocess.check_output(argv, cwd=cwd, env=env or child_environment(), stderr=subprocess.PIPE)


def safe_extract(archive, destination):
    destination = Path(destination)
    with tarfile.open(archive) as source:
        members = source.getmembers()
        for member in members:
            name = PurePosixPath(member.name)
            require(not name.is_absolute() and ".." not in name.parts, "archive pathname escapes workspace")
            target = destination.joinpath(*name.parts)
            require(target.resolve().is_relative_to(destination.resolve()), "archive target escapes workspace")
            if member.issym():
                link = PurePosixPath(member.linkname)
                require(not link.is_absolute() and
                        (target.parent / str(link)).resolve().is_relative_to(destination.resolve()),
                        "archive symlink escapes workspace")
            require(member.isdir() or member.isreg() or member.issym(), "special archive entry is not allowed")
        # Extract entries ourselves: an earlier symlink cannot redirect a later
        # file outside the disposable root (including under older Python).
        for member in members:
            target = destination / member.name
            require(target.resolve().is_relative_to(destination.resolve()), "archive traversal through symlink")
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
            elif member.issym():
                target.parent.mkdir(parents=True, exist_ok=True)
                target.symlink_to(member.linkname)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                require(not target.is_symlink(), "refuse archive replacement through symlink")
                with source.extractfile(member) as incoming, target.open("wb") as outgoing:
                    shutil.copyfileobj(incoming, outgoing)
                target.chmod(member.mode & 0o777)
                os.utime(target, (member.mtime, member.mtime))


def archive_tree(source, output):
    with tarfile.open(output, "w") as archive:
        for path in sorted(Path(source).rglob("*")):
            relative = path.relative_to(source).as_posix()
            info = archive.gettarinfo(str(path), arcname=relative)
            info.uid = info.gid = 0
            info.uname = info.gname = ""
            info.mtime = PACKAGE_TIME
            if info.isfile():
                with path.open("rb") as stream:
                    archive.addfile(info, stream)
            else:
                archive.addfile(info)


def project_files(root):
    project = root / "bench_project"
    project.mkdir()
    (project / "calc.py").write_text(
        "def total(values):\n    return sum(values) + 1  # deliberate fixture defect\n")
    (project / "test_calc.py").write_text(
        "import unittest\nfrom calc import total\n\nclass Totals(unittest.TestCase):\n"
        "    def test_empty(self): self.assertEqual(total([]), 0)\n"
        "    def test_positive(self): self.assertEqual(total([2, 3, 5]), 10)\n"
        "    def test_negative(self): self.assertEqual(total([-3, 1]), -2)\n")
    (project / "run_tests.py").write_text(
        "import io,json,sys,unittest\n"
        "suite=unittest.defaultTestLoader.loadTestsFromName('test_calc.Totals')\n"
        "stream=io.StringIO(); result=unittest.TextTestRunner(stream=stream).run(suite)\n"
        "print(json.dumps({'tests':result.testsRun,'failures':len(result.failures),'errors':len(result.errors)},sort_keys=True))\n"
        "if not result.wasSuccessful(): print(stream.getvalue(),file=sys.stderr)\n"
        "sys.exit(0 if result.wasSuccessful() else 1)\n")
    # A real dependency-aware incremental build: each source is compiled only
    # when its digest changed; output manifest bytes and rebuild set are checked.
    (project / "build.py").write_text(
        "import hashlib,json,os,py_compile\nfrom pathlib import Path\n"
        "out=Path('build');out.mkdir(exist_ok=True); manifest=out/'manifest.json'\n"
        "old=json.loads(manifest.read_text()) if manifest.exists() else {}; new={}; rebuilt=[]\n"
        "for p in sorted([Path('calc.py'),*Path('modules').glob('*.py')]):\n"
        "    key=p.as_posix(); h=hashlib.sha256(p.read_bytes()).hexdigest(); new[key]=h\n"
        "    if old.get(key)!=h:\n"
        "        py_compile.compile(str(p),cfile=str(out/(key.replace('/','_')+'c')),doraise=True);rebuilt.append(key)\n"
        "temp=out/'manifest.new';temp.write_text(json.dumps(new,sort_keys=True));os.replace(temp,manifest)\n"
        "print(json.dumps({'rebuilt':rebuilt,'sources':len(new)},sort_keys=True))\n")
    modules = project / "modules"
    modules.mkdir()
    for i in range(64):
        (modules / f"m{i:03}.py").write_text(f"def value():\n    return {i}\n")
    (project / "README.txt").write_text(f"offline coding fixture\n{NEEDLE}\n")
    return project


def initialize_git(tree, force=False):
    env = child_environment()
    env.update(GIT_AUTHOR_NAME="Offline Benchmark", GIT_AUTHOR_EMAIL="benchmark@example.invalid",
               GIT_COMMITTER_NAME="Offline Benchmark", GIT_COMMITTER_EMAIL="benchmark@example.invalid",
               GIT_AUTHOR_DATE="2023-11-14T22:13:20+0000", GIT_COMMITTER_DATE="2023-11-14T22:13:20+0000")
    command(GIT + ["init", "--initial-branch=fixture", "-q"], tree, env)
    command(GIT + ["add", *( ["--force"] if force else []), "--all"], tree, env)
    command(GIT + ["add", "--force", "bench_project", ".gitignore", "bench-link"], tree, env)
    command(GIT + ["commit", "--no-gpg-sign", "-qm", "Fixed offline workload input"], tree, env)
    return command(GIT + ["rev-parse", "HEAD"], tree, env).decode().strip()


def rg_commands(rg="rg"):
    return {
        "rg-list": [rg, "--files", "--sort", "path"],
        "rg-search": [rg, "--line-number", "--no-heading", "--color", "never", "--sort", "path", NEEDLE, "."],
        "rg-list-wide": [rg, "--files", "--hidden", "--no-ignore", "--glob", "!.git", "--glob", "!bench_project/build", "--sort", "path"],
        "rg-search-wide": [rg, "--hidden", "--no-ignore", "--glob", "!.git", "--glob", "!bench_project/build", "--line-number",
                           "--no-heading", "--color", "never", "--sort", "path", NEEDLE, "."],
    }


def patch_project(tree):
    path = tree / "bench_project/calc.py"
    require(path.read_text() == "def total(values):\n    return sum(values) + 1  # deliberate fixture defect\n",
            "fixture patch precondition does not match")
    replacement = path.with_suffix(".new")
    replacement.write_text("def total(values):\n    return sum(values)\n")
    replacement.replace(path)


def make_repository(output, name, count=None, source=None, revision=None, paths=()):
    with tempfile.TemporaryDirectory(prefix="repository-", dir=output) as temporary:
        tree = Path(temporary)
        provenance = {"kind": "synthetic", "file_count": count}
        if source:
            require(revision and len(revision) in (40, 64) and all(c in "0123456789abcdef" for c in revision),
                    "real repository requires an explicit full commit hash")
            actual = command(GIT + ["rev-parse", "--verify", revision + "^{commit}"], source).decode().strip()
            require(actual == revision, "revision does not identify the requested full commit")
            archive = tree / "source.tar"
            with archive.open("wb") as stream:
                subprocess.run(GIT + ["archive", "--format=tar", revision, "--", *paths], cwd=source,
                               env=child_environment(), check=True, stdout=stream, stderr=subprocess.PIPE)
            source_hash = file_digest(archive)
            safe_extract(archive, tree)
            archive.unlink()
            provenance = {"kind": "pinned-repository", "commit": revision,
                          "selected_paths": list(paths), "source_archive_sha256": source_hash,
                          "scope": "selected committed subtree" if paths else "complete committed repository"}
            require(not (tree / "bench_project").exists(), "source conflicts with bench_project fixture path")
        else:
            for i in range(count):
                # Fixed shallow/deep paths and dependency-heavy ignored data.
                group = "vendor" if i % 10 == 0 else "src"
                depth = "deep/a/b/c" if i % 4 == 0 else "shallow"
                path = tree / group / depth / f"p{i % 128:03}" / f"f{i:06}.py"
                path.parent.mkdir(parents=True, exist_ok=True)
                marker = NEEDLE if i % 997 == 0 else "ordinary_source"
                path.write_text(f"# {marker}\ndef value():\n    return {i}\n")
        ignore = tree / ".gitignore"
        ignore.write_text((ignore.read_text() if ignore.is_file() else "") +
                          "\n/vendor/\n/ignored/\n/bench_project/build/\n__pycache__/\n*.pyc\n")
        project_files(tree)
        (tree / "bench-link").symlink_to("bench_project/README.txt")
        commit = initialize_git(tree, force=source is not None)
        (tree / "ignored").mkdir(exist_ok=True)
        (tree / "ignored/hidden.txt").write_text(NEEDLE + " ignored\n")
        (tree / "bench-untracked.txt").write_text("untracked " + NEEDLE + "\n")
        (tree / "bench_project/README.txt").write_text("edited documentation\n" + NEEDLE + "\n")
        # Only this newly created disposable repository is repacked. The
        # operator's source repository is read by archive, never repacked/gc'd.
        # Packing avoids shipping a second large loose-object file tree; the
        # measured working tree and its fixed HEAD stay unchanged.
        command(GIT + ["-c", "pack.threads=1", "repack", "-a", "-d"], tree)
        require(command(GIT + ["rev-parse", "HEAD"], tree).decode().strip() == commit,
                "fixture repack changed its commit")
        expected = {}
        commands = {"git-status": GIT + ["status", "--porcelain=v1", "--untracked-files=all"],
                    "git-diff": GIT + ["diff", "--no-ext-diff", "--binary", "--", "."]}
        rg = shutil.which("rg")
        if rg:
            commands.update(rg_commands(rg))
        for key, argv in commands.items():
            data = command(argv, tree)
            expected[key] = {"sha256": digest(data), "bytes": len(data), "lines": data.count(b"\n")}
        artifact = output / (name + ".tar")
        archive_tree(tree, artifact)
        patch_project(tree)
        data = command(commands["git-diff"], tree)
        expected["git-diff-patched"] = {"sha256": digest(data), "bytes": len(data)}
        return {"archive": artifact.name, "sha256": file_digest(artifact), "provenance": provenance,
                "fixture_commit": commit, "expected": expected,
                "git_object_storage": "packed in disposable fixture preparation; working-tree file count unchanged",
                "rg_oracle": "host rg output" if rg else "unavailable: rg cases will be skipped",
                "patched_calc_sha256": file_digest(tree / "bench_project/calc.py")}


def package_payload(version, number):
    return (f"/* deterministic package v{version} file {number:05} */\n".encode() * 24)


def tar_bytes(entries):
    raw = io.BytesIO()
    with tarfile.open(fileobj=raw, mode="w") as archive:
        for name, data, mode, kind, link in entries:
            info = tarfile.TarInfo(name)
            info.mtime = PACKAGE_TIME
            info.mode = mode
            info.type = kind
            info.linkname = link
            info.size = len(data) if kind == tarfile.REGTYPE else 0
            archive.addfile(info, io.BytesIO(data) if info.size else None)
    result = io.BytesIO()
    with gzip.GzipFile(fileobj=result, mode="wb", mtime=0, filename="") as stream:
        stream.write(raw.getvalue())
    return result.getvalue()


def make_package(path, count, version):
    control = (f"Package: {PACKAGE}\nVersion: {version}\nArchitecture: all\n"
               "Maintainer: Offline Test <test@example.invalid>\n"
               "Description: disposable deterministic offline workload\n").encode()
    entries = [(name, b"", 0o755, tarfile.DIRTYPE, "") for name in
               ("./usr", "./usr/share", f"./usr/share/{PACKAGE}")]
    entries.extend((f"./usr/share/{PACKAGE}/{i:05}.txt", package_payload(version, i),
                    0o640 if i % 11 == 0 else 0o644, tarfile.REGTYPE, "") for i in range(count))
    entries.extend([(f"./usr/share/{PACKAGE}/hardlink", b"", 0o640, tarfile.LNKTYPE,
                     f"./usr/share/{PACKAGE}/00000.txt"),
                    (f"./usr/share/{PACKAGE}/symlink", b"", 0o777, tarfile.SYMTYPE, "00000.txt")])
    members = [("debian-binary", b"2.0\n"),
               ("control.tar.gz", tar_bytes([("./control", control, 0o644, tarfile.REGTYPE, "")])),
               ("data.tar.gz", tar_bytes(entries))]
    with path.open("wb") as output:
        output.write(b"!<arch>\n")
        for name, data in members:
            output.write(f'{name + "/":<16}{0:<12}{0:<6}{0:<6}{100644:<8}{len(data):<10}`\n'.encode())
            output.write(data)
            if len(data) % 2:
                output.write(b"\n")
    return {"path": path.name, "sha256": file_digest(path), "count": count, "version": version,
            "package": PACKAGE, "source": "deterministic generated archive; no maintainer scripts"}


def inspect_package(path):
    require(shutil.which("dpkg-deb"), "dpkg-deb is required to pin explicit offline .deb inputs")
    control = subprocess.check_output(["dpkg-deb", "--field", str(path)], env=child_environment())
    metadata = Parser().parsestr(control.decode())
    scripts = []
    control_tar = subprocess.check_output(["dpkg-deb", "--ctrl-tarfile", str(path)], env=child_environment())
    with tarfile.open(fileobj=io.BytesIO(control_tar)) as archive:
        scripts = sorted(member.name.removeprefix("./") for member in archive
                         if member.name.removeprefix("./") in ("preinst", "postinst", "prerm", "postrm", "config", "triggers"))
    payload = []
    process = subprocess.Popen(["dpkg-deb", "--fsys-tarfile", str(path)], env=child_environment(), stdout=subprocess.PIPE)
    try:
        with tarfile.open(fileobj=process.stdout, mode="r|") as archive:
            for member in archive:
                name = PurePosixPath(member.name)
                require(not name.is_absolute() and ".." not in name.parts, "offline package has escaping pathname")
                if member.isdir():
                    continue
                require(len(payload) < 100000, "offline package exceeds bounded 100000-file oracle")
                item = {"path": str(name), "mode": member.mode & 0o7777, "mtime": member.mtime}
                if member.isreg():
                    checksum = hashlib.sha256()
                    with archive.extractfile(member) as stream:
                        for block in iter(lambda: stream.read(1024 * 1024), b""):
                            checksum.update(block)
                    item.update(kind="file", sha256=checksum.hexdigest(), size=member.size)
                elif member.issym():
                    item.update(kind="symlink", target=member.linkname)
                elif member.islnk():
                    target = PurePosixPath(member.linkname)
                    require(not target.is_absolute() and ".." not in target.parts, "offline package hardlink escapes")
                    item.update(kind="hardlink", target=str(target))
                else:
                    item.update(kind="unsupported-special")
                payload.append(item)
        require(process.wait() == 0, "dpkg-deb failed while pinning payload")
    finally:
        process.stdout.close()
        if process.poll() is None:
            process.kill()
            process.wait()
    return {"package": metadata["Package"], "version": metadata["Version"],
            "architecture": metadata["Architecture"], "maintainer_entries": scripts,
            "payload": payload, "scope": "real archive unpack/removal only; dependency configuration is a separate gate"}


def prepare(output, counts=(10000, 100000), package_counts=(1000, 10000), repository=None,
            revision=None, repository_paths=(), offline_debs=()):
    output = Path(output).resolve()
    require(not output.exists(), "bundle destination must not already exist")
    output.mkdir(parents=True)
    manifest = {"schema": SCHEMA, "cache_condition": "fixture preparation warms cache; cold cache is not claimed",
                "repositories": {}, "packages": {}, "offline_debs": [], "preparation_tools": {}}
    for tool in ("git", "rg", "dpkg", "apt-get", "python3"):
        path = shutil.which(tool)
        if path:
            version = subprocess.run([path, "--version"], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                     env=child_environment(), timeout=10)
            manifest["preparation_tools"][tool] = {"sha256": file_digest(path),
                "version": version.stdout.decode(errors="replace").splitlines()[:1]}
    for count in counts:
        require(1 <= count <= 100000, "repository counts must be in 1..100000")
        manifest["repositories"][f"synthetic-{count}"] = make_repository(output, f"synthetic-{count}", count=count)
    if repository is not None:
        manifest["repositories"]["pinned"] = make_repository(output, "pinned", source=Path(repository).resolve(),
            revision=revision, paths=repository_paths)
    for count in package_counts:
        require(1 <= count <= 10000, "package counts must be in 1..10000")
        manifest["packages"][str(count)] = [make_package(output / f"fixture-{count}-v{v}.deb", count, v)
                                            for v in (1, 2)]
    # Explicitly provided package files only; never search caches or fetch them.
    # The safe unpack/removal case never runs maintainer scripts. Packages
    # requiring them remain explicit skipped inputs for a provisioned-root gate.
    for index, source in enumerate(offline_debs):
        source = Path(source).resolve()
        require(source.is_file() and source.suffix == ".deb", "offline package input must be a .deb file")
        destination = output / f"offline-{index:03}.deb"
        shutil.copyfile(source, destination)
        manifest["offline_debs"].append({"path": destination.name, "sha256": file_digest(destination),
                                          "source_name": source.name, **inspect_package(destination)})
    for name in ("common.py", "fixtures.py", "worker.py"):
        shutil.copyfile(Path(__file__).with_name(name), output / name)
    manifest["worker_sources"] = {name: file_digest(output / name) for name in ("common.py", "fixtures.py", "worker.py")}
    write_json(output / "manifest.json", manifest)
    return manifest
