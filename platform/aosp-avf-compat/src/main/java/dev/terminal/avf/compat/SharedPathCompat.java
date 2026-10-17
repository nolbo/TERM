package dev.terminal.avf.compat;

import android.system.virtualmachine.VirtualMachineCustomImageConfig.SharedPath;

/** Direct calls to the two known AOSP ABIs. Does not bypass platform API access checks. */
public final class SharedPathCompat {
    private SharedPathCompat() {}

    public static SharedPath create(
            boolean preferAndroid17, String path, int hostUid, int hostGid, int guestUid,
            int guestGid, int mask, String tag, String socket, String socketPath) {
        try {
            return preferAndroid17
                    ? Android17.create(path, hostUid, hostGid, guestUid, guestGid, mask, tag, socket, socketPath)
                    : Legacy.create(path, hostUid, hostGid, guestUid, guestGid, mask, tag, socket, socketPath);
        } catch (NoSuchMethodError preferredMissing) {
            // OEMs can backport an ABI independently of the Android SDK level.
            try {
                return preferAndroid17
                        ? Legacy.create(path, hostUid, hostGid, guestUid, guestGid, mask, tag, socket, socketPath)
                        : Android17.create(path, hostUid, hostGid, guestUid, guestGid, mask, tag, socket, socketPath);
            } catch (NoSuchMethodError alternativeMissing) {
                preferredMissing.addSuppressed(alternativeMissing);
                throw preferredMissing;
            }
        }
    }

    // Keep each version's method reference in its own class, loaded only when selected.
    private static final class Android17 {
        static SharedPath create(
                String path, int hostUid, int hostGid, int guestUid, int guestGid, int mask,
                String tag, String socket, String socketPath) {
            return new SharedPath(path, hostUid, hostGid, guestUid, guestGid, mask, tag, socket, socketPath);
        }
    }

    private static final class Legacy {
        static SharedPath create(
                String path, int hostUid, int hostGid, int guestUid, int guestGid, int mask,
                String tag, String socket, String socketPath) {
            // Never fall back to privileged/service-domain exports on older platforms.
            return new SharedPath(path, hostUid, hostGid, guestUid, guestGid, mask, tag, socket, true, socketPath);
        }
    }
}
