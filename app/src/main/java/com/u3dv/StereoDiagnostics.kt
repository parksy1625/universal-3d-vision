package com.u3dv

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Experimental sparse image correspondences, NOT calibrated stereo depth.
 * Images must first be geometrically rectified before disparity can become metric depth.
 */
object StereoDiagnostics {
    data class Sample(val timestampNs: Long, val gray: ByteArray)
    data class Result(val matches: Int, val meanShiftX: Float, val meanShiftY: Float, val meanCost: Float)

    fun compare(a: ByteArray, b: ByteArray, width: Int = 160, height: Int = 120): Result {
        var matched = 0
        var dxSum = 0f
        var dySum = 0f
        var costSum = 0f
        // Sparse patch search with local contrast and second-best rejection.
        for (y in 20 until height - 20 step 16) for (x in 20 until width - 20 step 16) {
            var minValue = 255
            var maxValue = 0
            for (oy in -3..3 step 2) for (ox in -3..3 step 2) {
                val v = a[(y + oy) * width + x + ox].toInt() and 255
                if (v < minValue) minValue = v
                if (v > maxValue) maxValue = v
            }
            if (maxValue - minValue < 24) continue
            var best = Int.MAX_VALUE
            var second = Int.MAX_VALUE
            var bestX = 0
            var bestY = 0
            for (cy in (y - 12)..(y + 12) step 2) for (cx in (x - 32)..(x + 32) step 2) {
                if (cx < 4 || cx >= width - 4 || cy < 4 || cy >= height - 4) continue
                var sad = 0
                for (oy in -3..3 step 2) for (ox in -3..3 step 2) {
                    val va = a[(y + oy) * width + x + ox].toInt() and 255
                    val vb = b[(cy + oy) * width + cx + ox].toInt() and 255
                    sad += abs(va - vb)
                }
                if (sad < best) {
                    second = best
                    best = sad
                    bestX = cx
                    bestY = cy
                } else if (sad < second) second = sad
            }
            // Heuristic only: wide-angle vs normal-angle requires geometric calibration.
            if (best < 400 && best * 100 < second * 92) {
                matched++
                dxSum += bestX - x
                dySum += bestY - y
                costSum += best / 16f
            }
        }
        return if (matched == 0) Result(0, 0f, 0f, 0f)
            else Result(matched, dxSum / matched, dySum / matched, costSum / matched)
    }
}
