package com.unspektrawesome.preview

enum class LensSessionRecoveryPhase { WAITING, CONFIGURING, FAILED }

data class LensSessionRecoveryStatus(
    val lensName: String,
    val phase: LensSessionRecoveryPhase,
    val retriesStarted: Int = 0,
    val manual: Boolean = false,
    val retryInSeconds: Int = 5,
    val detail: String? = null,
) {
    val busy: Boolean get() = phase != LensSessionRecoveryPhase.FAILED
}

internal class LensSessionRecovery {
    private var retriesStarted = 0
    private var manual = false
    var status: LensSessionRecoveryStatus? = null
        private set

    fun failed(lensName: String, detail: String? = null, allowRetry: Boolean = true): Boolean {
        val current = status ?: LensSessionRecoveryStatus(lensName, LensSessionRecoveryPhase.CONFIGURING,
            retriesStarted = retriesStarted, manual = manual)
        if (current.phase != LensSessionRecoveryPhase.CONFIGURING) return false
        val retry = allowRetry && !current.manual && current.retriesStarted < AUTOMATIC_RETRIES
        status = current.copy(phase = if (retry) LensSessionRecoveryPhase.WAITING else LensSessionRecoveryPhase.FAILED,
            retryInSeconds = 5, detail = detail)
        return retry
    }

    fun beginAutomaticRetry(): Boolean {
        val current = status ?: return false
        if (current.phase != LensSessionRecoveryPhase.WAITING) return false
        retriesStarted++
        status = current.copy(phase = LensSessionRecoveryPhase.CONFIGURING, retriesStarted = retriesStarted)
        return true
    }

    fun beginManualRetry(): Boolean {
        val current = status ?: return false
        if (current.busy) return false
        manual = true
        status = current.copy(phase = LensSessionRecoveryPhase.CONFIGURING, manual = true)
        return true
    }

    fun cancel() {
        status = status?.copy(phase = LensSessionRecoveryPhase.FAILED)
    }

    fun updateCountdown(seconds: Int) {
        val current = status ?: return
        if (current.phase == LensSessionRecoveryPhase.WAITING) {
            status = current.copy(retryInSeconds = seconds.coerceIn(1, 5))
        }
    }

    fun previewReady() {
        if (status?.phase == LensSessionRecoveryPhase.CONFIGURING) status = null
    }

    fun reset() {
        status = null
        retriesStarted = 0
        manual = false
    }

    companion object {
        const val AUTOMATIC_RETRIES = 3
        const val RETRY_DELAY_MS = 5_000L
    }
}
