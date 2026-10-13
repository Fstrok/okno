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
 * Modifiers are one-shot toggles: tap Ctrl, then a letter, to send Ctrl+letter.
 */
class ExtraKeysView(context: Context, attrs: AttributeSet?) : HorizontalScrollView(context, attrs) {

    enum class Modifier { CTRL, ALT, WIN }

    /** What a key does when pressed. */
    sealed interface Action {
        data class Key(val keyCode: Int) : Action
        data class Text(val codePoint: Int) : Action
        data class Toggle(val modifier: Modifier) : Action
        data class Button(val id: String) : Action
    }

    interface Listener {
        fun onKey(keyCode: Int)
        fun onText(codePoint: Int)
        fun onButton(id: String, anchor: View)
    }

    var listener: Listener? = null

    private val active = mutableSetOf<Modifier>()
    private val modifierViews = mutableMapOf<Modifier, TextView>()
    private val repeatHandler = Handler(Looper.getMainLooper())
    private val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

    init {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(Color.parseColor("#1B1B1F"))
        addView(row)
        setKeys(TERMINAL_KEYS)
    }

    fun setKeys(keys: List<Pair<String, Action>>) {
        row.removeAllViews()
        modifierViews.clear()
        active.clear()
        for ((label, action) in keys) row.addView(makeKey(label, action))
    }

    /** Read-and-reset, as TerminalView expects from its client. */
    fun consume(modifier: Modifier): Boolean = active.remove(modifier).also { if (it) highlight(modifier) }

    /** Takes all active toggles at once (they apply to the next key only). */
    fun consumeAll(): Set<Modifier> = active.toSet().also {
        active.clear()
        Modifier.entries.forEach(::highlight)
    }

    private fun highlight(modifier: Modifier) {
        modifierViews[modifier]?.setTextColor(if (modifier in active) Color.parseColor("#7AA2F7") else Color.WHITE)
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
            is Action.Toggle -> {
                modifierViews[action.modifier] = view
                view.setOnClickListener {
                    if (!active.remove(action.modifier)) active += action.modifier
                    highlight(action.modifier)
                }
            }
            is Action.Button -> view.setOnClickListener { listener?.onButton(action.id, it) }
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

        const val BUTTON_MENU = "menu"
        const val BUTTON_KEYBOARD = "keyboard"

        private val ARROWS = listOf(
            "←" to Action.Key(KeyEvent.KEYCODE_DPAD_LEFT),
            "↓" to Action.Key(KeyEvent.KEYCODE_DPAD_DOWN),
            "↑" to Action.Key(KeyEvent.KEYCODE_DPAD_UP),
            "→" to Action.Key(KeyEvent.KEYCODE_DPAD_RIGHT),
        )

        private val NAVIGATION = listOf(
            "HOME" to Action.Key(KeyEvent.KEYCODE_MOVE_HOME),
            "END" to Action.Key(KeyEvent.KEYCODE_MOVE_END),
            "PGUP" to Action.Key(KeyEvent.KEYCODE_PAGE_UP),
            "PGDN" to Action.Key(KeyEvent.KEYCODE_PAGE_DOWN),
        )

        val TERMINAL_KEYS: List<Pair<String, Action>> = listOf(
            "☰" to Action.Button(BUTTON_MENU),
            "ESC" to Action.Key(KeyEvent.KEYCODE_ESCAPE),
            "TAB" to Action.Key(KeyEvent.KEYCODE_TAB),
            "CTRL" to Action.Toggle(Modifier.CTRL),
            "ALT" to Action.Toggle(Modifier.ALT),
        ) + ARROWS + listOf(
            "-" to Action.Text('-'.code),
            "/" to Action.Text('/'.code),
            "|" to Action.Text('|'.code),
            "~" to Action.Text('~'.code),
        ) + NAVIGATION

        val DESKTOP_KEYS: List<Pair<String, Action>> = listOf(
            "☰" to Action.Button(BUTTON_MENU),
            "⌨" to Action.Button(BUTTON_KEYBOARD),
            "ESC" to Action.Key(KeyEvent.KEYCODE_ESCAPE),
            "TAB" to Action.Key(KeyEvent.KEYCODE_TAB),
            "CTRL" to Action.Toggle(Modifier.CTRL),
            "ALT" to Action.Toggle(Modifier.ALT),
            "WIN" to Action.Key(KeyEvent.KEYCODE_META_LEFT),
        ) + ARROWS + listOf(
            "DEL" to Action.Key(KeyEvent.KEYCODE_FORWARD_DEL),
        ) + NAVIGATION + (1..12).map { "F$it" to Action.Key(KeyEvent.KEYCODE_F1 + it - 1) }
    }
}
