package com.example.mystore

import android.content.pm.PackageInstaller
import java.io.FileNotFoundException
import java.io.IOException

/** Hebrew, user-facing messages for install / uninstall failures. */
object Errors {
    const val NO_SPACE = "אין מספיק מקום פנוי בטלפון. מחק משהו ונסה שוב."
    const val APP_PROBLEM =
        "ההתקנה נכשלה בגלל בעיה באפליקציה עצמה. ייתכן שהיא לא מתאימה למכשיר, שהקובץ פגום, או שקיימת גרסה אחרת שמתנגשת איתה."
    const val FILE_MISSING = "קובץ ההתקנה לא נמצא. רענן את הרשימה ונסה שוב."
    const val INSTALL_FAILED = "ההתקנה נכשלה. נסה שוב."
    const val UNINSTALL_FAILED = "ההסרה נכשלה. נסה שוב."
    const val NO_LAUNCHER = "לאפליקציה הזו אין מסך הפעלה, אי אפשר לפתוח אותה."

    /** [op] is "install" or "uninstall". */
    fun forStatus(status: Int, op: String): String {
        if (op == "uninstall") {
            return UNINSTALL_FAILED
        }
        return when (status) {
            PackageInstaller.STATUS_FAILURE_STORAGE -> NO_SPACE
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_INVALID -> APP_PROBLEM
            else -> INSTALL_FAILED
        }
    }

    fun forException(e: Throwable, op: String): String {
        val msg = e.message ?: ""
        val uninstall = op == "uninstall"
        return when {
            e is FileNotFoundException -> FILE_MISSING
            e is IOException && (msg.contains("ENOSPC") || msg.contains("No space left", ignoreCase = true)) -> NO_SPACE
            uninstall -> UNINSTALL_FAILED
            else -> INSTALL_FAILED
        }
    }
}
