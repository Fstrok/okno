package io.okno.desktop

import android.view.KeyEvent

/** Android key codes and characters → X11 keysyms, as sent in RFB key events. */
object Keysyms {
    const val BACKSPACE = 0xff08
    const val RETURN = 0xff0d
    const val DELETE = 0xffff
    const val SHIFT_L = 0xffe1
    const val CONTROL_L = 0xffe3
    const val ALT_L = 0xffe9
    const val SUPER_L = 0xffeb

    private val SPECIAL = mapOf(
        KeyEvent.KEYCODE_DEL to BACKSPACE,
        KeyEvent.KEYCODE_TAB to 0xff09,
        KeyEvent.KEYCODE_ENTER to RETURN,
        KeyEvent.KEYCODE_NUMPAD_ENTER to RETURN,
        KeyEvent.KEYCODE_ESCAPE to 0xff1b,
        KeyEvent.KEYCODE_FORWARD_DEL to DELETE,
        KeyEvent.KEYCODE_MOVE_HOME to 0xff50,
        KeyEvent.KEYCODE_DPAD_LEFT to 0xff51,
        KeyEvent.KEYCODE_DPAD_UP to 0xff52,
        KeyEvent.KEYCODE_DPAD_RIGHT to 0xff53,
        KeyEvent.KEYCODE_DPAD_DOWN to 0xff54,
        KeyEvent.KEYCODE_PAGE_UP to 0xff55,
        KeyEvent.KEYCODE_PAGE_DOWN to 0xff56,
        KeyEvent.KEYCODE_MOVE_END to 0xff57,
        KeyEvent.KEYCODE_INSERT to 0xff63,
        KeyEvent.KEYCODE_MENU to 0xff67,
        KeyEvent.KEYCODE_SHIFT_LEFT to SHIFT_L,
        KeyEvent.KEYCODE_SHIFT_RIGHT to 0xffe2,
        KeyEvent.KEYCODE_CTRL_LEFT to CONTROL_L,
        KeyEvent.KEYCODE_CTRL_RIGHT to 0xffe4,
        KeyEvent.KEYCODE_ALT_LEFT to ALT_L,
        KeyEvent.KEYCODE_ALT_RIGHT to 0xffea,
        KeyEvent.KEYCODE_META_LEFT to SUPER_L,
        KeyEvent.KEYCODE_META_RIGHT to 0xffec,
        KeyEvent.KEYCODE_CAPS_LOCK to 0xffe5,
    ) + (0 until 12).associate { (KeyEvent.KEYCODE_F1 + it) to (0xffbe + it) }

    fun fromKeyCode(keyCode: Int): Int? = SPECIAL[keyCode]

    /** X11 Cyrillic keysyms 0x6c0..0x6df (lower case) follow KOI8-R letter order. */
    private const val KOI8_LETTERS = "юабцдефгхийклмнопярстужвьызшэщчъ"

    /**
     * Latin-1 and Russian map to the classic keysyms, which the desktop's
     * keymap (rootfs-overlay/usr/share/X11/xkb/symbols/okno) contains, so
     * Xvnc finds a real key for them. Anything else uses X11's Unicode range,
     * for which Xvnc assigns a spare key on the fly.
     */
    fun fromCodePoint(codePoint: Int): Int {
        when (codePoint) {
            '\n'.code -> return RETURN
            '\t'.code -> return 0xff09
            '\b'.code -> return BACKSPACE
            in 0x20..0x7e, in 0xa0..0xff -> return codePoint
            'ё'.code -> return 0x6a3
            'Ё'.code -> return 0x6b3
        }
        val lower = KOI8_LETTERS.indexOf(codePoint.toChar())
        if (lower >= 0) return 0x6c0 + lower
        val upper = KOI8_LETTERS.indexOf(codePoint.toChar().lowercaseChar())
        if (codePoint in 0x0410..0x042f && upper >= 0) return 0x6e0 + upper
        return 0x01000000 or codePoint
    }
}
