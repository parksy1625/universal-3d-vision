package com.u3dv

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.media.Image
import com.google.ar.core.TrackingState
import kotlin.math.*
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import com.google.ar.core.Config
import com.google.ar.core.Session
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : Activity() {
    private lateinit var glView: GLSurfaceView
    private lateinit var status: TextView
    private var session: Session? = null
    private var renderer: DepthRenderer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            text = "Initializing ARCore..."
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(0x88000000.toInt())
            setPadding(20, 14, 20, 14)
        }
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            val depthRenderer = DepthRenderer { msg -> runOnUiThread { status.text = msg } }
            renderer = depthRenderer
            setRenderer(depthRenderer)
            val pinch = ScaleGestureDetector(this@MainActivity, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    depthRenderer.zoom = (depthRenderer.zoom / detector.scaleFactor).coerceIn(0.3f, 15f)
                    return true
                }
            })
            var px = 0f
            var py = 0f
            setOnTouchListener { _, event ->
                pinch.onTouchEvent(event)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { px = event.x; py = event.y }
                    MotionEvent.ACTION_MOVE -> {
                        if (event.pointerCount == 1 && !pinch.isInProgress) {
                            depthRenderer.yaw += (event.x - px) * 0.35f
                            depthRenderer.pitch = (depthRenderer.pitch + (event.y - py) * 0.35f).coerceIn(-89f, 89f)
                        }
                        px = event.x; py = event.y
                    }
                }
                true
            }
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        val layout = FrameLayout(this)
        layout.addView(glView)
        layout.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        setContentView(layout)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 101)
        }
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            try {
                if (session == null) {
                    val s = Session(this)
                    if (!s.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)) {
                        status.text = "Raw Depth unsupported"
                        s.close()
                    } else {
                        val config = Config(s)
                        config.depthMode = Config.DepthMode.RAW_DEPTH_ONLY
                        s.configure(config)
                        session = s
                    }
                }
                session?.resume()
            } catch (e: Exception) { status.text = "ARCore: ${e.message}" }
        }
        glView.onResume()
    }

    override fun onPause() {
        glView.onPause()
        session?.pause()
        super.onPause()
    }

    override fun onDestroy() { session?.close(); super.onDestroy() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) recreate()
    }

    inner class DepthRenderer(private val report: (String) -> Unit) : GLSurfaceView.Renderer {
        @Volatile var yaw = 0f
        @Volatile var pitch = 15f
        @Volatile var zoom = 2.5f
        private var program = 0
        private var texture = 0
        private var width = 1
        private var height = 1
        private var lastReport = 0L
        private val projection = FloatArray(16)
        private val view = FloatArray(16)
        private val mvp = FloatArray(16)
        private val vertices = ByteBuffer.allocateDirect(100000 * 6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        // Quantized global voxel map, bounded to avoid unlimited memory use.
        private val voxels = LinkedHashMap<Long, FloatArray>()
        private val maxPoints = 80000
        private var accepted = 0
        private var rejected = 0
        private var origin: FloatArray? = null
        private var frameNumber = 0

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.025f, 0.035f, 0.06f, 1f)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            texture = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            program = link(
                "attribute vec3 aPosition; attribute vec3 aColor; uniform mat4 uMvp; varying vec3 vColor; void main(){gl_Position=uMvp*vec4(aPosition,1.0);gl_PointSize=3.0;vColor=aColor;}",
                "precision mediump float; varying vec3 vColor; void main(){gl_FragColor=vec4(vColor,1.0);}"
            )
        }
        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            width = w; height = h
            GLES20.glViewport(0, 0, w, h)
        }
        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            val s = session ?: return
            try {
                s.setCameraTextureName(texture)
                s.setDisplayGeometry(0, width, height)
                val frame = s.update()
                val camera = frame.camera
                if (camera.trackingState != TrackingState.TRACKING) return
                frameNumber++
                // Capture at a lower cadence to keep GPU rendering responsive.
                if (frameNumber % 3 == 0) {
                    val depth = try { frame.acquireRawDepthImage16Bits() } catch (_: Exception) { null }
                    if (depth != null) {
                        val color = try { frame.acquireCameraImage() } catch (_: Exception) { null }
                        val confidence = try { frame.acquireRawDepthConfidenceImage() } catch (_: Exception) { null }
                        try {
                            val intr = camera.imageIntrinsics
                            val focal = intr.focalLength
                            val principal = intr.principalPoint
                            val dims = intr.imageDimensions
                            val sx = depth.width.toFloat() / dims[0]
                            val sy = depth.height.toFloat() / dims[1]
                            val fx = focal[0] * sx
                            val fy = focal[1] * sy
                            val cx = principal[0] * sx
                            val cy = principal[1] * sy
                            val confidencePlane = confidence?.planes?.get(0)
                            val confidenceBuffer = confidencePlane?.buffer
                            val plane = depth.planes[0]
                            val bytes = plane.buffer.order(ByteOrder.nativeOrder())
                            val pose = camera.pose
                            if (origin == null) origin = pose.translation.clone()
                            val base = origin!!
                            for (y in 0 until depth.height step 3) {
                                for (x in 0 until depth.width step 3) {
                                    val offset = y * plane.rowStride + x * plane.pixelStride
                                    val mm = bytes.getShort(offset).toInt() and 0xffff
                                    if (mm < 200 || mm > 5000) { rejected++; continue }
                                    if (confidencePlane != null && confidenceBuffer != null) {
                                        val ci = y * confidencePlane.rowStride + x * confidencePlane.pixelStride
                                        if (ci >= confidenceBuffer.limit() || (confidenceBuffer.get(ci).toInt() and 255) < 160) {
                                            rejected++; continue
                                        }
                                    }
                                    val z = mm / 1000f
                                    val local = floatArrayOf((x-cx)*z/fx, -(y-cy)*z/fy, -z)
                                    val world = pose.transformPoint(local)
                                    val xx = world[0]-base[0]; val yy = world[1]-base[1]; val zz = world[2]-base[2]
                                    val ix = floor(xx / 0.025f).toInt()
                                    val iy = floor(yy / 0.025f).toInt()
                                    val iz = floor(zz / 0.025f).toInt()
                                    val key = ((ix.toLong() and 0x1fffffL) shl 42) or
                                        ((iy.toLong() and 0x1fffffL) shl 21) or (iz.toLong() and 0x1fffffL)
                                    val rgb = sampleYuv(color, x, y, depth.width, depth.height)
                                    accepted++
                                    if (!voxels.containsKey(key) && voxels.size >= maxPoints) {
                                        val it = voxels.keys.iterator()
                                        if (it.hasNext()) { it.next(); it.remove() }
                                    }
                                    val old = voxels[key]
                                    if (old == null) {
                                        voxels[key] = floatArrayOf(xx, yy, zz, rgb[0], rgb[1], rgb[2], 1f)
                                    } else {
                                        val n = old[6].coerceAtMost(12f)
                                        val alpha = 1f / (n + 1f)
                                        for (j in 0..5) old[j] += (floatArrayOf(xx, yy, zz, rgb[0], rgb[1], rgb[2])[j] - old[j]) * alpha
                                        old[6] = (n + 1f).coerceAtMost(12f)
                                    }
                                }
                            }
                        } finally { confidence?.close(); color?.close(); depth.close() }
                    }
                }
                vertices.clear()
                for (p in voxels.values) vertices.put(p, 0, 6)
                val count = vertices.position() / 6
                if (count > 0) {
                    vertices.flip()
                    Matrix.perspectiveM(projection, 0, 60f, width.toFloat()/height, 0.05f, 100f)
                    val a = Math.toRadians(yaw.toDouble())
                    val b = Math.toRadians(pitch.toDouble())
                    val cx = (zoom * cos(b) * sin(a)).toFloat()
                    val cy = (zoom * sin(b)).toFloat()
                    val cz = (zoom * cos(b) * cos(a)).toFloat()
                    Matrix.setLookAtM(view, 0, cx, cy, cz, 0f, 0f, -1.5f, 0f, 1f, 0f)
                    Matrix.multiplyMM(mvp, 0, projection, 0, view, 0)
                    GLES20.glUseProgram(program)
                    GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)
                    val pos = GLES20.glGetAttribLocation(program, "aPosition")
                    val col = GLES20.glGetAttribLocation(program, "aColor")
                    GLES20.glEnableVertexAttribArray(pos)
                    GLES20.glEnableVertexAttribArray(col)
                    vertices.position(0)
                    GLES20.glVertexAttribPointer(pos, 3, GLES20.GL_FLOAT, false, 24, vertices)
                    vertices.position(3)
                    GLES20.glVertexAttribPointer(col, 3, GLES20.GL_FLOAT, false, 24, vertices)
                    GLES20.glDrawArrays(GLES20.GL_POINTS, 0, count)
                    GLES20.glDisableVertexAttribArray(pos)
                    GLES20.glDisableVertexAttribArray(col)
                }
                val now = System.currentTimeMillis()
                if (now - lastReport > 1000) {
                    report("3D · $count voxels · accepted $accepted / filtered $rejected · orbit / zoom")
                    lastReport = now
                }
            } catch (e: Exception) {
                val now = System.currentTimeMillis()
                if (now - lastReport > 1000) { report("Capture: ${e.message}"); lastReport = now }
            }
        }

        // CPU YUV_420_888 sampling. Approximate depth/image alignment for prototype;
        // exact geometric reprojection and color calibration are planned next.
        private fun sampleYuv(image: Image?, x: Int, y: Int, dw: Int, dh: Int): FloatArray {
            if (image == null) return floatArrayOf(0.4f, 0.8f, 1f)
            val px = (x.toLong() * image.width / dw).toInt().coerceIn(0, image.width-1)
            val py = (y.toLong() * image.height / dh).toInt().coerceIn(0, image.height-1)
            fun read(i: Int, xx: Int, yy: Int): Int {
                val p = image.planes[i]
                val offset = yy * p.rowStride + xx * p.pixelStride
                return p.buffer.get(offset).toInt() and 255
            }
            val yy = read(0, px, py)
            val u = read(1, px/2, py/2)-128
            val v = read(2, px/2, py/2)-128
            return floatArrayOf(
                ((yy + 1.402f*v)/255f).coerceIn(0f,1f),
                ((yy - 0.344136f*u - 0.714136f*v)/255f).coerceIn(0f,1f),
                ((yy + 1.772f*u)/255f).coerceIn(0f,1f)
            )
        }
        private fun shader(type: Int, source: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, source)
            GLES20.glCompileShader(id)
            return id
        }
        private fun link(vertex: String, fragment: String): Int {
            val id = GLES20.glCreateProgram()
            GLES20.glAttachShader(id, shader(GLES20.GL_VERTEX_SHADER, vertex))
            GLES20.glAttachShader(id, shader(GLES20.GL_FRAGMENT_SHADER, fragment))
            GLES20.glLinkProgram(id)
            return id
        }
    }
}
