package com.unspektrawesome.camera.exposure

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class AeEngine(val label: String) { ANDROID("Android"), SPEKTRA("Unspektra AE") }
enum class AeMeter(val label: String) { CENTER_WEIGHTED("Center weighted"), MEDIAN("Median") }
enum class AeMode(val isoLocked: Boolean, val shutterLocked: Boolean, val label: String) {
    AUTO(false, false, "Auto"), ISO_PRIORITY(true, false, "ISO priority"),
    SHUTTER_PRIORITY(false, true, "Shutter priority"), MANUAL(true, true, "Manual");

    fun toggleIso() = if (this == MANUAL) ISO_PRIORITY else from(!isoLocked, shutterLocked)
    fun toggleShutter() = if (this == MANUAL) SHUTTER_PRIORITY else from(isoLocked, !shutterLocked)

    companion object {
        fun from(iso: Boolean, shutter: Boolean) = entries.first { it.isoLocked == iso && it.shutterLocked == shutter }
    }
}

data class AeLimits(
    val minIso: Int? = null,
    val maxIso: Int? = null,
    val minShutterNs: Long? = null,
    val maxShutterNs: Long? = null,
) {
    fun resolve(device: ExposureBounds): ExposureBounds {
        val lowerIso = (minIso ?: device.minIso).coerceIn(device.minIso, device.maxIso)
        val upperIso = (maxIso ?: device.maxIso).coerceIn(lowerIso, device.maxIso)
        val lowerTime = (minShutterNs ?: device.minShutterNs).coerceIn(device.minShutterNs, device.maxShutterNs)
        val upperTime = (maxShutterNs ?: DEFAULT_MAX_SHUTTER_NS).coerceIn(lowerTime, device.maxShutterNs)
        return ExposureBounds(lowerIso, upperIso, lowerTime, upperTime)
    }

    companion object {
        private const val DEFAULT_MAX_SHUTTER_NS = 1_000_000_000L / 30
    }
}

data class ExposureBounds(val minIso: Int, val maxIso: Int, val minShutterNs: Long, val maxShutterNs: Long) {
    init { require(minIso > 0 && maxIso >= minIso && minShutterNs > 0 && maxShutterNs >= minShutterNs) }
    fun clamp(value: SensorExposure) = SensorExposure(value.iso.coerceIn(minIso, maxIso), value.timeNs.coerceIn(minShutterNs, maxShutterNs))
}

data class SensorExposure(val iso: Int, val timeNs: Long)

object AeBalance {
    const val MIN = -3f
    const val MAX = 3f
    fun normalize(value: Float): Float = if (value.isFinite()) value.coerceIn(MIN, MAX) else 0f

    fun preferredIsoEv(productEv: Double, baseIso: Int, focalLength35mm: Float?, stops: Float): Double {
        val focal = focalLength35mm?.takeIf { it.isFinite() && it > 0f }?.toDouble() ?: 50.0
        val baseIsoEv = log2(baseIso.toDouble())
        val reference = baseIsoEv + log2(1_000_000_000.0 / (2.0 * focal))
        // Below the lens's reference light level, share additional exposure between time and ISO.
        return baseIsoEv + maxOf(0.0, (productEv - reference) * .5) + normalize(stops)
    }
}

class SpektraExposureController {
    var mode = AeMode.AUTO
        private set
    var fixed = SensorExposure(1, 1)
        private set
    var generation = 0L
        private set
    var meteringRevision = 0L
        private set
    private var lastTimestamp = 0L
    private var requestedIsoEv = 0.0
    private var requestedTimeEv = 0.0
    private var lastRequest = SensorExposure(1, 1)
    val requestedExposure: SensorExposure get() = lastRequest
    private var filteredTargetEv: Double? = null
    private val recentTargets = ArrayDeque<Pair<Long, Double>>()
    private var fastMetering = false
    private var fastMeteringUntilNs: Long? = null
    var correctionEv = 0.0
        private set
    var limited = false
        private set

