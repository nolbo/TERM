package dev.terminal.runtime.lifecycle

import dev.terminal.runtime.session.SessionRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class StoragePreparationLocksTest {
    @Test fun `deferred adoption never blocks another storage while installation is active`() = runBlocking {
        val locks = StoragePreparationLocks()
        val release = CompletableDeferred<Unit>()
        val install = async(start = CoroutineStart.UNDISPATCHED) {
            locks.withStorage(SessionRuntime.ROOTLESS, "installing") { release.await() }
        }
        try {
            withTimeout(5000) {
                assertFalse(locks.tryWithAll { error("Adoption must be deferred") })
                locks.withStorage(SessionRuntime.NATIVE, "other") { }
                locks.withStorage(SessionRuntime.ROOTLESS_UBUNTU, "other") { }
            }
            assertFalse(install.isCompleted)
        } finally { release.complete(Unit); install.await() }
        assertTrue(locks.tryWithAll { })
    }

    @Test fun `launch resolves after adoption and excludes adoption until session creation completes`() = runBlocking {
        val locks = StoragePreparationLocks()
        var storage = "legacy"
        val releaseAdoption = CompletableDeferred<Unit>()
        val releaseCreation = CompletableDeferred<Unit>()
        val creating = CompletableDeferred<String>()
        val adoption = async(start = CoroutineStart.UNDISPATCHED) {
            locks.tryWithAll { releaseAdoption.await(); storage = "adopted" }
        }
        val launch = async(start = CoroutineStart.UNDISPATCHED) {
            locks.withStorage(resolve = { storage }, key = { SessionRuntime.NATIVE to it }) {
                creating.complete(it)
                releaseCreation.await()
            }
        }
        try {
            assertFalse(creating.isCompleted)
            releaseAdoption.complete(Unit)
            assertEquals("adopted", withTimeout(5000) { creating.await() })
            assertFalse(locks.tryWithAll { error("Creation must exclude adoption") })
            locks.withStorage(SessionRuntime.ROOTLESS, "other") { }
        } finally { releaseAdoption.complete(Unit); releaseCreation.complete(Unit) }
        adoption.await(); launch.await()
        assertTrue(locks.tryWithAll { })
    }

    @Test fun `failed launch resolution releases adoption barrier`() = runBlocking {
        val locks = StoragePreparationLocks()
        assertTrue(runCatching {
            locks.withStorage(resolve = { error("Deleted environment") }, key = { SessionRuntime.NATIVE to "unused" }) { }
        }.isFailure)
        assertTrue(locks.tryWithAll { })
    }

    @Test fun `install blocks only its own storage`() = runBlocking {
        val locks = StoragePreparationLocks()
        val release = CompletableDeferred<Unit>()
        val install = async(start = CoroutineStart.UNDISPATCHED) {
            locks.withStorage(SessionRuntime.ROOTLESS, "installing") { release.await() }
        }
        val sameStorage = async(start = CoroutineStart.UNDISPATCHED) {
            locks.withStorage(SessionRuntime.ROOTLESS, "installing") { true }
        }
        try {
            withTimeout(5000) {
                locks.withStorage(SessionRuntime.NATIVE) { }
                locks.withStorage(SessionRuntime.ROOTLESS) { }
                locks.withStorage(SessionRuntime.ROOTLESS, "other-profile") { }
                locks.withStorage(SessionRuntime.ROOTLESS_UBUNTU) { }
            }
            assertFalse(sameStorage.isCompleted)
        } finally { release.complete(Unit) }
        install.await()
        assertTrue(sameStorage.await())
    }

    @Test fun `migration drains preparation and excludes new storage`() = runBlocking {
        val locks = StoragePreparationLocks()
        val releaseInstall = CompletableDeferred<Unit>()
        val releaseMigration = CompletableDeferred<Unit>()
        val migrationEntered = CompletableDeferred<Unit>()
        val install = async(start = CoroutineStart.UNDISPATCHED) {
            locks.withStorage(SessionRuntime.ROOTLESS) { releaseInstall.await() }
        }
        val migration = async(start = CoroutineStart.UNDISPATCHED) {
            locks.withAll { migrationEntered.complete(Unit); releaseMigration.await() }
        }
        val next = async(start = CoroutineStart.UNDISPATCHED) {
            locks.withStorage(SessionRuntime.NATIVE, "new") { true }
        }
        try {
            assertFalse(migrationEntered.isCompleted)
            assertFalse(next.isCompleted)
            releaseInstall.complete(Unit)
            withTimeout(5000) { migrationEntered.await() }
            assertFalse(next.isCompleted)
        } finally { releaseInstall.complete(Unit); releaseMigration.complete(Unit) }
        withTimeout(5000) { install.await(); migration.await(); assertTrue(next.await()) }
    }

    @Test fun `cancelling queued preparation does not strand migration`() = runBlocking {
        val locks = StoragePreparationLocks()
        val release = CompletableDeferred<Unit>()
        val install = async(start = CoroutineStart.UNDISPATCHED) {
            locks.withStorage(SessionRuntime.ROOTLESS) { release.await() }
        }
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            locks.withStorage(SessionRuntime.ROOTLESS) { error("Cancelled preparation ran") }
        }
        queued.cancelAndJoin()
        install.cancelAndJoin()
        withTimeout(5000) { locks.withAll { }; locks.withStorage(SessionRuntime.ROOTLESS) { } }
    }
}
