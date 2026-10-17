package io.okno

import android.os.Build
import com.termux.x11.LorieApp
import com.termux.x11.MainActivity
import com.termux.x11.R as X11R

/** Termux:X11's application, without its own notification: Okno's service has one. */
class OknoApp : LorieApp() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) notificationManager.deleteNotificationChannel(getString(X11R.string.lorie_app_name))
    }

    override fun onActivityResumed(activity: MainActivity) = Unit
}
