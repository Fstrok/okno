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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import android.widget.Toast
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

class TerminalActivity : Activity(), ServiceConnection {
    private var service: LinuxService? = null
    private lateinit var terminalView: TerminalView
    private lateinit var extraKeys: ExtraKeysView

    private val prefs by lazy { getSharedPreferences("okno", MODE_PRIVATE) }
    private var fontSize = 0
    private val minFontSize by lazy { sp(6f) }
    private val maxFontSize by lazy { sp(36f) }

    private val currentSession: TerminalSession? get() = terminalView.currentSession
    private val commandSessions = mutableSetOf<TerminalSession>()

    private val exitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = finishAndRemoveTask()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)
        terminalView = findViewById(R.id.terminal)
        extraKeys = findViewById(R.id.extra_keys)

        fontSize = prefs.getInt(PREF_FONT_SIZE, sp(12f)).coerceIn(minFontSize, maxFontSize)
        terminalView.setTextSize(fontSize)
        terminalView.setTerminalViewClient(viewClient)
        extraKeys.listener = keysListener

        val filter = IntentFilter(LinuxService.ACTION_EXITED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(exitReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(exitReceiver, filter)
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, LinuxService::class.java)
        startForegroundService(intent)
        bindService(intent, this, 0)
    }

    override fun onStop() {
        service?.sessionClient = null
        if (service != null) unbindService(this)
        service = null
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(exitReceiver)
        super.onDestroy()
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        val svc = (binder as LinuxService.LocalBinder).service
        service = svc
        svc.sessionClient = sessionClient
        attachSessions()
    }

    override fun onServiceDisconnected(name: ComponentName) {
        service = null
    }

    private fun attachSessions() {
        val svc = service ?: return
        if (!svc.env.isInstalled) {
            // The system is installed from the main screen.
            finish()
            return
        }
        // A command handed over by the desktop (e.g. installing programs) gets
        // its own session, once: the intent is consumed so rotation won't rerun it.
        intent.getStringArrayExtra(EXTRA_COMMAND)?.let { command ->
            intent.removeExtra(EXTRA_COMMAND)
            val session = svc.createSession(command.toList())
            commandSessions += session
            showSession(session)
            return
        }
        showSession(currentSession?.takeIf { it in svc.sessions } ?: svc.sessions.lastOrNull() ?: svc.createSession())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (service != null) attachSessions()
    }

    private fun showSession(session: TerminalSession) {
        terminalView.attachSession(session)
        terminalView.onScreenUpdated()
        terminalView.requestFocus()
    }

    private fun closeSession(session: TerminalSession) {
        val svc = service ?: return
        val index = svc.sessions.indexOf(session)
        svc.removeSession(session)
        if (svc.sessions.isEmpty()) {
            finish()
            return
        }
        showSession(svc.sessions[(index - 1).coerceAtLeast(0)])
    }

    @Deprecated("Activity is the platform class; targetSdk 28 still routes Back here")
    override fun onBackPressed() {
        if (terminalView.isSelectingText) terminalView.stopTextSelectionMode()
        else finish() // back to the desktop; sessions keep running in the service
    }

    private fun showMenu(anchor: View) {
        val svc = service ?: return
        val menu = PopupMenu(this, anchor)
        svc.sessions.forEachIndexed { i, s ->
            val title = (s.title?.takeIf { it.isNotBlank() } ?: "bash") + if (s.isRunning) "" else " (завершена)"
            menu.menu.add(GROUP_SESSIONS, i, i, "${i + 1}: $title").apply {
                isCheckable = true
                isChecked = s == currentSession
            }
        }
        menu.menu.add(0, MENU_NEW, 100, "Новая сессия")
        menu.menu.add(0, MENU_CLOSE, 101, "Закрыть сессию")
        menu.menu.add(0, MENU_NETWORK, 102, "Сеть…")
        if (!hasStorage()) menu.menu.add(0, MENU_STORAGE, 103, "Доступ к памяти телефона")
        menu.menu.add(0, MENU_WAKE, 104, "Не засыпать").apply {
            isCheckable = true
            isChecked = svc.isHoldingWakeLock
        }
        if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
            menu.menu.add(0, MENU_BATTERY, 105, "Не ограничивать в фоне")
        }
        menu.menu.add(0, MENU_DESKTOP, 106, "Рабочий стол")
        menu.menu.add(0, MENU_EXIT, 107, "Выход")
        menu.setOnMenuItemClickListener { item ->
            when {
                item.groupId == GROUP_SESSIONS -> svc.sessions.getOrNull(item.itemId)?.let(::showSession)
                item.itemId == MENU_NEW -> showSession(svc.createSession())
                item.itemId == MENU_CLOSE -> currentSession?.let(::closeSession)
                item.itemId == MENU_NETWORK -> AlertDialog.Builder(this)
                    .setTitle("Сеть")
                    .setMessage(svc.networkSummary())
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                item.itemId == MENU_STORAGE -> requestPermissions(
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_STORAGE
                )
                item.itemId == MENU_WAKE -> svc.toggleWakeLock()
                item.itemId == MENU_BATTERY -> startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
                item.itemId == MENU_DESKTOP -> finish()
                item.itemId == MENU_EXIT -> svc.exit()
            }
            true
        }
        menu.show()
    }

    private fun hasStorage() =
        checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQUEST_STORAGE && hasStorage()) {
            Toast.makeText(this, "Память телефона будет в /sdcard в новых сессиях", Toast.LENGTH_LONG).show()
        }
    }

    private fun changeFontSize(increase: Boolean) {
        fontSize = (fontSize + if (increase) 2 else -2).coerceIn(minFontSize, maxFontSize)
        prefs.edit().putInt(PREF_FONT_SIZE, fontSize).apply()
        terminalView.setTextSize(fontSize)
    }

    private fun sp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics).toInt()

    private val keysListener = object : ExtraKeysView.Listener {
        override fun onKey(keyCode: Int) {
            terminalView.onKeyDown(keyCode, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        }

        override fun onText(codePoint: Int) {
            terminalView.inputCodePoint(codePoint, false, false)
        }

        override fun onButton(id: String, anchor: View) {
            when (id) {
                ExtraKeysView.BUTTON_MENU -> showMenu(anchor)
                ExtraKeysView.BUTTON_PASTE -> sessionClient.onPasteTextFromClipboard(currentSession)
            }
        }
    }

    private val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            if (scale < 0.9f || scale > 1.1f) {
                changeFontSize(scale > 1f)
                return 1f
            }
            return scale
        }

        override fun onSingleTapUp(e: MotionEvent) {
            terminalView.requestFocus()
            getSystemService(InputMethodManager::class.java).showSoftInput(terminalView, 0)
        }

        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = true
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        override fun copyModeChanged(copyMode: Boolean) = Unit

        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
            if (!session.isRunning && keyCode == KeyEvent.KEYCODE_ENTER) {
                closeSession(session)
                return true
            }
            return false
        }

        override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
        override fun onLongPress(event: MotionEvent) = false
        override fun readControlKey() = extraKeys.consume(ExtraKeysView.Modifier.CTRL)
        override fun readAltKey() = extraKeys.consume(ExtraKeysView.Modifier.ALT)
        override fun readShiftKey() = false
        override fun readFnKey() = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession) = false
        override fun onEmulatorSet() = Unit

        override fun logError(tag: String?, message: String?) { Log.e(tag, message.orEmpty()) }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag, message.orEmpty()) }
        override fun logInfo(tag: String?, message: String?) { Log.i(tag, message.orEmpty()) }
        override fun logDebug(tag: String?, message: String?) { Log.d(tag, message.orEmpty()) }
        override fun logVerbose(tag: String?, message: String?) { Log.v(tag, message.orEmpty()) }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag, message, e) }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag, "", e) }
    }

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            if (changedSession == currentSession) terminalView.onScreenUpdated()
        }

        override fun onTitleChanged(changedSession: TerminalSession) = Unit
        override fun onSessionFinished(finishedSession: TerminalSession) {
            // A command run for the desktop (installing programs) that went
            // fine closes by itself; failures stay on screen to be read.
            if (commandSessions.remove(finishedSession) && finishedSession.exitStatus == 0) {
                closeSession(finishedSession)
            }
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Okno", text))
            Toast.makeText(this@TerminalActivity, "Скопировано", Toast.LENGTH_SHORT).show()
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val clip = getSystemService(ClipboardManager::class.java).primaryClip ?: return
            val text = clip.getItemAt(0)?.coerceToText(this@TerminalActivity)?.toString() ?: return
            session?.emulator?.paste(text)
        }

        override fun onBell(session: TerminalSession) = Unit

        override fun onColorsChanged(session: TerminalSession) {
            if (session == currentSession) terminalView.invalidate()
        }

        override fun onTerminalCursorStateChange(state: Boolean) {
            terminalView.setTerminalCursorBlinkerState(state, false)
        }

        override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

        override fun logError(tag: String?, message: String?) { Log.e(tag, message.orEmpty()) }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag, message.orEmpty()) }
        override fun logInfo(tag: String?, message: String?) { Log.i(tag, message.orEmpty()) }
        override fun logDebug(tag: String?, message: String?) { Log.d(tag, message.orEmpty()) }
        override fun logVerbose(tag: String?, message: String?) { Log.v(tag, message.orEmpty()) }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag, message, e) }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag, "", e) }
    }

    companion object {
        /** String array: a command to run in a new session instead of a shell. */
        const val EXTRA_COMMAND = "io.okno.COMMAND"
        private const val TAG = "Okno"
        private const val PREF_FONT_SIZE = "font_size"
        private const val REQUEST_STORAGE = 1
        private const val GROUP_SESSIONS = 1
        private const val MENU_NEW = 1000
        private const val MENU_CLOSE = 1001
        private const val MENU_NETWORK = 1002
        private const val MENU_STORAGE = 1003
        private const val MENU_WAKE = 1004
        private const val MENU_BATTERY = 1005
        private const val MENU_EXIT = 1006
        private const val MENU_DESKTOP = 1007
    }
}
