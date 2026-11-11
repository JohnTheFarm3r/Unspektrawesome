package com.unspektrawesome.video

import kotlin.math.abs

/** Android's k1, k2, k3, p1, p2 model in intrinsic-normalized camera coordinates. */
internal class GyroLensDistortion private constructor(
    val intrinsics: DoubleArray, val coefficients: DoubleArray,
) {
    fun distortTo(u: Double, v: Double, result: DoubleArray) {
        val y = (v-intrinsics[5])/intrinsics[4]
        val x = (u-intrinsics[2]-intrinsics[1]*y)/intrinsics[0]
        val r2 = x*x+y*y
        val radial = 1+r2*(coefficients[0]+r2*(coefficients[1]+r2*coefficients[2]))
        val dx = x*radial+2*coefficients[3]*x*y+coefficients[4]*(r2+2*x*x)
        val dy = y*radial+2*coefficients[4]*x*y+coefficients[3]*(r2+2*y*y)
        result[0] = intrinsics[0]*dx+intrinsics[1]*dy+intrinsics[2]
        result[1] = intrinsics[4]*dy+intrinsics[5]
    }

    fun undistortTo(u: Double, v: Double, result: DoubleArray): Boolean {
        val observedY = (v-intrinsics[5])/intrinsics[4]
        val observedX = (u-intrinsics[2]-intrinsics[1]*observedY)/intrinsics[0]
        var x = observedX; var y = observedY
        val k1 = coefficients[0]; val k2 = coefficients[1]; val k3 = coefficients[2]
        val p1 = coefficients[3]; val p2 = coefficients[4]
        repeat(6) {
            val r2 = x*x+y*y
            val radial = 1+r2*(k1+r2*(k2+r2*k3))
            val derivative = k1+r2*(2*k2+3*r2*k3)
            val ex = x*radial+2*p1*x*y+p2*(r2+2*x*x)-observedX
            val ey = y*radial+2*p2*x*y+p1*(r2+2*y*y)-observedY
            val xx = radial+2*x*x*derivative+2*p1*y+6*p2*x
            val yy = radial+2*y*y*derivative+6*p1*y+2*p2*x
            val xy = 2*x*y*derivative+2*p1*x+2*p2*y
            val determinant = xx*yy-xy*xy
            if (!determinant.isFinite() || determinant <= 1e-8 || xx <= 0 || yy <= 0) return false
            x -= (yy*ex-xy*ey)/determinant
            y -= (xx*ey-xy*ex)/determinant
        }
        val idealU = intrinsics[0]*x+intrinsics[1]*y+intrinsics[2]
        val idealV = intrinsics[4]*y+intrinsics[5]
        distortTo(idealU, idealV, result)
        if (!result.all { it.isFinite() } || abs(result[0]-u) > 1e-7 || abs(result[1]-v) > 1e-7) return false
        result[0] = idealU; result[1] = idealV
        return true
    }

    companion object {
        fun create(intrinsics: DoubleArray, coefficients: DoubleArray, bounds: DoubleArray): GyroLensDistortion? {
            if (intrinsics.size != 9 || coefficients.size != 5 || bounds.size != 4 ||
                !(intrinsics+coefficients+bounds).all { it.isFinite() } ||
                intrinsics[0] <= 0 || intrinsics[4] <= 0 || bounds[2] <= 0 || bounds[3] <= 0) return null
            val lens = GyroLensDistortion(intrinsics.copyOf(), coefficients.copyOf())
            val point = DoubleArray(2)
            // Reject calibrations that fold or cannot be inverted over the usable sensor.
            for (row in 0..16) for (column in 0..16)
                if (!lens.undistortTo(bounds[0]+bounds[2]*column/16, bounds[1]+bounds[3]*row/16, point)) return null
            return lens
        }
    }
}
