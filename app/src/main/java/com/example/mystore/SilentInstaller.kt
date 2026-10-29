package com.example.mystore

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File

class SilentInstaller(private val context: Context) {

    /** Must be called from a background thread. */
    fun install(entry: AppEntry) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        params.setAppPackageName(entry.packageName)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }

        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            val file = File(entry.apkPath)
            session.openWrite("pkg", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            session.commit(resultSender(sessionId, entry.packageName, "install"))
        }
    }

    fun uninstall(packageName: String) {
        context.packageManager.packageInstaller
            .uninstall(packageName, resultSender(packageName.hashCode(), packageName, "uninstall"))
    }

    private fun resultSender(requestCode: Int, packageName: String, op: String) =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, InstallResultReceiver::class.java)
                .setPackage(context.packageName)
                .putExtra("pkg", packageName)
                .putExtra("op", op),
            PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        ).intentSender
}
