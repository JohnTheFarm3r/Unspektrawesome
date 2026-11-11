package com.unspektrawesome.gallery

import android.content.Context
import com.unspektrawesome.diagnostics.InternalLogRecorder
import com.unspektrawesome.preview.PersistedCapture

internal class RawExportCleanup(context: Context) {
    private val preferences = RawCleanupPreferences(context)
    private val store = GalleryStore.get(context)
    private val validator = JpegExportValidator(context.contentResolver, context.packageName)

    fun afterExport(id: String, authorization: String?) {
        if (authorization == null) return
        synchronized(PersistedCapture.exportLock) {
            val shot = store.shots.value.firstOrNull { it.id == id } ?: return
            if (shot.state != ExportState.EXPORTED || !shot.hasRaw || shot.jpegUri == null) return
            try {
                val verified = validator.verify(shot.jpegUri, shot.width, shot.height)
                preferences.ifAuthorized(authorization) {
                    check(validator.unchanged(verified)) { "JPEG changed before RAW cleanup" }
                    store.deleteVerifiedRaw(id, verified.uri)
                }
            } catch (error: Exception) {
                store.keepRawAfterExport(id, "RAW kept: ${error.message ?: "export could not be verified"}. Review the JPEG before removing the original yourself.")
                InternalLogRecorder.warning("RawCleanup", "retained id=$id reason=${error.message}")
            } catch (error: OutOfMemoryError) {
                store.keepRawAfterExport(id, "RAW kept: not enough memory to verify the JPEG.")
            }
        }
    }
}
