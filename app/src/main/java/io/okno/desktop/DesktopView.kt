package io.okno.desktop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Shows the Linux desktop and turns touches and typing into mouse and
 * keyboard input:
 * - tap: left click (two quick taps: double click)
 * - press and drag: drag with the left button (move windows, select text)
 * - long press: right click
 * - two fingers up/down: mouse wheel
 * - pinch: zoom; when zoomed in, two fingers move the view
 */
class DesktopView(context: Context, attrs: AttributeSet?) : View(context, attrs), RfbClient.Listener {

    /** Sends one key press to the desktop, applying on-screen modifier toggles. */
    fun interface KeySender {
        fun send(keysym: Int)
    }

    val framebuffer = Framebuffer()
    var client: RfbClient? = null
    var keySender: KeySender? = null

    /** Called on the UI thread when the view size suggests a new desktop resolution. */
    var onSizeChanged: ((width: Int, height: Int) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val dirty = Rect()
    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // View transform: desktop pixel = (view pixel - offset) / scale.
    private var fullHeight = 0 // view height without the on-screen keyboard
    private var lastTouchDesktopY = 0
    private var fitScale = 1f
    private var zoom = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.BLACK)
    }

    // ---- frames (from the network thread) ----

    override fun onFrame(x: Int, y: Int, w: Int, h: Int) {
        synchronized(dirty) { dirty.union(x, y, x + w, y + h) }
        postInvalidateOnAnimation()
    }

    override fun onResize(width: Int, height: Int) {
        synchronized(dirty) { dirty.set(0, 0, width, height) }
        main.post {
            zoom = 1f
            updateTransform()
            invalidate()
        }
    }

    override fun onResizeSupported() {
        main.post { onSizeChanged?.invoke(width, fullHeight) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // The keyboard only takes height away. Keep the desktop's resolution
        // and scale, and keep the last touched spot in view instead.
        val keyboardResize = w == oldw && h < fullHeight
        if (!keyboardResize) fullHeight = h
        updateTransform()
        if (keyboardResize) {
            offsetY = height / 2f - lastTouchDesktopY * scale
            clampOffset()
        }
        if (w > 0 && h > 0 && !keyboardResize) onSizeChanged?.invoke(w, fullHeight)
    }

    override fun onDraw(canvas: Canvas) {
        val fb = framebuffer
        synchronized(fb) {
            if (fb.width == 0 || fb.height == 0) return
            var bmp = bitmap
            if (bmp == null || bmp.width != fb.width || bmp.height != fb.height) {
                bmp = Bitmap.createBitmap(fb.width, fb.height, Bitmap.Config.ARGB_8888)
                bitmap = bmp
                synchronized(dirty) { dirty.set(0, 0, fb.width, fb.height) }
            }
            synchronized(dirty) {
                if (!dirty.intersect(0, 0, fb.width, fb.height)) dirty.setEmpty()
                if (!dirty.isEmpty) {
                    bmp.setPixels(fb.pixels, dirty.top * fb.width + dirty.left, fb.width,
                        dirty.left, dirty.top, dirty.width(), dirty.height())
                    dirty.setEmpty()
                }
            }
            matrix.setScale(scale, scale)
            matrix.postTranslate(offsetX, offsetY)
            paint.isFilterBitmap = scale != 1f
            canvas.drawBitmap(bmp, matrix, paint)
        }
    }

    private val scale: Float get() = fitScale * zoom

    private fun updateTransform() {
        val fbW = framebuffer.width
        val fbH = framebuffer.height
        if (fbW == 0 || fbH == 0 || width == 0 || height == 0) return
        fitScale = minOf(width.toFloat() / fbW, maxOf(height, fullHeight).toFloat() / fbH)
        clampOffset()
    }

    private fun clampOffset() {
        val contentW = framebuffer.width * scale
        val contentH = framebuffer.height * scale
        offsetX = if (contentW <= width) (width - contentW) / 2 else offsetX.coerceIn(width - contentW, 0f)
        offsetY = if (contentH <= height) (height - contentH) / 2 else offsetY.coerceIn(height - contentH, 0f)
    }

    private fun desktopX(viewX: Float) = ((viewX - offsetX) / scale).toInt()
    private fun desktopY(viewY: Float) = ((viewY - offsetY) / scale).toInt()

    // ---- touch → mouse ----

    private enum class Mode { NONE, TAP, DRAG, LONG_PRESSED, TWO_FINGER, SCROLL, ZOOM_PAN }

    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private var startSpan = 0f
    private var startZoom = 1f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var scrollRemainder = 0f

    private val longPress = Runnable {
        if (mode == Mode.TAP) {
            mode = Mode.LONG_PRESSED
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            click(BUTTON_RIGHT, downX, downY)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val c = client ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = Mode.TAP
                downX = event.x
                downY = event.y
                lastTouchDesktopY = desktopY(downY)
                c.sendPointer(0, desktopX(downX), desktopY(downY))
                main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                main.removeCallbacks(longPress)
                if (mode == Mode.DRAG) c.sendPointer(0, desktopX(event.x), desktopY(event.y))
                if (event.pointerCount == 2) {
                    mode = Mode.TWO_FINGER
                    startSpan = span(event)
                    startZoom = zoom
                    lastFocusX = focusX(event)
                    lastFocusY = focusY(event)
                    scrollRemainder = 0f
                }
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.TAP -> if (hypot(event.x - downX, event.y - downY) > touchSlop) {
                    main.removeCallbacks(longPress)
                    mode = Mode.DRAG
                    c.sendPointer(BUTTON_LEFT, desktopX(downX), desktopY(downY))
                    c.sendPointer(BUTTON_LEFT, desktopX(event.x), desktopY(event.y))
                }
                Mode.DRAG -> c.sendPointer(BUTTON_LEFT, desktopX(event.x), desktopY(event.y))
                Mode.TWO_FINGER, Mode.SCROLL, Mode.ZOOM_PAN -> twoFingerMove(event, c)
                else -> Unit
            }
            MotionEvent.ACTION_UP -> {
                main.removeCallbacks(longPress)
                when (mode) {
                    Mode.TAP -> {
                        click(BUTTON_LEFT, event.x, event.y)
                        performClick()
                    }
                    Mode.DRAG -> c.sendPointer(0, desktopX(event.x), desktopY(event.y))
                    else -> Unit
                }
                mode = Mode.NONE
            }
            MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(longPress)
                if (mode == Mode.DRAG) c.sendPointer(0, desktopX(event.x), desktopY(event.y))
                mode = Mode.NONE
            }
        }
        return true
    }

    private fun twoFingerMove(event: MotionEvent, c: RfbClient) {
        if (event.pointerCount < 2) return
        val span = span(event)
        val fx = focusX(event)
        val fy = focusY(event)
        if (mode == Mode.TWO_FINGER) {
            mode = when {
                abs(span - startSpan) > touchSlop * 2 -> Mode.ZOOM_PAN
                zoom > 1f && hypot(fx - lastFocusX, fy - lastFocusY) > touchSlop -> Mode.ZOOM_PAN
                abs(fy - lastFocusY) > touchSlop -> Mode.SCROLL
                else -> return
            }
        }
        when (mode) {
            Mode.SCROLL -> {
                // Fingers up → content moves up → wheel down, like a touchpad.
                scrollRemainder += fy - lastFocusY
                val step = SCROLL_STEP_DP * resources.displayMetrics.density
                while (abs(scrollRemainder) >= step) {
                    val button = if (scrollRemainder < 0) BUTTON_WHEEL_DOWN else BUTTON_WHEEL_UP
                    c.sendPointer(button, desktopX(fx), desktopY(fy))
                    c.sendPointer(0, desktopX(fx), desktopY(fy))
                    scrollRemainder += if (scrollRemainder < 0) step else -step
                }
            }
            Mode.ZOOM_PAN -> {
                // Keep the desktop point under the fingers' focus where it is.
                val desktopFx = (lastFocusX - offsetX) / scale
                val desktopFy = (lastFocusY - offsetY) / scale
                zoom = (startZoom * span / startSpan).coerceIn(1f, MAX_ZOOM)
                offsetX = fx - desktopFx * scale
                offsetY = fy - desktopFy * scale
                clampOffset()
                invalidate()
            }
            else -> Unit
        }
        lastFocusX = fx
        lastFocusY = fy
    }

    override fun performClick(): Boolean = super.performClick()

    private fun click(button: Int, x: Float, y: Float) {
        val c = client ?: return
        c.sendPointer(button, desktopX(x), desktopY(y))
        c.sendPointer(0, desktopX(x), desktopY(y))
    }

    private fun span(e: MotionEvent) = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)).coerceAtLeast(1f)
    private fun focusX(e: MotionEvent) = (e.getX(0) + e.getX(1)) / 2
    private fun focusY(e: MotionEvent) = (e.getY(0) + e.getY(1)) / 2

    // ---- keyboard ----

    private var keyboardShown = false

    fun toggleKeyboard() {
        requestFocus()
        val imm = context.getSystemService(InputMethodManager::class.java)
        val shown = if (Build.VERSION.SDK_INT >= 30) {
            rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        } else {
            keyboardShown
        }
        if (shown) imm.hideSoftInputFromWindow(windowToken, 0) else imm.showSoftInput(this, 0)
        keyboardShown = !shown
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // A password-style field: no autocorrect or suggestions, every key goes out at once.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, false) {
            private var composing = ""

            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                replaceComposing(text.toString())
                composing = ""
                return true
            }

            // Some keyboards compose words even in password fields: mirror the
            // composing text on the desktop by retyping the difference.
            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                replaceComposing(text.toString())
                composing = text.toString()
                return true
            }

            override fun finishComposingText(): Boolean {
                composing = ""
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength) { keySender?.send(Keysyms.BACKSPACE) }
                repeat(afterLength) { keySender?.send(Keysyms.DELETE) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) typeKey(event)
                return true
            }

            private fun replaceComposing(text: String) {
                val common = composing.commonPrefixWith(text).length
                repeat(composing.length - common) { keySender?.send(Keysyms.BACKSPACE) }
                typeText(text.substring(common))
            }
        }
    }

    fun typeText(text: String) {
        text.codePoints().forEach { keySender?.send(Keysyms.fromCodePoint(it)) }
    }

    private fun typeKey(event: KeyEvent) {
        val special = Keysyms.fromKeyCode(event.keyCode)
        if (special != null) {
            keySender?.send(special)
            return
        }
        val cp = event.getUnicodeChar(event.metaState and (KeyEvent.META_SHIFT_MASK or KeyEvent.META_CAPS_LOCK_ON))
        if (cp > 0) keySender?.send(Keysyms.fromCodePoint(cp))
    }

    // Hardware keyboard: forward presses and releases as they happen, modifiers included.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = forwardHardwareKey(event, true)
        || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = forwardHardwareKey(event, false)
        || super.onKeyUp(keyCode, event)

    private fun forwardHardwareKey(event: KeyEvent, down: Boolean): Boolean {
        if (event.isSystem) return false
        val c = client ?: return false
        val keysym = Keysyms.fromKeyCode(event.keyCode)
            ?: event.getUnicodeChar(event.metaState and (KeyEvent.META_SHIFT_MASK or KeyEvent.META_CAPS_LOCK_ON))
                .takeIf { it > 0 }?.let(Keysyms::fromCodePoint)
            ?: return false
        c.sendKey(keysym, down)
        return true
    }

    companion object {
        const val BUTTON_LEFT = 1
        const val BUTTON_RIGHT = 4
        const val BUTTON_WHEEL_UP = 8
        const val BUTTON_WHEEL_DOWN = 16
        private const val MAX_ZOOM = 4f
        private const val SCROLL_STEP_DP = 24f
    }
}
