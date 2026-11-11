package com.unspektrawesome.gallery

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.unspektrawesome.MainActivity
import com.unspektrawesome.ui.camera.SavedCameraBrightness
import com.unspektrawesome.ui.camera.UnspektrawesomeTheme

class GalleryActivity : ComponentActivity() {
    private var showProcessing by mutableStateOf(false)
    private var storageAccessReviewed by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (openOnboardingIfNeeded()) return
        enableEdgeToEdge()
        showProcessing = intent.getBooleanExtra("processing", false)
        storageAccessReviewed = !com.unspektrawesome.ui.onboarding.StorageAccessSetup.needed(this)
        setContent { UnspektrawesomeTheme {
            SavedCameraBrightness()
            if (!storageAccessReviewed) com.unspektrawesome.ui.onboarding.StorageAccessOnboarding { storageAccessReviewed = true; startPanoramaExport() }
            else GalleryScreen(showProcessing)
        } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (openOnboardingIfNeeded()) return
        showProcessing = intent.getBooleanExtra("processing", false)
        startPanoramaExport()
    }

    override fun onResume() {
        super.onResume()
        startPanoramaExport()
    }

    private fun startPanoramaExport() {
        if (!storageAccessReviewed) return
        val id = intent.getStringExtra("panorama_export") ?: return
        intent.removeExtra("panorama_export")
        lifecycleScope.launch {
            val shot = withContext(Dispatchers.IO) { GalleryStore.get(this@GalleryActivity).shots.value.firstOrNull { it.id == id && it.panorama } }
            if (shot?.state in setOf(ExportState.WAITING, ExportState.FAILED, ExportState.SKIPPED)) lifecycle.withResumed {
                GalleryProcessing.start(this@GalleryActivity, listOf(id))
            }
        }
    }

    private fun openOnboardingIfNeeded(): Boolean {
        if (com.unspektrawesome.language.AppLanguage.state.value.choiceMade &&
            com.unspektrawesome.ui.onboarding.GuideProgressStore(this).load("introduction",
                com.unspektrawesome.ui.onboarding.ONBOARDING_REVISION,
                com.unspektrawesome.ui.onboarding.ONBOARDING_PAGES) == com.unspektrawesome.ui.onboarding.ONBOARDING_PAGES) return false
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        finish()
        return true
    }

    companion object {
        fun intent(context: Context, processing: Boolean = false, panoramaExport: String? = null) = Intent(context, GalleryActivity::class.java)
            .putExtra("processing", processing).putExtra("panorama_export", panoramaExport).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
