package com.example.mystore

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File
import java.io.FileNotFoundException
import java.util.zip.ZipFile

class SilentInstaller(private val context: Context) {

    /** Must be called from a background thread. */
    fun install(entry: AppEntry) {
        val work = File(context.cacheDir, "install/${System.nanoTime()}")
        try {
            val files: List<File> = when (val s = entry.source) {
                is AppSource.Apk -> listOf(File(s.path))
                is AppSource.Zip -> extract(s, work)
            }
            commitFiles(entry.packageName, files)
        } finally {
            // The system has already copied the data into the install session.
            work.deleteRecursively()
        }
    }

    /** Extracts the APK files of a zip. Only now, when the person taps install. */
    private fun extract(s: AppSource.Zip, work: File): List<File> {
        val zipFile = File(s.zipPath)
        if (!zipFile.exists()) throw FileNotFoundException(s.zipPath)
        work.mkdirs()
        val out = mutableListOf<File>()
        ZipFile(zipFile).use { zf ->
            s.entries.forEachIndexed { i, name ->
                val e = zf.getEntry(name) ?: throw FileNotFoundException(name)
                val f = File(work, "$i.apk")
                zf.getInputStream(e).use { ins -> f.outputStream().use { ins.copyTo(it) } }
                out.add(f)
            }
        }
        return out
    }

    private fun commitFiles(packageName: String, files: List<File>) {
        for (f in files) if (!f.isFile) throw FileNotFoundException(f.path)

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        params.setAppPackageName(packageName)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }

        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            files.forEachIndexed { i, file ->
                session.openWrite("$i.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
            }
            session.commit(resultSender(sessionId, packageName, "install"))
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
