package io.okno.desktop

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Minimal RFB 3.8 (VNC) client for the desktop running inside Linux.
 *
 * The connection is a private Unix socket on the same device, so it uses no
 * authentication and the cheapest encodings (Raw, CopyRect): bandwidth is
 * free, CPU is not. The server may resize the desktop (ExtendedDesktopSize),
 * which is how the desktop follows the phone's rotation.
 *
 * [run] blocks on the calling thread and reads server messages; the send*
 * methods may be called from any thread.
 */
class RfbClient(input: InputStream, output: OutputStream, private val framebuffer: Framebuffer) {

    interface Listener {
        /** A region of [Framebuffer] changed (reader thread). */
        fun onFrame(x: Int, y: Int, w: Int, h: Int)

        /** The desktop got a new size; [Framebuffer] was already resized (reader thread). */
        fun onResize(width: Int, height: Int)

        /** The server accepts [requestDesktopSize] from now on (reader thread). */
        fun onResizeSupported()

        /** Text copied on the desktop (reader thread). */
        fun onClipboardText(text: String) = Unit
    }

    var listener: Listener? = null

    private val input = DataInputStream(input.buffered(1 shl 16))
    private val output = BufferedOutputStream(output, 1 shl 12)
    private val writeLock = Any()
    private var rectBytes = ByteArray(0)

    @Volatile
    var supportsResize = false
        private set

    fun run() {
        handshake()
        requestUpdate(incremental = false)
        while (true) {
            when (val type = input.readUnsignedByte()) {
                0 -> readFramebufferUpdate()
                1 -> { // SetColourMapEntries: unused with true colour
                    input.skipFully(3)
                    input.skipFully(input.readUnsignedShort() * 6)
                }
                2 -> Unit // Bell
                3 -> readServerCutText()
                150 -> Unit // EndOfContinuousUpdates
                else -> throw IOException("unknown RFB message $type")
            }
        }
    }

