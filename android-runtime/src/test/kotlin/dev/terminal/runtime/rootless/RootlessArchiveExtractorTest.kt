package dev.terminal.runtime.rootless

import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootlessArchiveExtractorTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun verifiesChecksumAndPreservesModesSafeLinksAndForwardHardLinks() {
        val archive = fixture(
            directory(ROOT, 0b111_101_101),
            symlink("$ROOT/bin", "usr/bin"),
            hardLink("$ROOT/usr/bin/sh-copy", "$ROOT/usr/bin/sh"),
            directory("$ROOT/usr", 0b111_101_101),
            directory("$ROOT/usr/bin", 0b111_101_101),
            file("$ROOT/usr/bin/sh", "shell".encodeToByteArray(), 0b111_101_101),
        )
        val destination = temporary.newFolder("rootfs").toPath()
        val extractor = RootlessArchiveExtractor(ROOT, maxExtractedBytes = 1024)
        val digest = Files.readAllBytes(archive).let { bytes ->
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }

        extractor.verifySha256(archive, digest)
        extractor.extract(archive, destination)

        assertTrue(Files.isExecutable(destination.resolve("bin/sh")))
        assertArrayEquals("shell".encodeToByteArray(), Files.readAllBytes(destination.resolve("usr/bin/sh")))
        assertTrue(Files.isSameFile(destination.resolve("usr/bin/sh"), destination.resolve("usr/bin/sh-copy")))
        assertTrue(Files.readSymbolicLink(destination.resolve("bin")).toString() == "usr/bin")
    }

    @Test fun rejectsChecksumMismatchTraversalAndEscapingSymbolicLink() {
        val extractor = RootlessArchiveExtractor(ROOT, maxExtractedBytes = 1024)
        val valid = fixture(file("$ROOT/file", byteArrayOf(1), 0b110_100_100))
        assertTrue(runCatching { extractor.verifySha256(valid, "00".repeat(32)) }.isFailure)

        val traversal = fixture(file("$ROOT/../../escape", byteArrayOf(1), 0b110_100_100))
        val traversalRoot = temporary.newFolder("traversal").toPath()
        assertTrue(runCatching { extractor.extract(traversal, traversalRoot) }.isFailure)
        assertFalse(Files.exists(traversalRoot.parent.resolve("escape")))

        val link = fixture(symlink("$ROOT/link", "../../escape"))
        assertTrue(runCatching { extractor.extract(link, temporary.newFolder("link").toPath()) }.isFailure)
    }

    @Test fun rejectsExpandedSizeLimitAndUnexpectedSpecialFiles() {
        val tooLarge = fixture(file("$ROOT/file", byteArrayOf(1, 2, 3, 4), 0b110_100_100))
        assertTrue(runCatching {
            RootlessArchiveExtractor(ROOT, maxExtractedBytes = 3).extract(tooLarge, temporary.newFolder("large").toPath())
        }.isFailure)

        val special = TarArchiveEntry("$ROOT/etc/device", TarConstants.LF_CHR).apply { mode = 0b110_100_100 }
        assertTrue(runCatching {
            RootlessArchiveExtractor(ROOT, maxExtractedBytes = 1024)
                .extract(fixture(Entry(special)), temporary.newFolder("special").toPath())
        }.isFailure)
    }

    /**
     * Optional local integration check for release engineering. CI stays hermetic: it never
     * downloads this archive, but a previously verified catalog artifact can be supplied.
     */
    @Test fun extractsPinnedCatalogArchiveWhenExplicitlySupplied() {
        val supplied = System.getenv("ROOTLESS_TEST_ARCHIVE")?.let(Path::of)
        assumeTrue("ROOTLESS_TEST_ARCHIVE was not supplied", supplied != null && Files.isRegularFile(supplied))
        val archive = requireNotNull(supplied)
        val destination = temporary.newFolder("pinned-rootfs").toPath()
        val extractor = RootlessArchiveExtractor("debian-trixie-aarch64", 2L * 1024 * 1024 * 1024)

        extractor.verifySha256(archive, PINNED_ARCHIVE_SHA256)
        extractor.extract(archive, destination)

        assertTrue(Files.isExecutable(destination.resolve("bin/sh")))
        assertTrue(
            Files.isExecutable(destination.resolve("usr/bin/apt")) ||
                Files.isExecutable(destination.resolve("usr/bin/apt-get")),
        )
    }

    private fun fixture(vararg entries: Entry): Path {
        val archive = temporary.newFile("fixture-${System.nanoTime()}.tar.xz").toPath()
        BufferedOutputStream(Files.newOutputStream(archive)).use { file ->
            XZCompressorOutputStream(file).use { xz ->
                TarArchiveOutputStream(xz).use { tar ->
                    entries.forEach { item ->
                        tar.putArchiveEntry(item.entry)
                        item.bytes?.let(tar::write)
                        tar.closeArchiveEntry()
                    }
                    tar.finish()
                }
            }
        }
        return archive
    }

    private fun directory(name: String, mode: Int) = Entry(TarArchiveEntry("${name.removeSuffix("/")}/").apply {
        this.mode = mode
        size = 0
    })

    private fun file(name: String, bytes: ByteArray, mode: Int) = Entry(TarArchiveEntry(name).apply {
        this.mode = mode
        size = bytes.size.toLong()
    }, bytes)

    private fun symlink(name: String, target: String) = Entry(TarArchiveEntry(name, TarConstants.LF_SYMLINK).apply {
        linkName = target
        mode = 0b111_111_111
    })

    private fun hardLink(name: String, target: String) = Entry(TarArchiveEntry(name, TarConstants.LF_LINK).apply {
        linkName = target
        mode = 0b111_101_101
    })

    private data class Entry(val entry: TarArchiveEntry, val bytes: ByteArray? = null)

    private companion object {
        const val ROOT = "debian-test-aarch64"
        const val PINNED_ARCHIVE_SHA256 = "3834a11cbc6496935760bdc20cca7e2c25724d0cd8f5e4926da8fd5ca1857918"
    }
}
