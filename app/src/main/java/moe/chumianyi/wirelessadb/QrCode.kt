package moe.chumianyi.wirelessadb

import android.graphics.Bitmap
import android.graphics.Color
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.NotFoundException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import java.util.EnumMap

object QrCode {

    /** 本应用自己的连接码，格式：wadb://connect?host=1.2.3.4&port=41234 */
    fun endpointText(host: String, port: Int): String = "wadb://connect?host=$host&port=$port"

    fun encode(content: String, size: Int): Bitmap {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
        hints[EncodeHintType.MARGIN] = 1
        hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val offset = y * size
            for (x in 0 until size) {
                pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    /** 解析系统「使用二维码配对设备」生成的 WIFI:T:ADB;S:...;P:...;; */
    fun parsePairing(raw: String): Pair<String, String>? {
        var text = raw.trim()
        if (text.startsWith("WIFI:", ignoreCase = true)) {
            text = text.substring(5)
        }
        val type = Regex("T:([^;]+)").find(text)?.groupValues?.get(1)?.trim()
        if (type == null || !type.equals("ADB", ignoreCase = true)) return null
        val service = Regex("S:([^;]+)").find(text)?.groupValues?.get(1)?.trim() ?: return null
        val password = Regex("P:([^;]+)").find(text)?.groupValues?.get(1)?.trim() ?: return null
        if (service.isEmpty() || password.isEmpty()) return null
        return service to password
    }

    /** 解析本应用的连接码 */
    fun parseEndpoint(raw: String): Pair<String, Int>? {
        val text = raw.trim()
        if (!text.startsWith("wadb://", ignoreCase = true)) return null
        val query = if (text.contains('?')) text.substringAfter('?') else ""
        if (query.isEmpty()) return null
        var host = ""
        var port = 0
        for (part in query.split('&')) {
            val key = part.substringBefore('=', "")
            val value = part.substringAfter('=', "")
            when (key) {
                "host" -> host = value
                "port" -> port = value.toIntOrNull() ?: 0
            }
        }
        if (host.isEmpty() || port <= 0) return null
        return host to port
    }

    /** 从 CameraX 的 YUV 帧里解二维码 */
    fun decodeImage(image: ImageProxy): String? {
        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null
        val rowStride = plane.rowStride
        val buffer = plane.buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)

        val luminance = ByteArray(width * height)
        if (rowStride == width && data.size >= width * height) {
            System.arraycopy(data, 0, luminance, 0, width * height)
        } else {
            for (row in 0 until height) {
                val srcPos = row * rowStride
                val copyLength = minOf(width, data.size - srcPos)
                if (copyLength <= 0) break
                System.arraycopy(data, srcPos, luminance, row * width, copyLength)
            }
        }

        val base = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
        val turns = when (image.imageInfo.rotationDegrees) {
            90 -> 3
            180 -> 2
            270 -> 1
            else -> 0
        }
        var source: LuminanceSource = base
        if (turns > 0) {
            try {
                repeat(turns) { source = source.rotateCounterClockwise() }
            } catch (t: Throwable) {
                source = base
            }
        }

        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
        hints[DecodeHintType.POSSIBLE_FORMATS] = listOf(BarcodeFormat.QR_CODE)
        hints[DecodeHintType.TRY_HARDER] = true
        hints[DecodeHintType.CHARACTER_SET] = "UTF-8"
        return try {
            MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (e: NotFoundException) {
            null
        } catch (t: Throwable) {
            null
        }
    }
}
