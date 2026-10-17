package io.okno

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
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
        setBackgroundColor(BAR)
        row.setPadding(dp(3), dp(4), dp(3), dp(4))
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
        val on = modifier in active
        modifierViews[modifier]?.apply {
            setTextColor(if (on) BAR else TEXT)
            (background as RippleDrawable).getDrawable(0).setTint(if (on) ACCENT else KEY)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeKey(label: String, action: Action): TextView {
        val view = TextView(context).apply {
            text = label
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (label.length == 1) 19f else 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            minWidth = dp(44)
            minHeight = dp(40)
            setPadding(dp(10), 0, dp(10), 0)
            val cap = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(KEY) }
            background = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), cap, null)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)).apply { setMargins(dp(3), 0, dp(3), 0) }
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
        private val BAR = Color.parseColor("#1F232B")
        private val KEY = Color.parseColor("#2F343F")
        private val TEXT = Color.parseColor("#D3DAE3")
        private val ACCENT = Color.parseColor("#7AA2F7")

        private const val REPEAT_DELAY_MS = 400L
        private const val REPEAT_INTERVAL_MS = 50L

        const val BUTTON_MENU = "menu"
        const val BUTTON_KEYBOARD = "keyboard"
        const val BUTTON_PASTE = "paste"

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
            "📋" to Action.Button(BUTTON_PASTE),
            "⏎" to Action.Key(KeyEvent.KEYCODE_ENTER),
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
            "📋" to Action.Button(BUTTON_PASTE),
            "⏎" to Action.Key(KeyEvent.KEYCODE_ENTER),
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
