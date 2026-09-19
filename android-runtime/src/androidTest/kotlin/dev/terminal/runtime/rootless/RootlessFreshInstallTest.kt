package dev.terminal.runtime.rootless

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.runtime.native.ApkNativeExecutableLoader
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in network installation in the library's standalone test APK, never the user's app. */
@RunWith(AndroidJUnit4::class)
class RootlessFreshInstallTest {
    @Test fun installsDistributionAndVerifiesNativeLinkEnvironment() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("rootlessFreshInstall") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.terminal.runtime.test") {
            "Fresh-install tests must run in the standalone runtime test APK"
        }
        val distribution = RootlessDistribution.valueOf(
            (arguments.getString("rootlessDistribution") ?: "DEBIAN").uppercase(Locale.ROOT),
        )
        val temporary = Files.createTempDirectory(context.filesDir.toPath(), "fresh-rootless-")
        val layout = RootlessDebianLayout(File(temporary.toFile(), "rootless/${distribution.directory}"), distribution)
        var previousPhase: RootlessPhase? = null
        val reporter = RootlessProgressReporter { phase, _ ->
            if (phase != previousPhase) {
                Log.i("RootlessFreshInstall", "${distribution.name}: ${phase.name}")
                previousPhase = phase
            }
        }
        try {
            layout.createHostDirectories()
            val loader = ApkNativeExecutableLoader(context)
            val proot = loader.resolvePackagedExecutable("proot").getOrThrow()
            val guestLoader = loader.resolvePackagedExecutable("proot-loader").getOrThrow()
            val statKernel = arguments.getString("rootlessStatKernel") == "true"
            val pathKernel = arguments.getString("rootlessPathKernel") == "true"
            val mutationEntry = arguments.getString("rootlessMutationEntry") == "true"
            val fstatEntry = arguments.getString("rootlessFstatEntry") == "true"
            val openNotifyThread = arguments.getString("rootlessOpenNotifyThread") == "true"
            val tcgetsDirect = arguments.getString("rootlessTcgetsDirect") == "true"
            val idEntry = arguments.getString("rootlessIdEntry") == "true"
            require(!(tcgetsDirect || idEntry) || statKernel) { "Entry experiments require the verified experimental payload" }
            val fastUnlink = arguments.getString("rootlessFastUnlink") == "true"
            val fastCreate = arguments.getString("rootlessFastCreate") == "true"
            val fastOpen = arguments.getString("rootlessFastOpen") == "true"
            val sharedStat = arguments.getString("rootlessSharedStat") == "true"
            val fastfd = arguments.getString("rootlessFastfd") == "true"
            require(!sharedStat || fastfd) { "Shared stat requires fastfd" }
            require(!fastUnlink || fastOpen) { "Fast unlink requires fast open" }
            require(!fastCreate || fastOpen) { "Fast create requires fast open" }
            require(!fastOpen || fastfd) { "Fast open requires fastfd" }
            require(!fastfd || statKernel) { "Fastfd requires the verified experimental payload" }
            val fastfdLibrary = if (fastfd) File(context.applicationInfo.nativeLibraryDir, "libproot-faststat.so") else null
            fastfdLibrary?.let { library ->
                val expected = requireNotNull(arguments.getString("rootlessFastfdSha256"))
                require(expected.matches(Regex("[0-9a-f]{64}")))
                val actual = MessageDigest.getInstance("SHA-256").digest(library.readBytes())
                    .joinToString("") { "%02x".format(it) }
                assertEquals("Experimental guest DSO digest", expected, actual)
                Log.i("RootlessFreshInstall", "FASTFD=1 dso=$actual")
            }
            require(!pathKernel || statKernel) { "Path experiment requires the verified stat experiment payload" }
            require(!mutationEntry || statKernel) { "Mutation experiment requires the verified stat experiment payload" }
            require(!fstatEntry || statKernel) { "Fstat experiment requires the verified stat experiment payload" }
            require(!openNotifyThread || statKernel) { "Notification experiment requires the verified stat experiment payload" }
            if (statKernel) {
                val expected = requireNotNull(arguments.getString("rootlessProotSha256"))
                require(expected.matches(Regex("[0-9a-f]{64}")))
                val actual = MessageDigest.getInstance("SHA-256").digest(proot.readBytes())
                    .joinToString("") { "%02x".format(it) }
                assertEquals("Experimental PRoot digest", expected, actual)
                Log.i("RootlessFreshInstall", "STAT_KERNEL=1 proot=$actual")
            }
            val denyNativeLinks = arguments.getString("rootlessDenyNativeLinks") == "true"
            val packagedLibrary = if (dev.terminal.runtime.BuildConfig.ROOTLESS_ACCELERATION &&
                !fastfd && !openNotifyThread) File(context.applicationInfo.nativeLibraryDir, "libproot-faststat.so") else null
            val expectStdio = dev.terminal.runtime.BuildConfig.ROOTLESS_STDIO_BUFFER && !denyNativeLinks
            val stdioCheck = if (expectStdio) """
                import ctypes,os,tempfile
                assert os.environ.get('PROOT_STDIO_BUFFER')=='1'
                libc=ctypes.CDLL(None,use_errno=True)
                libc.fopen.argtypes=[ctypes.c_char_p,ctypes.c_char_p]
                libc.fopen.restype=ctypes.c_void_p
                libc.fclose.argtypes=[ctypes.c_void_p]
                libc.fflush.argtypes=[ctypes.c_void_p]
                libc.fileno.argtypes=[ctypes.c_void_p]
                libc.__fbufsize.argtypes=[ctypes.c_void_p]
                libc.__fbufsize.restype=ctypes.c_size_t
                for name in ('fread','fwrite'):
                    operation=getattr(libc,name)
                    operation.argtypes=[ctypes.c_void_p,ctypes.c_size_t,ctypes.c_size_t,ctypes.c_void_p]
                    operation.restype=ctypes.c_size_t
                block=bytes(range(256))*16
                source=ctypes.create_string_buffer(block)
                target=ctypes.create_string_buffer(len(block))
                with tempfile.TemporaryDirectory() as directory:
                    path=os.fsencode(directory+'/stdio')
                    stream=libc.fopen(path,b'wb')
                    assert stream,ctypes.get_errno()
                    try:
                        assert libc.__fbufsize(stream)==65536
                        for _ in range(512):
                            assert libc.fwrite(source,1,len(block),stream)==len(block)
                        assert libc.fflush(stream)==0
                        os.fsync(libc.fileno(stream))
                    finally:
                        assert libc.fclose(stream)==0
                    assert os.stat(path).st_size==512*len(block)
                    stream=libc.fopen(path,b'rb')
                    assert stream,ctypes.get_errno()
                    try:
                        assert libc.__fbufsize(stream)==65536
                        for _ in range(512):
                            assert libc.fread(target,1,len(block),stream)==len(block)
                            assert target.raw==block
                        assert libc.fread(target,1,1,stream)==0
                    finally:
                        assert libc.fclose(stream)==0
                print('PASS packaged stdio buffer and content')
            """.trimIndent() else ""
            RootlessBootstrapper(context, layout, reporter, createHardLink = { link, original ->
                if (denyNativeLinks) throw java.nio.file.FileSystemException(
                    link.toString(), original.toString(), "Operation not permitted")
                Files.createLink(link, original)
            }).installIfNeeded().getOrThrow()
            assertEquals(!denyNativeLinks, RootlessStorageFormat.usesNativeLinks(layout.rootfs.toPath()))
            assertEquals(denyNativeLinks, RootlessStorageFormat.usesEmulatedLinks(layout.rootfs.toPath()))
            val launch = buildRootlessLaunchSpec(layout, proot, guestLoader, "/bin/sh", "/", emptyMap())
            assertEquals(denyNativeLinks, launch.arguments.contains("--link2symlink"))
            RootlessEnvironmentSetup(context, layout, proot, guestLoader, reporter,
                statKernel, pathKernel, mutationEntry, fstatEntry, openNotifyThread, fastfdLibrary, fastOpen, fastCreate, fastUnlink, tcgetsDirect, idEntry, sharedStat).prepare()
            RootlessEnvironmentProfile.validate(layout).getOrThrow()
            val check = runRootlessSetupCommand(layout, proot, guestLoader, listOf("/bin/sh", "-c", """
                set -eu
                ${if (fastfd) "exec 3>&2; export PROOT_FASTSTAT_REPORT=1 PROOT_FASTSTAT_REPORT_FD=3" else ":"}
                test "${'$'}(id -u)" = 0
                work=${'$'}(mktemp -d)
                trap 'rm -rf "${'$'}work"' EXIT
                printf 'native link\n' > "${'$'}work/a"
                ln "${'$'}work/a" "${'$'}work/b"
                ${if (!denyNativeLinks) "test ! -L \"${'$'}work/b\"" else ":"}
                test "${'$'}(stat -c %h "${'$'}work/b")" = 2
                rm "${'$'}work/a"
                test "${'$'}(cat "${'$'}work/b")" = 'native link'
                ${if (fastOpen) "cat /etc/debian_version >/dev/null" else ":"}
                ${if (fastCreate || fastUnlink) "python3 -B -c 'import os,sys; os.chdir(sys.argv[1]); f=open(\"created\", \"wb\"); f.write(b\"created\"); f.close(); assert open(\"created\", \"rb\").read()==b\"created\"; os.unlink(\"created\")' \"${'$'}work\"" else ":"}
                printf 'PASS fresh distribution native links\n'
            """.trimIndent()), statKernelAcceleration = statKernel, pathKernelAcceleration = pathKernel,
                mutationEntryAcceleration = mutationEntry, fstatEntryAcceleration = fstatEntry,
                openNotifyThread = openNotifyThread, fastfdLibrary = fastfdLibrary, fastOpen = fastOpen, fastCreate = fastCreate, fastUnlink = fastUnlink,
                tcgetsDirect = tcgetsDirect, idEntry = idEntry, sharedStat = sharedStat,
                accelerationLibrary = packagedLibrary)
            assertEquals(check.output, 0, check.exitCode)
            assertTrue(check.output.contains("PASS fresh distribution native links"))
            if (expectStdio) {
                val stdio = runRootlessSetupCommand(layout, proot, guestLoader,
                    listOf("/usr/bin/python3", "-c", stdioCheck), accelerationLibrary = packagedLibrary)
                assertEquals(stdio.output, 0, stdio.exitCode)
                assertTrue(stdio.output.contains("PASS packaged stdio buffer and content"))
                Log.i("RootlessFreshInstall", "${distribution.name}: STDIO_MAINTENANCE_VERIFIED")
            }
            if (sharedStat) assertTrue("No accelerated path stat observed: ${check.output}",
                Regex("\"hits\":([1-9][0-9]*)").containsMatchIn(check.output))
            if (fastfd) assertTrue("No accelerated fstat observed: ${check.output}",
                Regex("\"fd_hits\":([1-9][0-9]*)").containsMatchIn(check.output))
            if (fastOpen) assertTrue("No accelerated open observed: ${check.output}",
                Regex("\"open_hits\":([1-9][0-9]*)").containsMatchIn(check.output))
            if (fastCreate) assertTrue("No accelerated creation observed: ${check.output}",
                Regex("\"create_hits\":([1-9][0-9]*)").containsMatchIn(check.output))
            if (fastUnlink) assertTrue("No accelerated unlink observed: ${check.output}",
                Regex("\"unlink_hits\":([1-9][0-9]*)").containsMatchIn(check.output))
            if (dev.terminal.runtime.BuildConfig.ROOTLESS_ACCELERATION) {
                val cacheDirectoriesBefore = context.cacheDir.list().orEmpty().filter { it.startsWith("nss-") }.toSet()
                fun alive(pid: Int): Boolean = runCatching {
                    File("/proc/$pid/stat").readText().substringAfterLast(')').trim().first() !in setOf('Z', 'X')
                }.getOrDefault(false)
                // Only Debian installs the optional NSS cache; all distributions exercise the PTY.
                if (distribution == RootlessDistribution.DEBIAN) {
                    var cachePid = 0
                    RootlessNssCache(layout, proot, guestLoader, context.cacheDir).use { cache ->
                        assertTrue(cache.ensureStarted())
                        val pidFile = File(cache.bindings.first().substringBefore(":"), "nscd.pid")
                        withTimeout(5_000) {
                            while (!pidFile.isFile) kotlinx.coroutines.delay(20)
                        }
                        cachePid = pidFile.readText().trim().toInt()
                        assertTrue(alive(cachePid))
                        assertTrue(cache.ensureStarted())
                        assertEquals(cachePid, pidFile.readText().trim().toInt())
                        android.os.Process.sendSignal(cachePid, 9)
                        withTimeout(5_000) {
                            do {
                                kotlinx.coroutines.delay(20)
                                assertTrue(cache.ensureStarted())
                                val replacement = pidFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
                            } while (replacement == null || replacement == cachePid)
                        }
                        cachePid = pidFile.readText().trim().toInt()
                        assertTrue(alive(cachePid))
                    }
                    withTimeout(5_000) {
                        while (alive(cachePid)) kotlinx.coroutines.delay(20)
                    }
                }
                val controller = RootlessSessionController(layout, proot, guestLoader, cacheDirectory = context.cacheDir)
                try {
                    val output = StringBuilder()
                    val observed = async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(30_000) {
                            controller.events.first { event ->
                                if (event is dev.terminal.protocol.SessionEvent.Output) output.append(event.bytes.toString(Charsets.UTF_8))
                                output.contains("PASS accelerated PTY")
                            }
                        }
                    }
                    val command = listOf("/usr/bin/python3", "-c",
                        """
                        import ctypes,os,stat,subprocess,tarfile,tempfile,pwd,grp
                        assert pwd.getpwnam('root').pw_uid==0
                        assert grp.getgrnam('root').gr_gid==0
                        maps=open('/proc/self/maps').read().splitlines()
                        ${if (distribution == RootlessDistribution.DEBIAN) "assert len([line for line in maps if 'r--s' in line and '/run/db' in line])==2" else "# NSS shared caching is Debian-only"}
                        assert os.stat('/etc/passwd').st_size>0
                        c=ctypes.CDLL(None,use_errno=True)
                        ${if (!denyNativeLinks) "assert c.proot_faststat_hits()>0" else "assert not any('terminal-faststat.so' in line for line in maps)"}
                        ${if (!denyNativeLinks) "n=c.proot_fastclose_hits()" else "n=0"}
                        f=os.open('/etc/passwd',os.O_RDONLY)
                        os.close(f)
                        ${if (!denyNativeLinks) "assert c.proot_fastclose_hits()>n" else "assert os.stat('/etc/passwd').st_size>0"}
                        with tempfile.TemporaryDirectory() as root:
                            target=root+'/target'
                            os.mkdir(target)
                            assert c.syscall(452,-100,os.fsencode(target),0o751,0x100)==0, ctypes.get_errno()
                            assert stat.S_IMODE(os.stat(target).st_mode)==0o751
                            archive=root+'/fixture.tar'
                            with tarfile.open(archive,'w') as output:
                                entry=tarfile.TarInfo('directory')
                                entry.type=tarfile.DIRTYPE
                                entry.mode=0o705
                                output.addfile(entry)
                            subprocess.run(['tar','-xf',archive,'-C',target],check=True)
                            assert stat.S_IMODE(os.stat(target+'/directory').st_mode)==0o705
                        print('PASS accelerated PTY')
                        """.trimIndent().replace("print('PASS accelerated PTY')",
                            stdioCheck + "\nprint('PASS accelerated PTY')"))
                    controller.create("/bin/sh", "/root", mapOf(
                        dev.terminal.runtime.session.SessionCommand.KEY to dev.terminal.runtime.session.SessionCommand.encode(command)),
                        24u, 80u).getOrThrow()
                    observed.await()
                    if (expectStdio) assertTrue(output.toString().contains("PASS packaged stdio buffer and content"))
                } finally {
                    controller.close()
                }
                assertEquals(cacheDirectoriesBefore,
                    context.cacheDir.list().orEmpty().filter { it.startsWith("nss-") }.toSet())
            }
            Log.i("RootlessFreshInstall", "${distribution.name}: VERIFIED")
        } finally {
            deleteRootlessTree(temporary)
        }
    }
}
