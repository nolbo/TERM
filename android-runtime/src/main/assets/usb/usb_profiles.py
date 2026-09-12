"""Explicit local serial preferences. Profiles never store device IDs or grants."""
import json
import os
import stat

FIELDS = {"baud", "bits", "stops", "parity", "dtr", "rts", "port"}


def validate(value):
    if not isinstance(value, dict) or set(value) != FIELDS:
        raise ValueError("Invalid serial profile fields")
    for key, low, high in (("baud", 1, 4000000), ("bits", 5, 8), ("stops", 0, 2), ("parity", 0, 4)):
        if type(value[key]) is not int or not low <= value[key] <= high:
            raise ValueError("Invalid serial profile " + key)
    if any(type(value[key]) is not bool for key in ("dtr", "rts")):
        raise ValueError("Invalid serial profile control lines")
    if value["port"] is not None and (type(value["port"]) is not int or not 0 <= value["port"] <= 255):
        raise ValueError("Invalid serial profile port")
    return value


def load(path):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, "rb") as source:
        info = os.fstat(source.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_size > 4096:
            raise ValueError("Profile must be a regular file <=4096 bytes")
        data = source.read(4097)
        if len(data) > 4096:
            raise ValueError("Profile too large")
        return validate(json.loads(data))


def save(path, value):
    data = json.dumps(validate(value), sort_keys=True).encode() + b"\n"
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "wb") as target:
        target.write(data); target.flush(); os.fsync(target.fileno())


def settings(args):
    if args.profile:
        value = load(args.profile)
    else:
        value = validate(dict(baud=args.baud, bits=args.bits, stops=args.stops, parity=args.parity,
                              dtr=not args.no_dtr, rts=not args.no_rts, port=args.port))
    if args.save_profile:
        save(args.save_profile, value)
    return value
