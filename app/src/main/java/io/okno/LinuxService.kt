package io.okno

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Owns the Linux sessions so they keep running while the app is in the
 * background. The foreground notification is what keeps Android from killing it.
 */
class LinuxService : Service() {
    inner class LocalBinder : Binder() {
        val service: LinuxService get() = this@LinuxService
    }

    lateinit var env: LinuxEnvironment
        private set
    private lateinit var network: NetworkConfig
    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    val sessions = mutableListOf<TerminalSession>()

    /** Set by the visible activity; sessions report screen updates to it. */
    var sessionClient: TerminalSessionClient? = null
        set(value) {
            field = value
            sessions.forEach { it.updateTerminalSessionClient(value ?: idleClient) }
        }
    private val idleClient = IdleSessionClient()

    private var installing = false
    private var desktop: Process? = null
    private var ssh: Process? = null
    private var sshTookWakeLock = false
    private val prefs by lazy { getSharedPreferences("okno", MODE_PRIVATE) }
    private var installProgress = 0
    private var onInstallProgress: ((Int) -> Unit)? = null
    private var onInstallDone: ((Throwable?) -> Unit)? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    val isHoldingWakeLock: Boolean get() = wakeLock != null

    override fun onCreate() {
        super.onCreate()
        env = LinuxEnvironment(this)
        network = NetworkConfig(this, env)
        network.startWatching()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EXIT -> exit()
            ACTION_TOGGLE_WAKE_LOCK -> toggleWakeLock()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        desktop?.destroy()
        ssh?.destroy()
        sessions.forEach { it.finishIfRunning() }
        sessions.clear()
        network.stopWatching()
        releaseLocks()
        worker.shutdown()
        super.onDestroy()
    }

    val isInstalling: Boolean get() = installing

    /**
     * Unpacks the Linux system (or follows an install already running);
     * callbacks arrive on the main thread.
     */
    fun install(onProgress: (Int) -> Unit, onDone: (Throwable?) -> Unit) {
        // A re-created activity takes over the callbacks of a running install.
        onInstallProgress = onProgress
        onInstallDone = onDone
        onProgress(installProgress)
        if (installing) return
        installing = true
        installProgress = 0
        worker.execute {
            val error = runCatching {
                RootfsInstaller(this, env).install { p ->
                    main.post {
                        installProgress = p
                        onInstallProgress?.invoke(p)
                    }
                }
            }.exceptionOrNull()
            main.post {
                installing = false
                val done = onInstallDone
                onInstallProgress = null
                onInstallDone = null
                done?.invoke(error)
            }
        }
    }

    /**
     * Updates the installed system in place (see [RootfsInstaller.update]);
     * the desktop must be stopped. [onDone] arrives on the main thread.
     */
    fun updateSystem(onDone: (Throwable?) -> Unit) {
        worker.execute {
            val error = runCatching { RootfsInstaller(this, env).update() }.exceptionOrNull()
            main.post { onDone(error) }
        }
    }

    /** Unix socket of the desktop's VNC server, as seen from Android. */
    val desktopSocket: File get() = File(env.rootfs, "tmp/.okno/vnc.sock")

    val isDesktopRunning: Boolean get() = desktop?.isAlive == true

    /** Starts Xvnc + XFCE inside Linux (see rootfs-overlay/usr/local/bin/okno-desktop). */
    fun startDesktop(width: Int, height: Int, dpi: Int) {
        if (isDesktopRunning) return
        desktopSocket.delete()
        desktop = startLinuxProcess(listOf(DESKTOP_LAUNCHER, "$width", "$height", "$dpi"), DESKTOP_LOG)
        updateNotification()
    }

    fun stopDesktop() {
        desktop?.destroy()
        desktop = null
        updateNotification()
    }

    fun desktopLog(): String = readLog(DESKTOP_LOG)

    val isSshRunning: Boolean get() = ssh?.isAlive == true

    /** Root password for SSH: generated once, kept until the user asks for a new one. */
    val sshPassword: String
        get() = prefs.getString(PREF_SSH_PASSWORD, null) ?: newSshPassword()

    fun newSshPassword(): String {
        val alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        val password = (1..12).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        prefs.edit().putString(PREF_SSH_PASSWORD, password).apply()
        if (isSshRunning) {
            stopSsh()
            startSsh()
        }
        return password
    }

    /** SSH server on port [SSH_PORT] (see rootfs-overlay/usr/local/bin/okno-ssh). */
    fun startSsh() {
        if (isSshRunning) return
        val process = startLinuxProcess(listOf(SSH_LAUNCHER), SSH_LOG)
        // The password goes through stdin so it never shows up in a process list.
        process.outputStream.bufferedWriter().use { it.write(sshPassword + "\n") }
        ssh = process
        // With the screen off, Wi-Fi would doze and drop SSH connections.
        if (!isHoldingWakeLock) {
            toggleWakeLock()
            sshTookWakeLock = true
        }
        updateNotification()
    }

