package com.unspektrawesome.camera

import com.unspektrawesome.vulkan.params.WhiteBalanceGains
import kotlin.math.abs

/** Camera2 reference-sensor calibration, with white balance applied once in camera RGB. */
object RawColorCalibration {
    private var cachedCalibration: ColorCalibration? = null
    private var cachedGains: WhiteBalanceGains? = null
    private var cachedMatrix: FloatArray? = null

    @Synchronized
    fun afterWhiteBalance(calibration: ColorCalibration, gains: WhiteBalanceGains): FloatArray {
        if (cachedCalibration === calibration && cachedGains == gains) return cachedMatrix!!.clone()
        return calculate(calibration, gains).also {
            cachedCalibration = calibration
            cachedGains = gains
            cachedMatrix = it.clone()
        }
    }

    fun illuminantWeight(calibration: ColorCalibration, gains: WhiteBalanceGains): Double {
        val temperature = WhiteBalanceTemperature.fromCamera2Gains(gains.red, gains.green, gains.blue, calibration)?.toDouble()
        val t1 = WhiteBalanceTemperature.illuminantTemperature(calibration.illuminant1)
        val t2 = WhiteBalanceTemperature.illuminantTemperature(calibration.illuminant2)
        return if (temperature != null && t1 != null && t2 != null && t1 != t2) {
            ((1.0 / temperature - 1.0 / t2) / (1.0 / t1 - 1.0 / t2)).coerceIn(0.0, 1.0)
        } else 1.0
    }

    private fun calculate(calibration: ColorCalibration, gains: WhiteBalanceGains): FloatArray {
        val neutral = doubleArrayOf(1.0 / gains.red, 1.0 / gains.green, 1.0 / gains.blue)
        val weight = illuminantWeight(calibration, gains)
        val cc = interpolate(calibration.calibrationMatrix1(), calibration.calibrationMatrix2(), weight) ?: identity()
        val forward = interpolate(calibration.forwardMatrix1(), calibration.forwardMatrix2(), weight)
        val cameraToXyz = if (forward != null) {
            val inverseCalibration = inverse(cc)
            val referenceNeutral = map(inverseCalibration, neutral)
            require(referenceNeutral.all { it.isFinite() && it > 0.0 }) { "Calibration produces an invalid reference neutral" }
            val white = map(forward, doubleArrayOf(1.0, 1.0, 1.0))
            require(white.all { it.isFinite() && it > 0.0 }) { "Forward matrix has an invalid white point" }
            val normalizedForward = multiply(diagonal(DoubleArray(3) { D50[it] / white[it] }), forward)
            multiply(normalizedForward, multiply(diagonal(DoubleArray(3) { 1.0 / referenceNeutral[it] }), inverseCalibration))
        } else {
            val color = interpolate(
                WhiteBalanceTemperature.calibratedMatrix(calibration.colorMatrix1(), calibration.calibrationMatrix1()),
                WhiteBalanceTemperature.calibratedMatrix(calibration.colorMatrix2(), calibration.calibrationMatrix2()),
                weight,
            )
                ?: throw IllegalArgumentException("Sensor metadata has no usable color transform")
            val cameraToIlluminantXyz = inverse(color)
            val white = map(cameraToIlluminantXyz, neutral)
            require(white.all { it.isFinite() && it > 0.0 }) { "Color matrix produces an invalid white point" }
            val sourceWhite = DoubleArray(3) { white[it] / white[1] }
            val sourceCone = map(BRADFORD, sourceWhite)
            val targetCone = map(BRADFORD, D50)
            val adaptation = multiply(inverse(BRADFORD), multiply(diagonal(DoubleArray(3) { targetCone[it] / sourceCone[it] }), BRADFORD))
            multiply(adaptation, cameraToIlluminantXyz).map { it / white[1] }.toDoubleArray()
        }
        val working = multiply(XYZ_D50_TO_LINEAR_SRGB, multiply(cameraToXyz, diagonal(neutral)))
        require(working.all(Double::isFinite)) { "Camera color transform must be finite" }
        return working.map(Double::toFloat).toFloatArray()
    }

    private fun interpolate(first: FloatArray?, second: FloatArray?, weight: Double): DoubleArray? {
        if (first == null && second == null) return null
        val a = first ?: second!!
        val b = second ?: first!!
        require(a.all(Float::isFinite) && b.all(Float::isFinite)) { "Color matrices must be finite" }
        return DoubleArray(9) { a[it] * weight + b[it] * (1.0 - weight) }
    }

    private fun identity() = diagonal(doubleArrayOf(1.0, 1.0, 1.0))
    private fun diagonal(value: DoubleArray) = DoubleArray(9) { if (it / 3 == it % 3) value[it / 3] else 0.0 }
    private fun map(matrix: DoubleArray, value: DoubleArray) = DoubleArray(3) { row -> (0..2).sumOf { matrix[row * 3 + it] * value[it] } }
    private fun multiply(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { index -> (0..2).sumOf { a[index / 3 * 3 + it] * b[it * 3 + index % 3] } }

    private fun inverse(m: DoubleArray): DoubleArray {
        val determinant = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
        require(determinant.isFinite() && abs(determinant) > 1e-10) { "Camera color matrix is singular" }
        return doubleArrayOf(
            m[4] * m[8] - m[5] * m[7], m[2] * m[7] - m[1] * m[8], m[1] * m[5] - m[2] * m[4],
            m[5] * m[6] - m[3] * m[8], m[0] * m[8] - m[2] * m[6], m[2] * m[3] - m[0] * m[5],
            m[3] * m[7] - m[4] * m[6], m[1] * m[6] - m[0] * m[7], m[0] * m[4] - m[1] * m[3],
        ).map { it / determinant }.toDoubleArray()
    }

    private val D50 = doubleArrayOf(0.3457 / 0.3585, 1.0, (1.0 - 0.3457 - 0.3585) / 0.3585)
    private val BRADFORD = doubleArrayOf(0.8951, 0.2664, -0.1614, -0.7502, 1.7135, 0.0367, 0.0389, -0.0685, 1.0296)
    private val XYZ_D50_TO_LINEAR_SRGB = doubleArrayOf(
        3.1341359, -1.6173863, -0.4906619,
        -0.9787955, 1.9162546, 0.0334427,
        0.0719554, -0.2289768, 1.4053860,
    )
}
