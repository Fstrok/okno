package io.okno

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * Opens links from Linux in the phone's browser. okno-open-android (the
 * BROWSER of every Linux session and the desktop's web browser) drops each
 * link as a file into /tmp/.okno/open; this watches that folder.
 *
 * Logins of command-line tools (Claude Code, gh, ...) then happen in the
 * browser the user is already signed in to, and their localhost callbacks
 * reach Linux, which shares the phone's network.
 */
class LinkOpener(private val context: Context, rootfs: File) {
    private val dir = File(rootfs, "tmp/.okno/open")
    private val main = Handler(Looper.getMainLooper())
    private var observer: FileObserver? = null

    /** Starts watching; again after a reinstall replaced the folder. Idempotent. */
    fun start() {
        if (observer != null && dir.isDirectory) return
        stop()
        if (!dir.isDirectory && !dir.mkdirs()) return
        @Suppress("DEPRECATION") // the File constructor needs API 29
        observer = object : FileObserver(dir.path, MOVED_TO or CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null && path.endsWith(".url")) open(File(dir, path))
            }
        }.also { it.startWatching() }
        // Links dropped while nobody was watching.
        dir.listFiles { f -> f.name.endsWith(".url") }?.forEach(::open)
    }

    fun stop() {
        observer?.stopWatching()
        observer = null
    }

    private fun open(file: File) {
        val url = runCatching { file.readText().trim() }.getOrNull()
        file.delete()
        val uri = url?.let(Uri::parse) ?: return
        // Only web links: anything else could start arbitrary apps.
        if (uri.scheme != "http" && uri.scheme != "https") return
        main.post {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Log.w("Okno", "cannot open $uri", e)
            }
        }
    }
}
