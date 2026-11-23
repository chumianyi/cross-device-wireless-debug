package moe.chumianyi.wirelessadb

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_QR_TEXT = "qr_text"
    }

    private lateinit var previewView: PreviewView
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private lateinit var executor: ExecutorService
    private var finished = false
    private var lastDecodeAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        previewView = findViewById(R.id.previewView)
        executor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "没有相机权限，无法扫码", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                bindCamera()
            } catch (t: Throwable) {
                Toast.makeText(this, "相机初始化失败：${t.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(1280, 720))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        imageAnalysis.setAnalyzer(executor) { image -> analyze(image) }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalysis
            )
            analysis = imageAnalysis
        } catch (t: Throwable) {
            Toast.makeText(this, "无法打开相机：${t.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun analyze(image: ImageProxy) {
        try {
            if (finished) return
            val now = System.currentTimeMillis()
            if (now - lastDecodeAt < 300L) return
            lastDecodeAt = now
            val text = QrCode.decodeImage(image)
            if (text != null) {
                finished = true
                runOnUiThread { deliver(text) }
            }
        } catch (t: Throwable) {
            // 单帧失败忽略，等下一帧
        } finally {
            image.close()
        }
    }

    private fun deliver(text: String) {
        val data = Intent()
        data.putExtra(EXTRA_QR_TEXT, text)
        setResult(RESULT_OK, data)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            analysis?.clearAnalyzer()
        } catch (t: Throwable) {
            // ignore
        }
        try {
            cameraProvider?.unbindAll()
        } catch (t: Throwable) {
            // ignore
        }
        try {
            executor.shutdown()
        } catch (t: Throwable) {
            // ignore
        }
    }
}
