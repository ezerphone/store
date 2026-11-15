package com.example.mystore

/**
 * An app that is not in the scanned folders: a single .apk, or a .zip that contains the app's APK file.
 * Give an exact [path], or a [fileName] to look for under [searchRoots] (found once, then remembered).
 */
data class ExtraSource(
    val path: String? = null,
    val fileName: String? = null,
    val searchRoots: List<String> = emptyList(),
    /** The folder (category) the app is shown in. null = the home screen. */
    val category: String? = null,
    /** Optional display name. By default the app's own name is used. */
    val label: String? = null
)

object Config {
    /**
     * התיקיות בקושחה שבהן נמצאים קובצי ה-APK וקובצי ה-zip. אפשר להוסיף כמה שרוצים.
     * כל תת-תיקייה בתוכן היא קטגוריה (רמה אחת).
     */
    val APK_DIRS = listOf("/system/etc/mystore/apks")

    /** קבצים בודדים מחוץ לתיקיות שלמעלה, למשל zip במחיצת product. */
    val EXTRA_SOURCES = listOf(
        ExtraSource(
            fileName = "Anker.zip",
            searchRoots = listOf("/product", "/product_a"),
            category = "אפליקציות לאוזניות"
        )
    )
}
