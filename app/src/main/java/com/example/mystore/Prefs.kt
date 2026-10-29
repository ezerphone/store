package com.example.mystore

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/** In-memory lock state; reset whenever the store leaves the screen. */
object AppState {
    @Volatile
    var unlocked = false
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("mystore_prefs", Context.MODE_PRIVATE)

    /** 0 = automatic (follow system), 1 = light, 2 = dark */
    var displayMode: Int
        get() = sp.getInt("display", 0)
        set(v) = sp.edit().putInt("display", v).apply()

    var themeIndex: Int
        get() = sp.getInt("theme", 0).coerceIn(0, Themes.accents.size - 1)
        set(v) = sp.edit().putInt("theme", v).apply()

    var askedNotifications: Boolean
        get() = sp.getBoolean("asked_notif", false)
        set(v) = sp.edit().putBoolean("asked_notif", v).apply()

    /** 0 = grid, 1 = list */
    var layoutMode: Int
        get() = sp.getInt("layout", 0).coerceIn(0, 1)
        set(v) = sp.edit().putInt("layout", v).apply()

    /** 0 = small, 1 = medium, 2 = large */
    var iconSize: Int
        get() = sp.getInt("icon_size", 1).coerceIn(0, 2)
        set(v) = sp.edit().putInt("icon_size", v).apply()

    var animations: Boolean
        get() = sp.getBoolean("anim", true)
        set(v) = sp.edit().putBoolean("anim", v).apply()

    var pinned: Set<String>
        get() = HashSet<String>(sp.getStringSet("pinned", emptySet<String>()) ?: emptySet<String>())
        set(v) = sp.edit().putStringSet("pinned", HashSet<String>(v)).apply()

    var hidden: Set<String>
        get() = HashSet<String>(sp.getStringSet("hidden", emptySet<String>()) ?: emptySet<String>())
        set(v) = sp.edit().putStringSet("hidden", HashSet<String>(v)).apply()

    /** Package names in the order the user arranged them . */
    var manualOrder: List<String>
        get() = (sp.getString("manual", "") ?: "").split("\n").filter { it.isNotEmpty() }
        set(v) = sp.edit().putString("manual", v.joinToString("\n")).apply()

    val lockEnabled: Boolean
        get() = sp.getString("pin_hash", null) != null

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        sp.edit().putString("pin_salt", salt).putString("pin_hash", hash(salt, pin)).apply()
    }

    fun checkPin(pin: String): Boolean {
        val salt = sp.getString("pin_salt", null) ?: return false
        val stored = sp.getString("pin_hash", null) ?: return false
        return hash(salt, pin) == stored
    }

    fun clearPin() {
        sp.edit().remove("pin_salt").remove("pin_hash").apply()
    }

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest((salt + pin).toByteArray())
            .joinToString("") { "%02x".format(it) }
}
