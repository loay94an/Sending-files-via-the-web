package com.wasla.tv
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class WaslaBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val service = Intent(context, WaslaLocalServerService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service)
            else context.startService(service)
        } catch (_: Exception) {
            // Android/vendor background-start policies may defer startup until user opens the receiver.
        }
    }
}
