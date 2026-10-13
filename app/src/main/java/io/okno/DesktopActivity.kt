package io.okno

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import io.okno.desktop.DesktopView
import io.okno.desktop.Keysyms
import io.okno.desktop.RfbClient
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The app's main screen. First launch: one "Install" button. After that:
 * the Linux desktop, started automatically.
 */
class DesktopActivity : Activity(), ServiceConnection {
    private var service: LinuxService? = null
    private lateinit var desktopView: DesktopView
    private lateinit var extraKeys: ExtraKeysView
    private lateinit var overlay: View
    private lateinit var overlayTitle: TextView
    private lateinit var overlayText: TextView
    private lateinit var overlayProgress: ProgressBar
    private lateinit var overlayButton: Button

    private val main = Handler(Looper.getMainLooper())
    // Keeps key events in order: one thread writes them all.
    private val sendQueue = Executors.newSingleThreadExecutor()
    private var socket: LocalSocket? = null
    private var connection: Thread? = null

    private val exitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            disconnect()
            finishAndRemoveTask()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_desktop)
        desktopView = findViewById(R.id.desktop)
        extraKeys = findViewById(R.id.extra_keys)
        overlay = findViewById(R.id.overlay)
        overlayTitle = findViewById(R.id.overlay_title)
        overlayText = findViewById(R.id.overlay_text)
        overlayProgress = findViewById(R.id.overlay_progress)
        overlayButton = findViewById(R.id.overlay_button)

        extraKeys.setKeys(ExtraKeysView.DESKTOP_KEYS)
        extraKeys.listener = keysListener
        desktopView.keySender = DesktopView.KeySender(::sendKey)
        desktopView.onSizeChanged = { w, h -> resizeDesktop(w, h) }
        hideSystemBars()

        val filter = IntentFilter(LinuxService.ACTION_EXITED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(exitReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(exitReceiver, filter)
    }

    override fun onDestroy() {
        unregisterReceiver(exitReceiver)
        sendQueue.shutdown()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, LinuxService::class.java)
        startForegroundService(intent)
        bindService(intent, this, 0)
    }

    override fun onStop() {
        disconnect()
        if (service != null) unbindService(this)
        service = null
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        val svc = (binder as LinuxService.LocalBinder).service
        service = svc
        when {
            svc.env.isInstalled -> startDesktop()
            svc.isInstalling -> install()
            else -> showWelcome()
        }
    }

    override fun onServiceDisconnected(name: ComponentName) {
        service = null
    }

    // ---- install ----

    private fun showWelcome() {
        showOverlay(
            title = getString(R.string.welcome_title),
            text = getString(R.string.welcome_text),
            button = getString(R.string.welcome_install),
        ) { install() }
    }

    private fun install() {
        val svc = service ?: return
        showOverlay(getString(R.string.installing_title), getString(R.string.setup_installing, 0), progress = 0)
        svc.install(
            onProgress = { percent ->
                overlayProgress.progress = percent
                overlayText.text = getString(R.string.setup_installing, percent)
            },
            onDone = { error ->
                if (error == null) {
                    startDesktop()
                } else {
                    Log.e(TAG, "install failed", error)
                    showOverlay(
                        title = getString(R.string.installing_title),
                        text = getString(R.string.setup_failed, error.message ?: error.toString()),
                        button = getString(R.string.setup_retry),
                    ) { install() }
                }
            },
        )
    }

    // ---- desktop ----

    private fun startDesktop() {
        val svc = service ?: return
        if (desktopView.width == 0) {
            // Desktop size depends on the view size; wait for the first layout.
            desktopView.post { startDesktop() }
            return
        }
        if (!svc.isDesktopRunning) {
            val (w, h) = desktopSizeFor(desktopView.width, desktopView.height)
            svc.startDesktop(w, h, DESKTOP_DPI)
            showOverlay(getString(R.string.starting_title), getString(R.string.starting_text), progress = -1)
        }
        connect()
    }

    /** Desktop resolution: the view size scaled so the shorter side is about 720 px. */
    private fun desktopSizeFor(viewW: Int, viewH: Int): Pair<Int, Int> {
        val factor = (minOf(viewW, viewH) / TARGET_SHORT_SIDE).coerceAtLeast(1f)
        // Even sizes keep Xvnc and the scaling filter happy.
        val w = ((viewW / factor).roundToInt() / 2) * 2
        val h = ((viewH / factor).roundToInt() / 2) * 2
        return w to h
    }

    private fun resizeDesktop(viewW: Int, viewH: Int) {
        val client = desktopView.client ?: return
        if (!client.supportsResize) return
        val (w, h) = desktopSizeFor(viewW, viewH)
        if (w != desktopView.framebuffer.width || h != desktopView.framebuffer.height) {
            sendQueue.execute { runCatching { client.requestDesktopSize(w, h) } }
        }
    }

    private fun connect() {
        if (connection?.isAlive == true) return
        val svc = service ?: return
        val socketFile = svc.desktopSocket
        connection = Thread {
            val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
            var lastError: Throwable? = null
            while (System.currentTimeMillis() < deadline && !Thread.currentThread().isInterrupted) {
                val s = LocalSocket()
                try {
                    s.connect(LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
                    socket = s
                    val client = RfbClient(s.inputStream, s.outputStream, desktopView.framebuffer)
                    client.listener = desktopView
                    main.post {
                        desktopView.client = client
                        hideOverlay()
                    }
                    client.run() // until the connection breaks
                } catch (e: Exception) {
                    // Any failure, including a malformed message, just ends this connection.
                    lastError = e
                    runCatching { s.close() }
                    if (socket === s) break // was connected: the desktop went away
                    try {
                        Thread.sleep(RETRY_DELAY_MS)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            }
            if (Thread.currentThread().isInterrupted) return@Thread
            Log.w(TAG, "desktop connection ended", lastError)
            main.post { onDesktopLost() }
        }.apply { start() }
    }

    private fun disconnect() {
        connection?.interrupt()
        connection = null
        runCatching { socket?.close() }
        socket = null
        desktopView.client = null
    }

    private fun onDesktopLost() {
        desktopView.client = null
        socket = null
        val log = service?.desktopLog().orEmpty().lines().takeLast(12).joinToString("\n")
        showOverlay(
            title = getString(R.string.desktop_stopped_title),
            text = getString(R.string.desktop_stopped_text) + if (log.isNotBlank()) "\n\n$log" else "",
            button = getString(R.string.desktop_restart),
        ) { restartDesktop() }
    }

    private fun restartDesktop() {
        disconnect()
        service?.stopDesktop()
        startDesktop()
    }

    // ---- keys ----

    /** One key press; on-screen Ctrl/Alt toggles wrap it like held modifiers. */
    private fun sendKey(keysym: Int) {
        val client = desktopView.client ?: return
        val modifiers = extraKeys.consumeAll().map {
            when (it) {
                ExtraKeysView.Modifier.CTRL -> Keysyms.CONTROL_L
                ExtraKeysView.Modifier.ALT -> Keysyms.ALT_L
                ExtraKeysView.Modifier.WIN -> Keysyms.SUPER_L
            }
        }
        sendQueue.execute {
            runCatching {
                modifiers.forEach { client.sendKey(it, true) }
                client.sendKey(keysym, true)
                client.sendKey(keysym, false)
                modifiers.asReversed().forEach { client.sendKey(it, false) }
            }
        }
    }

    private val keysListener = object : ExtraKeysView.Listener {
        override fun onKey(keyCode: Int) {
            Keysyms.fromKeyCode(keyCode)?.let(::sendKey)
        }

        override fun onText(codePoint: Int) = sendKey(Keysyms.fromCodePoint(codePoint))

        override fun onButton(id: String, anchor: View) {
            when (id) {
                ExtraKeysView.BUTTON_KEYBOARD -> desktopView.toggleKeyboard()
                ExtraKeysView.BUTTON_MENU -> showMenu(anchor)
            }
        }
    }

    private fun showMenu(anchor: View) {
        val svc = service ?: return
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, MENU_TERMINAL, 0, "Терминал")
        menu.menu.add(0, MENU_RESTART, 1, "Перезапустить рабочий стол")
        menu.menu.add(0, MENU_NETWORK, 2, "Сеть…")
        menu.menu.add(0, MENU_WAKE, 3, "Не засыпать").apply {
            isCheckable = true
            isChecked = svc.isHoldingWakeLock
        }
        if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
            menu.menu.add(0, MENU_BATTERY, 4, "Не ограничивать в фоне")
        }
        menu.menu.add(0, MENU_EXIT, 5, "Выход")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_TERMINAL -> startActivity(Intent(this, TerminalActivity::class.java))
                MENU_RESTART -> restartDesktop()
                MENU_NETWORK -> AlertDialog.Builder(this)
                    .setTitle("Сеть")
                    .setMessage(svc.networkSummary())
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                MENU_WAKE -> svc.toggleWakeLock()
                MENU_BATTERY -> startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
                MENU_EXIT -> svc.exit()
            }
            true
        }
        menu.show()
    }

    @Deprecated("Activity is the platform class; targetSdk 28 still routes Back here")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Hardware keyboards type into the desktop even when another view has focus.
        if (overlay.visibility != View.VISIBLE && !desktopView.hasFocus() && !event.isSystem) {
            desktopView.requestFocus()
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- overlay ----

    private fun showOverlay(title: String, text: String, button: String? = null, progress: Int? = null, onClick: (() -> Unit)? = null) {
        overlay.visibility = View.VISIBLE
        overlayTitle.text = title
        overlayText.text = text
        overlayProgress.visibility = if (progress == null) View.GONE else View.VISIBLE
        overlayProgress.isIndeterminate = progress == -1
        if (progress != null && progress >= 0) overlayProgress.progress = progress
        overlayButton.visibility = if (button == null) View.GONE else View.VISIBLE
        overlayButton.text = button
        overlayButton.setOnClickListener { onClick?.invoke() }
    }

    private fun hideOverlay() {
        overlay.visibility = View.GONE
        desktopView.requestFocus()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    companion object {
        private const val TAG = "Okno"
        private const val DESKTOP_DPI = 132
        private const val TARGET_SHORT_SIDE = 720f
        private const val CONNECT_TIMEOUT_MS = 60_000L
        private const val RETRY_DELAY_MS = 300L
        private const val MENU_TERMINAL = 1
        private const val MENU_RESTART = 2
        private const val MENU_NETWORK = 3
        private const val MENU_WAKE = 4
        private const val MENU_BATTERY = 5
        private const val MENU_EXIT = 6
    }
}
