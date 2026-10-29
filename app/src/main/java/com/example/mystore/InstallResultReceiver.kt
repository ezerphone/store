package com.example.mystore

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val pkg = intent.getStringExtra("pkg")
        val op = intent.getStringExtra("op") ?: "install"

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                if (op == "install" && pkg != null) {
                    notifyInstalled(context, pkg)
                } else {
                    toast(context, "האפליקציה הוסרה בהצלחה")
                }
            }
            else -> {
                // Technical details go to logcat; the person sees a clear Hebrew message.
                // For STATUS_PENDING_USER_ACTION the confirmation intent is intentionally NOT launched.
                Log.e(TAG, "$op failed, status=$status, message=$msg")
                toast(context, Errors.forStatus(status, op))
            }
        }
        context.sendBroadcast(
            Intent(ACTION_REFRESH).setPackage(context.packageName).putExtra("pkg", pkg)
        )
    }

    private fun toast(context: Context, text: String) =
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    @Suppress("DEPRECATION")
    private fun notifyInstalled(context: Context, pkg: String) {
        val pm = context.packageManager
        val label = try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }

        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm == null || !nm.areNotificationsEnabled()) {
            // Notification permission not granted: fall back to a toast.
            toast(context, "$label הותקנה בהצלחה")
            return
        }

        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "התקנות", NotificationManager.IMPORTANCE_DEFAULT)
        )

        val open = pm.getLaunchIntentForPackage(pkg)
            ?: Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            context,
            pkg.hashCode(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val n = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_store)
            .setContentTitle("ההתקנה הושלמה")
            .setContentText("$label הותקנה בהצלחה")
            .setColor(0xFF2563EB.toInt())
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(pkg.hashCode(), n)
    }

    companion object {
        const val TAG = "MyStore"
        const val ACTION_REFRESH = "com.example.mystore.REFRESH"
        const val CHANNEL_ID = "installs"
    }
}