    fun stopSsh() {
        ssh?.destroy()
        ssh = null
        if (sshTookWakeLock && isHoldingWakeLock) toggleWakeLock()
        sshTookWakeLock = false
        updateNotification()
    }

    fun sshAddresses(): List<String> = network.reachableAddresses()

    fun sshLog(): String = readLog(SSH_LOG)

    private fun startLinuxProcess(command: List<String>, log: String): Process {
        network.writeResolvConf()
        val args = env.prootArgs(command)
        val builder = ProcessBuilder(listOf(env.prootPath) + args.drop(1))
            .directory(env.filesDir)
            .redirectErrorStream(true)
            .redirectOutput(File(env.filesDir, log))
        builder.environment().clear()
        env.hostEnvironment().forEach {
            val (key, value) = it.split('=', limit = 2)
            builder.environment()[key] = value
        }
        return builder.start()
    }

    private fun readLog(name: String): String =
        File(env.filesDir, name).takeIf { it.exists() }?.readText()?.takeLast(4000).orEmpty()

    /** A terminal session running [command] (a login shell by default). */
    fun createSession(command: List<String> = LinuxEnvironment.LOGIN_SHELL): TerminalSession {
        network.writeResolvConf()
        val session = TerminalSession(
            env.prootPath,
            env.filesDir.absolutePath,
            env.prootArgs(command),
            env.hostEnvironment(),
            TRANSCRIPT_ROWS,
            sessionClient ?: idleClient,
        )
        sessions += session
        updateNotification()
        return session
    }

    fun removeSession(session: TerminalSession) {
        session.finishIfRunning()
        sessions.remove(session)
        updateNotification()
    }

    fun networkSummary(): String = network.summary()

    fun exit() {
        stopDesktop()
        stopSsh()
        sessionClient = null
        sessions.forEach { it.finishIfRunning() }
        sessions.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        sendBroadcast(Intent(ACTION_EXITED).setPackage(packageName))
    }

    /** Keeps CPU and Wi-Fi awake with the screen off (long builds, servers). */
    fun toggleWakeLock() {
        if (wakeLock != null) {
            releaseLocks()
        } else {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "okno:linux")
                .apply { acquire() }
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "okno:linux")
                .apply { acquire() }
        }
        updateNotification()
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Linux", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, DesktopActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val exit = PendingIntent.getService(
            this, 1, Intent(this, LinuxService::class.java).setAction(ACTION_EXIT), PendingIntent.FLAG_IMMUTABLE
        )
        val wake = PendingIntent.getService(
            this, 2, Intent(this, LinuxService::class.java).setAction(ACTION_TOGGLE_WAKE_LOCK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val text = buildList {
            if (isDesktopRunning) add("рабочий стол")
            if (isSshRunning) add("SSH :$SSH_PORT")
            if (sessions.isNotEmpty()) add("терминалов: ${sessions.size}")
            if (isHoldingWakeLock) add("без сна")
        }.joinToString(" · ").ifEmpty { "готов" }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Okno: Linux работает")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Выход", exit).build())
            .addAction(
                Notification.Action.Builder(null, if (isHoldingWakeLock) "Разрешить сон" else "Не засыпать", wake)
                    .build()
            )
            .build()
    }

    /** Used while no activity is visible: output is still buffered by the session. */
    private class IdleSessionClient : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) = Unit
        override fun onTitleChanged(changedSession: TerminalSession) = Unit
        override fun onSessionFinished(finishedSession: TerminalSession) = Unit
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) = Unit
        override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }

    companion object {
        private const val CHANNEL_ID = "linux"
        private const val NOTIFICATION_ID = 1
        private const val TRANSCRIPT_ROWS = 5000
        private const val DESKTOP_LAUNCHER = "/usr/local/bin/okno-desktop"
        private const val DESKTOP_LOG = "desktop.log"
        private const val SSH_LAUNCHER = "/usr/local/bin/okno-ssh"
        private const val SSH_LOG = "ssh.log"
        private const val PREF_SSH_PASSWORD = "ssh_password"
        const val SSH_PORT = 8022
        const val ACTION_EXIT = "io.okno.EXIT"
        const val ACTION_TOGGLE_WAKE_LOCK = "io.okno.TOGGLE_WAKE_LOCK"
        const val ACTION_EXITED = "io.okno.EXITED"
    }
}
