package com.u3dv

import java.io.Writer
import kotlin.math.floor
import kotlin.math.sqrt

/** Conservative local surface triangulation of accumulated 2.5cm voxels.
 * Only joins spatially adjacent cells, never bridges long gaps. Mesh is not watertight.
 */
object SurfaceMesh {
    private const val CELL = 0.025f
    private fun key(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and 0x1fffffL) shl 42) or ((y.toLong() and 0x1fffffL) shl 21) or (z.toLong() and 0x1fffffL)
    private fun distance(a: FloatArray, b: FloatArray): Float {
        val dx=a[0]-b[0]; val dy=a[1]-b[1]; val dz=a[2]-b[2]
        return sqrt(dx*dx+dy*dy+dz*dz)
    }
    /** Bounded live triangle buffer, 6 floats per vertex (position + RGB). */
    fun liveTriangles(points: List<FloatArray>, maxTriangles: Int = 120000): FloatArray {
        val cells = HashMap<Long, Int>(points.size * 2)
        points.forEachIndexed { i,p ->
            if (p[6] >= 2f) cells[key(floor(p[0]/CELL).toInt(),floor(p[1]/CELL).toInt(),floor(p[2]/CELL).toInt())] = i
        }
        val out = FloatArray(maxTriangles * 3 * 6)
        var cursor = 0
        val axes = arrayOf(
            arrayOf(intArrayOf(1,0,0), intArrayOf(0,1,0)),
            arrayOf(intArrayOf(1,0,0), intArrayOf(0,0,1)),
            arrayOf(intArrayOf(0,1,0), intArrayOf(0,0,1))
        )
        fun emit(a: FloatArray,b: FloatArray,c: FloatArray) {
            if (cursor + 18 > out.size) return
            for (p in arrayOf(a,b,c)) for (i in 0..5) out[cursor++] = p[i]
        }
        // Rotate sampling start across updates to avoid permanently starving late-scanned areas.
        val stride = (points.size / 90000).coerceAtLeast(1)
        for (index in points.indices step stride) {
            val p = points[index]
            if (cursor + 36 > out.size) break
            if (p[6] < 2f) continue
            val x=floor(p[0]/CELL).toInt()
            val y=floor(p[1]/CELL).toInt()
            val z=floor(p[2]/CELL).toInt()
            for (axis in axes) {
                val a=axis[0]; val b=axis[1]
                val j=cells[key(x+a[0],y+a[1],z+a[2])] ?: continue
                val k=cells[key(x+b[0],y+b[1],z+b[2])] ?: continue
                val pj=points[j]; val pk=points[k]
                if (distance(p,pj) > 0.055f || distance(p,pk) > 0.055f ||
                    distance(pj,pk) > 0.075f) continue
                emit(p,pj,pk)
                val l=cells[key(x+a[0]+b[0],y+a[1]+b[1],z+a[2]+b[2])]
                if (l != null) {
                    val pl=points[l]
                    if (distance(pl,pj) < 0.055f && distance(pl,pk) < 0.055f) emit(pj,pl,pk)
                }
            }
        }
        return out.copyOf(cursor)
    }

    fun writeObj(points: List<FloatArray>, writer: Writer): Int {
        val cells = HashMap<Long, Int>(points.size * 2)
        points.forEachIndexed { i,p ->
            cells[key(floor(p[0]/CELL).toInt(),floor(p[1]/CELL).toInt(),floor(p[2]/CELL).toInt())] = i
        }
        writer.write("# U3DV experimental local surface mesh; not watertight\n")
        for (p in points) writer.write("v ${p[0]} ${p[1]} ${p[2]} ${p[3]} ${p[4]} ${p[5]}\n")
        var faces = 0
        // Three orthogonal local planes; each cell creates up to two triangles per plane.
        val axes = arrayOf(
            arrayOf(intArrayOf(1,0,0), intArrayOf(0,1,0)),
            arrayOf(intArrayOf(1,0,0), intArrayOf(0,0,1)),
            arrayOf(intArrayOf(0,1,0), intArrayOf(0,0,1))
        )
        for ((i,p) in points.withIndex()) {
            if (p[6] < 2f) continue
            val x=floor(p[0]/CELL).toInt()
            val y=floor(p[1]/CELL).toInt()
            val z=floor(p[2]/CELL).toInt()
            for (axis in axes) {
                val a=axis[0]; val b=axis[1]
                val j=cells[key(x+a[0],y+a[1],z+a[2])] ?: continue
                val k=cells[key(x+b[0],y+b[1],z+b[2])] ?: continue
                val l=cells[key(x+a[0]+b[0],y+a[1]+b[1],z+a[2]+b[2])]
                if (points[j][6] < 2f || points[k][6] < 2f) continue
                if (distance(p,points[j]) > 0.055f || distance(p,points[k]) > 0.055f ||
                    distance(points[j],points[k]) > 0.075f) continue
                writer.write("f ${i+1} ${j+1} ${k+1}\n")
                faces++
                if (l != null && points[l][6] >= 2f &&
                    distance(points[l],points[j]) < 0.055f && distance(points[l],points[k]) < 0.055f) {
                    writer.write("f ${j+1} ${l+1} ${k+1}\n")
                    faces++
                }
            }
        }
        return faces
    }
}
