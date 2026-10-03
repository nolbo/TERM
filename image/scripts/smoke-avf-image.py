#!/usr/bin/env python3
"""Two QEMU boots prove overlay root boot and persistence; this is not AVF device validation."""
from pathlib import Path
import subprocess

root = Path("/output")
for expected in ["TERM_AVF_FIRST_BOOT_OK", "TERM_AVF_PERSISTENCE_OK"]:
    command = ["qemu-system-aarch64", "-machine", "virt", "-cpu", "max", "-accel", "tcg",
               "-m", "2048", "-display", "none", "-monitor", "none", "-no-reboot",
               "-kernel", str(root / "vmlinuz"), "-initrd", str(root / "initrd.img"),
               "-append", "root=/dev/vda rootfstype=ext4 ro console=hvc0 init=/usr/local/lib/droid/image-selftest",
               "-device", "virtio-serial-pci", "-chardev", "stdio,id=console,signal=off",
               "-device", "virtconsole,chardev=console"]
    for index, (name, readonly) in enumerate([("system.img", True), ("home.img", False), ("config.img", True)]):
        command += ["-drive", f"if=none,id=disk{index},file={root / name},format=raw,readonly={'on' if readonly else 'off'}",
                    "-device", f"virtio-blk-pci,drive=disk{index}"]
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=600)
    if result.returncode or expected.encode() not in result.stdout:
        print(result.stdout.decode(errors="replace")[-12000:])
        raise SystemExit("Generated AVF image failed boot/persistence smoke test")
    print(expected, flush=True)
