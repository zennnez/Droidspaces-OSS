package com.droidspaces.app.util

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Renders a QR code as an Android Bitmap. Using ZXing core here rather than a
 * hand-rolled encoder: QR's Reed-Solomon error correction is easy to get subtly
 * wrong in a way that only shows up as "doesn't actually scan" -- not something
 * verifiable without a physical device and scanner. ZXing core is pure Java, no
 * native/Android-specific dependencies, and is the de facto standard for this.
 */
object DiscoveryQrCode {
    fun generate(content: String, sizePx: Int = 512): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val matrix = writer.encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
            for (x in 0 until sizePx) {
                for (y in 0 until sizePx) {
                    bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }
}
