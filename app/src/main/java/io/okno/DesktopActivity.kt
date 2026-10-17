package io.okno

import android.Manifest
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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import com.termux.x11.LoriePreferences
import com.termux.x11.MainActivity
import kotlin.math.roundToInt

/**
 * The app's main screen. First launch: one "Install" button. After that:
 * the Linux desktop, started automatically and shown by Termux:X11's view
 * (this activity is its MainActivity, with Okno's setup screens and menu
 * on top). Back and the ☰ key open Okno's menu.
 */
class DesktopActivity : MainActivity(), ServiceConnection {
    private var linux: LinuxService? = null
    private lateinit var overlay: View
    private lateinit var overlayTitle: TextView
    private lateinit var overlayText: TextView
    private lateinit var overlayProgress: ProgressBar
    private lateinit var overlayButton: Button

    private val settings by lazy { getSharedPreferences("okno", MODE_PRIVATE) }
    private var wasConnected = false

    private val exitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = finishAndRemoveTask()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyDisplaySettings()
        super.onCreate(savedInstanceState)
        layoutInflater.inflate(R.layout.okno_overlay, findViewById<ViewGroup>(android.R.id.content))
        overlay = findViewById(R.id.overlay)
        overlayTitle = findViewById(R.id.overlay_title)
        overlayText = findViewById(R.id.overlay_text)
        overlayProgress = findViewById(R.id.overlay_progress)
        overlayButton = findViewById(R.id.overlay_button)
        // Termux:X11 shows or hides its view on (dis)connection, which re-lays out the window.
        window.decorView.viewTreeObserver.addOnGlobalLayoutListener { onConnectionMaybeChanged() }

        val filter = IntentFilter(LinuxService.ACTION_EXITED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(exitReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(exitReceiver, filter)
    }

    override fun onDestroy() {
        unregisterReceiver(exitReceiver)
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, LinuxService::class.java)
        startForegroundService(intent)
        bindService(intent, this, 0)
    }

    override fun onStop() {
        if (linux != null) unbindService(this)
        linux = null
        super.onStop()
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        val svc = (binder as LinuxService.LocalBinder).service
        linux = svc
        when {
            // Older systems bring their own X server: this app's desktop needs the new files.
            svc.env.isInstalled && !svc.env.hasX11Desktop -> updateSystem()
            svc.env.isInstalled -> {
                startDesktop()
                offerSystemUpdate()
            }
            svc.isInstalling -> install()
            else -> showWelcome()
        }
    }

    override fun onServiceDisconnected(name: ComponentName) {
        linux = null
    }

    /** Termux:X11 opens its settings from Back, the ☰ key and its notification: Okno's menu instead. */
    override fun startActivity(intent: Intent) {
        if (intent.component?.className == LoriePreferences::class.java.name && !intent.getBooleanExtra(EXTRA_X11_SETTINGS, false)) {
            showMenu()
        } else {
            super.startActivity(intent)
        }
    }

    /**
     * Termux:X11's settings as Okno uses them: the desktop scaled to the
     * chosen interface size, touches acting like a touchscreen, Okno's keys.
     * Later changes in "Экран и ввод" are kept, except the scale.
     */
    private fun applyDisplaySettings() {
        val x11 = getSharedPreferences("${packageName}_preferences", MODE_PRIVATE) // Termux:X11's settings
        val shortSidePx = resources.displayMetrics.run { minOf(widthPixels, heightPixels) }
        val shortSide = settings.getInt(PREF_SHORT_SIDE, UI_SIZES[1].first)
        val edit = x11.edit()
            .putString("displayResolutionMode", "scaled")
            .putInt("displayScale", (shortSidePx * 100f / shortSide).roundToInt().coerceIn(100, 300))
        if (!settings.getBoolean(PREF_X11_DEFAULTS, false)) {
            edit.putString("touchMode", "2")
                .putBoolean("fullscreen", true)
                .putString("backButtonAction", "open preferences")
                .putString("extra_keys_config", EXTRA_KEYS)
            settings.edit().putBoolean(PREF_X11_DEFAULTS, true).apply()
        }
        edit.apply()
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
        val svc = linux ?: return
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
        val svc = linux ?: return
        if (!svc.isDesktopRunning) svc.startDesktop(DESKTOP_DPI)
        else if (getLorieView().connected()) return
        showOverlay(getString(R.string.starting_title), getString(R.string.starting_text), progress = -1)
        overlay.removeCallbacks(startWatch)
        overlay.postDelayed(startWatch, 1000)
    }

    /** Until Termux:X11 connects: a desktop that died on the way is reported, not waited for. */
    private val startWatch = object : Runnable {
        override fun run() {
            if (getLorieView().connected()) return
            if (linux?.isDesktopRunning == false) onDesktopLost() else overlay.postDelayed(this, 1000)
        }
    }

    private fun onConnectionMaybeChanged() {
        val connected = getLorieView()?.connected() ?: return
        if (connected == wasConnected) return
        wasConnected = connected
        if (connected) hideOverlay()
        else if (linux?.isDesktopRunning == false) onDesktopLost()
    }

    private fun onDesktopLost() {
        val log = linux?.desktopLog().orEmpty().lines().takeLast(12).joinToString("\n")
        showOverlay(
            title = getString(R.string.desktop_stopped_title),
            text = getString(R.string.desktop_stopped_text) + if (log.isNotBlank()) "\n\n$log" else "",
            button = getString(R.string.desktop_restart),
        ) { restartDesktop() }
    }

