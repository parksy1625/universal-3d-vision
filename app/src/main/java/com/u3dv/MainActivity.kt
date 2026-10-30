package com.u3dv

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
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
            setRenderer(DepthRenderer { msg -> runOnUiThread { status.text = msg } })
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
        private var program = 0
        private var texture = 0
        private var width = 1
        private var height = 1
        private var lastReport = 0L
        private val projection = FloatArray(16)
        private val vertices = ByteBuffer.allocateDirect(100000 * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.025f, 0.035f, 0.06f, 1f)
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            texture = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            program = link(
                "attribute vec3 aPosition; uniform mat4 uProjection; varying float vDepth; void main(){gl_Position=uProjection*vec4(aPosition,1.0);gl_PointSize=2.5;vDepth=-aPosition.z;}",
                "precision mediump float; varying float vDepth; void main(){float t=clamp(vDepth/5.0,0.0,1.0);gl_FragColor=vec4(0.25+0.55*t,0.85-0.4*t,1.0-t*0.25,1.0);}"
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
                camera.getProjectionMatrix(projection, 0, 0.05f, 30f)
                val image = try { frame.acquireRawDepthImage16Bits() } catch (_: Exception) { return }
                var count = 0
                image.use { depth ->
                    val intrinsics = camera.textureIntrinsics
                    val focal = intrinsics.focalLength
                    val principal = intrinsics.principalPoint
                    val dims = intrinsics.imageDimensions
                    val sx = depth.width.toFloat() / dims[0]
                    val sy = depth.height.toFloat() / dims[1]
                    val fx = focal[0] * sx
                    val fy = focal[1] * sy
                    val cx = principal[0] * sx
                    val cy = principal[1] * sy
                    val plane = depth.planes[0]
                    val bytes = plane.buffer.order(ByteOrder.nativeOrder())
                    vertices.clear()
                    val stride = plane.rowStride
                    val pixelStride = plane.pixelStride
                    for (y in 0 until depth.height step 2) {
                        for (x in 0 until depth.width step 2) {
                            if (count >= 100000) break
                            val offset = y * stride + x * pixelStride
                            val millimeters = bytes.getShort(offset).toInt() and 0xffff
                            if (millimeters == 0 || millimeters > 10000) continue
                            val z = millimeters / 1000f
                            vertices.put((x - cx) * z / fx)
                            vertices.put(-(y - cy) * z / fy)
                            vertices.put(-z)
                            count++
                        }
                    }
                }
                if (count > 0) {
                    vertices.flip()
                    GLES20.glUseProgram(program)
                    GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uProjection"), 1, false, projection, 0)
                    val pos = GLES20.glGetAttribLocation(program, "aPosition")
                    GLES20.glEnableVertexAttribArray(pos)
                    GLES20.glVertexAttribPointer(pos, 3, GLES20.GL_FLOAT, false, 0, vertices)
                    GLES20.glDrawArrays(GLES20.GL_POINTS, 0, count)
                    GLES20.glDisableVertexAttribArray(pos)
                }
                val now = System.currentTimeMillis()
                if (now - lastReport > 1000) { report("Live depth · $count points · ${camera.trackingState}"); lastReport = now }
            } catch (e: Exception) {
                val now = System.currentTimeMillis()
                if (now - lastReport > 1000) { report("Capture: ${e.message}"); lastReport = now }
            }
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
