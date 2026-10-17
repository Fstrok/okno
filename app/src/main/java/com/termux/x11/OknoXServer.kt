package com.termux.x11

import android.os.Looper

/**
 * Entry point of Okno's X server process (io.okno.LinuxService starts it with
 * app_process): Termux:X11's CmdEntryPoint, with the native library loaded
 * from the app's lib dir. Okno's APK keeps its libraries compressed (proot
 * must be extracted to run), and CmdEntryPoint looks for them inside the APK.
 */
object OknoXServer {
    @JvmStatic
    fun main(args: Array<String>) {
        CmdEntryPoint.ctx = CmdEntryPoint.createContext()
        System.loadLibrary("Xlorie")
        CmdEntryPoint.handler.post { CmdEntryPoint(args) }
        Looper.loop()
    }
}
