package dev.terminal.runtime.session

import dev.terminal.protocol.FrameKind
import dev.terminal.protocol.FrameTransport
import dev.terminal.protocol.ProtocolFrame
import dev.terminal.protocol.ProtocolSessionClient
import dev.terminal.protocol.Service
import dev.terminal.protocol.SessionConnectionState
import dev.terminal.protocol.SessionController
import dev.terminal.protocol.SessionDescriptor
import dev.terminal.protocol.SessionEvent
import dev.terminal.protocol.SessionStatus
import dev.terminal.protocol.SessionResponse
import dev.terminal.protocol.SessionResponseCodec
import java.io.IOException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedSessionRegistryTest {
    @Test fun environmentEditAndRenamePreserveExistingSessionAndFutureLaunchUsesNewOwner() = runBlocking {
        val metadata = EnvironmentRegistry(kotlin.io.path.createTempDirectory("session-environments").toFile())
        val first = Environment.create(SessionRuntime.NATIVE, "First")
        val second = Environment.create(SessionRuntime.NATIVE, "Second")
        var profile = metadata.transaction { save(SessionProfile("profile", "Profile", SessionRuntime.NATIVE, "/bin/sh"), first) }
        val launches = mutableMapOf<String, SessionLaunchSpec>()
        val registry = UnifiedSessionRegistry(recordLaunch = { id, spec -> launches[id] = spec })
        val ownerA = FakeController("native-a")
        val ownerB = FakeController("native-b")
        registry.register(first, ownerA)
        registry.register(second, ownerB)
        try {
            registry.create(metadata.transaction { resolve(profile) }, 24u, 80u).getOrThrow()
            profile = metadata.transaction { save(profile.copy(environmentId = null), second) }
            metadata.transaction { rename(first.id, "Renamed") }
            registry.create(metadata.transaction { resolve(profile) }, 24u, 80u).getOrThrow()
            assertEquals(first.id, registry.environmentOf("native-a"))
            assertEquals(second.id, registry.environmentOf("native-b"))
            assertEquals("First", launches.getValue("native-a").executionEnvironment?.name)
            registry.detach("native-a").getOrThrow()
            registry.write("native-a", byteArrayOf(1)).getOrThrow()
            assertEquals(1, ownerA.writeCount)
            assertTrue(runCatching { registry.deleteStorageIfIdle(first.sessionRuntime, first.storage.id) {} }.isFailure)
            assertFalse(ownerA.closed)
        } finally { registry.close() }
    }

    @Test fun restoredEnvironmentSnapshotAndLegacyCheckpointOwnershipSurviveReconnect() = runBlocking {
        val environment = Environment.create(SessionRuntime.AVF, "AVF Debian")
        val profile = SessionProfile("avf-profile", "AVF", SessionRuntime.AVF, "/bin/bash", environmentId = environment.id)
        val spec = profile.resolve(environment)
        val saved = mapOf("avf-restored" to spec.toJson().toString())
        val registry = UnifiedSessionRegistry(restoreLaunch = { saved[it]?.let(SessionLaunchSpec::fromJson) })
        val owner = FakeController("avf-restored")
        registry.register(environment, owner)
        registry.list().getOrThrow()
        assertEquals(environment.id, registry.environmentOf("avf-restored"))
        assertEquals(spec, registry.launchOf("avf-restored"))
        registry.terminalStates.create("avf-restored", 80, 24)
        registry.configureTerminal("avf-restored", 24, 80, 8, 16, java.util.Properties())
        owner.disconnect()
        assertTrue(registry.verifiedSessions(environment).isFailure)
        assertTrue(runCatching { registry.deleteStorageIfIdle(environment.sessionRuntime, environment.storage.id) {} }.isFailure)
        registry.register(environment.copy(name = "Renamed"), FakeController("avf-restored"))
        registry.list().getOrThrow()
        assertEquals(spec, registry.launchOf("avf-restored"))
        assertEquals(environment.id, registry.environmentOf("avf-restored"))
        assertTrue(registry.terminalStates.sameDimensions("avf-restored", 80, 24))
        registry.close()
    }

    @Test fun legacyAdoptionDefersActiveOrUnverifiableOwnerAndPreservesLiveSnapshot() = runBlocking {
        val launches = mutableMapOf<String, SessionLaunchSpec>()
        val registry = UnifiedSessionRegistry(recordLaunch = { id, spec -> launches[id] = spec })
        val controller = FakeController("native-legacy")
        registry.register(SessionRuntime.NATIVE, controller)
        val old = SessionProfile("old", "Old", SessionRuntime.NATIVE, "/bin/sh").resolve()
        registry.create(old, 24u, 80u).getOrThrow()
        var commits = 0
        val newId = java.util.UUID.randomUUID().toString()
        val adopt = { commits++; newId }
        assertFalse(registry.adoptLegacyStorageIfIdle(SessionRuntime.NATIVE, adopt))
        assertEquals(0, commits)
        assertEquals("legacy", registry.storageOf("native-legacy"))
        assertEquals(old, launches["native-legacy"])
        registry.write("native-legacy", byteArrayOf(1)).getOrThrow()
        controller.failList = true
        assertTrue(runCatching { registry.adoptLegacyStorageIfIdle(SessionRuntime.NATIVE, adopt) }.isFailure)
        assertEquals(0, commits)
        controller.failList = false
        registry.terminate("native-legacy", 9).getOrThrow()
        assertTrue(registry.adoptLegacyStorageIfIdle(SessionRuntime.NATIVE, adopt))
        assertEquals(1, commits)
        assertFalse(controller.closed)
        assertFalse(registry.isRegistered(SessionRuntime.NATIVE))
        assertTrue(registry.isRegistered(SessionRuntime.NATIVE, newId))
        registry.close()
    }

    @Test fun failedAdoptionCommitKeepsRegistrationAndCanRetry() = runBlocking {
        val registry = UnifiedSessionRegistry()
        val controller = FakeController("native-idle")
        registry.register(SessionRuntime.NATIVE, controller)
        registry.terminate("native-idle", 9).getOrThrow()
        assertTrue(runCatching { registry.adoptLegacyStorageIfIdle(SessionRuntime.NATIVE) { error("manifest write failed") } }.isFailure)
        assertTrue(registry.isRegistered(SessionRuntime.NATIVE))
        assertFalse(controller.closed)
        val id = java.util.UUID.randomUUID().toString()
        assertTrue(registry.adoptLegacyStorageIfIdle(SessionRuntime.NATIVE) { id })
        assertTrue(registry.isRegistered(SessionRuntime.NATIVE, id))
        registry.close()
    }
    @Test fun sameRuntimeStorageOwnersRemainIndependentAndSharingUsesSameOwner() = runBlocking {
        val registry = UnifiedSessionRegistry()
        val firstId = java.util.UUID.randomUUID().toString()
        val secondId = java.util.UUID.randomUUID().toString()
        val first = FakeController("native-first")
        val second = FakeController("native-second")
        registry.register(SessionRuntime.NATIVE, first, firstId)
        registry.register(SessionRuntime.NATIVE, second, secondId)
        val profile = SessionProfile("one", "One", SessionRuntime.NATIVE, "/bin/sh", storageId = firstId)
        registry.create(profile.resolve(), 24u, 80u).getOrThrow()
        registry.create(profile.copy(id = "shared").resolve(), 24u, 80u).getOrThrow()
        registry.create(profile.copy(id = "two", storageId = secondId).resolve(), 24u, 80u).getOrThrow()
        assertEquals(2, first.createCount)
        assertEquals(1, second.createCount)
        assertEquals(2, registry.list().getOrThrow().size)
        registry.write("native-first", byteArrayOf(1)).getOrThrow()
        assertEquals(1, first.writeCount)
        assertEquals(0, second.writeCount)
        registry.terminate("native-first", 9).getOrThrow()
        assertEquals(listOf("native-second"), registry.list().getOrThrow().map { it.id })
        assertFalse(second.closed)
        assertEquals(secondId, registry.storageOf("native-second"))
        registry.close()
    }
    @Test fun directRuntimeRequestsResolveProfilesWithoutRedirectingAndKeepPathCommands() = runBlocking {
        val launches = mutableMapOf<String, SessionLaunchSpec>()
        val registry = UnifiedSessionRegistry(recordLaunch = { id, spec -> launches[id] = spec })
        SessionRuntime.entries.forEach { runtime ->
            registry.register(runtime, FakeController(runtime.idPrefix + "profile"))
            val result = registry.create(runtime, "/bin/sh", "/", mapOf(SessionCommand.KEY to SessionCommand.encode(listOf("printf", "space quote'"))), 24u, 80u).getOrThrow()
            val spec = launches.getValue(result.id)
            assertEquals(SessionProfile.builtInId(runtime), spec.profileId)
            assertEquals(runtime, spec.runtime)
            assertEquals(listOf("/bin/sh", "-c", "exec \"\$@\"", "/bin/sh", "printf", "space quote'"), spec.arguments())
        }
        registry.close()
    }

    @Test fun launchSnapshotSurvivesDetachResizeListAndOnlyExplicitTerminationKillsSession() = runBlocking {
        val launches = mutableMapOf<String, SessionLaunchSpec>()
        val registry = UnifiedSessionRegistry(recordLaunch = { id, spec -> launches[id] = spec })
        val controller = FakeController("native-profile")
        registry.register(SessionRuntime.NATIVE, controller)
        val profile = SessionProfile("work", "Work", SessionRuntime.NATIVE, "/bin/sh", listOf("-l"), "/", mapOf("WORK" to "old"))
        val original = profile.resolve()
        val created = registry.create(original, 24u, 80u).getOrThrow()
        profile.copy(environment = mapOf("WORK" to "new")).resolve()
        registry.detach(created.id).getOrThrow()
        registry.attach(created.id).getOrThrow()
        registry.resize(created.id, 30u, 90u).getOrThrow()
        registry.list().getOrThrow()
        assertEquals("old", launches.getValue(created.id).environment["WORK"])
        assertFalse(controller.closed)
        assertTrue(controller.terminatedIds.isEmpty())
        registry.terminate(created.id, 9).getOrThrow()
        assertEquals(listOf(created.id), controller.terminatedIds)
        registry.close()
    }

    @Test fun migrationRejectsLiveOrUnverifiableSiblingSessions() = runBlocking {
        for (failed in listOf(false, true)) {
            val registry = UnifiedSessionRegistry()
            val sibling = FakeController("native", failList = failed)
            registry.register(SessionRuntime.NATIVE, sibling)
            var ran = false
            assertTrue(runCatching { registry.migrateIfAllIdle(SessionRuntime.ROOTLESS) { ran = true } }.isFailure)
            assertFalse(ran)
            assertFalse(sibling.closed)
            registry.close()
        }
    }

    @Test fun migrationExcludesCreationAcrossAllRuntimesAndReleasesLocks() = runBlocking {
        val registry = UnifiedSessionRegistry()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val task = launch { registry.migrateIfAllIdle(SessionRuntime.ROOTLESS) { entered.complete(Unit); release.await() } }
        entered.await()
        val creations = SessionRuntime.entries.map { runtime -> async {
            registry.create(runtime, "/bin/sh", "/", rows = 24u, cols = 80u)
        } }
        kotlinx.coroutines.yield()
        assertTrue(creations.none { it.isCompleted })
        release.complete(Unit)
        task.join()
        creations.forEach { assertTrue(it.await().isFailure) }
        registry.close()
    }

    @Test fun pendingMigrationGuardRejectsNewSessionsBeforeControllerCreate() = runBlocking {
        val registry = UnifiedSessionRegistry(beforeCreate = { error("pending migration") })
        val controller = FakeController("native")
        registry.register(SessionRuntime.NATIVE, controller)
        assertTrue(registry.create(SessionRuntime.NATIVE, "/bin/sh", "/", rows = 24u, cols = 80u).isFailure)
        assertEquals(0, controller.createCount)
        registry.close()
    }

    @Test fun maintenanceBlocksSameStorageCreationButNotSiblingOperations() = runBlocking {
        val registry = UnifiedSessionRegistry()
        registry.register(SessionRuntime.ROOTLESS_UBUNTU, FakeController("ubuntu-live"))
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val maintenance = kotlinx.coroutines.CoroutineScope(coroutineContext).launch {
            registry.removeIfIdle(SessionRuntime.ROOTLESS) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val creation = kotlinx.coroutines.CoroutineScope(coroutineContext).async {
            registry.create(SessionRuntime.ROOTLESS, "/bin/sh", "/", rows = 24u, cols = 80u)
        }
        try {
            kotlinx.coroutines.yield()
            assertFalse(creation.isCompleted)
            kotlinx.coroutines.withTimeout(1000) {
                assertEquals(listOf("ubuntu-live"), registry.list().getOrThrow().map { it.id })
                assertTrue(registry.create(SessionRuntime.ROOTLESS_UBUNTU, "/bin/sh", "/", rows = 24u, cols = 80u).isSuccess)
                val storage = java.util.UUID.randomUUID().toString()
                registry.register(SessionRuntime.ROOTLESS, FakeController("independent"), storage)
                assertTrue(registry.create(SessionRuntime.ROOTLESS, "/bin/sh", "/", rows = 24u, cols = 80u,
                    storageId = storage).isSuccess)
            }
        } finally {
            release.complete(Unit)
            maintenance.join()
            registry.close()
        }
        assertTrue(creation.await().isFailure)
    }

    @Test fun routesOperationsToOwningRuntimeAndDetachDoesNotCloseController() = runBlocking {
        val native = FakeController("native-one")
        val rootless = FakeController("rootless-one")
        val registry = UnifiedSessionRegistry()
        registry.register(SessionRuntime.NATIVE, native)
        registry.register(SessionRuntime.ROOTLESS, rootless)

        val created = registry.create(SessionRuntime.ROOTLESS, "/bin/sh", "/root", rows = 24u, cols = 80u).getOrThrow()
        assertEquals("rootless-one", created.id)
        assertEquals(SessionRuntime.ROOTLESS, registry.runtimeOf(created.id))

        registry.write(created.id, "apt update\n".encodeToByteArray()).getOrThrow()
        registry.detach(created.id).getOrThrow()

        assertEquals(0, native.writeCount)
        assertEquals(1, rootless.writeCount)
        assertEquals(1, rootless.detachCount)
        assertFalse(rootless.closed)
        registry.close()
        assertTrue(rootless.closed)
    }

    @Test fun explicitRuntimeSelectionNeverFallsBack() = runBlocking {
        val registry = UnifiedSessionRegistry()
        registry.register(SessionRuntime.NATIVE, FakeController("native-one"))

        val result = registry.create(SessionRuntime.ROOTLESS, "/bin/sh", "/root", rows = 24u, cols = 80u)

        assertTrue(result.isFailure)
        registry.close()
    }

    @Test fun unavailableOptionalRuntimeDoesNotHideHealthySessions() = runBlocking {
        val registry = UnifiedSessionRegistry()
        registry.register(SessionRuntime.NATIVE, FakeController("native-one"))
        registry.register(SessionRuntime.ROOTLESS, FakeController("rootless-one", failList = true))

        val sessions = registry.list().getOrThrow()

        assertEquals(listOf("native-one"), sessions.map { it.id })
        registry.close()
    }

    @Test fun terminatingMiddleSessionLeavesSiblingsAndControllerAlive() = runBlocking {
        val native = FakeController("native-a", extraIds = listOf("native-b", "native-c"))
        val registry = UnifiedSessionRegistry()
        registry.register(SessionRuntime.NATIVE, native)
        assertEquals(listOf("native-a", "native-b", "native-c"), registry.list().getOrThrow().map { it.id })

        registry.terminate("native-b", 9).getOrThrow()

        assertEquals(listOf("native-a", "native-c"), registry.list().getOrThrow().map { it.id })
        assertEquals(listOf("native-b"), native.terminatedIds)
        assertFalse(native.closed)
        registry.close()
    }

    @Test fun terminatingOneRuntimeDoesNotHideOrRecreateAnotherRuntime() = runBlocking {
        val native = FakeController("native-one")
        val rootless = FakeController("rootless-one")
        val registry = UnifiedSessionRegistry()
        registry.register(SessionRuntime.NATIVE, native)
        registry.register(SessionRuntime.ROOTLESS, rootless)
        registry.list().getOrThrow()

        registry.terminate("rootless-one", 9).getOrThrow()

        assertEquals(listOf("native-one"), registry.list().getOrThrow().map { it.id })
        assertEquals(0, native.createCount)
        assertFalse(native.closed)
        assertFalse(rootless.closed)
        registry.close()
    }

    @Test fun listFailureRetainsLastKnownSessionsForThatRuntime() = runBlocking {
        val native = FakeController("native-one")
        val rootless = FakeController("rootless-one")
        val registry = UnifiedSessionRegistry()
        registry.register(SessionRuntime.NATIVE, native)
        registry.register(SessionRuntime.ROOTLESS, rootless)
        registry.list().getOrThrow()
        rootless.failList = true

        assertEquals(listOf("native-one", "rootless-one"), registry.list().getOrThrow().map { it.id })
        registry.close()
    }

    @Test fun equalCreationTimesRetainRuntimeOrderRegardlessOfRegistrationOrder() = runBlocking {
        val registry = UnifiedSessionRegistry()
        registry.register(SessionRuntime.ROOTLESS, FakeController("rootless-one"))
        registry.register(SessionRuntime.NATIVE, FakeController("native-one"))
        repeat(3) {
            assertEquals(listOf("native-one", "rootless-one"), registry.list().getOrThrow().map { it.id })
        }
        registry.close()
    }

    @Test fun stoppingAvfWithAReadFailurePreservesUbuntuAndSiblingEnvironmentSessions() = runBlocking {
        val registry = UnifiedSessionRegistry()
        val stopped = Environment.create(SessionRuntime.AVF, "AVF to stop")
        val ubuntuEnvironment = Environment.create(SessionRuntime.ROOTLESS_UBUNTU, "Ubuntu")
        val nativeEnvironment = Environment.create(SessionRuntime.NATIVE, "Native")
        val avfEnvironment = Environment.create(SessionRuntime.AVF, "Other AVF")
        val siblings = mapOf(
            ubuntuEnvironment to FakeController("rootless-ubuntu-live"),
            nativeEnvironment to FakeController("native-live"),
            avfEnvironment to FakeController("avf-live"),
        )
        val transport = IdleAvfTransport()
        val stoppedClient = ProtocolSessionClient(transport, scope = this)
        try {
            registry.register(stopped, stoppedClient)
            siblings.forEach { (environment, owner) -> registry.register(environment, owner) }
            val before = registry.list().getOrThrow()
            registry.terminalStates.create("rootless-ubuntu-live", 80, 24)
            registry.terminalStates.output("rootless-ubuntu-live", "UBUNTU KEPT".toByteArray())

            registry.deleteStorageIfIdle(stopped.sessionRuntime, stopped.storage.id) { release ->
                // Stopping a VM can reset vsock before the service releases its client.
                transport.incoming.close(IOException("AVF vsock reset during stop"))
                withTimeout(1_000) {
                    stoppedClient.connectionState.first { it == SessionConnectionState.DISCONNECTED }
                }
                release()
            }

            assertFalse(registry.isRegistered(stopped.sessionRuntime, stopped.storage.id))
            assertEquals(before, registry.list().getOrThrow())
            before.forEach { registry.write(it.id, byteArrayOf(1)).getOrThrow() }
            siblings.values.forEach { owner ->
                assertFalse(owner.closed)
                assertEquals(1, owner.writeCount)
            }
            var checkpointSeen = false
            registry.terminalStates.subscribe("rootless-ubuntu-live", object : RuntimeTerminalState.Sink {
                override fun checkpoint(emulator: com.termux.terminal.TerminalEmulator, viewport: RuntimeTerminalState.Viewport?) {
                    checkpointSeen = true
                    assertTrue(emulator.screen.transcriptText.contains("UBUNTU KEPT"))
                }
                override fun output(bytes: ByteArray) = Unit
                override fun resized(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) = Unit
                override fun exited(code: Int) = Unit
            })
            assertTrue(checkpointSeen)
        } finally { registry.close() }
    }

    @Test fun reconnectingAnAvfTransportPreservesSiblingOwnersAndTerminalCheckpoint() = runBlocking {
        val disconnected = java.util.concurrent.LinkedBlockingQueue<Pair<SessionRuntime, String>>()
        val native = FakeController("native-a")
        val avf = FakeController("avf-a")
        val registry = UnifiedSessionRegistry(onDisconnected = { runtime, storage -> disconnected.offer(runtime to storage) })
        try {
            registry.register(SessionRuntime.NATIVE, native)
            registry.register(SessionRuntime.AVF, avf)
            registry.list().getOrThrow()
            registry.terminalStates.create("avf-a", 80, 24)
            registry.terminalStates.output("avf-a", "KEPT".toByteArray())
            avf.failList = true
            avf.disconnect()
            assertEquals(SessionRuntime.AVF to "legacy", disconnected.poll(2, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(listOf("legacy"), registry.disconnectedStorages(SessionRuntime.AVF))
            assertEquals(SessionRuntime.AVF, registry.runtimeOf("avf-a"))
            assertEquals(listOf("native-a", "avf-a"), registry.list().getOrThrow().map { it.id })
            registry.register(SessionRuntime.AVF, FakeController("avf-a"))
            assertTrue(registry.disconnectedStorages(SessionRuntime.AVF).isEmpty())
            registry.write("native-a", byteArrayOf(1)).getOrThrow()
            assertEquals(1, native.writeCount)
            assertFalse(native.closed)
            val checkpoint = object : RuntimeTerminalState.Sink {
                override fun checkpoint(emulator: com.termux.terminal.TerminalEmulator, viewport: RuntimeTerminalState.Viewport?) {
                    assertTrue(emulator.screen.transcriptText.contains("KEPT"))
                }
                override fun output(bytes: ByteArray) = Unit
                override fun resized(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) = Unit
                override fun exited(code: Int) = Unit
            }
            registry.terminalStates.subscribe("avf-a", checkpoint)
            assertTrue(avf.closed)
        } finally { registry.close() }
    }

    @Test fun duplicateTerminationCannotTouchSiblingOrCloseRegistry() = runBlocking {
        val native = FakeController("native-a", extraIds = listOf("native-b"))
        val registry = UnifiedSessionRegistry()
        registry.register(SessionRuntime.NATIVE, native)
        registry.list().getOrThrow()

        registry.terminate("native-a", 9).getOrThrow()
        assertTrue(registry.terminate("native-a", 9).isFailure)

        assertEquals(listOf("native-b"), registry.list().getOrThrow().map { it.id })
        assertEquals(listOf("native-a"), native.terminatedIds)
        assertFalse(native.closed)
        registry.close()
    }

    @Test fun failedTerminationKeepsOwnerAndAllSessions() = runBlocking {
        val native = FakeController("native-a", extraIds = listOf("native-b"))
        val registry = UnifiedSessionRegistry()
        registry.register(SessionRuntime.NATIVE, native)
        registry.list().getOrThrow()
        native.failTerminate = true

        assertTrue(registry.terminate("native-a", 9).isFailure)

        assertEquals(SessionRuntime.NATIVE, registry.runtimeOf("native-a"))
        assertEquals(listOf("native-a", "native-b"), registry.list().getOrThrow().map { it.id })
        assertFalse(native.closed)
        registry.close()
    }

    @Test fun distributionSessionsRemainIndependentAcrossDetachAndTerminate() = runBlocking {
        val registry = UnifiedSessionRegistry()
        val runtimes = listOf(SessionRuntime.ROOTLESS, SessionRuntime.ROOTLESS_UBUNTU, SessionRuntime.ROOTLESS_ARCH)
        val controllers = runtimes.associateWith { FakeController(it.idPrefix + "test") }
        controllers.forEach { (runtime, controller) -> registry.register(runtime, controller) }
        val sessions = runtimes.map { registry.create(it, "/bin/bash", "/root", rows = 24u, cols = 80u).getOrThrow() }
        registry.detach(sessions[1].id).getOrThrow()
        registry.write(sessions[2].id, "pacman -V\n".encodeToByteArray()).getOrThrow()
        registry.terminate(sessions[1].id, 9).getOrThrow()
        assertEquals(setOf(sessions[0].id, sessions[2].id), registry.list().getOrThrow().map { it.id }.toSet())
        assertEquals(1, controllers.getValue(SessionRuntime.ROOTLESS_ARCH).writeCount)
        assertEquals(0, controllers.getValue(SessionRuntime.ROOTLESS).writeCount)
        assertTrue(controllers.values.none { it.closed })
        registry.close()
    }

    @Test fun removalRejectsLiveSessionsAndUnknownSessionState() = runBlocking {
        val registry = UnifiedSessionRegistry()
        val controller = FakeController("rootless-live")
        registry.register(SessionRuntime.ROOTLESS, controller)
        var removed = false
        assertTrue(runCatching { registry.removeIfIdle(SessionRuntime.ROOTLESS) { removed = true } }.isFailure)
        controller.failList = true
        assertTrue(runCatching { registry.removeIfIdle(SessionRuntime.ROOTLESS) { removed = true } }.isFailure)
        assertFalse(removed)
        assertTrue(registry.isRegistered(SessionRuntime.ROOTLESS))
        assertFalse(controller.closed)
        registry.close()
    }

    @Test fun removalUnregistersOnlyIdleDistributionAndAllowsReinstallation() = runBlocking {
        val registry = UnifiedSessionRegistry()
        val controller = FakeController("rootless-idle")
        val sibling = FakeController("ubuntu-live")
        registry.register(SessionRuntime.ROOTLESS, controller)
        registry.register(SessionRuntime.ROOTLESS_UBUNTU, sibling)
        registry.terminate("rootless-idle", 9).getOrThrow()
        registry.removeIfIdle(SessionRuntime.ROOTLESS) { controller.close() }
        assertFalse(registry.isRegistered(SessionRuntime.ROOTLESS))
        assertTrue(registry.create(SessionRuntime.ROOTLESS, "/bin/sh", "/", rows = 24u, cols = 80u).isFailure)
        assertEquals(listOf("ubuntu-live"), registry.list().getOrThrow().map { it.id })
        assertFalse(sibling.closed)
        registry.register(SessionRuntime.ROOTLESS, FakeController("rootless-new"))
        assertTrue(registry.create(SessionRuntime.ROOTLESS, "/bin/sh", "/", rows = 24u, cols = 80u).isSuccess)
        registry.close()
    }

    @Test fun storageDeletionRejectsActiveOrUnknownSessionsAndPreservesSiblings() = runBlocking {
        val registry = UnifiedSessionRegistry()
        val storage = java.util.UUID.randomUUID().toString()
        val siblingStorage = java.util.UUID.randomUUID().toString()
        val controller = FakeController("native-managed")
        val sibling = FakeController("native-sibling")
        registry.register(SessionRuntime.NATIVE, controller, storage)
        registry.register(SessionRuntime.NATIVE, sibling, siblingStorage)
        var deleted = false
        assertTrue(runCatching { registry.deleteStorageIfIdle(SessionRuntime.NATIVE, storage) { deleted = true } }.isFailure)
        controller.failList = true
        assertTrue(runCatching { registry.deleteStorageIfIdle(SessionRuntime.NATIVE, storage) { deleted = true } }.isFailure)
        assertFalse(deleted)
        assertFalse(controller.closed)
        controller.failList = false
        registry.list().getOrThrow()
        registry.terminate("native-managed", 9).getOrThrow()
        registry.deleteStorageIfIdle(SessionRuntime.NATIVE, storage) { deleted = true }
        assertTrue(deleted)
        assertTrue(controller.closed)
        assertFalse(registry.isRegistered(SessionRuntime.NATIVE, storage))
        assertEquals(listOf("native-sibling"), registry.list().getOrThrow().map { it.id })
        assertFalse(sibling.closed)
        registry.close()
    }

    private class IdleAvfTransport : FrameTransport {
        val incoming = Channel<ProtocolFrame>(Channel.UNLIMITED)
        override suspend fun send(frame: ProtocolFrame): Result<Unit> = runCatching {
            incoming.send(ProtocolFrame(service = Service.SESSION, kind = FrameKind.RESPONSE, requestId = frame.requestId,
                payload = SessionResponseCodec.encode(SessionResponse.Listed(emptyList()))))
        }
        override fun incomingFrames(): Flow<ProtocolFrame> = incoming.receiveAsFlow()
        override fun close() { incoming.close() }
    }

    private class FakeController(
        private val id: String,
        failList: Boolean = false,
        extraIds: List<String> = emptyList(),
    ) : SessionController {
        private val mutableEvents = MutableSharedFlow<SessionEvent>()
        private val mutableState = MutableStateFlow(SessionConnectionState.CONNECTED)
        override val events: SharedFlow<SessionEvent> = mutableEvents
        override val connectionState: StateFlow<SessionConnectionState> = mutableState
        fun disconnect() { mutableState.value = SessionConnectionState.DISCONNECTED }
        var writeCount = 0
        var detachCount = 0
        var createCount = 0
        var closed = false
        var failList = failList
        var failTerminate = false
        val terminatedIds = mutableListOf<String>()
        private val descriptors = (listOf(id) + extraIds).associateWithTo(linkedMapOf(), ::descriptor)

        override suspend fun create(shell: String, cwd: String, env: Map<String, String>, rows: UShort, cols: UShort): Result<SessionDescriptor> {
            createCount++
            return Result.success(descriptors.getValue(id))
        }
        override suspend fun list() = if (failList) {
            Result.failure(IllegalStateException("runtime unavailable"))
        } else {
            Result.success(descriptors.values.toList())
        }
        override suspend fun attach(sessionId: String) = runCatching { descriptors.getValue(sessionId) }
        override suspend fun detach(sessionId: String): Result<Unit> { detachCount++; return Result.success(Unit) }
        override suspend fun resize(sessionId: String, rows: UShort, cols: UShort) =
            runCatching { descriptors.getValue(sessionId).copy(rows = rows, cols = cols) }
        override suspend fun write(sessionId: String, bytes: ByteArray): Result<Int> { writeCount++; return Result.success(bytes.size) }
        override suspend fun terminate(sessionId: String, signal: Int): Result<Unit> =
            if (failTerminate) {
                Result.failure(IllegalStateException("terminate failed"))
            } else if (descriptors.remove(sessionId) != null) {
                terminatedIds += sessionId
                Result.success(Unit)
            } else {
                Result.failure(NoSuchElementException("Session not found"))
            }
        override fun close() { closed = true; mutableState.value = SessionConnectionState.DISCONNECTED }

        private fun descriptor(sessionId: String) =
            SessionDescriptor(sessionId, sessionId, "/bin/sh", "/", sessionId.last().code.toLong(), 24u, 80u, SessionStatus.RUNNING)
    }
}
