package io.okno

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import java.io.File
import java.io.FileInputStream

/**
 * Where the Linux system lives on the device and how it is started.
 *
 * Debian runs under proot: proot intercepts system calls with ptrace and makes
 * [rootfs] look like "/" to every process, with a fake root user. No root on
 * the phone is needed for this.
 */
class LinuxEnvironment(context: Context) {
    val filesDir: File = context.filesDir
    val rootfs = File(filesDir, "rootfs")
    private val tmpDir = File(filesDir, "proot-tmp")
    private val fakeProcDir = File(filesDir, "fake-proc")
    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    private val proot = File(nativeDir, "libproot.so")
    private val loader = File(nativeDir, "libproot-loader.so")

    /** Installed and new enough to have the desktop (the first versions had none). */
    val isInstalled: Boolean
        get() = File(rootfs, INSTALLED_MARKER).exists() && File(rootfs, "usr/local/bin/okno-desktop").exists()

    /** Version of the installed system image, as written by [RootfsInstaller]. */
    val installedVersion: String?
        get() = File(rootfs, INSTALLED_MARKER).takeIf { it.exists() }?.readText()?.trim()

    val prootPath: String get() = proot.absolutePath

    /** Environment of the proot process itself (Android side). */
    fun hostEnvironment(): Array<String> = arrayOf(
        "PROOT_TMP_DIR=${tmpDir.absolutePath}",
        "PROOT_LOADER=${loader.absolutePath}",
        "LD_LIBRARY_PATH=${nativeDir.absolutePath}",
        "HOME=${filesDir.absolutePath}",
        "TMPDIR=${tmpDir.absolutePath}",
        "PATH=/system/bin",
        "ANDROID_ROOT=/system",
        "ANDROID_DATA=/data",
    )

    /**
     * Full argv (including argv[0]) for running [command] inside Debian.
     * The guest gets a clean environment via `env -i`, so nothing from
     * Android (LD_LIBRARY_PATH in particular) leaks into glibc programs.
     */
    fun prootArgs(command: List<String>): Array<String> {
        tmpDir.mkdirs()
        val args = mutableListOf(
            "proot",
            "--kill-on-exit",
            "--link2symlink",
            "--sysvipc",
            "-L",
            "-0",
            "-r", rootfs.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/dev/urandom:/dev/random",
            "-b", "/proc/self/fd:/dev/fd",
            "-b", "/proc/self/fd/0:/dev/stdin",
            "-b", "/proc/self/fd/1:/dev/stdout",
            "-b", "/proc/self/fd/2:/dev/stderr",
            "-b", "${File(rootfs, "tmp").absolutePath}:/dev/shm",
        )
        for ((procPath, fake) in fakeProcFiles()) {
            args += listOf("-b", "${fake.absolutePath}:$procPath")
        }
        val storage = Environment.getExternalStorageDirectory()
        if (storage.canRead()) {
            args += listOf("-b", "${storage.absolutePath}:/sdcard")
        }
        args += listOf("-w", "/root", "/usr/bin/env", "-i")
        args += GUEST_ENV
        args += command
        return args.toTypedArray()
    }

    /**
     * Android hides several /proc files from apps; programs like top, ps and
     * free break without them. Replace each unreadable one with a stand-in.
     */
    private fun fakeProcFiles(): Map<String, File> {
        fakeProcDir.mkdirs()
        val cpus = Runtime.getRuntime().availableProcessors()
        val uptime = "%.2f".format(java.util.Locale.ROOT, SystemClock.elapsedRealtime() / 1000.0)
        val candidates = mapOf(
            "/proc/loadavg" to "0.12 0.07 0.02 2/165 765\n",
            "/proc/stat" to buildString {
                append("cpu  1957 0 2877 93280 262 342 254 87 0 0\n")
                repeat(cpus) { append("cpu$it 245 0 360 11660 33 43 32 11 0 0\n") }
                append("intr 0\nctxt 0\nbtime 0\nprocesses 0\nprocs_running 1\nprocs_blocked 0\n")
                append("softirq 0 0 0 0 0 0 0 0 0 0 0\n")
            },
            "/proc/uptime" to "$uptime $uptime\n",
            "/proc/version" to "Linux version ${System.getProperty("os.version")} (okno@okno) #1 SMP PREEMPT\n",
            "/proc/vmstat" to "nr_free_pages 0\nnr_inactive_anon 0\nnr_active_anon 0\nnr_inactive_file 0\nnr_active_file 0\n",
            "/proc/sys/kernel/cap_last_cap" to "40\n",
            "/proc/sys/fs/inotify/max_user_watches" to "4096\n",
        )
        val result = LinkedHashMap<String, File>()
        for ((path, content) in candidates) {
            if (isReadable(path)) continue
            val fake = File(fakeProcDir, path.removePrefix("/proc/").replace('/', '_'))
            fake.writeText(content)
            result[path] = fake
        }
        return result
    }

    private fun isReadable(path: String): Boolean = try {
        FileInputStream(path).use { it.read() }
        true
    } catch (e: Exception) {
        false
    }

    companion object {
        const val INSTALLED_MARKER = ".okno-installed"

        val LOGIN_SHELL = listOf("/bin/bash", "--login")

        private val GUEST_ENV = listOf(
            "HOME=/root",
            "USER=root",
            "LOGNAME=root",
            "SHELL=/bin/bash",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=C.UTF-8",
            "TMPDIR=/tmp",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        )
    }
}
