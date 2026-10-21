"""Validation shared by the AVF kernel builder, image factory and tests."""
import hashlib
import json
from pathlib import Path
import re
import zlib

KERNEL_DIR = Path(__file__).resolve().parents[1] / "kernel"
SOURCE = json.loads((KERNEL_DIR / "source.json").read_text())


def sha256(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def parse_config(text):
    values = {}
    for line in text.splitlines():
        if match := re.fullmatch(r"(CONFIG_[A-Za-z0-9_]+)=(.+)", line):
            key, value = match.groups()
        elif match := re.fullmatch(r"# (CONFIG_[A-Za-z0-9_]+) is not set", line):
            key, value = match[1], "n"
        elif not line or line.startswith("#"):
            continue
        else:
            raise ValueError("Invalid kernel config line")
        if key in values:
            raise ValueError(f"Duplicate kernel config: {key}")
        values[key] = value
    return values


def image_config(image):
    """Accept only a raw ARM64 Image with a bounded, embedded IKCONFIG."""
    data = Path(image).read_bytes()
    if data[56:60] != b"ARM\x64":
        raise ValueError("Expected raw ARM64 Linux Image")
    start = data.find(b"IKCFG_ST")
    end = data.find(b"IKCFG_ED", start + 8)
    if start < 0 or end <= start + 8:
        raise ValueError("Kernel has no embedded .config")
    inflater = zlib.decompressobj(16 + zlib.MAX_WBITS)
    config = inflater.decompress(data[start + 8:end], 1024 * 1024 + 1)
    if len(config) > 1024 * 1024 or not inflater.eof:
        raise ValueError("Invalid or oversized embedded .config")
    versions = set(re.findall(rb"Linux version ([^ \x00\n]+)", data))
    if len(versions) != 1:
        raise ValueError("Missing or ambiguous kernel release")
    release = versions.pop().decode("ascii")
    if not re.fullmatch(r"[A-Za-z0-9._+-]{1,160}", release):
        raise ValueError("Invalid kernel release")
    return config, release


def requirements():
    required = parse_config((KERNEL_DIR / "containers.fragment").read_text())
    # Boot, storage, vsock and existing guest hardening must survive the rebuild.
    required.update(dict.fromkeys([
        "CONFIG_ARM64", "CONFIG_BLK_DEV_INITRD", "CONFIG_DEVTMPFS",
        "CONFIG_VIRTIO_BLK", "CONFIG_VIRTIO_CONSOLE", "CONFIG_VIRTIO_PCI",
        "CONFIG_VIRTIO_NET", "CONFIG_VIRTIO_VSOCKETS", "CONFIG_VIRTIO_FS",
        "CONFIG_FUSE_FS", "CONFIG_EXT4_FS", "CONFIG_PROC_FS", "CONFIG_SYSFS",
        "CONFIG_SECURITY", "CONFIG_SECURITY_SELINUX", "CONFIG_MODULE_SIG",
        "CONFIG_HARDENED_USERCOPY", "CONFIG_STACKPROTECTOR_STRONG",
        "CONFIG_STRICT_KERNEL_RWX", "CONFIG_RANDOMIZE_BASE", "CONFIG_CFI_CLANG",
    ], "y"))
    return required


def validate_config(config):
    values = parse_config(config.decode("utf-8"))
    required = requirements()
    missing = [f"{key}={value} (actual {values.get(key, 'absent')})"
               for key, value in required.items() if values.get(key, "n") != value]
    if missing:
        raise ValueError("Kernel requirements not met: " + ", ".join(missing))
    return values


def validate_provenance(metadata, kernel_hash):
    if (metadata.get("formatVersion") != 1 or metadata.get("buildSystem") != "Android Kleaf"
            or metadata.get("commonRevision") != SOURCE["commonRevision"]
            or metadata.get("baseKernelSha256") != SOURCE["baseKernelSha256"]
            or metadata.get("fragmentSha256") != sha256(KERNEL_DIR / "containers.fragment")
            or metadata.get("artifacts", {}).get("vmlinuz") != kernel_hash):
        raise ValueError("Kernel provenance differs from the reviewed recipe or image")
    if metadata.get("requiredConfig") != requirements():
        raise ValueError("Kernel provenance omits required final configuration")
    for name in ("vmlinuz", "initrd.img", "kernel.config", "source-manifest.xml"):
        if not re.fullmatch(r"[a-f0-9]{64}", metadata.get("artifacts", {}).get(name, "")):
            raise ValueError("Invalid kernel artifact hash")


def validate_boot_report(report):
    if report != {'mode': 'qemu-offline', 'boots': [
            {'boot': 1, 'probe': 'passed'}, {'boot': 2, 'probe': 'passed'}]}:
        raise ValueError("Missing container kernel two-boot verification")


def validate_bundle(directory):
    root = Path(directory)
    limits = {"vmlinuz": 256 << 20, "initrd.img": 512 << 20,
              "kernel.config": 1 << 20, "source-manifest.xml": 4 << 20,
              "kernel-build.json": 1 << 20}
    for name, limit in limits.items():
        path = root / name
        if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= limit:
            raise ValueError(f"Missing or unsafe kernel bundle artifact: {name}")
    metadata = json.loads((root / "kernel-build.json").read_text())
    validate_provenance(metadata, sha256(root / "vmlinuz"))
    for name in limits.keys() - {"kernel-build.json"}:
        if metadata.get("artifacts", {}).get(name) != sha256(root / name):
            raise ValueError(f"Kernel bundle checksum mismatch: {name}")
    config, release = image_config(root / "vmlinuz")
    if config != (root / "kernel.config").read_bytes() or release != metadata.get("kernelRelease"):
        raise ValueError("Built kernel differs from its final .config or release")
    validate_config(config)
    return metadata
