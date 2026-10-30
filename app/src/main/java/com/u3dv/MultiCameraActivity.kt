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
import android.graphics.Bitmap
import android.widget.ImageView
import android.widget.ScrollView
import java.util.concurrent.ConcurrentHashMap
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
    private lateinit var previews: LinearLayout
    private val views = mutableMapOf<String, ImageView>()
    private val timestamps = ConcurrentHashMap<String, Long>()
    private val calibration = mutableMapOf<String, String>()
    private val samples = mutableMapOf<String, ArrayDeque<StereoDiagnostics.Sample>>()
    private var stereoStatus = "Waiting for matched frames"
    private var pairedCount = 0

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
        previews = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this)
        scroll.addView(previews)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
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
            timestamps.clear()
            views.clear()
            calibration.clear()
            samples.clear()
            stereoStatus = "Waiting for matched frames"
            pairedCount = 0
            previews.removeAllViews()
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
                val intr = characteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
                val dist = characteristics.get(CameraCharacteristics.LENS_DISTORTION)
                val poseT = characteristics.get(CameraCharacteristics.LENS_POSE_TRANSLATION)
                val poseR = characteristics.get(CameraCharacteristics.LENS_POSE_ROTATION)
                calibration[physicalId] = "intr=${intr?.contentToString()} dist=${dist?.contentToString()} poseT=${poseT?.contentToString()} poseR=${poseR?.contentToString()}"
                val label = TextView(this).apply {
                    text = "Physical $physicalId"
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 17f
                }
                val preview = ImageView(this).apply {
                    adjustViewBounds = true
                    layoutParams = LinearLayout.LayoutParams(-1, 260)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                previews.addView(label)
                previews.addView(preview)
                views[physicalId] = preview
                val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
                val counter = AtomicInteger(0)
                counts[physicalId] = counter
                reader.setOnImageAvailableListener({ source ->
                    val image = source.acquireLatestImage()
                    if (image != null) {
                        counter.incrementAndGet()
                        timestamps[physicalId] = image.timestamp
                        if (counter.get() % 15 == 1) {
                            val w = image.width
                            val h = image.height
                            val plane = image.planes[0]
                            val data = plane.buffer
                            val pixels = IntArray(160 * 120)
                            val gray = ByteArray(160 * 120)
                            for (yy in 0 until 120) {
                                val sy = yy * h / 120
                                for (xx in 0 until 160) {
                                    val sx = xx * w / 160
                                    val y = data.get(sy * plane.rowStride + sx * plane.pixelStride).toInt() and 255
                                    pixels[yy * 160 + xx] = android.graphics.Color.rgb(y, y, y)
                                    gray[yy * 160 + xx] = y.toByte()
                                }
                            }
                            val queue = samples.getOrPut(physicalId) { ArrayDeque() }
                            queue.addLast(StereoDiagnostics.Sample(image.timestamp, gray))
                            while (queue.size > 5) queue.removeFirst()
                            if (physicalId == "5") {
                                val other = samples["2"]
                                val closest = other?.minByOrNull { kotlin.math.abs(it.timestampNs - image.timestamp) }
                                if (closest != null) {
                                    val deltaMs = kotlin.math.abs(closest.timestampNs - image.timestamp) / 1_000_000.0
                                    if (deltaMs <= 40.0) {
                                        val result = StereoDiagnostics.compare(closest.gray, gray)
                                        pairedCount++
                                        stereoStatus = "2/5 pairs=$pairedCount dt=${"%.2f".format(deltaMs)}ms matches=${result.matches} shift=(${"%.1f".format(result.meanShiftX)}, ${"%.1f".format(result.meanShiftY)})"
                                    } else stereoStatus = "2/5 closest dt=${"%.2f".format(deltaMs)}ms (too far)"
                                }
                            }
                            val bitmap = Bitmap.createBitmap(pixels, 160, 120, Bitmap.Config.ARGB_8888)
                            views[physicalId]?.post { views[physicalId]?.setImageBitmap(bitmap) }
                        }
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
                                                val ts = timestamps.values.toList()
                                                val spread = if (ts.size >= 2) (ts.maxOrNull()!! - ts.minOrNull()!!) / 1_000_000.0 else -1.0
                                                val details = calibration.entries.joinToString("\n") { (id, value) -> "[$id] $value" }
                                                output.text = "Logical $selected\n$stats\n$stereoStatus\nLatest timestamp spread: ${"%.2f".format(spread)} ms (not paired frames)\nExperimental correspondences, NOT metric depth.\n$lastError\n$details"
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
