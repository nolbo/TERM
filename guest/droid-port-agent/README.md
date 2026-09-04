# droid-port-agent

The agent observes guest TCP listen sockets from `/proc/net/tcp{,6}` and emits
deduplicated `PORT` stream events to host CID only on vsock 40502. It reports
ports but never opens Android-facing listeners itself; host forwarding remains
an explicit policy-controlled step.