    private fun restartDesktop() {
        linux?.stopDesktop()
        startDesktop()
    }

    // ---- menu ----

    private fun showMenu() {
        val svc = linux ?: return
        val items = buildList {
            add("Программы…" to ::showProgramsDialog)
            add("Терминал" to { startActivity(Intent(this@DesktopActivity, TerminalActivity::class.java)) })
            add("Тест графики" to { runInTerminal("okno-gfx-test") })
            add("Перезапустить рабочий стол" to ::restartDesktop)
            add("Размер интерфейса…" to ::showUiSizeDialog)
            add("Экран и ввод…" to {
                startActivity(Intent(this@DesktopActivity, LoriePreferences::class.java)
                    .setAction(Intent.ACTION_MAIN).putExtra(EXTRA_X11_SETTINGS, true))
            })
            if (!hasStorage()) add("Доступ к файлам телефона" to ::askStorage)
            add("Сеть…" to {
                AlertDialog.Builder(this@DesktopActivity).setTitle("Сеть").setMessage(svc.networkSummary())
                    .setPositiveButton(android.R.string.ok, null).show()
            })
            add((if (svc.isSshRunning) "Удалённый доступ (SSH): вкл" else "Удалённый доступ (SSH)…") to ::showSshDialog)
            add((if (svc.isHoldingWakeLock) "✓ Не засыпать" else "Не засыпать") to svc::toggleWakeLock)
            if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
                add("Не ограничивать в фоне" to {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                })
            }
            add("Выход" to svc::exit)
        }
        AlertDialog.Builder(this)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    private fun askStorage() {
        AlertDialog.Builder(this)
            .setTitle("Файлы телефона")
            .setMessage("Память телефона появится в проводнике как «Телефон» (/sdcard). " +
                "Для этого рабочий стол перезапустится, открытые программы закроются.")
            .setPositiveButton("Разрешить") { _, _ ->
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_STORAGE_LATER)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * A newer APK may carry a newer system image (new tools, fixes). The
     * update keeps what the user installed; a clean reinstall is offered too.
     * Asked once per version, never forced.
     */
    private fun offerSystemUpdate() {
        val svc = linux ?: return
        val bundled = RootfsInstaller.bundledVersion(this)
        if (bundled == "unknown" || svc.env.installedVersion == bundled) return
        if (settings.getString(PREF_DECLINED_UPDATE, null) == bundled) return
        AlertDialog.Builder(this)
            .setTitle("Обновление системы")
            .setMessage(
                "В этой версии Okno обновлён Linux: новые возможности и исправления.\n\n" +
                    "Установленные программы и ваши файлы сохранятся, рабочий стол перезапустится. " +
                    "Если новой версии нужны пакеты, они докачаются при запуске."
            )
            .setPositiveButton("Обновить") { _, _ -> updateSystem() }
            .setNegativeButton("Не сейчас") { _, _ ->
                settings.edit().putString(PREF_DECLINED_UPDATE, bundled).apply()
            }
            .setNeutralButton("Переустановить начисто") { _, _ -> confirmReinstall() }
            .show()
    }

    private fun updateSystem() {
        val svc = linux ?: return
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
        val svc = linux ?: return
        svc.stopDesktop()
        svc.stopSsh()
        svc.sessions.toList().forEach { svc.removeSession(it) }
        install()
    }

    private fun showUiSizeDialog() {
        val current = settings.getInt(PREF_SHORT_SIDE, UI_SIZES[1].first)
        AlertDialog.Builder(this)
            .setTitle("Размер интерфейса")
            .setSingleChoiceItems(UI_SIZES.map { it.second }.toTypedArray(), UI_SIZES.indexOfFirst { it.first == current }) { dialog, which ->
                settings.edit().putInt(PREF_SHORT_SIDE, UI_SIZES[which].first).apply()
                // Applied live: Termux:X11 follows its settings, the desktop changes resolution.
                applyDisplaySettings()
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
            else -> super.onRequestPermissionsResult(requestCode, permissions, grantResults)
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
        val svc = linux ?: return
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
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("SSH", command))
            }
            .setNegativeButton("Выключить") { _, _ -> svc.stopSsh() }
            .setNeutralButton("Новый пароль") { _, _ ->
                svc.newSshPassword()
                showSshDialog()
            }
            .show()
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
    }

    companion object {
        private const val TAG = "Okno"
        private const val DESKTOP_DPI = 132
        private const val REQUEST_STORAGE_FIRST = 1
        private const val REQUEST_STORAGE_LATER = 2
        private const val PREF_SHORT_SIDE = "desktop_short_side"
        private const val PREF_DECLINED_UPDATE = "declined_system_update"
        private const val PREF_X11_DEFAULTS = "x11_defaults"
        private const val EXTRA_X11_SETTINGS = "io.okno.X11_SETTINGS"

        /** Two rows that fit a phone in portrait: ☰ opens Okno's menu, ⎘ pastes the phone's clipboard. */
        private const val EXTRA_KEYS = "[['DRAWER','KEYBOARD','PASTE','ESC','TAB','HOME','UP','END','PGUP'], " +
            "['CTRL','ALT','META','ENTER','DEL','LEFT','DOWN','RIGHT','PGDN']]"

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
