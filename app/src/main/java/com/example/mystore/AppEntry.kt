package com.example.mystore

import android.content.Context
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Where the APK file(s) of an app come from. */
sealed class AppSource {
    /** A plain .apk file. */
    data class Apk(val path: String) : AppSource()

    /** Entries (APK files) inside a .zip. They are extracted only when the app is installed. */
    data class Zip(val zipPath: String, val entries: List<String>) : AppSource()
}

data class AppEntry(
    val label: String,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val sizeBytes: Long,
    /** The folder the app is shown in (name of the sub-folder). null = home screen. */
    val category: String?,
    val source: AppSource,
    val icon: Drawable
)

object ApkScanner {
    private const val TAG = "MyStore"
    private const val MAX_ZIP_APKS = 32
    private const val MAX_ZIP_BYTES = 1_500_000_000L
    private const val SEARCH_DEPTH = 6

    private class Src(val file: File, val category: String?, val label: String?)

    fun scan(context: Context): List<AppEntry> {
        File(context.cacheDir, "zipwork").deleteRecursively()

        val usedKeys = mutableSetOf<String>()
        val entries = mutableListOf<AppEntry>()
        for (src in collectSources(context)) {
            try {
                if (isZip(src.file)) {
                    entries.addAll(loadZip(context, src, usedKeys))
                } else {
                    loadApk(context, src)?.let { entries.add(it) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Bad file ${src.file.name}: ${e.message}")
            }
        }
        cleanupMeta(context, usedKeys)

        // If the same package appears twice, keep the newest version.
        return entries
            .groupBy { it.packageName }
            .map { (_, list) -> list.maxByOrNull { it.versionCode }!! }
            .sortedBy { it.label.lowercase() }
    }

    // ------------------------------------------------------------------ finding files

    private fun isZip(f: File) = f.name.endsWith(".zip", ignoreCase = true)
    private fun isPackage(f: File) = f.isFile && (f.name.endsWith(".apk", ignoreCase = true) || isZip(f))

    private fun collectSources(context: Context): List<Src> {
        val out = mutableListOf<Src>()
        for (dir in Config.APK_DIRS) {
            val children = File(dir).listFiles()
            if (children == null) {
                Log.w(TAG, "Cannot list $dir")
                continue
            }
            for (c in children) {
                if (isPackage(c)) {
                    out.add(Src(c, null, null))
                } else if (c.isDirectory && !c.name.startsWith(".")) {
                    c.listFiles()?.filter { isPackage(it) }?.forEach { out.add(Src(it, c.name, null)) }
                }
            }
        }
        for (x in Config.EXTRA_SOURCES) {
            val f = resolve(context, x) ?: continue
            out.add(Src(f, x.category, x.label))
        }
        return out
    }

    /** The exact path, or a search by file name (the result is remembered so the search runs once). */
    private fun resolve(context: Context, x: ExtraSource): File? {
        x.path?.let { p ->
            val f = File(p)
            if (f.isFile) return f
            Log.w(TAG, "Not found: $p")
            return null
        }
        val name = x.fileName ?: return null
        val sp = context.getSharedPreferences("mystore_scan", Context.MODE_PRIVATE)
        val key = "found:$name"
        sp.getString(key, null)?.let { saved ->
            val f = File(saved)
            if (f.isFile) return f
        }
        for (root in x.searchRoots) {
            val found = findByName(File(root), name, SEARCH_DEPTH)
            if (found != null) {
                sp.edit().putString(key, found.absolutePath).apply()
                return found
            }
        }
        Log.w(TAG, "Not found by name: $name")
        return null
    }

    private fun findByName(dir: File, name: String, depth: Int): File? {
        val children = dir.listFiles() ?: return null
        for (c in children) {
            if (c.isFile && c.name.equals(name, ignoreCase = true)) return c
        }
        if (depth <= 0) return null
        for (c in children) {
            if (c.isDirectory && !Files.isSymbolicLink(c.toPath())) {
                findByName(c, name, depth - 1)?.let { return it }
            }
        }
        return null
    }

    // ------------------------------------------------------------------ plain APK

    private fun loadApk(context: Context, src: Src): AppEntry? {
        val pm = context.packageManager
        val f = src.file
        val info = pm.getPackageArchiveInfo(f.absolutePath, 0) ?: return null
        val ai = info.applicationInfo ?: return null
        // Needed so that loadLabel()/loadIcon() read resources from the APK file.
        ai.sourceDir = f.absolutePath
        ai.publicSourceDir = f.absolutePath
        return AppEntry(
            label = src.label ?: ai.loadLabel(pm).toString(),
            packageName = info.packageName,
            versionCode = versionOf(info),
            versionName = info.versionName ?: "",
            sizeBytes = f.length(),
            category = src.category,
            source = AppSource.Apk(f.absolutePath),
            icon = ai.loadIcon(pm)
        )
    }

    // ------------------------------------------------------------------ zip

    /**
     * Reading the name and icon needs the APK, so a zip is opened once. The result (name, icon, version)
     * is saved in a small file keyed by the zip's path, size and date, and reused on every refresh.
     * It is read again only if the zip changes. The extracted APK is deleted right away.
     */
    private fun loadZip(context: Context, src: Src, used: MutableSet<String>): List<AppEntry> {
        val zip = src.file
        val key = md5("${zip.absolutePath}|${zip.length()}|${zip.lastModified()}")
        used.add(key)
        val dir = File(context.filesDir, "zipmeta/$key")
        val metaFile = File(dir, "meta.json")
        if (!metaFile.isFile) buildMeta(context, zip, dir)
        if (!metaFile.isFile) return emptyList()

        val arr = JSONObject(metaFile.readText()).getJSONArray("apps")
        val result = mutableListOf<AppEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val bmp = BitmapFactory.decodeFile(File(dir, o.getString("icon")).absolutePath) ?: continue
            val names = o.getJSONArray("entries")
            result.add(
                AppEntry(
                    label = src.label ?: o.getString("label"),
                    packageName = o.getString("pkg"),
                    versionCode = o.getLong("vc"),
                    versionName = o.optString("vn", ""),
                    sizeBytes = o.getLong("size"),
                    category = src.category,
                    source = AppSource.Zip(zip.absolutePath, (0 until names.length()).map { names.getString(it) }),
                    icon = BitmapDrawable(context.resources, bmp)
                )
            )
        }
        return result
    }

    private class Parsed(val name: String, val file: File, val info: PackageInfo)

    private fun buildMeta(context: Context, zip: File, dir: File) {
        val pm = context.packageManager
        val work = File(context.cacheDir, "zipwork/${dir.name}")
        work.deleteRecursively()
        work.mkdirs()
        try {
            ZipFile(zip).use { zf ->
                val names = zf.entries().toList()
                    .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                    .map { it.name }
                val declared = names.sumOf { maxOf(0L, zf.getEntry(it).size) }
                if (names.isEmpty() || names.size > MAX_ZIP_APKS || declared > MAX_ZIP_BYTES) {
                    Log.w(TAG, "Unsupported zip ${zip.name}: ${names.size} APKs, $declared bytes")
                    writeMeta(dir, JSONArray())
                    return
                }

                // Extract with generated names only (never the names from inside the zip).
                val parsed = mutableListOf<Parsed>()
                var total = 0L
                names.forEachIndexed { i, n ->
                    val out = File(work, "$i.apk")
                    zf.getInputStream(zf.getEntry(n)).use { ins -> out.outputStream().use { ins.copyTo(it) } }
                    total += out.length()
                    val info = pm.getPackageArchiveInfo(out.absolutePath, 0)
                    if (info?.applicationInfo != null) parsed.add(Parsed(n, out, info))
                }

                val groups = parsed.groupBy { it.info.packageName }
                val apps = JSONArray()
                dir.mkdirs()
                var idx = 0
                for ((pkg, list) in groups) {
                    val base = list.firstOrNull { File(it.name).name.equals("base.apk", ignoreCase = true) }
                        ?: list.maxByOrNull { it.file.length() }!!
                    val ai = base.info.applicationInfo!!
                    ai.sourceDir = base.file.absolutePath
                    ai.publicSourceDir = base.file.absolutePath
                    val label = ai.loadLabel(pm).toString()
                    val iconName = "icon$idx.png"
                    File(dir, iconName).outputStream().use { toBitmap(ai.loadIcon(pm)).compress(Bitmap.CompressFormat.PNG, 100, it) }

                    // One app in the zip: install every APK in it (they may be split APKs).
                    val entryNames = if (groups.size == 1) names else list.map { it.name }
                    val size = if (groups.size == 1) total else list.sumOf { it.file.length() }
                    apps.put(
                        JSONObject()
                            .put("label", label)
                            .put("pkg", pkg)
                            .put("vc", versionOf(base.info))
                            .put("vn", base.info.versionName ?: "")
                            .put("size", size)
                            .put("icon", iconName)
                            .put("entries", JSONArray(entryNames))
                    )
                    idx++
                }
                writeMeta(dir, apps)
            }
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        } finally {
            work.deleteRecursively()
        }
    }

    /** meta.json is written last, so a half-built folder is never used. */
    private fun writeMeta(dir: File, apps: JSONArray) {
        dir.mkdirs()
        File(dir, "meta.json").writeText(JSONObject().put("apps", apps).toString())
    }

    private fun cleanupMeta(context: Context, usedKeys: Set<String>) {
        File(context.filesDir, "zipmeta").listFiles()?.forEach {
            if (it.name !in usedKeys) it.deleteRecursively()
        }
    }

    private fun toBitmap(d: Drawable, size: Int = 192): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        return bmp
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    @Suppress("DEPRECATION")
    fun versionOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
}
