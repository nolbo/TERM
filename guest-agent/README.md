# XTermX AVF guest agent

Install `xtermx_agent.py` as `/usr/libexec/xtermx-agent` in the Debian AVF image and enable
`xtermx-agent.service`. Install `xtermx_api.py` as `/usr/bin/xtermx-api`, then create these symlinks
to it:

- `/usr/bin/termux-clipboard-get`
- `/usr/bin/termux-clipboard-set`
- `/usr/bin/termux-open-url`
- `/usr/bin/termux-share`

For an unpacked root filesystem, `./install-into-rootfs.sh ROOTFS_DIRECTORY` performs those steps
and enables the systemd unit without executing target-architecture binaries.

The daemon requires Python 3 with `AF_VSOCK` support. It owns guest ptys independently of Android
connections, retains one MiB of output per session, and resumes from the host's last output offset
after reconnecting. It exposes Android APIs only through the local
`/run/xtermx-agent/api.sock` socket.

The host expects the provisioned AVF image and `vm_config.json` in the app's `files/avf` directory.
A template is available as `vm_config.example.json`. A minimal config is:

```json
{
  "name": "xtermx_debian",
  "bootloader": "bootloader.bin",
  "memory_bytes": 2147483648,
  "match_host_cpu": true,
  "debuggable": false,
  "disks": [
    {"image": "rootfs.img", "writable": true}
  ]
}
```

## Image bundle for the Android setup screen

The Android app accepts a `tar.gz` bundle with these top-level files:

* `manifest.json` — `format_version: 1`, `guest_protocol_version: 2`, the target
  `architecture` (`arm64` or `x86_64`), and a `files` object mapping every regular file name to
  its SHA-256 digest.
* `vm_config.json` — the AVF custom image configuration shown above.
* The bootloader/kernel/initrd and disk files referenced by `vm_config.json`.

`create-image-bundle.sh` creates this bundle and its manifest from an already prepared image:

```sh
./create-image-bundle.sh xtermx-arm64.tar.gz arm64 vm_config.json bootloader.bin rootfs.img
```

For a build with a pinned download source, set `XTERMX_AVF_IMAGE_URL_ARM64` and
`XTERMX_AVF_IMAGE_SHA256_ARM64` (or the corresponding `X86_64` variables) before running the
Android Gradle build. The app will then download only that HTTPS URL and require the exact
configured checksum. With no variables, the setup screen intentionally offers import only.

An optional `agent_token` string in `vm_config.json` is sent on every host attachment. If it is
used, configure the same value as `XTERMX_AGENT_TOKEN` in the guest service environment. The
daemon never passes this root-service environment or token into an interactive shell.

AVF and custom-VM permissions must be granted by the platform or on a debuggable development
device. When any requirement is missing, the Android application keeps using its local Termux pty.

The raw `xtermx-api` command also exposes chunked, path-confined storage operations. Use the
`exchange` root for the app-private exchange directory or `shared` for Android shared storage:

```sh
xtermx-api file_list '{"root":"shared","path":"Download"}'
xtermx-api file_read '{"root":"shared","path":"Download/example.txt","offset":0}'
```

`file_read` returns Base64 data in the response. `file_write` accepts at most 512 KiB per call and
requires `data`, `offset`, and optional `truncate` fields. The Android host canonicalizes every path
and rejects traversal outside the selected root.
