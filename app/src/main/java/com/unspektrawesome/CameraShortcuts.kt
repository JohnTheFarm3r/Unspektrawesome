package com.unspektrawesome

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import com.unspektrawesome.language.AppLanguage

internal const val ACTION_PHOTO_MODE = "com.unspektrawesome.action.PHOTO_MODE"
internal const val ACTION_VIDEO_MODE = "com.unspektrawesome.action.VIDEO_MODE"

internal fun installCameraShortcuts(context: Context) {
    val shortcuts = listOf(
        Triple("photo_mode", "Photo Mode", ACTION_PHOTO_MODE),
        Triple("video_mode", "Video Mode", ACTION_VIDEO_MODE),
    ).mapIndexed { rank, (id, label, action) ->
        ShortcutInfo.Builder(context, id)
            .setShortLabel(AppLanguage.text(label))
            .setRank(rank)
            .setIcon(Icon.createWithResource(context, if (id == "photo_mode") R.drawable.ic_shortcut_photo else R.drawable.ic_shortcut_video))
            .setIntent(Intent(context, MainActivity::class.java).setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            .build()
    }
    runCatching {
        context.getSystemService(ShortcutManager::class.java).apply {
            removeDynamicShortcuts(listOf("reset_language"))
            disableShortcuts(listOf("reset_language"))
            dynamicShortcuts = shortcuts
        }
    }
}

internal fun Intent.requestedVideoMode(): Boolean? = when (action) {
    ACTION_PHOTO_MODE -> false
    ACTION_VIDEO_MODE -> true
    else -> null
}
