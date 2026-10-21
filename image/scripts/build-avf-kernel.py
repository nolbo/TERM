#!/usr/bin/env python3
"""Build the pinned AVF guest kernel with official Android Kleaf (Linux x86_64)."""
import argparse
import gzip
import json
import platform
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request

from avf_kernel import KERNEL_DIR, SOURCE, image_config, requirements, sha256, validate_bundle, validate_config


def build(checkout, output, base_kernel=None):
    if platform.system() != "Linux" or platform.machine() != "x86_64":
        raise RuntimeError("Official Kleaf prebuilts require Linux x86_64; no ARM64 toolchain substitution is permitted")
    checkout, output = Path(checkout).resolve(), Path(output).resolve()
    if output.exists():
        raise ValueError("Kernel output exists; refusing to replace a candidate")
    bazel = checkout / "tools/bazel"
    if not bazel.is_file() or not (checkout / ".repo").is_dir():
        raise ValueError("An official repo-synced common-android16-6.12 checkout is required")
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=checkout / "common", text=True).strip()
    if revision != SOURCE["commonRevision"]:
        raise ValueError("Kernel common source differs from the approved kernel commit")
    if subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=no"], cwd=checkout / "common"):
        raise ValueError("Kernel source has local changes")
    package = checkout / "term_avf"
    if package.exists():
        raise ValueError("term_avf already exists in the external checkout")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".kernel-build-", dir=output.parent) as temporary:
        work = Path(temporary)
        base = work / "base-Image"
        if base_kernel:
            shutil.copyfile(base_kernel, base)
        else:
            # The approved TERM mirror contains Google's exact original bytes.
            with urllib.request.urlopen(SOURCE["baseKernelUri"], timeout=60) as response:
                with gzip.GzipFile(fileobj=response) as stream, base.open("wb") as target:
                    data = stream.read((256 << 20) + 1)
                    if len(data) > 256 << 20:
                        raise ValueError("Base kernel exceeds size limit")
                    target.write(data)
        if sha256(base) != SOURCE["baseKernelSha256"]:
            raise ValueError("Approved base kernel checksum mismatch")
        baseline, release = image_config(base)
        if release != SOURCE["baseKernelVersion"]:
            raise ValueError("Unexpected approved base kernel version")
        package.mkdir()
        try:
            (package / "base_defconfig").write_bytes(baseline)
            for name in ("BUILD.bazel", "containers.fragment"):
                shutil.copyfile(KERNEL_DIR / name, package / name)
            dist = work / "dist"
            subprocess.run([str(bazel), "run", "//term_avf:dist", "--", f"--destdir={dist}"], cwd=checkout, check=True)
            config, release = image_config(dist / "Image")
            if config != (dist / ".config").read_bytes():
                raise ValueError("Kleaf output .config differs from the built Image")
            validate_config(config)
            bundle = work / "bundle"
            bundle.mkdir()
            shutil.copyfile(dist / "Image", bundle / "vmlinuz")
            shutil.copyfile(dist / "initramfs.img", bundle / "initrd.img")
            (bundle / "kernel.config").write_bytes(config)
            resolved = subprocess.check_output(["repo", "manifest", "-r"], cwd=checkout)
            (bundle / "source-manifest.xml").write_bytes(resolved)
            metadata = {
                "formatVersion": 1, "buildSystem": "Android Kleaf", "kernelRelease": release,
                "commonRevision": revision, "baseKernelSha256": SOURCE["baseKernelSha256"],
                "fragmentSha256": sha256(KERNEL_DIR / "containers.fragment"),
                "requiredConfig": requirements(),
                "artifacts": {name: sha256(bundle / name) for name in
                              ("vmlinuz", "initrd.img", "kernel.config", "source-manifest.xml")},
            }
            (bundle / "kernel-build.json").write_text(json.dumps(metadata, indent=2) + "\n")
            validate_bundle(bundle)
            bundle.rename(output)
        finally:
            # Only the newly allocated package in the external checkout is owned here.
            shutil.rmtree(package)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkout", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--base-kernel", type=Path, help="Optional local raw approved Image (hash checked)")
    args = parser.parse_args()
    build(args.checkout, args.output, args.base_kernel)
