package com.unspektrawesome.gallery

import android.content.Context
import java.util.UUID

internal class RawCleanupPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("gallery", Context.MODE_PRIVATE)

    fun authorization(): String? = synchronized(lock) {
        preferences.getString("raw_cleanup_authorization", null)
    }

    fun setEnabled(enabled: Boolean): Boolean = synchronized(lock) {
        preferences.edit().putString("raw_cleanup_authorization", if (enabled) UUID.randomUUID().toString() else null).commit()
    }

    fun ifAuthorized(authorization: String?, action: () -> Unit): Boolean = synchronized(lock) {
        if (authorization == null || authorization != authorization()) return false
        action()
        true
    }

    companion object {
        private val lock = Any()
    }
}
