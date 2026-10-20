package dev.terminal.app

import dev.terminal.image.AvfImageRelease

/** Debug builds approve every bundled candidate; guest capabilities are checked before boot. */
internal fun avfImageUpdatesApproved(release: AvfImageRelease?, debugBuild: Boolean): Boolean =
    release != null && (debugBuild || release.maintenanceVersion == 1)