    fun reset(mode: AeMode, exposure: SensorExposure): Long {
        this.mode = mode
        fixed = exposure
        lastTimestamp = 0L
        requestedIsoEv = log2(exposure.iso.toDouble())
        requestedTimeEv = log2(exposure.timeNs.toDouble())
        lastRequest = exposure
        resetMetering()
        return ++generation
    }

    fun resetMetering() {
        invalidateMetering()
        filteredTargetEv = null
        recentTargets.clear()
        fastMetering = true
        fastMeteringUntilNs = null
        correctionEv = 0.0
        limited = false
    }

    fun invalidateMetering() {
        meteringRevision++
    }

    fun update(
        meteredEv: Double, actual: SensorExposure, timestampNs: Long, frameGeneration: Long,
        bounds: ExposureBounds, compensationEv: Double, balance: Float = 0f,
        baseIso: Int = bounds.minIso, focalLength35mm: Float? = null,
        frameRequestedIso: Int? = null,
        recording: Boolean = false,
    ): SensorExposure? {
        val frameIso = frameRequestedIso ?: actual.iso
        if (mode == AeMode.MANUAL || frameGeneration != generation || timestampNs <= lastTimestamp ||
            frameIso <= 0 || actual.timeNs <= 0 || !meteredEv.isFinite() ||
            !compensationEv.isFinite()) return null
        if (lastTimestamp > 0 && timestampNs - lastTimestamp < METER_INTERVAL_NS) return null
        val elapsed = if (lastTimestamp == 0L) 1.0 / 30.0 else (timestampNs - lastTimestamp) * 1e-9
        lastTimestamp = timestampNs
        if (fastMetering) {
            val deadline = fastMeteringUntilNs ?: (timestampNs + FAST_METERING_DURATION_NS).also { fastMeteringUntilNs = it }
            if (timestampNs >= deadline) fastMetering = false
        }
        val filterSeconds = if (fastMetering) FAST_TARGET_FILTER_SECONDS else SCENE_TARGET_FILTER_SECONDS
        val responseSeconds = if (fastMetering) FAST_RESPONSE_SECONDS else SCENE_RESPONSE_SECONDS
        val speed = if (fastMetering) FAST_SPEED_EV_PER_SECOND else SCENE_SPEED_EV_PER_SECOND
        val deadband = if (fastMetering) FAST_DEADBAND_EV else SCENE_DEADBAND_EV
        correctionEv = meteredEv + compensationEv
        val fixedIsoEv = log2(fixed.iso.coerceIn(bounds.minIso, bounds.maxIso).toDouble())
        val fixedTimeEv = log2(fixed.timeNs.coerceIn(bounds.minShutterNs, bounds.maxShutterNs).toDouble())
        val minIsoEv = if (mode.isoLocked) fixedIsoEv else log2(bounds.minIso.toDouble())
        val maxIsoEv = if (mode.isoLocked) fixedIsoEv else log2(bounds.maxIso.toDouble())
        val minTimeEv = if (mode.shutterLocked) fixedTimeEv else log2(bounds.minShutterNs.toDouble())
        val maxTimeEv = if (mode.shutterLocked) fixedTimeEv else log2(bounds.maxShutterNs.toDouble())
        requestedIsoEv = requestedIsoEv.coerceIn(minIsoEv, maxIsoEv)
        requestedTimeEv = requestedTimeEv.coerceIn(minTimeEv, maxTimeEv)

        // Keep ISO in the request scale: DCG results can report a different gain scale.
        // Use this frame's request, since newer requests may still be in flight.
        val measuredTarget = log2(frameIso.toDouble()) + log2(actual.timeNs.toDouble()) + correctionEv
        val reachableTarget = measuredTarget.coerceIn(minIsoEv + minTimeEv, maxIsoEv + maxTimeEv)
        limited = abs(measuredTarget - reachableTarget) > .08
        recentTargets.addLast(timestampNs to reachableTarget)
        while (recentTargets.first().first < timestampNs - SCENE_HISTORY_NS) recentTargets.removeFirst()
        val observedTarget = if (fastMetering) reachableTarget else {
            val values = recentTargets.map { it.second }.sorted()
            (values[(values.size - 1) / 2] + values[values.size / 2]) * .5
        }
        val target = filteredTargetEv?.let { previous ->
            previous + (observedTarget - previous) * (1.0 - exp(-elapsed / filterSeconds))
        } ?: observedTarget
        filteredTargetEv = target

        val current = requestedIsoEv + requestedTimeEv
        val error = target - current
        val maxStep = minOf(MAX_STEP_EV, elapsed * speed)
        val step = if (abs(error) < deadband) 0.0 else
            (error * (1.0 - exp(-elapsed / responseSeconds))).coerceIn(-maxStep, maxStep)

        // Bound both controls so moving toward a lower ISO cannot suddenly lengthen the shutter.
        val isoLow = maxOf(minIsoEv, requestedIsoEv - maxStep)
        val isoHigh = minOf(maxIsoEv, requestedIsoEv + maxStep)
        val timeLow = maxOf(minTimeEv, requestedTimeEv - maxStep)
        val timeHigh = minOf(maxTimeEv, requestedTimeEv + maxStep)
        val product = (current + step).coerceIn(isoLow + timeLow, isoHigh + timeHigh)
        val feasibleIsoLow = maxOf(isoLow, product - timeHigh)
        val feasibleIsoHigh = maxOf(feasibleIsoLow, minOf(isoHigh, product - timeLow))
        val previousIsoEv = requestedIsoEv
        val previousTimeEv = requestedTimeEv
        requestedIsoEv = AeBalance.preferredIsoEv(product, baseIso, focalLength35mm, balance)
            .coerceIn(feasibleIsoLow, feasibleIsoHigh)
        requestedTimeEv = (product - requestedIsoEv).coerceIn(minTimeEv, maxTimeEv)
        if (fastMetering && abs(error) < FAST_DEADBAND_EV &&
            abs(requestedIsoEv - previousIsoEv) < FAST_DEADBAND_EV &&
            abs(requestedTimeEv - previousTimeEv) < FAST_DEADBAND_EV) fastMetering = false
        val result = SensorExposure(
            2.0.pow(requestedIsoEv).roundToInt().coerceIn(bounds.minIso, bounds.maxIso),
            2.0.pow(requestedTimeEv).roundToLong().coerceIn(bounds.minShutterNs, bounds.maxShutterNs))
        if (result == lastRequest) return null
        if (recording && bounds.clamp(lastRequest) == lastRequest && abs(log2(result.iso.toDouble() / lastRequest.iso)) +
            abs(log2(result.timeNs.toDouble() / lastRequest.timeNs)) < RECORDING_REQUEST_DEADBAND_EV) return null
        lastRequest = result
        return result
    }

    companion object {
        const val METER_INTERVAL_NS = 16_000_000L
        private const val RECORDING_REQUEST_DEADBAND_EV = .01
        private const val MAX_STEP_EV = .20
        private const val FAST_METERING_DURATION_NS = 1_500_000_000L
        private const val FAST_SPEED_EV_PER_SECOND = 12.0
        private const val FAST_RESPONSE_SECONDS = .05
        private const val FAST_TARGET_FILTER_SECONDS = .025
        private const val FAST_DEADBAND_EV = .025
        private const val SCENE_HISTORY_NS = 300_000_000L
        private const val SCENE_SPEED_EV_PER_SECOND = 4.0
        private const val SCENE_RESPONSE_SECONDS = .10
        private const val SCENE_TARGET_FILTER_SECONDS = .10
        private const val SCENE_DEADBAND_EV = .04
    }
}
