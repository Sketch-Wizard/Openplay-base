package org.openplay.player

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Private folder: files live in app-private storage (invisible to other apps), gated by a PIN. */
object Vault {
    private fun prefs(c: Context) = c.getSharedPreferences("vault", Context.MODE_PRIVATE)
    private fun hash(pin: String) = MessageDigest.getInstance("SHA-256")
        .digest("openplay:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    fun hasPin(c: Context) = prefs(c).contains("pin")
    fun setPin(c: Context, pin: String) = prefs(c).edit().putString("pin", hash(pin)).apply()
    fun check(c: Context, pin: String) = prefs(c).getString("pin", null) == hash(pin)
    private fun dir(c: Context) = File(c.filesDir, "vault").apply { mkdirs() }
    fun list(c: Context): List<File> = dir(c).listFiles()?.sortedBy { it.name } ?: emptyList()

    fun add(c: Context, v: Video): Boolean = try {
        c.contentResolver.openInputStream(v.uri)?.use { i ->
            File(dir(c), v.title).outputStream().use { o -> i.copyTo(o) }
        } != null
    } catch (e: Exception) { false }
}
