# droid-agent

`droid-agent` is the sole guest process that accepts the host-only vsock bridge
on port 40501. Guest programs use the permission-restricted Unix socket
`/run/droid/droid-agent.sock`; they do not receive a host vsock socket or any
Android Binder handle. The initial local protocol backs `droid clipboard`,
`droid open`, and `droid notify`.
