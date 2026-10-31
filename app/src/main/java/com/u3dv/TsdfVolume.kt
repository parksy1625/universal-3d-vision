package com.u3dv

import kotlin.math.*

/**
 * Bounded sparse TSDF. Observations update a short segment around each depth hit,
 * including free space in front of the surface. World coordinates are relative
 * to the first ARCore camera translation.
 *
 * Surface extraction uses marching tetrahedra (six tetrahedra per cube).
 * This is not classical Marching Cubes and does not guarantee watertight output.
 * All methods are called from the GL thread except polygonize(snapshot).
 */
class TsdfVolume(
    private val cell: Float = 0.04f,
    private val truncation: Float = 0.12f,
    private val maxCells: Int = 400000
) {
    private data class Cell(var sdf: Float, var weight: Float, var r: Float, var g: Float, var b: Float)
    data class Sample(val x: Int, val y: Int, val z: Int, val sdf: Float,
                      val weight: Float, val r: Float, val g: Float, val b: Float)
    private val cells = HashMap<Long, Cell>()
    private fun key(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and 0x1fffffL) shl 42) or
        ((y.toLong() and 0x1fffffL) shl 21) or (z.toLong() and 0x1fffffL)
    private fun index(v: Float) = floor(v / cell).toInt()
    val size: Int get() = cells.size

    fun integrate(camera: FloatArray, surface: FloatArray, rgb: FloatArray, confidence: Float) {
        val dx = surface[0] - camera[0]
        val dy = surface[1] - camera[1]
        val dz = surface[2] - camera[2]
        val length = sqrt(dx*dx + dy*dy + dz*dz)
        if (length < 0.25f || length > 4.5f) return
        val ux = dx / length; val uy = dy / length; val uz = dz / length
        // Sample both sides of the measured surface; avoid allocating cells for
        // the entire ray. Positive SDF is in front of the observed surface.
        for (step in -3..3) {
            val offset = step * cell
            val x = index(surface[0] + ux * offset)
            val y = index(surface[1] + uy * offset)
            val z = index(surface[2] + uz * offset)
            val k = key(x,y,z)
            val measured = (-offset / truncation).coerceIn(-1f, 1f)
            val w = confidence.coerceIn(0.1f, 1f)
            val old = cells[k]
            if (old == null) {
                if (cells.size >= maxCells) continue
                cells[k] = Cell(measured, w, rgb[0], rgb[1], rgb[2])
            } else {
                // Ignore contradictory readings when this cell already has
                // substantial evidence; prevents transient depth spikes.
                if (old.weight > 5f && abs(old.sdf - measured) > 1.5f) continue
                val alpha = w / (old.weight + w)
                old.sdf += (measured - old.sdf) * alpha
                old.r += (rgb[0] - old.r) * alpha
                old.g += (rgb[1] - old.g) * alpha
                old.b += (rgb[2] - old.b) * alpha
                old.weight = (old.weight + w).coerceAtMost(32f)
            }
        }
    }

    fun snapshot(maxSamples: Int = 180000): List<Sample> {
        val stride = (cells.size / maxSamples).coerceAtLeast(1)
        val out = ArrayList<Sample>(min(cells.size, maxSamples))
        var i = 0
        for ((k,v) in cells) {
            if (i++ % stride != 0) continue
            fun unpack(shift: Int): Int {
                val n = ((k ushr shift) and 0x1fffffL).toInt()
                return if (n >= 0x100000) n - 0x200000 else n
            }
            out.add(Sample(unpack(42), unpack(21), unpack(0), v.sdf, v.weight, v.r, v.g, v.b))
        }
        return out
    }

    companion object {
        private val corners = arrayOf(
            intArrayOf(0,0,0), intArrayOf(1,0,0), intArrayOf(1,1,0), intArrayOf(0,1,0),
            intArrayOf(0,0,1), intArrayOf(1,0,1), intArrayOf(1,1,1), intArrayOf(0,1,1)
        )
        private val tetra = arrayOf(
            intArrayOf(0,5,1,6), intArrayOf(0,1,2,6), intArrayOf(0,2,3,6),
            intArrayOf(0,3,7,6), intArrayOf(0,7,4,6), intArrayOf(0,4,5,6)
        )
        private fun key(x: Int, y: Int, z: Int): Long =
            ((x.toLong() and 0x1fffffL) shl 42) or
            ((y.toLong() and 0x1fffffL) shl 21) or (z.toLong() and 0x1fffffL)

        fun polygonize(samples: List<Sample>, maxTriangles: Int = 120000,
                       cell: Float = 0.04f): FloatArray {
            val map = HashMap<Long, Sample>(samples.size * 2)
            for (s in samples) if (s.weight >= 0.8f) map[key(s.x,s.y,s.z)] = s
            val output = FloatArray(maxTriangles * 18)
            var count = 0
            fun vertex(a: Sample, b: Sample): FloatArray {
                val t = (a.sdf / (a.sdf - b.sdf)).coerceIn(0f, 1f)
                fun mix(u: Float, v: Float) = u + (v-u)*t
                return floatArrayOf(
                    mix(a.x.toFloat(), b.x.toFloat())*cell,
                    mix(a.y.toFloat(), b.y.toFloat())*cell,
                    mix(a.z.toFloat(), b.z.toFloat())*cell,
                    mix(a.r,b.r), mix(a.g,b.g), mix(a.b,b.b)
                )
            }
            fun triangle(a: FloatArray,b: FloatArray,c: FloatArray) {
                if (count + 18 > output.size) return
                for (p in arrayOf(a,b,c)) for (v in p) output[count++] = v
            }
            for (s in samples) {
                if (count + 216 > output.size) break
                val cube = arrayOfNulls<Sample>(8)
                var complete = true
                for (i in corners.indices) {
                    val o = corners[i]
                    cube[i] = map[key(s.x+o[0],s.y+o[1],s.z+o[2])]
                    if (cube[i] == null) { complete = false; break }
                }
                if (!complete) continue
                for (t in tetra) {
                    val inside = ArrayList<Int>(4)
                    val outside = ArrayList<Int>(4)
                    for (i in t) if (cube[i]!!.sdf < 0f) inside.add(i) else outside.add(i)
                    if (inside.isEmpty() || outside.isEmpty()) continue
                    if (inside.size == 1 || inside.size == 3) {
                        val a = if (inside.size == 1) inside[0] else outside[0]
                        val other = if (inside.size == 1) outside else inside
                        triangle(vertex(cube[a]!!,cube[other[0]]!!),
                                 vertex(cube[a]!!,cube[other[1]]!!),
                                 vertex(cube[a]!!,cube[other[2]]!!))
                    } else {
                        val a=vertex(cube[inside[0]]!!,cube[outside[0]]!!)
                        val b=vertex(cube[inside[0]]!!,cube[outside[1]]!!)
                        val c=vertex(cube[inside[1]]!!,cube[outside[0]]!!)
                        val d=vertex(cube[inside[1]]!!,cube[outside[1]]!!)
                        triangle(a,b,c); triangle(b,d,c)
                    }
                }
            }
            return output.copyOf(count)
        }
    }
}