    private fun handshake() {
        val version = ByteArray(12).also { input.readFully(it) }.toString(Charsets.US_ASCII)
        if (!version.startsWith("RFB 003.")) throw IOException("not a VNC server: $version")
        write { it.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII)) }

        val types = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
        if (types.isEmpty()) throw IOException("server refused: ${readString()}")
        if (SECURITY_NONE !in types) throw IOException("server requires a password")
        write { it.write(SECURITY_NONE.toInt()) }
        if (input.readInt() != 0) throw IOException("security handshake failed: ${readString()}")

        write { it.write(1) } // ClientInit: shared session
        val width = input.readUnsignedShort()
        val height = input.readUnsignedShort()
        input.skipFully(16) // server pixel format: we ask for our own below
        readString() // desktop name
        framebuffer.resize(width, height)
        listener?.onResize(width, height)

        write { out ->
            // SetPixelFormat: 32 bpp, little endian, 0x00RRGGBB — Android's int colour layout.
            out.write(byteArrayOf(0, 0, 0, 0, 32, 24, 0, 1, 0, 255.toByte(), 0, 255.toByte(), 0, 255.toByte(), 16, 8, 0, 0, 0, 0))
            val encodings = intArrayOf(
                ENC_COPYRECT, ENC_RAW, ENC_EXTENDED_DESKTOP_SIZE, ENC_DESKTOP_SIZE, ENC_LAST_RECT,
                ENC_EXTENDED_CLIPBOARD,
            )
            val msg = ByteBuffer.allocate(4 + encodings.size * 4)
            msg.put(2).put(0).putShort(encodings.size.toShort())
            encodings.forEach { msg.putInt(it) }
            out.write(msg.array())
        }
    }

    private fun readFramebufferUpdate() {
        input.skipFully(1)
        val rects = input.readUnsignedShort()
        for (i in 0 until rects) {
            val x = input.readUnsignedShort()
            val y = input.readUnsignedShort()
            val w = input.readUnsignedShort()
            val h = input.readUnsignedShort()
            when (val encoding = input.readInt()) {
                ENC_RAW -> readRaw(x, y, w, h)
                ENC_COPYRECT -> {
                    val srcX = input.readUnsignedShort()
                    val srcY = input.readUnsignedShort()
                    framebuffer.copyRect(srcX, srcY, x, y, w, h)
                    listener?.onFrame(x, y, w, h)
                }
                ENC_DESKTOP_SIZE -> resize(w, h)
                ENC_EXTENDED_DESKTOP_SIZE -> {
                    if (!supportsResize) {
                        supportsResize = true
                        listener?.onResizeSupported()
                    }
                    val screens = input.readUnsignedByte()
                    input.skipFully(3 + screens * 16)
                    resize(w, h)
                }
                ENC_LAST_RECT -> break
                else -> throw IOException("unexpected encoding $encoding")
            }
        }
        requestUpdate(incremental = true)
    }

    /** Reads the whole rectangle off the socket first, so the lock is held only for bulk copies. */
    private fun readRaw(x: Int, y: Int, w: Int, h: Int) {
        val size = w * h * 4
        if (rectBytes.size < size) rectBytes = ByteArray(size)
        input.readFully(rectBytes, 0, size)
        val ints = ByteBuffer.wrap(rectBytes, 0, size).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        synchronized(framebuffer) {
            val pixels = framebuffer.pixels
            val stride = framebuffer.width
            for (row in 0 until h) {
                val base = (y + row) * stride + x
                ints.get(pixels, base, w)
                for (i in base until base + w) pixels[i] = pixels[i] or OPAQUE
            }
        }
        listener?.onFrame(x, y, w, h)
    }

    private fun resize(width: Int, height: Int) {
        if (width == framebuffer.width && height == framebuffer.height) return
        framebuffer.resize(width, height)
        listener?.onResize(width, height)
    }

    // ---- clipboard ----
    //
    // Plain RFB clipboard text is Latin-1 only. The Extended Clipboard
    // extension (TigerVNC, rfbproto "ExtendedClipboard") carries UTF-8 and
    // works by announcing: notify → request → provide (zlib-compressed).

    @Volatile
    private var extendedClipboard = false

    @Volatile
    private var localClipboard: String? = null

    private fun readServerCutText() {
        input.skipFully(3)
        val length = input.readInt()
        if (length >= 0) {
            val bytes = ByteArray(length).also { input.readFully(it) }
            listener?.onClipboardText(bytes.toString(Charsets.ISO_8859_1).replace("\r\n", "\n"))
            return
        }
        val payload = ByteArray(-length).also { input.readFully(it) }
        val buffer = ByteBuffer.wrap(payload)
        val flags = buffer.int
        when {
            flags and CLIP_CAPS != 0 -> {
                extendedClipboard = true
                // We take text only, and ask for it rather than having it pushed.
                sendExtendedClipboard(CLIP_CAPS or CLIP_REQUEST or CLIP_PEEK or CLIP_NOTIFY or CLIP_PROVIDE or CLIP_TEXT) {
                    it.putInt(MAX_CLIPBOARD_BYTES)
                }
                // Text copied on the phone before the server was ready.
                if (localClipboard != null) sendExtendedClipboard(CLIP_NOTIFY or CLIP_TEXT)
            }
            flags and CLIP_REQUEST != 0 -> if (flags and CLIP_TEXT != 0) provideClipboard()
            flags and CLIP_PEEK != 0 -> sendExtendedClipboard(CLIP_NOTIFY or if (localClipboard != null) CLIP_TEXT else 0)
            flags and CLIP_NOTIFY != 0 -> if (flags and CLIP_TEXT != 0) sendExtendedClipboard(CLIP_REQUEST or CLIP_TEXT)
            flags and CLIP_PROVIDE != 0 -> if (flags and CLIP_TEXT != 0) {
                val data = inflate(payload, buffer.position())
                val size = data.int
                if (size in 0..data.remaining()) {
                    val text = String(data.array(), data.position(), size, Charsets.UTF_8)
                    listener?.onClipboardText(text.trimEnd('\u0000').replace("\r\n", "\n"))
                }
            }
        }
    }

    /**
     * Offers text copied on the phone to the desktop. Right after connecting,
     * before the server has announced the Extended Clipboard, the text waits
     * and is offered then: sent any earlier it would be lost (plain cut text
     * carries Latin-1 only) or land in the middle of the handshake.
     */
    fun sendClipboard(text: String) {
        localClipboard = text
        if (extendedClipboard) sendExtendedClipboard(CLIP_NOTIFY or CLIP_TEXT)
    }

    private fun provideClipboard() {
        val text = localClipboard ?: return
        val utf8 = (text.replace("\r\n", "\n").replace("\n", "\r\n") + "\u0000").toByteArray(Charsets.UTF_8)
        val raw = ByteBuffer.allocate(4 + utf8.size).putInt(utf8.size).put(utf8).array()
        val deflater = Deflater()
        deflater.setInput(raw)
        deflater.finish()
        val compressed = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (!deflater.finished()) compressed.write(chunk, 0, deflater.deflate(chunk))
        deflater.end()
        val bytes = compressed.toByteArray()
        sendExtendedClipboard(CLIP_PROVIDE or CLIP_TEXT, bytes.size) { it.put(bytes) }
    }

    private fun sendExtendedClipboard(flags: Int, extra: Int = 0, body: (ByteBuffer) -> Unit = {}) {
        val size = 4 + extra + if (flags and CLIP_CAPS != 0) 4 else 0
        val msg = ByteBuffer.allocate(8 + size)
        msg.put(6).put(0).putShort(0).putInt(-size).putInt(flags)
        body(msg)
        write { it.write(msg.array(), 0, msg.position()) }
    }

    private fun inflate(payload: ByteArray, offset: Int): ByteBuffer {
        val inflater = Inflater()
        inflater.setInput(payload, offset, payload.size - offset)
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(chunk)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(chunk, 0, n)
            if (out.size() > MAX_CLIPBOARD_BYTES) break
        }
        inflater.end()
        return ByteBuffer.wrap(out.toByteArray())
    }

    fun requestUpdate(incremental: Boolean) = write { out ->
        val msg = ByteBuffer.allocate(10)
        msg.put(3).put(if (incremental) 1 else 0)
            .putShort(0).putShort(0)
            .putShort(framebuffer.width.toShort()).putShort(framebuffer.height.toShort())
        out.write(msg.array())
    }

    fun sendPointer(buttons: Int, x: Int, y: Int) = write { out ->
        val msg = ByteBuffer.allocate(6)
        msg.put(5).put(buttons.toByte())
            .putShort(x.coerceIn(0, framebuffer.width - 1).toShort())
            .putShort(y.coerceIn(0, framebuffer.height - 1).toShort())
        out.write(msg.array())
    }

    fun sendKey(keysym: Int, down: Boolean) = write { out ->
        val msg = ByteBuffer.allocate(8)
        msg.put(4).put(if (down) 1 else 0).putShort(0).putInt(keysym)
        out.write(msg.array())
    }

    /** Asks the server to change the desktop resolution (needs ExtendedDesktopSize). */
    fun requestDesktopSize(width: Int, height: Int) = write { out ->
        val msg = ByteBuffer.allocate(24)
        msg.put(251.toByte()).put(0)
            .putShort(width.toShort()).putShort(height.toShort())
            .put(1).put(0) // one screen
            .putInt(0).putShort(0).putShort(0).putShort(width.toShort()).putShort(height.toShort()).putInt(0)
        out.write(msg.array())
    }

    private fun write(block: (OutputStream) -> Unit) {
        synchronized(writeLock) {
            block(output)
            output.flush()
        }
    }

    private fun readString(): String {
        val bytes = ByteArray(input.readInt()).also { input.readFully(it) }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun DataInputStream.skipFully(n: Int) {
        var left = n
        while (left > 0) {
            val skipped = skipBytes(left)
            if (skipped <= 0) {
                readByte()
                left--
            } else {
                left -= skipped
            }
        }
    }

    companion object {
        private const val SECURITY_NONE: Byte = 1
        private const val ENC_RAW = 0
        private const val ENC_COPYRECT = 1
        private const val ENC_DESKTOP_SIZE = -223
        private const val ENC_LAST_RECT = -224
        private const val ENC_EXTENDED_DESKTOP_SIZE = -308
        private const val ENC_EXTENDED_CLIPBOARD = 0xC0A1E5CE.toInt()
        private const val CLIP_TEXT = 1
        private const val CLIP_CAPS = 1 shl 24
        private const val CLIP_REQUEST = 1 shl 25
        private const val CLIP_PEEK = 1 shl 26
        private const val CLIP_NOTIFY = 1 shl 27
        private const val CLIP_PROVIDE = 1 shl 28
        private const val MAX_CLIPBOARD_BYTES = 4 shl 20
        private const val OPAQUE = 0xFF000000.toInt()
    }
}

/** Desktop pixels shared between the network thread and the UI; lock on the instance. */
class Framebuffer {
    var width = 0
        private set
    var height = 0
        private set
    var pixels = IntArray(0)
        private set

    @Synchronized
    fun resize(w: Int, h: Int) {
        width = w
        height = h
        pixels = IntArray(w * h)
    }

    @Synchronized
    fun copyRect(srcX: Int, srcY: Int, x: Int, y: Int, w: Int, h: Int) {
        val rows = if (srcY < y) (h - 1 downTo 0) else (0 until h)
        for (row in rows) {
            System.arraycopy(pixels, (srcY + row) * width + srcX, pixels, (y + row) * width + x, w)
        }
    }
}
