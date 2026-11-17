package com.wasla.eshareremote

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Camera
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.LuminanceSource
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("DEPRECATION")
class QrScannerActivity : Activity(), SurfaceHolder.Callback, Camera.PreviewCallback {
    private var camera: Camera? = null
    private lateinit var preview: SurfaceView
    private val reader = MultiFormatReader()
    private val completed = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xff071426.toInt()
        window.navigationBarColor = 0xff071426.toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xff071426.toInt())
        }
        root.addView(TextView(this).apply {
            text = "امسح رمز وصلة QR"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(0xffffffff.toInt())
            setPadding(16, 24, 16, 24)
        }, LinearLayout.LayoutParams(-1, -2))
        preview = SurfaceView(this)
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(TextView(this).apply {
            text = "وجّه الكاميرا إلى الرمز الظاهر على شاشة التلفاز"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xffb9cce3.toInt())
            setPadding(16, 20, 16, 24)
        }, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        preview.holder.addCallback(this)
        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
        hints[DecodeHintType.POSSIBLE_FORMATS] = listOf(BarcodeFormat.QR_CODE)
        hints[DecodeHintType.TRY_HARDER] = true
        reader.setHints(hints)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST &&
            (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            setResult(RESULT_CANCELED)
            finish()
        } else if (requestCode == CAMERA_PERMISSION_REQUEST) {
            preview.holder.surface?.let { if (it.isValid) openCamera(preview.holder) }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        try { camera?.startPreview() } catch (_: Exception) { }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        releaseCamera()
    }

    private fun openCamera(holder: SurfaceHolder) {
        if (camera != null || isFinishing) return
        try {
            val opened = Camera.open()
            val info = Camera.CameraInfo()
            Camera.getCameraInfo(0, info)
            val rotation = when (windowManager.defaultDisplay.rotation) {
                android.view.Surface.ROTATION_90 -> 90
                android.view.Surface.ROTATION_180 -> 180
                android.view.Surface.ROTATION_270 -> 270
                else -> 0
            }
            val display = if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                (360 - (info.orientation + rotation) % 360) % 360
            } else {
                (info.orientation - rotation + 360) % 360
            }
            opened.setDisplayOrientation(display)
            opened.setPreviewDisplay(holder)
            opened.setPreviewCallback(this)
            camera = opened
            opened.startPreview()
        } catch (_: Exception) {
            releaseCamera()
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    override fun onPreviewFrame(data: ByteArray?, sourceCamera: Camera?) {
        if (data == null || sourceCamera == null || completed.get()) return
        val size = sourceCamera.parameters.previewSize ?: return
        try {
            val source = PlanarYUVLuminanceSource(data, size.width, size.height, 0, 0, size.width, size.height, false)
            val result = tryDecode(source) ?: if (source.isRotateSupported) tryDecode(source.rotateCounterClockwise()) else null
            if (result != null && completed.compareAndSet(false, true)) {
                runOnUiThread {
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, result))
                    finish()
                }
            }
        } catch (_: NotFoundException) {
            reader.reset()
        } catch (_: Exception) {
            reader.reset()
        }
    }

    private fun tryDecode(source: LuminanceSource): String? {
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: NotFoundException) {
            reader.reset()
            null
        }
    }

    private fun releaseCamera() {
        val active = camera ?: return
        camera = null
        try { active.setPreviewCallback(null) } catch (_: Exception) { }
        try { active.stopPreview() } catch (_: Exception) { }
        try { active.release() } catch (_: Exception) { }
    }

    override fun onPause() {
        releaseCamera()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::preview.isInitialized && preview.holder.surface?.isValid == true &&
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera(preview.holder)
        }
    }

    companion object {
        const val EXTRA_RESULT = "WASLA_QR_RESULT"
        private const val CAMERA_PERMISSION_REQUEST = 8104
    }
}
