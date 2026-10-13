package io.okno

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Row of keys that phone keyboards lack: Esc, Tab, Ctrl, Alt, arrows, etc.
 * Ctrl and Alt are one-shot toggles: tap Ctrl, then a letter, to send Ctrl+letter.
 */
class ExtraKeysView(context: Context, attrs: AttributeSet?) : HorizontalScrollView(context, attrs) {

    /** What a key does when pressed. */
    sealed interface Action {
        data class Key(val keyCode: Int) : Action
        data class Text(val codePoint: Int) : Action
        data object Ctrl : Action
        data object Alt : Action
        data object Menu : Action
    }

    interface Listener {
        fun onKey(keyCode: Int)
        fun onText(codePoint: Int)
        fun onMenu(anchor: View)
    }

    var listener: Listener? = null

    private var ctrl = false
    private var alt = false
    private lateinit var ctrlView: TextView
    private lateinit var altView: TextView
    private val repeatHandler = Handler(Looper.getMainLooper())

    init {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(Color.parseColor("#1B1B1F"))
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, action) in KEYS) row.addView(makeKey(label, action))
        addView(row)
    }

    /** Read-and-reset, as TerminalView expects from its client. */
    fun consumeCtrl(): Boolean = ctrl.also { if (it) setCtrl(false) }

    fun consumeAlt(): Boolean = alt.also { if (it) setAlt(false) }

    private fun setCtrl(on: Boolean) {
        ctrl = on
        highlight(ctrlView, on)
    }

    private fun setAlt(on: Boolean) {
        alt = on
        highlight(altView, on)
    }

    private fun highlight(view: TextView, on: Boolean) {
        view.setTextColor(if (on) Color.parseColor("#7AA2F7") else Color.WHITE)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeKey(label: String, action: Action): TextView {
        val view = TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            minWidth = dp(46)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            val ripple = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            setBackgroundResource(ripple.resourceId)
        }
        when (action) {
            Action.Ctrl -> {
                ctrlView = view
                view.setOnClickListener { setCtrl(!ctrl) }
            }
            Action.Alt -> {
                altView = view
                view.setOnClickListener { setAlt(!alt) }
            }
            Action.Menu -> view.setOnClickListener { listener?.onMenu(it) }
            else -> view.setOnTouchListener { v, event -> handleRepeat(v, event, action) }
        }
        return view
    }

    /** Holding a key repeats it, like on a hardware keyboard. */
    private fun handleRepeat(view: View, event: MotionEvent, action: Action): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.isPressed = true
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                fire(action)
                repeatHandler.postDelayed(object : Runnable {
                    override fun run() {
                        fire(action)
                        repeatHandler.postDelayed(this, REPEAT_INTERVAL_MS)
                    }
                }, REPEAT_DELAY_MS)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.isPressed = false
                repeatHandler.removeCallbacksAndMessages(null)
                if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            }
        }
        return true
    }

    private fun fire(action: Action) {
        when (action) {
            is Action.Key -> listener?.onKey(action.keyCode)
            is Action.Text -> listener?.onText(action.codePoint)
            else -> Unit
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REPEAT_DELAY_MS = 400L
        private const val REPEAT_INTERVAL_MS = 50L

        private val KEYS: List<Pair<String, Action>> = listOf(
            "☰" to Action.Menu,
            "ESC" to Action.Key(KeyEvent.KEYCODE_ESCAPE),
            "TAB" to Action.Key(KeyEvent.KEYCODE_TAB),
            "CTRL" to Action.Ctrl,
            "ALT" to Action.Alt,
            "←" to Action.Key(KeyEvent.KEYCODE_DPAD_LEFT),
            "↓" to Action.Key(KeyEvent.KEYCODE_DPAD_DOWN),
            "↑" to Action.Key(KeyEvent.KEYCODE_DPAD_UP),
            "→" to Action.Key(KeyEvent.KEYCODE_DPAD_RIGHT),
            "-" to Action.Text('-'.code),
            "/" to Action.Text('/'.code),
            "|" to Action.Text('|'.code),
            "~" to Action.Text('~'.code),
            "HOME" to Action.Key(KeyEvent.KEYCODE_MOVE_HOME),
            "END" to Action.Key(KeyEvent.KEYCODE_MOVE_END),
            "PGUP" to Action.Key(KeyEvent.KEYCODE_PAGE_UP),
            "PGDN" to Action.Key(KeyEvent.KEYCODE_PAGE_DOWN),
        )
    }
}
