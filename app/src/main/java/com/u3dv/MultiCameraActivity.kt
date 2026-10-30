package com.u3dv

import android.app.Activity
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.ImageFormat
import android.util.Size
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dedicated Camera2 experiment: ARCore must be paused before entering this Activity.
 * Tests whether the logical rear camera can deliver independent physical camera streams.
 */
class MultiCameraActivity : Activity() {
    private lateinit var output: TextView
    private lateinit var manager: CameraManager
    private var camera: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private val readers = mutableListOf<ImageReader>()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val counts = mutableMapOf<String, AtomicInteger>()
    private var started = false
    private var lastError = ""
    private var selected = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = getSystemService(CAMERA_SERVICE) as CameraManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 30, 24, 24)
        }
        val start = Button(this).apply {
            text = "Test rear physical cameras"
            setOnClickListener { begin() }
        }
        val stop = Button(this).apply {
            text = "Stop / return"
            setOnClickListener { finish() }
        }
        output = TextView(this).apply {
            textSize = 15f
            setTextColor(android.graphics.Color.WHITE)
            text = "This test temporarily stops ARCore. It opens the logical rear camera and requests physical streams.\n"
        }
        root.addView(start)
        root.addView(stop)
        root.addView(output)
        root.setBackgroundColor(0xff10141b.toInt())
        setContentView(root)
        thread = HandlerThread("camera2-physical-test").also { it.start() }
        handler = Handler(thread!!.looper)
    }

    private fun begin() {
        if (started) return
        started = true
        try {
            val id = manager.cameraIdList.firstOrNull {
                val c = manager.getCameraCharacteristics(it)
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                    c.physicalCameraIds.size >= 2
            } ?: throw IllegalStateException("No logical rear camera exposing multiple physical IDs")
            selected = id
            val physicalIds = manager.getCameraCharacteristics(id).physicalCameraIds.sorted()
            val selectedIds = physicalIds.take(3)
            if (selectedIds.size < 2) throw IllegalStateException("Need at least 2 physical IDs")
            counts.clear()
            readers.clear()
            val configs = mutableListOf<OutputConfiguration>()
            for (physicalId in selectedIds) {
                val characteristics = manager.getCameraCharacteristics(physicalId)
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: throw IllegalStateException("No stream map for $physicalId")
                val sizes = map.getOutputSizes(ImageFormat.YUV_420_888)
                    ?: throw IllegalStateException("No YUV stream for $physicalId")
                val size = sizes.filter { it.width <= 640 && it.height <= 480 }
                    .maxByOrNull { it.width * it.height } ?: sizes.minByOrNull { it.width * it.height }
                    ?: throw IllegalStateException("No size for $physicalId")
                val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
                val counter = AtomicInteger(0)
                counts[physicalId] = counter
                reader.setOnImageAvailableListener({ source ->
                    val image = source.acquireLatestImage()
                    if (image != null) {
                        counter.incrementAndGet()
                        image.close()
                    }
                }, handler)
                readers.add(reader)
                val config = OutputConfiguration(reader.surface)
                config.setPhysicalCameraId(physicalId)
                configs.add(config)
            }
            output.text = "Opening logical=$id physical=$selectedIds\nWaiting for frames..."
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    try {
                        val sessionConfig = SessionConfiguration(
                            SessionConfiguration.SESSION_REGULAR,
                            configs,
                            mainExecutor,
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    captureSession = session
                                    try {
                                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                        readers.forEach { request.addTarget(it.surface) }
                                        session.setRepeatingRequest(request.build(), null, handler)
                                        output.postDelayed(object : Runnable {
                                            override fun run() {
                                                if (!started) return
                                                val stats = counts.entries.joinToString("\n") { (key, value) ->
                                                    "physical $key: ${value.get()} frames"
                                                }
                                                output.text = "Logical camera $selected\n$stats\n$lastError\n\nNonzero counts for 2+ streams indicate simultaneous frame delivery."
                                                output.postDelayed(this, 1000)
                                            }
                                        }, 1000)
                                    } catch (e: Exception) { fail("Repeating request: ${e.message}") }
                                }
                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    fail("Physical multi-stream session configuration rejected")
                                }
                            }
                        )
                        device.createCaptureSession(sessionConfig)
                    } catch (e: Exception) { fail("Session creation: ${e.message}") }
                }
                override fun onDisconnected(device: CameraDevice) {
                    fail("Camera disconnected")
                    device.close()
                    camera = null
                }
                override fun onError(device: CameraDevice, error: Int) {
                    fail("Camera2 open error code=$error")
                    device.close()
                    camera = null
                }
            }, handler)
        } catch (e: Exception) { fail("Setup: ${e.message}") }
    }

    private fun fail(message: String) {
        lastError = message
        runOnUiThread { output.text = message }
    }

    override fun onDestroy() {
        started = false
        captureSession?.close()
        camera?.close()
        readers.forEach { it.close() }
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        super.onDestroy()
    }
}
