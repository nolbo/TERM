# Termux:API compatibility shims

These intentionally thin wrappers keep a single Android integration backend:

```text
termux-* command → droid CLI → droid-agent → capability broker
```

Initial commands are clipboard get/set, URL open, and notification. They do
not emulate Termux internals or bypass per-VM capability grants. Battery status
is also provided through the same `droid battery` capability path.
