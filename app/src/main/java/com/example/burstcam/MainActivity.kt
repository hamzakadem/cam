package com.example.burstcam

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import java.util.concurrent.ArrayBlockingQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class MainActivity : Activity(), TextureView.SurfaceTextureListener {

    private lateinit var tv: TextureView
    private lateinit var tvFrames: TextView
    private lateinit var tvStatus: TextView
    private lateinit var seek: SeekBar
    private lateinit var cbHdr: CheckBox
    private lateinit var cbSr: CheckBox
    private lateinit var btn: Button

    private lateinit var mgr: CameraManager
    private lateinit var chars: CameraCharacteristics
    private var camId = ""
    private var sensorOri = 90
    private var yuvSizes: Array<Size> = emptyArray()
    private lateinit var previewSize: Size

    private var cam: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null

    private val camThread = HandlerThread("cam").apply { start() }
    private val camH = Handler(camThread.looper)
    private val imgThread = HandlerThread("img").apply { start() }
    private val imgH = Handler(imgThread.looper)

    @Volatile private var lastExp = 10_000_000L
    @Volatile private var lastIso = 100
    @Volatile private var busy = false
    @Volatile private var queue: ArrayBlockingQueue<Frame>? = null

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mgr = getSystemService(CAMERA_SERVICE) as CameraManager
        pickCamera()
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        } else openCamera()
    }

    override fun onPause() {
        closeCamera()
        super.onPause()
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) openCamera()
        else status("Camera permission is required")
    }

    // --------------------------------------------------------------- setup

    private fun pickCamera() {
        camId = mgr.cameraIdList.first {
            mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
        }
        chars = mgr.getCameraCharacteristics(camId)
        sensorOri = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888)
        val st = map.getOutputSizes(SurfaceTexture::class.java)
        previewSize = st.filter { it.width <= 1920 && abs(it.width.toFloat() / it.height - 4f / 3f) < 0.02f }
            .maxByOrNull { it.width * it.height }
            ?: st.filter { it.width <= 1920 }.maxByOrNull { it.width * it.height }
                    ?: st.first()
    }

    private fun capSizeFor(sr: Boolean): Size {
        val target = if (sr) 3_200_000 else 12_500_000
        val c = yuvSizes.filter { abs(it.width.toFloat() / it.height - 4f / 3f) < 0.02f }
            .ifEmpty { yuvSizes.toList() }
        return c.filter { it.width * it.height <= target }.maxByOrNull { it.width * it.height }
            ?: c.minByOrNull { it.width * it.height }!!
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = FrameLayout(this)
        tv = TextureView(this)
        tv.surfaceTextureListener = this
        val sw = resources.displayMetrics.widthPixels
        val ph = sw * previewSize.width / previewSize.height   // sensor is landscape, UI is portrait
        root.addView(tv, FrameLayout.LayoutParams(sw, ph, Gravity.TOP))

        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(0xAA000000.toInt())
        panel.setPadding(dp(16), dp(12), dp(16), dp(20))

        tvFrames = TextView(this).apply { setTextColor(-1); textSize = 16f; text = "Frames: 15" }
        seek = SeekBar(this).apply { max = 30; progress = 10 }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { tvFrames.text = "Frames: ${p + 5}" }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        val presets = LinearLayout(this)
        for (n in intArrayOf(5, 10, 15, 25, 35)) {
            presets.addView(Button(this).apply {
                text = "$n"
                setOnClickListener { seek.progress = n - 5 }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        cbHdr = CheckBox(this).apply { text = "HDR+"; setTextColor(-1); isChecked = true }
        cbSr = CheckBox(this).apply { text = "Super-Res 2x"; setTextColor(-1) }
        cbSr.setOnCheckedChangeListener { _, _ -> if (!busy) camH.post { startSession() } }
        val opts = LinearLayout(this)
        opts.addView(cbHdr, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        opts.addView(cbSr, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        tvStatus = TextView(this).apply { setTextColor(0xFFAAFFAA.toInt()); text = "Ready" }
        btn = Button(this).apply { text = "Capture"; textSize = 18f; setOnClickListener { capture() } }

        panel.addView(tvFrames); panel.addView(seek); panel.addView(presets)
        panel.addView(opts); panel.addView(tvStatus); panel.addView(btn)
        root.addView(panel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
    }

    private fun status(s: String) = runOnUiThread { tvStatus.text = s }

    // --------------------------------------------------------------- camera

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { openCamera() }
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (cam != null || !tv.isAvailable) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        mgr.openCamera(camId, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) { cam = d; startSession() }
            override fun onDisconnected(d: CameraDevice) { d.close(); cam = null }
            override fun onError(d: CameraDevice, e: Int) { d.close(); cam = null; status("Camera error $e") }
        }, camH)
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { cam?.close() } catch (_: Exception) {}
        cam = null
        reader?.close(); reader = null
    }

    @Suppress("DEPRECATION")
    private fun startSession() {
        val c = cam ?: return
        val st = tv.surfaceTexture ?: return
        try { session?.close() } catch (_: Exception) {}
        session = null
        reader?.close()
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        val ps = Surface(st)
        val size = capSizeFor(cbSr.isChecked)
        val rd = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 8)
        rd.setOnImageAvailableListener(imgListener, imgH)
        reader = rd
        c.createCaptureSession(listOf(ps, rd.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                val pb = c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                pb.addTarget(ps)
                pb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                s.setRepeatingRequest(pb.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        se: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult
                    ) {
                        res.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastExp = it }
                        res.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
                    }
                }, camH)
                status("Ready  (${size.width}x${size.height})")
            }
            override fun onConfigureFailed(s: CameraCaptureSession) { status("Session configuration failed") }
        }, camH)
    }

    // -------------------------------------------------------------- capture

    private val imgListener = ImageReader.OnImageAvailableListener { r ->
        val img = try { r.acquireNextImage() } catch (e: Exception) { null }
            ?: return@OnImageAvailableListener
        val f = try { copyFrame(img) } finally { img.close() }
        val q = queue
        if (q != null) try { q.put(f) } catch (_: InterruptedException) {}
    }

    private fun copyFrame(img: Image): Frame {
        val w = img.width and 15.inv()
        val h = img.height and 15.inv()
        val p = img.planes
        return Frame(copyPlane(p[0], w, h), copyPlane(p[1], w / 2, h / 2), copyPlane(p[2], w / 2, h / 2))
    }

    private fun copyPlane(p: Image.Plane, w: Int, h: Int): ByteArray {
        val buf = p.buffer; val rs = p.rowStride; val ps = p.pixelStride
        val out = ByteArray(w * h)
        if (ps == 1) {
            for (row in 0 until h) { buf.position(row * rs); buf.get(out, row * w, w) }
        } else {
            val tmp = ByteArray(rs)
            for (row in 0 until h) {
                buf.position(row * rs)
                val len = min(rs, buf.remaining())
                buf.get(tmp, 0, len)
                for (col in 0 until w) out[row * w + col] = tmp[col * ps]
            }
        }
        return out
    }

    private fun capture() {
        if (busy) return
        val s = session ?: return
        val c = cam ?: return
        val rd = reader ?: return
        busy = true
        runOnUiThread { btn.isEnabled = false }

        val n = seek.progress + 5
        val hdr = cbHdr.isChecked
        val scale = if (cbSr.isChecked) 2 else 1
        val cw = rd.width and 15.inv(); val ch = rd.height and 15.inv()

        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        val manual = caps?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true

        // --- exposure: HDR+ => 1 EV under-exposed, short & constant (<= 40 ms)
        val baseE = lastExp.toDouble(); val baseI = lastIso.toDouble()
        var e = if (hdr) baseE * 0.5 else baseE
        var iso = baseI
        val maxT = 40_000_000.0
        if (e > maxT) { iso = baseI * e / maxT; e = maxT }
        val er = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val ir = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        if (er != null) e = e.coerceIn(er.lower.toDouble(), er.upper.toDouble())
        if (ir != null) iso = iso.coerceIn(ir.lower.toDouble(), ir.upper.toDouble())
        val ratio = (e * iso) / (baseE * baseI)
        val gain = if (manual) (1.0 / ratio).coerceIn(1.0, 8.0).toFloat() else 1f
        val sigma = 2f + 1.6f * sqrt(iso.toFloat() / 100f)

        val b = c.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        b.addTarget(rd.surface)
        if (manual) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, e.toLong())
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso.toInt())
        }
        b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        val nr = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
        if (nr != null && nr.contains(CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL))
            b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL)
        val reqs = List(n) { b.build() }

        val q = ArrayBlockingQueue<Frame>(4)
        queue = q

        Thread {
            try {
                val m = BurstMerger(cw, ch, scale, sigma, gain)
                for (i in 0 until n) {
                    val f = q.take()
                    m.add(f)
                    status("Merging ${i + 1}/$n ...")
                }
                status("Finishing (tone map / sharpen)...")
                val bmp = m.finish()
                save(bmp)
                status("Saved to Pictures/BurstCam  (${bmp.width}x${bmp.height})")
            } catch (t: Throwable) {
                status("Error: ${t.message}")
            } finally {
                queue = null
                busy = false
                runOnUiThread { btn.isEnabled = true }
            }
        }.start()

        status("Capturing $n frames ...")
        try {
            s.captureBurst(reqs, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(se: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                    status("A frame failed (reason ${f.reason})")
                }
            }, camH)
        } catch (t: Throwable) {
            status("Capture error: ${t.message}")
            busy = false
            runOnUiThread { btn.isEnabled = true }
        }
    }

    private fun save(bmp: Bitmap) {
        val mtx = Matrix().apply { postRotate(sensorOri.toFloat()) }   // portrait-locked UI
        val rot = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, mtx, true)
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "BURST_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BurstCam")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!
        contentResolver.openOutputStream(uri)!!.use { rot.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        rot.recycle(); bmp.recycle()
    }
}
