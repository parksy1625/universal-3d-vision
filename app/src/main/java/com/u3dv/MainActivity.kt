package com.u3dv

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.media.Image
import android.content.ContentValues
import android.provider.MediaStore
import com.google.ar.core.TrackingState
import kotlin.math.*
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Button
import android.widget.ScrollView
import android.app.AlertDialog
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
                        if (event.pointerCount == 2 && !pinch.isInProgress) {
                            depthRenderer.panX -= (event.x - px) * depthRenderer.zoom / glView.width.coerceAtLeast(1)
                            depthRenderer.panY += (event.y - py) * depthRenderer.zoom / glView.height.coerceAtLeast(1)
                        }
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
        val inventoryButton = Button(this).apply {
            text = "Cameras"
            setOnClickListener {
                val info = try { CameraInventory.describe(this@MainActivity) } catch (e: Exception) { "Camera discovery failed: ${e.message}" }
                val label = TextView(this@MainActivity).apply { text = info; setPadding(24, 20, 24, 20); setTextIsSelectable(true) }
                val scroll = ScrollView(this@MainActivity).apply { addView(label) }
                AlertDialog.Builder(this@MainActivity).setTitle("Camera capabilities").setView(scroll).setPositiveButton("Close", null).show()
            }
        }
        layout.addView(inventoryButton, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))
        val physicalButton = Button(this).apply {
            text = "Multi-camera test"
            setOnClickListener { startActivity(Intent(this@MainActivity, MultiCameraActivity::class.java)) }
        }
        val testParams = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START)
        layout.addView(physicalButton, testParams)
        val modeButton = Button(this).apply {
            text = "View: Mesh"
            setOnClickListener {
                val mesh = !(renderer?.showMesh ?: true)
                renderer?.showMesh = mesh
                text = if (mesh) "View: Mesh" else "View: Points"
            }
        }
        val modeParams = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END)
        modeParams.topMargin = (68 * resources.displayMetrics.density).toInt()
        layout.addView(modeButton, modeParams)
        val resetButton = Button(this).apply {
            text = "Reset view"
            setOnClickListener {
                renderer?.yaw = 0f
                renderer?.pitch = 15f
                renderer?.zoom = 2.5f
                renderer?.panX = 0f
                renderer?.panY = 0f
            }
        }
        val resetParams = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START)
        resetParams.topMargin = (68 * resources.displayMetrics.density).toInt()
        layout.addView(resetButton, resetParams)
        val exportButton = Button(this).apply {
            text = "Save PLY"
            setOnClickListener {
                glView.queueEvent {
                    val points = renderer?.snapshot() ?: emptyList()
                    Thread {
                        try {
                            val values = ContentValues().apply {
                                put(MediaStore.Downloads.DISPLAY_NAME, "u3dv_" + System.currentTimeMillis() + ".ply")
                                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                                put(MediaStore.Downloads.RELATIVE_PATH, "Download/U3DV")
                            }
                            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                                ?: throw IllegalStateException("Unable to create file")
                            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                                writer.write("ply\nformat ascii 1.0\nelement vertex " + points.size + "\nproperty float x\nproperty float y\nproperty float z\nproperty uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n")
                                for (p in points) {
                                    writer.write("${p[0]} ${p[1]} ${p[2]} ${(p[3]*255).toInt().coerceIn(0,255)} ${(p[4]*255).toInt().coerceIn(0,255)} ${(p[5]*255).toInt().coerceIn(0,255)}\n")
                                }
                            } ?: throw IllegalStateException("Unable to write file")
                            runOnUiThread { status.text = "Saved " + points.size + " points to Downloads/U3DV" }
                        } catch (e: Exception) {
                            runOnUiThread { status.text = "Save failed: " + e.message }
                        }
                    }.start()
                }
            }
        }
        layout.addView(exportButton, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        val meshButton = Button(this).apply {
            text = "Save OBJ"
            setOnClickListener {
                glView.queueEvent {
                    val points = renderer?.snapshot() ?: emptyList()
                    Thread {
                        try {
                            val values = ContentValues().apply {
                                put(MediaStore.Downloads.DISPLAY_NAME, "u3dv_mesh_" + System.currentTimeMillis() + ".obj")
                                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                                put(MediaStore.Downloads.RELATIVE_PATH, "Download/U3DV")
                            }
                            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                                ?: throw IllegalStateException("Unable to create OBJ")
                            val faces = contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                                SurfaceMesh.writeObj(points, writer)
                            } ?: throw IllegalStateException("Unable to write OBJ")
                            runOnUiThread { status.text = "Saved OBJ: " + points.size + " vertices, " + faces + " triangles" }
                        } catch (e: Exception) {
                            runOnUiThread { status.text = "OBJ failed: " + e.message }
                        }
                    }.start()
                }
            }
        }
        val meshParams = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        meshParams.bottomMargin = (56 * resources.displayMetrics.density).toInt()
        layout.addView(meshButton, meshParams)
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

    override fun onDestroy() { session?.close(); renderer?.shutdown(); super.onDestroy() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) recreate()
    }

    inner class DepthRenderer(private val report: (String) -> Unit) : GLSurfaceView.Renderer {
        @Volatile var yaw = 0f
        @Volatile var pitch = 15f
        @Volatile var zoom = 2.5f
        @Volatile var panX = 0f
        @Volatile var panY = 0f
        @Volatile var centerX = 0f
        @Volatile var centerY = 0f
        @Volatile var centerZ = -1.5f
        @Volatile var showMesh = true
        private val meshBuffer = ByteBuffer.allocateDirect(120000 * 3 * 6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        private var meshVertices = 0
        private var lastMeshUpdate = 0L
        private var program = 0
        private var texture = 0
        private var width = 1
        private var height = 1
        private var lastReport = 0L
        private val projection = FloatArray(16)
        private val view = FloatArray(16)
        private val mvp = FloatArray(16)
        private val vertices = ByteBuffer.allocateDirect(300000 * 6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        // Quantized global voxel map, bounded to avoid unlimited memory use.
        private val voxels = LinkedHashMap<Long, FloatArray>()
        private val tsdf = TsdfVolume()
        private val maxPoints = 1200000
        private val meshExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        @Volatile private var pendingMesh: FloatArray? = null
        @Volatile private var meshBusy = false
        private var lastPointUpdate = 0L
        private var displayedPoints = 0
        fun snapshot(): List<FloatArray> = voxels.values.map { it.copyOf() }
        fun shutdown() { meshExecutor.shutdownNow() }
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
                "attribute vec3 aPosition; attribute vec3 aColor; uniform mat4 uMvp; varying vec3 vColor; void main(){gl_Position=uMvp*vec4(aPosition,1.0);gl_PointSize=4.5;vColor=aColor;}",
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
                if (frameNumber % 2 == 0) {
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
                            val cameraOrigin = floatArrayOf(pose.tx()-base[0], pose.ty()-base[1], pose.tz()-base[2])
                            for (y in 2 until depth.height - 2 step 1) {
                                for (x in 2 until depth.width - 2 step 1) {
                                    val offset = y * plane.rowStride + x * plane.pixelStride
                                    val mm = bytes.getShort(offset).toInt() and 0xffff
                                    if (mm < 250 || mm > 4500) { rejected++; continue }
                                    var consistent = 0
                                    for ((dx, dy) in arrayOf(-2 to 0, 2 to 0, 0 to -2, 0 to 2)) {
                                        val offset2 = (y + dy) * plane.rowStride + (x + dx) * plane.pixelStride
                                        val neighbor = bytes.getShort(offset2).toInt() and 0xffff
                                        if (neighbor in 200..5000 && abs(neighbor - mm) <= 150) consistent++
                                    }
                                    val minNeighbors = if (mm > 2500) 3 else 2
                                    if (consistent < minNeighbors) { rejected++; continue }
                                    if (confidencePlane != null && confidenceBuffer != null) {
                                        val ci = y * confidencePlane.rowStride + x * confidencePlane.pixelStride
                                        val minConfidence = if (mm > 2500) 170 else 130
                                        if (ci >= confidenceBuffer.limit() || (confidenceBuffer.get(ci).toInt() and 255) < minConfidence) {
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
                                    val rgb = sampleYuv(if (color != null && kotlin.math.abs(color.timestamp - depth.timestamp) < 50000000L) color else null, local, focal, principal, dims)
                                    accepted++
                                    // Sparse TSDF fusion on a reduced sampling grid to bound CPU work.
                                    if (x % 3 == 0 && y % 3 == 0) {
                                        val conf = if (confidencePlane != null && confidenceBuffer != null && y * confidencePlane.rowStride + x * confidencePlane.pixelStride < confidenceBuffer.limit())
                                            ((confidenceBuffer.get(y * confidencePlane.rowStride + x * confidencePlane.pixelStride).toInt() and 255) / 255f)
                                        else 0.65f
                                        tsdf.integrate(cameraOrigin, floatArrayOf(xx,yy,zz), rgb, conf)
                                    }
                                    if (!voxels.containsKey(key) && voxels.size >= maxPoints) { rejected++; continue }
                                    val old = voxels[key]
                                    if (old == null) {
                                        voxels[key] = floatArrayOf(xx, yy, zz, rgb[0], rgb[1], rgb[2], 1f)
                                    } else {
                                        val n = old[6].coerceAtMost(24f)
                                        val dx = xx-old[0]; val dy = yy-old[1]; val dz = zz-old[2]
                                        if (dx*dx+dy*dy+dz*dz > 0.003025f) { rejected++; continue }
                                        val alpha = (consistent / 4f).coerceIn(0.5f,1f) / (n + 1f)
                                        for (j in 0..5) old[j] += (floatArrayOf(xx, yy, zz, rgb[0], rgb[1], rgb[2])[j] - old[j]) * alpha
                                        old[6] = (n + 1f).coerceAtMost(24f)
                                    }
                                }
                            }
                        } finally { confidence?.close(); color?.close(); depth.close() }
                    }
                }
                val meshNow = System.currentTimeMillis()
                if (showMesh && !meshBusy && meshNow - lastMeshUpdate > 2500) {
                    lastMeshUpdate = meshNow
                    meshBusy = true
                    val tsdfSamples = tsdf.snapshot()
                    val sample = ArrayList<FloatArray>()
                    val stride = (voxels.size / 150000).coerceAtLeast(1)
                    var i = 0
                    for (p in voxels.values) if (i++ % stride == 0) sample.add(p.copyOf())
                    meshExecutor.execute {
                        try {
                            val surface = TsdfVolume.polygonize(tsdfSamples)
                            pendingMesh = if (surface.isNotEmpty()) surface else SurfaceMesh.liveTriangles(sample)
                        }
                        finally { meshBusy = false }
                    }
                }
                val completed = pendingMesh
                if (completed != null) {
                    pendingMesh = null
                    meshBuffer.clear()
                    meshBuffer.put(completed)
                    meshVertices = completed.size / 6
                }
                if (meshNow - lastPointUpdate > 300 || !showMesh) {
                    vertices.clear()
                    val stride = ((voxels.size + 299999) / 300000).coerceAtLeast(1)
                    var index = 0
                    for (p in voxels.values) {
                        if (index++ % stride == 0 && vertices.remaining() >= 6 && p[6] >= 1f) vertices.put(p, 0, 6)
                    }
                    displayedPoints = vertices.position() / 6
                    lastPointUpdate = meshNow
                }
                val count = displayedPoints
                if (count > 0 && frameNumber % 45 == 0) {
                    var minX = Float.POSITIVE_INFINITY; var maxX = Float.NEGATIVE_INFINITY
                    var minY = Float.POSITIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
                    var minZ = Float.POSITIVE_INFINITY; var maxZ = Float.NEGATIVE_INFINITY
                    for (p in voxels.values) {
                        minX = min(minX, p[0]); maxX = max(maxX, p[0])
                        minY = min(minY, p[1]); maxY = max(maxY, p[1])
                        minZ = min(minZ, p[2]); maxZ = max(maxZ, p[2])
                    }
                    val tx = (minX + maxX) * 0.5f; val ty = (minY + maxY) * 0.5f; val tz = (minZ + maxZ) * 0.5f
                    centerX += (tx - centerX) * 0.12f
                    centerY += (ty - centerY) * 0.12f
                    centerZ += (tz - centerZ) * 0.12f
                }
                if (count > 0) {
                    vertices.position(0)
                    Matrix.perspectiveM(projection, 0, 60f, width.toFloat()/height, 0.05f, 100f)
                    val a = Math.toRadians(yaw.toDouble())
                    val b = Math.toRadians(pitch.toDouble())
                    val cx = (zoom * cos(b) * sin(a)).toFloat()
                    val cy = (zoom * sin(b)).toFloat()
                    val cz = (zoom * cos(b) * cos(a)).toFloat()
                    Matrix.setLookAtM(view, 0, centerX + panX + cx, centerY + panY + cy, centerZ + cz, centerX + panX, centerY + panY, centerZ, 0f, 1f, 0f)
                    Matrix.multiplyMM(mvp, 0, projection, 0, view, 0)
                    GLES20.glUseProgram(program)
                    GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)
                    val pos = GLES20.glGetAttribLocation(program, "aPosition")
                    val col = GLES20.glGetAttribLocation(program, "aColor")
                    GLES20.glEnableVertexAttribArray(pos)
                    GLES20.glEnableVertexAttribArray(col)
                    val useMesh = showMesh && meshVertices > 0
                    val active = if (useMesh) meshBuffer else vertices
                    active.position(0)
                    GLES20.glVertexAttribPointer(pos, 3, GLES20.GL_FLOAT, false, 24, active)
                    active.position(3)
                    GLES20.glVertexAttribPointer(col, 3, GLES20.GL_FLOAT, false, 24, active)
                    GLES20.glDrawArrays(if (useMesh) GLES20.GL_TRIANGLES else GLES20.GL_POINTS, 0, if (useMesh) meshVertices else count)
                    GLES20.glDisableVertexAttribArray(pos)
                    GLES20.glDisableVertexAttribArray(col)
                }
                val now = System.currentTimeMillis()
                if (now - lastReport > 1000) {
                    report("3D · $count voxels · ${if (showMesh) "TSDF mesh ${meshVertices/3} triangles" else "points"} · accepted $accepted / filtered $rejected")
                    lastReport = now
                }
            } catch (e: Exception) {
                val now = System.currentTimeMillis()
                if (now - lastReport > 1000) { report("Capture: ${e.message}"); lastReport = now }
            }
        }

        // Project the depth-derived camera-space ray using ARCore CPU image intrinsics.
        // The CPU image and raw depth may have different dimensions/crops; this is
        // a calibrated pinhole projection, not full lens-distortion correction.
        private fun sampleYuv(image: Image?, local: FloatArray, focal: FloatArray,
                              principal: FloatArray, dims: IntArray): FloatArray {
            if (image == null) return floatArrayOf(0.4f, 0.8f, 1f)
            val z = -local[2]
            if (z <= 0f) return floatArrayOf(0.4f, 0.8f, 1f)
            val u = (local[0] / z * focal[0] + principal[0]) * image.width / dims[0]
            val v = (-local[1] / z * focal[1] + principal[1]) * image.height / dims[1]
            if (!u.isFinite() || !v.isFinite() || u < 0 || v < 0 ||
                u >= image.width || v >= image.height) return floatArrayOf(0.4f, 0.8f, 1f)
            val px = u.toInt()
            val py = v.toInt()
            fun read(i: Int, xx: Int, yy: Int): Int {
                val p = image.planes[i]
                val offset = yy * p.rowStride + xx * p.pixelStride
                return p.buffer.get(offset).toInt() and 255
            }
            val yy = read(0, px, py)
            val cb = read(1, px/2, py/2)-128
            val cr = read(2, px/2, py/2)-128
            return floatArrayOf(
                ((yy + 1.402f*cr)/255f).coerceIn(0f,1f),
                ((yy - 0.344136f*cb - 0.714136f*cr)/255f).coerceIn(0f,1f),
                ((yy + 1.772f*cb)/255f).coerceIn(0f,1f)
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
