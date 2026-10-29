package com.example.mystore

import android.content.Context
import android.content.pm.PackageInfo
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import java.io.File

data class AppEntry(
    val label: String,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val sizeBytes: Long,
    val apkPath: String,
    val icon: Drawable
)

object ApkScanner {
    private const val TAG = "MyStore"

    fun scan(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val files = Config.APK_DIRS.flatMap { dir ->
            File(dir).listFiles { f -> f.isFile && f.name.endsWith(".apk", ignoreCase = true) }
                ?.toList() ?: run {
                Log.w(TAG, "Cannot list $dir")
                emptyList()
            }
        }

        val entries = files.mapNotNull { f ->
            try {
                val info = pm.getPackageArchiveInfo(f.absolutePath, 0) ?: return@mapNotNull null
                val ai = info.applicationInfo ?: return@mapNotNull null
                // Needed so that loadLabel()/loadIcon() read resources from the APK file.
                ai.sourceDir = f.absolutePath
                ai.publicSourceDir = f.absolutePath
                AppEntry(
                    label = ai.loadLabel(pm).toString(),
                    packageName = info.packageName,
                    versionCode = versionOf(info),
                    versionName = info.versionName ?: "",
                    sizeBytes = f.length(),
                    apkPath = f.absolutePath,
                    icon = ai.loadIcon(pm)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Bad APK ${f.name}: ${e.message}")
                null
            }
        }

        // If the same package appears twice, keep the newest version.
        return entries
            .groupBy { it.packageName }
            .map { (_, list) -> list.maxByOrNull { it.versionCode }!! }
            .sortedBy { it.label.lowercase() }
    }

    @Suppress("DEPRECATION")
    fun versionOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
}
