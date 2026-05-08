package com.xpunge.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import org.tensorflow.lite.Interpreter
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class XpungeDetector(private val context: Context) {

    companion object {
        private const val MODEL_ASSET = "model.enc"
        private const val INPUT_SIZE = 640
        private const val CONFIDENCE_THRESHOLD = 0.15f
        private const val NUM_DETECTIONS = 300
        private const val DETECTION_FIELDS = 6

        private val PART_B_MASKED = byteArrayOf(
            0x6E, 0xE6.toByte(), 0xB2.toByte(), 0x6C, 0x57, 0x31, 0xA7.toByte(), 0x09,
        )
        private val PART_B_MASK = byteArrayOf(
            0xC7.toByte(), 0x3B, 0x1A, 0x2B, 0x32, 0x0B, 0x18, 0xA7.toByte(),
        )
        private val PART_C = byteArrayOf(
            0x46, 0x6F, 0x8F.toByte(), 0xBE.toByte(), 0xC9.toByte(), 0x9D.toByte(), 0xBC.toByte(), 0xFD.toByte(),
        )

        internal fun buildSecret(): ByteArray {
            val partB = ByteArray(8) { i -> (PART_B_MASKED[i].toInt() xor PART_B_MASK[i].toInt()).toByte() }
            return XpungeKeyMaterial.partA + partB + PART_C + XpungeKeyMaterial.partD
        }
    }

    private val classNames = arrayOf("anus", "breast", "penis", "rear", "vagina")

    private var interpreter: Interpreter? = null

    var tier: String = "free"
        private set

    val isInitialized: Boolean get() = interpreter != null

    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3).apply {
            order(ByteOrder.nativeOrder())
        }
    private val outputBuffer = Array(1) { Array(NUM_DETECTIONS) { FloatArray(DETECTION_FIELDS) } }
    private val pixelArray = IntArray(INPUT_SIZE * INPUT_SIZE)

    @Throws(XpungeException::class)
    fun initialize(apiKey: String) {
        try {
            tier = validateKey(apiKey)
            loadModel()
        } catch (e: XpungeException) {
            throw e
        } catch (e: Exception) {
            throw XpungeException(e.message ?: "Initialization failed", e)
        }
    }

    @Throws(XpungeException::class)
    fun analyzeImage(imageBytes: ByteArray): List<Detection> {
        val interp = interpreter ?: throw XpungeException("Model not loaded — call initialize() first")
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: throw XpungeException("Could not decode image bytes")
        return letterboxAndDetect(interp, bitmap)
    }

    @Throws(XpungeException::class)
    fun analyzeFile(filePath: String): List<Detection> {
        val interp = interpreter ?: throw XpungeException("Model not loaded — call initialize() first")
        val bitmap = decodeSampledBitmap(filePath)
            ?: throw XpungeException("Could not decode image at: $filePath")
        return letterboxAndDetect(interp, correctOrientation(bitmap, filePath))
    }

    fun dispose() {
        interpreter?.close()
        interpreter = null
    }

    // ─── Key validation ───────────────────────────────────────────────────────

    private fun validateKey(apiKey: String): String {
        val parts = apiKey.split(".")
        if (parts.size != 3 || parts[0] != "xp1") throw XpungeException("Invalid API key format")

        val payloadBytes = base64UrlDecode(parts[1])
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(buildSecret(), "HmacSHA256"))
        val expected = mac.doFinal(payloadBytes).take(16).toByteArray()
        if (!expected.contentEquals(base64UrlDecode(parts[2]))) throw XpungeException("API key signature invalid")

        val payload = org.json.JSONObject(String(payloadBytes, Charsets.UTF_8))

        val exp = payload.optLong("exp", 0L)
        if (exp != 0L && exp <= System.currentTimeMillis() / 1000) throw XpungeException("API key expired")

        val callerPkg = context.packageName
        val allowed = payload.optJSONArray("pkgs")
            ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
            ?: listOfNotNull(payload.optString("pkg").takeIf { it.isNotEmpty() })
        if (allowed.isNotEmpty() && callerPkg !in allowed)
            throw XpungeException("API key is not registered for package '$callerPkg'")

        return payload.optString("tier", "free")
    }

    // ─── Model loading ────────────────────────────────────────────────────────

    private fun loadModel() {
        val encBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val modelBytes = decryptModel(encBytes)

        val tmpFile = java.io.File(context.cacheDir, "xpunge_model.tflite")
        tmpFile.writeBytes(modelBytes)

        val options = Interpreter.Options().apply {
            setNumThreads(Runtime.getRuntime().availableProcessors())
            setUseXNNPACK(false)
        }
        interpreter = Interpreter(tmpFile, options)
        tmpFile.delete()
    }

    private fun decryptModel(data: ByteArray): ByteArray {
        val secret = buildSecret()
        val rawKey = MessageDigest.getInstance("SHA-256").digest(secret)
        secret.fill(0)
        val key = SecretKeySpec(rawKey, "AES")
        rawKey.fill(0)
        val iv = data.copyOfRange(0, 16)
        val ciphertext = data.copyOfRange(16, data.size)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
        return cipher.doFinal(ciphertext)
    }

    // ─── Inference ────────────────────────────────────────────────────────────

    private fun letterboxAndDetect(interp: Interpreter, srcBitmap: Bitmap): List<Detection> {
        val imgW = srcBitmap.width.toFloat()
        val imgH = srcBitmap.height.toFloat()
        val scale = minOf(INPUT_SIZE / imgW, INPUT_SIZE / imgH)
        val newW = (imgW * scale).toInt()
        val newH = (imgH * scale).toInt()
        val padX = (INPUT_SIZE - newW) / 2
        val padY = (INPUT_SIZE - newH) / 2

        val scaled = Bitmap.createScaledBitmap(srcBitmap, newW, newH, true)
        if (scaled !== srcBitmap) srcBitmap.recycle()

        val letterboxed = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(letterboxed).apply {
            drawColor(android.graphics.Color.rgb(114, 114, 114))
            drawBitmap(scaled, padX.toFloat(), padY.toFloat(), null)
        }
        scaled.recycle()

        fillInputBuffer(letterboxed)
        letterboxed.recycle()

        interp.run(inputBuffer, outputBuffer)
        return parseDetections(outputBuffer[0], padX, padY, newW, newH)
    }

    private fun fillInputBuffer(bitmap: Bitmap) {
        inputBuffer.rewind()
        bitmap.getPixels(pixelArray, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (pixel in pixelArray) {
            inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
            inputBuffer.putFloat(((pixel shr 8)  and 0xFF) / 255.0f)
            inputBuffer.putFloat((pixel and 0xFF)          / 255.0f)
        }
        inputBuffer.rewind()
    }

    private fun parseDetections(
        detections: Array<FloatArray>,
        padX: Int, padY: Int, scaledW: Int, scaledH: Int,
    ): List<Detection> {
        val results = mutableListOf<Detection>()
        val sw = scaledW.toFloat()
        val sh = scaledH.toFloat()
        for (det in detections) {
            val conf = det[4]
            if (conf < CONFIDENCE_THRESHOLD) continue
            val x1 = det[0] * INPUT_SIZE; val y1 = det[1] * INPUT_SIZE
            val x2 = det[2] * INPUT_SIZE; val y2 = det[3] * INPUT_SIZE
            val classId = det[5].toInt()
            val nx = ((x1 - padX) / sw).coerceIn(0f, 1f)
            val ny = ((y1 - padY) / sh).coerceIn(0f, 1f)
            val nw = ((x2 - x1) / sw).coerceIn(0f, 1f)
            val nh = ((y2 - y1) / sh).coerceIn(0f, 1f)
            val label = if (classId in classNames.indices) classNames[classId] else "unknown"
            results.add(Detection(
                label      = label,
                confidence = conf.toDouble(),
                x          = nx.toDouble(),
                y          = ny.toDouble(),
                width      = nw.toDouble(),
                height     = nh.toDouble(),
            ))
        }
        return results
    }

    private fun decodeSampledBitmap(filePath: String): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(filePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        opts.inSampleSize = calculateInSampleSize(opts.outWidth, opts.outHeight)
        opts.inJustDecodeBounds = false
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888
        opts.inScaled = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            opts.inPreferredColorSpace = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
        return BitmapFactory.decodeFile(filePath, opts)
    }

    private fun calculateInSampleSize(srcW: Int, srcH: Int): Int {
        var s = 1; var hw = srcW / 2; var hh = srcH / 2
        while (hw >= INPUT_SIZE && hh >= INPUT_SIZE) { s *= 2; hw /= 2; hh /= 2 }
        return s
    }

    private fun correctOrientation(bitmap: Bitmap, filePath: String): Bitmap {
        return try {
            val exif = android.media.ExifInterface(filePath)
            val orientation = exif.getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL,
            )
            val matrix = android.graphics.Matrix()
            when (orientation) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90  -> matrix.postRotate(90f)
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL   -> matrix.postScale(1f, -1f)
                else -> return bitmap
            }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            rotated
        } catch (e: Exception) { bitmap }
    }

    private fun base64UrlDecode(s: String): ByteArray {
        var b64 = s.replace('-', '+').replace('_', '/')
        while (b64.length % 4 != 0) b64 += "="
        return Base64.decode(b64, Base64.DEFAULT)
    }
}
