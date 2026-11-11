package com.unspektrawesome.ui.onboarding

import android.content.Context

internal class GuideProgressStore(context: Context) {
    private val preferences = context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)

    fun load(name: String, revision: Int, steps: Int): Int {
        if (preferences.getInt("${name}_guide_revision", 0) != revision) {
            save(name, revision, 0)
            return 0
        }
        return preferences.getInt("${name}_guide_position", 0).coerceIn(0, steps)
    }

    fun save(name: String, revision: Int, step: Int) {
        preferences.edit().putInt("${name}_guide_revision", revision)
            .putInt("${name}_guide_position", step.coerceAtLeast(0)).apply()
    }
}
