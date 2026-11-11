package com.unspektrawesome.video

import kotlin.math.*

/** Causal counterpart of the offline stabilizer's quaternion integration and crop limiting. */
internal class GyroStabilizer(private val settings: LiveStabilizationSettings,
    private val historyNs: Long = 2_000_000_000L) {
    private val maximumSamples = maxOf(2048L, historyNs / 1_000_000 + 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    internal data class Quaternion(val w: Double = 1.0, val x: Double = 0.0,
        val y: Double = 0.0, val z: Double = 0.0) {
        operator fun times(b: Quaternion) = Quaternion(w*b.w-x*b.x-y*b.y-z*b.z,
            w*b.x+x*b.w+y*b.z-z*b.y, w*b.y-x*b.z+y*b.w+z*b.x, w*b.z+x*b.y-y*b.x+z*b.w)
        fun inverse() = Quaternion(w, -x, -y, -z)
        fun rotationVector(): DoubleArray {
            val sign = if (w < 0) -1.0 else 1.0
            val length = sqrt(x*x+y*y+z*z)
            val scale = if (length < 1e-12) 2.0 else 2*atan2(length, abs(w))/length
            return doubleArrayOf(x*scale*sign, y*scale*sign, z*scale*sign)
        }
        fun normalized(): Quaternion {
            val n = sqrt(w*w+x*x+y*y+z*z)
            return Quaternion(w/n, x/n, y/n, z/n)
        }
        fun slerp(other: Quaternion, t: Double): Quaternion {
            var b = other
            var d = w*b.w+x*b.x+y*b.y+z*b.z
            if (d < 0) { b = Quaternion(-b.w, -b.x, -b.y, -b.z); d = -d }
            var u = 1-t; var v = t
            if (d < .9995) {
                val a = acos(d.coerceIn(-1.0, 1.0))
                u = sin((1-t)*a)/sin(a); v = sin(t*a)/sin(a)
            }
            return Quaternion(u*w+v*b.w, u*x+v*b.x, u*y+v*b.y, u*z+v*b.z).normalized()
        }
        fun matrix() = doubleArrayOf(1-2*(y*y+z*z), 2*(x*y-z*w), 2*(x*z+y*w),
            2*(x*y+z*w), 1-2*(x*x+z*z), 2*(y*z-x*w),
            2*(x*z-y*w), 2*(y*z+x*w), 1-2*(x*x+y*y))
        companion object {
            fun delta(x: Double, y: Double, z: Double): Quaternion {
                val a = sqrt(x*x+y*y+z*z)
                val k = if (a < 1e-12) .5 else sin(a*.5)/a
                return Quaternion(cos(a*.5), x*k, y*k, z*k).normalized()
            }
        }
    }

    data class Calibration(val cameraFromDevice: Quaternion, val intrinsics: DoubleArray,
        val rowStart: Double, val rowSpan: Double, val crop: DoubleArray, val bounds: DoubleArray,
        val distortion: GyroLensDistortion? = null) {
        init {
            require(intrinsics.size == 9 && intrinsics.all { it.isFinite() })
            require(intrinsics[0] > 0 && intrinsics[4] > 0 && intrinsics[8] == 1.0)
            require(rowStart.isFinite() && rowSpan.isFinite() && rowSpan > 0)
            require(crop.size == 4 && bounds.size == 4 && (crop + bounds).all { it.isFinite() })
        }
    }

    data class Correction(val rows: FloatArray, val rowStart: Float, val rowSpan: Float,
        val scale: Float, val timestampNs: Long, val distortion: GyroLensDistortion? = null) {
        fun map(x: Double, y: Double) = DoubleArray(2).also { mapTo(x, y, it) }

        fun mapTo(x: Double, y: Double, result: DoubleArray) {
            if (distortion != null && !distortion.undistortTo(x, y, result)) {
                result.fill(Double.NaN); return
            }
            val idealX = if (distortion != null) result[0] else x
            val idealY = if (distortion != null) result[1] else y
            var sourceY = y
            // The observed source row determines exposure time, not the output row.
            repeat(3) {
                val band = ((sourceY-rowStart)/rowSpan*(ROWS-1)).coerceIn(0.0, (ROWS-1).toDouble())
                val i = floor(band).toInt().coerceAtMost(ROWS-2)
                val fraction = band-i
                fun value(r: Int): Double {
                    val offset = i*12+r*4
                    fun at(o: Int) = rows[o]*idealX+rows[o+1]*idealY+rows[o+2]
                    return at(offset)*(1-fraction)+at(offset+12)*fraction
                }
                val z = value(2)
                result[0] = if (z > 1e-8) value(0)/z else Double.NaN
                result[1] = if (z > 1e-8) value(1)/z else Double.NaN
                distortion?.distortTo(result[0], result[1], result)
                sourceY = result[1]
                if (!sourceY.isFinite()) return
            }
        }
    }

    private data class Sample(val time: Long, val x: Double, val y: Double, val z: Double,
        val orientation: Quaternion)
    private data class FrameMotion(val generation: Long, val observed: List<Quaternion>, val path: List<Sample>?)
    private val samples = ArrayDeque<Sample>()
    private var generation = 0L
    private var smoothGeneration = -1L
    private var smoothTime = 0L
    private var smooth: Quaternion? = null
    private var previousCenter: Quaternion? = null
    private val panVelocity = DoubleArray(3)
    private val motionVelocity = DoubleArray(3)
    private val panSpeed = DoubleArray(3)

    private fun follow(center: Quaternion, previous: Quaternion, target: Quaternion, dt: Double,
        pose: Quaternion, margins: DoubleArray): Quaternion {
        fun cameraRotation(q: Quaternion) = (pose*q*pose.inverse()).rotationVector()
        val motion = cameraRotation(previous.inverse()*center)
        val lag = cameraRotation(center.inverse()*target)
        val alpha = 1-exp(-dt/PAN_DIRECTION_SECONDS)
        val followAlpha = 1-exp(-dt/PAN_FOLLOW_SECONDS)
        for (axis in 0..2) {
            val velocity = motion[axis]/dt
            panVelocity[axis] += (velocity-panVelocity[axis])*alpha
            motionVelocity[axis] += (abs(velocity)-motionVelocity[axis])*alpha
            val coherence = ((abs(panVelocity[axis])/max(motionVelocity[axis], 1e-9)-.4)/.4).coerceIn(0.0, 1.0)
            val pan = abs(panVelocity[axis])*coherence*coherence*(3-2*coherence)
            panSpeed[axis] += (pan-panSpeed[axis])*followAlpha
            val margin = margins[axis]
            val rate = 1/settings.smoothingSeconds.toDouble()+2*panSpeed[axis]/max(margin, 1e-6)
            val decay = exp(-dt*rate)
            // Integrate the measured motion over this gyro interval.
            lag[axis] = (lag[axis]+motion[axis])*decay-motion[axis]*(1-decay)/(dt*rate)
            // Integrate a soft crop spring, with negligible influence on small corrections.
            val pressure = abs(lag[axis])/max(margin, 1e-6)
            lag[axis] /= (1+4*CROP_SPRING_RATE*dt*pressure.pow(4)).pow(.25)
        }
        return (center*pose.inverse()*Quaternion.delta(lag[0], lag[1], lag[2])*pose).normalized()
    }

    private fun smoothedTarget(center: Quaternion, centerTime: Long, motion: FrameMotion,
        calibration: Calibration): Quaternion {
        val pose = calibration.cameraFromDevice
        val margins = angularMargins(calibration)
        val readout = DoubleArray(3)
        for (q in motion.observed) {
            val rotation = (pose*center.inverse()*q*pose.inverse()).rotationVector()
            for (axis in 0..2) readout[axis] = max(readout[axis], abs(rotation[axis]))
        }
        // Leave room for simultaneous axes and rolling shutter within the selected crop.
        for (axis in 0..2) margins[axis] = max(0.0,
            margins[axis]*CROP_RESERVE/max(settings.strength.toDouble(), 1e-6)-readout[axis])
        var previous = previousCenter!!
        var target = smooth!!
        var time = smoothTime
        fun advance(timestamp: Long, orientation: Quaternion) {
            target = follow(orientation, previous, target, (timestamp-time)*1e-9, pose, margins)
            previous = orientation
            time = timestamp
        }
        // Filter every measured interval before sampling the virtual camera at frame time.
        for (sample in motion.path!!) advance(sample.time, sample.orientation)
        advance(centerTime, center)
        return target
    }

    private fun angularMargins(calibration: Calibration): DoubleArray {
        val k = calibration.intrinsics
        val crop = calibration.crop
        val bounds = calibration.bounds
        val margins = DoubleArray(3) { PI }
        val lens = calibration.distortion
        val point = DoubleArray(2)
        val step = 1e-4
        for (x in doubleArrayOf(crop[0], crop[0]+crop[2])) for (y in doubleArrayOf(crop[1], crop[1]+crop[3])) {
            if (lens != null && !lens.undistortTo(x, y, point)) return DoubleArray(3)
            val rayY = ((if (lens == null) y else point[1])-k[5])/k[4]
            val rayX = ((if (lens == null) x else point[0])-k[2]-k[1]*rayY)/k[0]
            val marginX = max(0.0, min(x-bounds[0], bounds[0]+bounds[2]-x))
            val marginY = max(0.0, min(y-bounds[1], bounds[1]+bounds[3]-y))
            for (axis in 0..2) {
                // First-order camera rotation of the corner ray, including lens distortion.
                val rx = rayX+when (axis) { 1 -> step; 2 -> -rayY*step; else -> 0.0 }
                val ry = rayY+when (axis) { 0 -> -step; 2 -> rayX*step; else -> 0.0 }
                val rz = 1+when (axis) { 0 -> rayY*step; 1 -> -rayX*step; else -> 0.0 }
                val u = (k[0]*rx+k[1]*ry)/rz+k[2]
                val v = k[4]*ry/rz+k[5]
                if (lens == null) { point[0] = u; point[1] = v } else lens.distortTo(u, v, point)
                val dx = abs(point[0]-x)/step
                val dy = abs(point[1]-y)/step
                if (dx > 1e-9) margins[axis] = min(margins[axis], marginX/dx)
                if (dy > 1e-9) margins[axis] = min(margins[axis], marginY/dy)
            }
        }
        return margins
    }

    @Synchronized fun add(timestampNs: Long, x: Double, y: Double, z: Double) {
        if (timestampNs <= 0 || !x.isFinite() || !y.isFinite() || !z.isFinite()) {
            samples.clear(); generation++; return
        }
        val previous = samples.lastOrNull()
        if (previous != null && timestampNs <= previous.time) return
        val dt = previous?.let { (timestampNs-it.time)*1e-9 } ?: 0.0
        if (dt > .1) { samples.clear(); generation++ }
        val q = if (previous == null || dt > .1) Quaternion() else
            (previous.orientation * Quaternion.delta((x+previous.x)*.5*dt,
                (y+previous.y)*.5*dt, (z+previous.z)*.5*dt)).normalized()
        samples.addLast(Sample(timestampNs, x, y, z, q))
        while (samples.size > maximumSamples || samples.size > 2 && timestampNs-samples[1].time > historyNs)
            samples.removeFirst()
    }

    @Synchronized internal fun orientations(times: LongArray): Pair<Long, List<Quaternion>>? {
        if (samples.size < 2 || times.any { it < samples.first().time || it > samples.last().time }) return null
        return generation to times.map { time ->
            var low = 0; var high = samples.lastIndex
            while (high-low > 1) {
                val middle = (low+high)/2
                if (samples[middle].time <= time) low = middle else high = middle
            }
            val a = samples[low]; val b = samples[high]
            a.orientation.slerp(b.orientation, (time-a.time).toDouble()/(b.time-a.time))
        }
    }

    @Synchronized private fun frameMotion(times: LongArray): FrameMotion? {
        val (currentGeneration, observed) = orientations(times) ?: return null
        val centerTime = times[ROWS/2]
        val path = if (smoothGeneration == currentGeneration && smoothTime >= samples.first().time &&
            centerTime > smoothTime && centerTime-smoothTime <= 1_000_000_000L)
            samples.filter { it.time > smoothTime && it.time < centerTime } else null
        return FrameMotion(currentGeneration, observed, path)
    }

    fun correction(timestampNs: Long, exposureNs: Long, skewNs: Long, calibration: Calibration): Correction? {
        if (exposureNs <= 0 || exposureNs > 1_000_000_000L || skewNs !in 0..1_000_000_000L) return null
        val times = LongArray(ROWS) { timestampNs+exposureNs/2+skewNs*it/(ROWS-1) }
        val motion = frameMotion(times) ?: return null
        val (currentGeneration, observed) = motion
        val center = observed[ROWS/2]
        val centerTime = times[ROWS/2]
        if (smoothTime >= centerTime && currentGeneration == smoothGeneration) return null
        val dt = if (smoothTime > 0) (centerTime-smoothTime)*1e-9 else 0.0
        val reset = smoothGeneration != currentGeneration || smooth == null || dt > 1 || dt <= 0 || motion.path == null
        val target = if (reset) {
            panVelocity.fill(0.0)
            motionVelocity.fill(0.0)
            panSpeed.fill(0.0)
            center
        } else smoothedTarget(center, centerTime, motion, calibration)

        val k = calibration.intrinsics
        val inverseK = doubleArrayOf(1/k[0], -k[1]/(k[0]*k[4]), (k[1]*k[5]/k[4]-k[2])/k[0],
            0.0, 1/k[4], -k[5]/k[4], 0.0, 0.0, 1.0)
        fun make(scale: Double): Correction {
            val rows = FloatArray(ROWS*12)
            observed.forEachIndexed { index, q ->
                val desired = q.slerp(target, settings.strength*scale)
                val rotation = calibration.cameraFromDevice * q.inverse() * desired * calibration.cameraFromDevice.inverse()
                val h = multiply(multiply(k, rotation.matrix()), inverseK)
                for (r in 0..2) for (c in 0..2) rows[index*12+r*4+c] = h[r*3+c].toFloat()
            }
            return Correction(rows, calibration.rowStart.toFloat(), calibration.rowSpan.toFloat(),
                scale.toFloat(), timestampNs, calibration.distortion)
        }
        fun fits(correction: Correction): Boolean {
            val crop = calibration.crop; val bounds = calibration.bounds
            val p = DoubleArray(2)
            fun point(x: Double, y: Double): Boolean {
                correction.mapTo(x, y, p)
                return p[0].isFinite() && p[1].isFinite() && p[0] >= bounds[0]-1e-7 && p[1] >= bounds[1]-1e-7 &&
                    p[0] <= bounds[0]+bounds[2]+1e-7 && p[1] <= bounds[1]+bounds[3]+1e-7
            }
            // Check both sides of every readout band and the top and bottom edges.
            for (i in 0..128) {
                val t = i/128.0
                if (!point(crop[0], crop[1]+crop[3]*t) || !point(crop[0]+crop[2], crop[1]+crop[3]*t) ||
                    !point(crop[0]+crop[2]*t, crop[1]) || !point(crop[0]+crop[2]*t, crop[1]+crop[3])) return false
            }
            return true
        }
        var scale = 1.0
        var result = make(scale)
        if (!fits(result)) {
            var low = 0.0; var high = scale
            result = make(0.0)
            if (!fits(result)) return null
            repeat(12) {
                val middle = (low+high)*.5
                val candidate = make(middle)
                if (fits(candidate)) { low = middle; result = candidate } else high = middle
            }
            scale = low
        }
        // Move the virtual camera when the margin runs out so a pan cannot accumulate lag.
        smooth = center.slerp(target, scale)
        previousCenter = center
        smoothGeneration = currentGeneration
        smoothTime = centerTime
        return result
    }

    companion object {
        const val ROWS = 33
        private const val PAN_DIRECTION_SECONDS = .15
        private const val PAN_FOLLOW_SECONDS = .12
        private const val CROP_RESERVE = .75
        private const val CROP_SPRING_RATE = 15.0
        private fun multiply(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
            val r = i/3; val c = i%3
            a[r*3]*b[c]+a[r*3+1]*b[3+c]+a[r*3+2]*b[6+c]
        }
    }
}
