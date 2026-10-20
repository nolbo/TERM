package dev.terminal.app

import dev.terminal.image.AvfDistribution
import dev.terminal.image.AvfImageRelease
import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class AvfImageUpdatePolicyTest {
    @Test fun debugApprovesEveryBundledDistributionWithoutMaintenanceApproval() {
        for (distribution in AvfDistribution.entries) {
            val release = AvfImageRelease(distribution.releaseId,
                URI("https://example.invalid/${distribution.id}/manifest.properties"), "a".repeat(64),
                1024, distribution = distribution)
            assertTrue(avfImageUpdatesApproved(release, debugBuild = true))
            assertFalse(avfImageUpdatesApproved(release, debugBuild = false))
            assertTrue(avfImageUpdatesApproved(release.copy(maintenanceVersion = 1), debugBuild = false))
        }
    }

    @Test fun approvalStillRequiresABundledCandidate() {
        assertFalse(avfImageUpdatesApproved(null, debugBuild = true))
        assertFalse(avfImageUpdatesApproved(null, debugBuild = false))
    }
}
