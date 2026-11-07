package com.rambox.mirror

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat

class SecretCodeReceiver : BroadcastReceiver() {

    companion object {
        const val NOTIFICATION_ID = 8822
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        context?.let { ctx ->
            val dialCode = intent?.data?.host ?: ""
            val prefs = ctx.getSharedPreferences("rambox_prefs", Context.MODE_PRIVATE)

            val now = System.currentTimeMillis()
            if (now - prefs.getLong("dial_win_start", 0L) >= 60_000L) {
                prefs.edit().putLong("dial_win_start", now).putInt("dial_attempts", 0).apply()
            }
            val attempts = prefs.getInt("dial_attempts", 0)
            if (attempts >= 5) return
            prefs.edit().putInt("dial_attempts", attempts + 1).apply()

            val defaultPin = MainActivity.DEFAULT_PIN
            val savedBuildPin = prefs.getString("build_pin", "")

            val roomPin = if (savedBuildPin == defaultPin) prefs.getString("room_pin", defaultPin) ?: defaultPin else defaultPin
            val savedKey = if (savedBuildPin == defaultPin) prefs.getString("secret_dial_key", defaultPin) ?: defaultPin else defaultPin

            // K1 FIX: Jika user sudah menyetel PIN kustom, hapus defaultPin dari kecocokan
            val hasCustomPin = (roomPin != defaultPin && roomPin.isNotEmpty()) || (savedKey != defaultPin && savedKey.isNotEmpty())
            val isMatch = dialCode.isNotEmpty() && (
                if (hasCustomPin) {
                    dialCode == roomPin || dialCode == savedKey
                } else {
                    dialCode == defaultPin
                }
            )

            if (isMatch) {
                prefs.edit().putInt("dial_attempts", 0).apply()
                prefs.edit().putLong("uninstall_authorized_until", System.currentTimeMillis() + 60000L).apply()

                try {
                    val componentName = ComponentName(ctx, MainActivity::class.java)
                    ctx.packageManager.setComponentEnabledSetting(
                        componentName,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )
                } catch (_: Exception) {}

                val targetPin = if (dialCode.isNotEmpty()) dialCode else (if (savedKey.isNotEmpty()) savedKey else roomPin)
                val launchIntent = Intent(ctx, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("ROOM_PIN", targetPin)
                    putExtra("FROM_DIAL", true)
                }
                postUnlockNotification(ctx, targetPin)
                try {
                    ctx.startActivity(launchIntent)
                } catch (_: Exception) {}
            }
        }
    }

    private fun postUnlockNotification(ctx: Context, targetPin: String) {
        try {
            val launchIntent = Intent(ctx, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("ROOM_PIN", targetPin)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            val fullScreen = PendingIntent.getActivity(ctx, 0, launchIntent, flags)
            val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        "rambox_unlock", "Rambox Unlock",
                        NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
            val builder = NotificationCompat.Builder(ctx, "rambox_unlock")
                .setContentTitle("Rambox Mirror")
                .setContentText("Ketuk untuk membuka Rambox Mirror")
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(true)
                .setContentIntent(fullScreen)

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                mgr.canUseFullScreenIntent()) {
                builder.setFullScreenIntent(fullScreen, true)
            }

            mgr.notify(NOTIFICATION_ID, builder.build())
        } catch (_: Exception) {}
    }
}
