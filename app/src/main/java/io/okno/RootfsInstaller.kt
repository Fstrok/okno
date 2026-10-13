package io.okno

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.GZIPInputStream

/** Unpacks the bundled Debian image into [LinuxEnvironment.rootfs]. */
class RootfsInstaller(private val context: Context, private val env: LinuxEnvironment) {

    /** Blocking; call off the main thread. [onProgress] receives 0..100. */
    fun install(onProgress: (Int) -> Unit) {
        // Unpack next to the final location and rename at the end, so an
        // interrupted install never leaves a half-extracted system behind.
        val staging = File(env.filesDir, "rootfs.staging")
        deleteTree(staging)
        staging.mkdirs()

        val total = context.assets.openFd(ASSET).use { it.length }
        val counter = CountingInputStream(context.assets.open(ASSET))
        val dirModes = ArrayList<Pair<File, Int>>()
        val hardLinks = ArrayList<Triple<File, String, Int>>()
        var lastPercent = -1

        TarArchiveInputStream(GZIPInputStream(BufferedInputStream(counter, 1 shl 16), 1 shl 16)).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val name = entry.name.removePrefix("./").trimEnd('/')
                if (name.isEmpty() || name.split('/').contains("..")) continue
                val target = File(staging, name)
                target.parentFile?.mkdirs()
                when {
                    entry.isDirectory -> {
                        target.mkdirs()
                        dirModes += target to entry.mode
                    }
                    entry.isSymbolicLink -> {
                        target.delete()
                        Os.symlink(entry.linkName, target.path)
                    }
                    // Android forbids hard links in app storage; copy instead.
                    entry.isLink -> hardLinks += Triple(target, entry.linkName, entry.mode)
                    entry.isFile -> {
                        FileOutputStream(target).use { tar.copyTo(it, 1 shl 16) }
                        chmod(target, entry.mode or 0b110_000_000)
                    }
                    // Device nodes and FIFOs cannot be created without root;
                    // /dev comes from Android through a proot bind instead.
                    else -> Unit
                }
                val percent = (counter.count * 100 / total).toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    onProgress(percent)
                }
            }
        }

        for ((target, linkName, mode) in hardLinks) {
            File(staging, linkName.removePrefix("./")).copyTo(target, overwrite = true)
            chmod(target, mode or 0b110_000_000)
        }

        configure(staging)

        // Directory modes last (deepest first): a read-only directory would
        // otherwise block writing its own contents. The app owns every file,
        // so keep owner rwx; proot's fake root cannot bypass real permissions.
        for ((dir, mode) in dirModes.asReversed()) chmod(dir, mode or 0b111_000_000)

        File(staging, LinuxEnvironment.INSTALLED_MARKER).writeText(rootfsVersion())
        deleteTree(env.rootfs)
        if (!staging.renameTo(env.rootfs)) throw IOException("cannot move ${staging.path} to ${env.rootfs.path}")
        onProgress(100)
    }

    /**
     * Device-specific setup. Everything static (hosts, apt settings, desktop
     * configuration) is baked into the image by scripts/build-rootfs.sh.
     */
    private fun configure(root: File) {
        // Android puts the app into supplementary groups (internet, storage,
        // ...); name them so tools like `id` and `groups` don't complain.
        val groupFile = File(root, "etc/group")
        val known = groupFile.readLines().mapNotNull { it.split(':').getOrNull(2)?.toIntOrNull() }.toSet()
        val missing = androidGroups().filter { it !in known }
        if (missing.isNotEmpty()) {
            groupFile.appendText(missing.joinToString("") { "aid_$it:x:$it:root\n" })
        }

        // Rewritten from Android's current network on every session start.
        File(root, "etc/resolv.conf").delete()
    }

    private fun androidGroups(): List<Int> {
        val groups = File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("Groups:") }
            ?.substringAfter(':')?.trim()?.split(Regex("\\s+"))
            ?.mapNotNull { it.toIntOrNull() }
            .orEmpty()
        return (groups + Os.getgid()).distinct()
    }

    private fun rootfsVersion(): String =
        try {
            context.assets.open("rootfs.version").bufferedReader().use { it.readText().trim() }
        } catch (e: IOException) {
            "unknown"
        }

    private fun chmod(file: File, mode: Int) {
        try {
            Os.chmod(file.path, mode and 0b111_111_111_111)
        } catch (e: ErrnoException) {
            // setuid/setgid bits may be refused; plain permissions are what matter.
            Os.chmod(file.path, mode and 0b111_111_111)
        }
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) count += it }

        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }

    companion object {
        private const val ASSET = "rootfs.tgz"

        /**
         * Recursive delete that never follows symlinks: the Linux tree is full
         * of absolute links that would point into Android's own filesystem.
         */
        fun deleteTree(dir: File) {
            val root = dir.toPath()
            if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(d: Path, attrs: BasicFileAttributes): FileVisitResult {
                    // Restore owner access so the contents can be removed.
                    d.toFile().setWritable(true, true)
                    d.toFile().setExecutable(true, true)
                    d.toFile().setReadable(true, true)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(d: Path, exc: IOException?): FileVisitResult {
                    Files.delete(d)
                    return FileVisitResult.CONTINUE
                }
            })
        }
    }
}
