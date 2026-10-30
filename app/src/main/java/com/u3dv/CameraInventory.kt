package com.u3dv

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build

/** Capability discovery only. Never opens a camera while ARCore owns it. */
object CameraInventory {
    fun describe(context: Context): String {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = manager.cameraIdList
        val lines = mutableListOf<String>()
        lines += "Camera2 IDs: ${ids.size}"
        for (id in ids) {
            try {
                val c = manager.getCameraCharacteristics(id)
                val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                    CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                    else -> "OTHER"
                }
                val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.joinToString(",") { "%.2f".format(it) } ?: "unknown"
                val physical = if (Build.VERSION.SDK_INT >= 28) c.physicalCameraIds.joinToString(",") else ""
                val capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.joinToString(",") ?: ""
                lines += "id=$id facing=$facing focal=[$focal] physical=[$physical] caps=[$capabilities]"
            } catch (e: Exception) { lines += "id=$id unavailable: ${e.message}" }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val sets = manager.concurrentCameraIds
            lines += "Concurrent combinations: ${sets.size}"
            for (set in sets) lines += set.sorted().joinToString(prefix="[", postfix="]")
        } else lines += "Concurrent camera query requires Android 11+"
        return lines.joinToString("\n")
    }
}
