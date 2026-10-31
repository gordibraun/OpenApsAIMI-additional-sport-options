package app.aaps.plugins.aps.openAPSAIMI.bridge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** Photos and screenshots travel over mobile data: keep them at most [MAX_SIDE] px on the long side. */
object BridgeImages {

    private const val MAX_SIDE = 1280
    private const val QUALITY = 82

    fun shrink(file: File) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE * 2) sample *= 2
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
            val scale = MAX_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height)
            val out = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
            file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        }
    }
}
