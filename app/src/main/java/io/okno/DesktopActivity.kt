package io.okno

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Rect
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
import android.widget.Toast
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
    private val prefs by lazy { getSharedPreferences("okno", MODE_PRIVATE) }
    // Keeps key events in order: one thread writes them all.
    private val sendQueue = Executors.newSingleThreadExecutor()
    private var socket: LocalSocket? = null
    private var connection: Thread? = null

    private val keysSafe = Rect()

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
        desktopView.onClipboard = ::onDesktopClipboard
        // In landscape the keys at both ends of the bar sit in the rounded bottom corners.
        extraKeys.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            SafeArea.insets(v, keysSafe)
            if (v.paddingLeft != keysSafe.left || v.paddingRight != keysSafe.right) {
                v.post { v.setPadding(keysSafe.left, 0, keysSafe.right, 0) }
            }
        }
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
        clipboard.addPrimaryClipChangedListener(clipboardListener)
        val intent = Intent(this, LinuxService::class.java)
        startForegroundService(intent)
        bindService(intent, this, 0)
    }

    override fun onStop() {
        clipboard.removePrimaryClipChangedListener(clipboardListener)
        disconnect()
        if (service != null) unbindService(this)
        service = null
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
            // Android lets apps read the clipboard only while focused.
            pushPhoneClipboard()
        }
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        val svc = (binder as LinuxService.LocalBinder).service
        service = svc
        when {
            svc.env.isInstalled -> {
                startDesktop()
                offerSystemUpdate()
            }
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
                    // Ask for the phone's files before the first start: the
                    // folder is attached when the desktop starts.
                    if (hasStorage()) startDesktop()
                    else requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_STORAGE_FIRST)
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
            val (w, h) = desktopSizeFor(desktopView.contentWidth, desktopView.contentHeight)
            svc.startDesktop(w, h, DESKTOP_DPI)
            showOverlay(getString(R.string.starting_title), getString(R.string.starting_text), progress = -1)
        }
        connect()
    }

    /** Desktop resolution: the view size scaled to the chosen interface size (shorter side). */
    private fun desktopSizeFor(viewW: Int, viewH: Int): Pair<Int, Int> {
        val shortSide = prefs.getInt(PREF_SHORT_SIDE, UI_SIZES[1].first)
        val factor = (minOf(viewW, viewH) / shortSide.toFloat()).coerceAtLeast(1f)
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
                        pushPhoneClipboard()
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

    // ---- clipboard ----
    //
    // Copy on either side, paste on the other. lastClipboard stops the text
    // from bouncing back and forth.

    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }
    private var lastClipboard: String? = null
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener { pushPhoneClipboard() }

    private fun phoneClipboardText(): String? = runCatching {
        clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
    }.getOrNull()

    private fun pushPhoneClipboard() {
        val client = desktopView.client ?: return
        val text = phoneClipboardText()
        if (text.isNullOrEmpty() || text == lastClipboard) return
        lastClipboard = text
        sendQueue.execute { runCatching { client.sendClipboard(text) } }
    }

    /**
     * The 📋 key: what was copied on the phone goes to the desktop and is
     * pasted where the cursor is. Shift+Insert pastes in GTK and Qt programs,
     * browsers, Windows programs and terminals alike (Xvnc sets the PRIMARY
     * selection too, which terminals paste with it).
     */
    private fun pasteFromPhone() {
        val client = desktopView.client ?: return
        val text = phoneClipboardText()
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, "Буфер обмена телефона пуст", Toast.LENGTH_SHORT).show()
            return
        }
        lastClipboard = text
        sendQueue.execute {
            runCatching {
                client.sendClipboard(text)
                client.sendKey(Keysyms.SHIFT_L, true)
                client.sendKey(Keysyms.INSERT, true)
                client.sendKey(Keysyms.INSERT, false)
                client.sendKey(Keysyms.SHIFT_L, false)
            }
        }
    }

    private fun onDesktopClipboard(text: String) {
        if (text == lastClipboard) return
        lastClipboard = text
        clipboard.setPrimaryClip(ClipData.newPlainText("Okno", text))
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
                ExtraKeysView.BUTTON_PASTE -> pasteFromPhone()
                ExtraKeysView.BUTTON_MENU -> showMenu(anchor)
            }
        }
    }

    private fun showMenu(anchor: View) {
        val svc = service ?: return
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, MENU_PROGRAMS, 0, "Программы…")
        menu.menu.add(0, MENU_TERMINAL, 0, "Терминал")
        menu.menu.add(0, MENU_GFX_TEST, 0, "Тест графики")
        menu.menu.add(0, MENU_RESTART, 1, "Перезапустить рабочий стол")
        menu.menu.add(0, MENU_UI_SIZE, 2, "Размер интерфейса…")
        if (!hasStorage()) menu.menu.add(0, MENU_STORAGE, 2, "Доступ к файлам телефона")
        menu.menu.add(0, MENU_NETWORK, 2, "Сеть…")
        menu.menu.add(0, MENU_SSH, 2, if (svc.isSshRunning) "Удалённый доступ (SSH): вкл" else "Удалённый доступ (SSH)…")
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
                MENU_PROGRAMS -> showProgramsDialog()
                MENU_UI_SIZE -> showUiSizeDialog()
                MENU_STORAGE -> AlertDialog.Builder(this)
                    .setTitle("Файлы телефона")
                    .setMessage("Память телефона появится в проводнике как «Телефон» (/sdcard). " +
                        "Для этого рабочий стол перезапустится, открытые программы закроются.")
                    .setPositiveButton("Разрешить") { _, _ ->
                        requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_STORAGE_LATER)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                MENU_TERMINAL -> startActivity(Intent(this, TerminalActivity::class.java))
                MENU_GFX_TEST -> runInTerminal("okno-gfx-test")
                MENU_RESTART -> restartDesktop()
                MENU_NETWORK -> AlertDialog.Builder(this)
                    .setTitle("Сеть")
                    .setMessage(svc.networkSummary())
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                MENU_SSH -> showSshDialog()
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

    /**
     * A newer APK may carry a newer system image (new tools, fixes). The
     * update keeps what the user installed; a clean reinstall is offered too.
     * Asked once per version, never forced.
     */
    private fun offerSystemUpdate() {
        val svc = service ?: return
        val bundled = RootfsInstaller.bundledVersion(this)
        if (bundled == "unknown" || svc.env.installedVersion == bundled) return
        if (prefs.getString(PREF_DECLINED_UPDATE, null) == bundled) return
        AlertDialog.Builder(this)
            .setTitle("Обновление системы")
            .setMessage(
                "В этой версии Okno обновлён Linux: новые возможности и исправления.\n\n" +
                    "Установленные программы и ваши файлы сохранятся, рабочий стол перезапустится. " +
                    "Если новой версии нужны пакеты, они докачаются при запуске."
            )
            .setPositiveButton("Обновить") { _, _ -> updateSystem() }
            .setNegativeButton("Не сейчас") { _, _ ->
                prefs.edit().putString(PREF_DECLINED_UPDATE, bundled).apply()
            }
            .setNeutralButton("Переустановить начисто") { _, _ -> confirmReinstall() }
            .show()
    }

    private fun updateSystem() {
        val svc = service ?: return
        disconnect()
        svc.stopDesktop()
        showOverlay("Обновление системы", "Обновляю файлы Okno…", progress = -1)
        svc.updateSystem { error ->
            if (error == null) {
                startDesktop()
            } else {
                Log.e(TAG, "update failed", error)
                showOverlay("Не удалось обновить", error.message ?: error.toString(), button = "Переустановить начисто") {
                    confirmReinstall()
                }
            }
        }
    }

    private fun confirmReinstall() {
        AlertDialog.Builder(this)
            .setTitle("Переустановить начисто?")
            .setMessage(
                "Система будет установлена заново: программы, установленные вами, и файлы в домашней " +
                    "папке (/root) удалятся. Файлы телефона (/sdcard) не затрагиваются."
            )
            .setPositiveButton("Переустановить") { _, _ -> reinstallSystem() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun reinstallSystem() {
        val svc = service ?: return
        disconnect()
        svc.stopDesktop()
        svc.stopSsh()
        svc.sessions.toList().forEach { svc.removeSession(it) }
        install()
    }

    private fun showUiSizeDialog() {
        val current = prefs.getInt(PREF_SHORT_SIDE, UI_SIZES[1].first)
        AlertDialog.Builder(this)
            .setTitle("Размер интерфейса")
            .setSingleChoiceItems(UI_SIZES.map { it.second }.toTypedArray(), UI_SIZES.indexOfFirst { it.first == current }) { dialog, which ->
                prefs.edit().putInt(PREF_SHORT_SIDE, UI_SIZES[which].first).apply()
                // Applied live: the desktop changes resolution, nothing restarts.
                resizeDesktop(desktopView.contentWidth, desktopView.contentHeight)
                dialog.dismiss()
            }
            .show()
    }

    private fun hasStorage() =
        checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when (requestCode) {
            REQUEST_STORAGE_FIRST -> startDesktop()
            REQUEST_STORAGE_LATER -> if (hasStorage()) restartDesktop()
        }
    }

    /** One tap installs: the picked programs go to okno-install in a visible terminal. */
    private fun showProgramsDialog() {
        val picked = BooleanArray(PROGRAMS.size)
        AlertDialog.Builder(this)
            .setTitle("Установить программы")
            .setMultiChoiceItems(PROGRAMS.map { it.second }.toTypedArray(), picked) { _, which, checked ->
                picked[which] = checked
            }
            .setPositiveButton("Установить") { _, _ ->
                val names = PROGRAMS.filterIndexed { i, _ -> picked[i] }.map { it.first }
                if (names.isNotEmpty()) runInTerminal("okno-install ${names.joinToString(" ")}")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Runs [command] in a visible terminal that stays open until Enter, so its output can be read. */
    private fun runInTerminal(command: String) {
        val script = "$command; echo; read -r -p 'Нажмите Enter, чтобы вернуться на рабочий стол' _"
        startActivity(
            Intent(this, TerminalActivity::class.java)
                .putExtra(TerminalActivity.EXTRA_COMMAND, arrayOf("/bin/bash", "-lc", script))
        )
    }

    private fun showSshDialog() {
        val svc = service ?: return
        val builder = AlertDialog.Builder(this).setTitle("Удалённый доступ (SSH)")
        if (!svc.isSshRunning) {
            builder.setMessage(
                "Управляйте Linux на телефоне с компьютера: терминал по SSH и файлы по SFTP " +
                    "(WinSCP, FileZilla, scp).\n\nКомпьютер должен быть в той же Wi‑Fi сети."
            )
                .setPositiveButton("Включить") { _, _ ->
                    svc.startSsh()
                    showSshDialog()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val addresses = svc.sshAddresses()
        val port = LinuxService.SSH_PORT
        val command = addresses.firstOrNull()?.let { "ssh root@$it -p $port" } ?: "ssh root@<адрес телефона> -p $port"
        val message = buildString {
            append("Команда для компьютера:\n  $command\n\n")
            append("Пароль: ${svc.sshPassword}\n\n")
            if (addresses.size > 1) append("Другие адреса телефона: ${addresses.drop(1).joinToString()}\n\n")
            if (addresses.isEmpty()) append("Телефон сейчас не в сети.\n\n")
            append("Файлы: SFTP на том же адресе и порту $port.\n")
            append("Пока SSH включён, телефон не отключает Wi‑Fi при погашенном экране.")
        }
        builder.setMessage(message)
            .setPositiveButton("Скопировать команду") { _, _ ->
                clipboard.setPrimaryClip(ClipData.newPlainText("SSH", command))
            }
            .setNegativeButton("Выключить") { _, _ -> svc.stopSsh() }
            .setNeutralButton("Новый пароль") { _, _ ->
                svc.newSshPassword()
                showSshDialog()
            }
            .show()
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
        private const val CONNECT_TIMEOUT_MS = 60_000L
        private const val RETRY_DELAY_MS = 300L
        private const val MENU_TERMINAL = 1
        private const val MENU_RESTART = 2
        private const val MENU_NETWORK = 3
        private const val MENU_WAKE = 4
        private const val MENU_BATTERY = 5
        private const val MENU_EXIT = 6
        private const val MENU_SSH = 7
        private const val MENU_PROGRAMS = 8
        private const val MENU_UI_SIZE = 9
        private const val MENU_STORAGE = 10
        private const val MENU_GFX_TEST = 11
        private const val REQUEST_STORAGE_FIRST = 1
        private const val REQUEST_STORAGE_LATER = 2
        private const val PREF_SHORT_SIDE = "desktop_short_side"
        private const val PREF_DECLINED_UPDATE = "declined_system_update"

        /** Desktop height (landscape) for each interface size: fewer pixels, bigger UI. */
        private val UI_SIZES = listOf(600 to "Крупный", 720 to "Обычный", 900 to "Мелкий")

        /** okno-install names (rootfs-overlay/usr/local/bin/okno-install) and labels. */
        private val PROGRAMS = listOf(
            "windows" to "Windows-программы (.exe, 32 и 64 бит)",
            "claude" to "Claude Code — ИИ-помощник, сам работает в браузере",
            "vscode" to "VS Code — редактор кода",
            "firefox" to "Firefox — браузер",
            "chromium" to "Chromium — браузер",
            "python" to "Python 3 + pip",
            "node" to "Node.js + npm",
            "git" to "Git",
            "cpp" to "C/C++: gcc, make, cmake, gdb",
            "java" to "Java (JDK)",
            "geany" to "Geany — лёгкая среда разработки",
        )
    }
}
