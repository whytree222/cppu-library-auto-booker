package cn.edu.cppu.libraryautobooker.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/**
 * Local-only ddddocr common_old inference, with no networking or diagnostic logging.
 * Construct, recognize and close on a worker thread. This engine serializes inference
 * and close, but callers must not recycle a supplied Bitmap while recognition runs.
 *
 * Model and alphabet: sml2h3/ddddocr c40f56f95412e10bcb9bd0bd24411e92f896d238,
 * common_old.onnx + charsets.py CHARSET_OLD. The frozen 1.5.8 inference contract
 * (6af073d867a61fba1212eec10295d47454ec82a3) uses height 64, Lanczos resize,
 * RGB-to-L luminance, normalization [-1, 1], and greedy CTC with blank index 0.
 */
class DdddOcrEngine(
    context: Context,
    modelAsset: String = "ocr/common_old.onnx",
    charsetAsset: String = "ocr/charsets.json"
) : AutoCloseable {
    data class Result(
        val text: String,
        /** Minimum emitted-character softmax over the full alphabet, not calibrated accuracy. */
        val confidence: Double,
        val unrestrictedText: String,
        val elapsedMillis: Long
    )

    private val environment = OrtEnvironment.getEnvironment()
    private val charset: List<String>
    private val sessionOptions: OrtSession.SessionOptions
    private val session: OrtSession
    private val inputName: String
    private var closed = false

    init {
        val assets = context.applicationContext.assets
        val charsetText = assets.open(charsetAsset).use {
            readLimited(it, MAX_CHARSET_BYTES).toString(Charsets.UTF_8)
        }
        val alphabet = if (charsetText.trimStart().startsWith("[")) JSONArray(charsetText)
        else JSONObject(charsetText).getJSONArray("charset")
        require(alphabet.length() in 11..20_000) { "字符表大小无效" }
        charset = List(alphabet.length()) { alphabet.getString(it) }
        require(charset[0].isEmpty() && charset.drop(1).all { it.isNotEmpty() && it.length <= 16 }) {
            "字符表与 CTC 模型不匹配"
        }
        val model = assets.open(modelAsset).use { readLimited(it, MAX_MODEL_BYTES) }
        sessionOptions = OrtSession.SessionOptions()
        val opened = try {
            sessionOptions.setIntraOpNumThreads(2)
            sessionOptions.setInterOpNumThreads(1)
            sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            environment.createSession(model, sessionOptions)
        } catch (error: Throwable) {
            sessionOptions.close()
            throw error
        }
        try {
            require(opened.inputNames.size == 1) { "模型输入数量无效" }
            inputName = opened.inputNames.first()
            val input = opened.inputInfo[inputName]?.info as? TensorInfo
                ?: error("模型输入类型无效")
            require(input.shape.size == 4 && (input.shape[0] < 0 || input.shape[0] == 1L) &&
                input.shape[1] == 1L && input.shape[2] == TARGET_HEIGHT.toLong()) {
                "模型输入尺寸不匹配"
            }
            session = opened
        } catch (error: Throwable) {
            opened.close()
            sessionOptions.close()
            throw error
        }
    }

    @Synchronized
    fun recognize(bytes: ByteArray, digitsOnly: Boolean = false): Result {
        check(!closed) { "识别引擎已关闭" }
        require(bytes.isNotEmpty() && bytes.size <= MAX_IMAGE_BYTES) { "图片文件过大或为空" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        validateDimensions(bounds.outWidth, bounds.outHeight)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: throw IllegalArgumentException("无法读取图片")
        return try { recognize(bitmap, digitsOnly) } finally { bitmap.recycle() }
    }

    @Synchronized
    fun recognize(bitmap: Bitmap, digitsOnly: Boolean = false): Result {
        check(!closed) { "识别引擎已关闭" }
        require(!bitmap.isRecycled) { "图片已释放" }
        validateDimensions(bitmap.width, bitmap.height)
        val started = SystemClock.elapsedRealtime()
        val prepared = preparePixels(bitmap)
        val buffer = ByteBuffer.allocateDirect(prepared.values.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(prepared.values).rewind()
        OnnxTensor.createTensor(environment, buffer,
            longArrayOf(1, 1, TARGET_HEIGHT.toLong(), prepared.width.toLong())).use { input ->
            session.run(mapOf(inputName to input)).use { outputs ->
                val output = outputs[0] as? OnnxTensor ?: error("模型输出类型无效")
                val shape = output.info.shape
                val steps = when {
                    shape.size == 3 && shape[1] == 1L -> shape[0]
                    shape.size == 3 && shape[0] == 1L -> shape[1]
                    shape.size == 2 -> shape[0]
                    else -> error("模型输出尺寸无效")
                }
                require(shape.last() == charset.size.toLong() && steps in 1..MAX_STEPS.toLong()) {
                    "模型输出与字符表不匹配"
                }
                val count = steps.toInt() * charset.size
                val raw = FloatArray(count)
                val scores = output.floatBuffer ?: error("模型输出不是浮点数据")
                require(scores.remaining() == count) { "模型输出长度无效" }
                scores.get(raw)
                val unrestricted = CtcDecoder.decode(raw, steps.toInt(), charset)
                val selected = if (digitsOnly) CtcDecoder.decode(raw, steps.toInt(), charset, true)
                    else unrestricted
                return Result(selected.text, selected.confidence, unrestricted.text,
                    SystemClock.elapsedRealtime() - started)
            }
        }
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            try { session.close() } finally { sessionOptions.close() }
        }
        // OrtEnvironment is process-wide and must not be closed by one UI task.
    }

    private data class Prepared(val width: Int, val values: FloatArray)

    private fun preparePixels(bitmap: Bitmap): Prepared {
        val width = floor(bitmap.width.toDouble() * TARGET_HEIGHT / bitmap.height).toInt()
        require(width in MIN_TARGET_WIDTH..MAX_TARGET_WIDTH) { "图片比例不适合识别" }
        val source = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(source, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val horizontal = coefficients(bitmap.width, width)
        val vertical = coefficients(bitmap.height, TARGET_HEIGHT)
        // Resize the RGB channels before L conversion, as in the upstream PIL pipeline.
        val intermediate = IntArray(width * bitmap.height)
        for (y in 0 until bitmap.height) {
            for (x in 0 until width) {
                intermediate[y * width + x] = filteredRgb(source, y * bitmap.width,
                    1, horizontal[x])
            }
        }
        val normalized = FloatArray(width * TARGET_HEIGHT)
        for (y in 0 until TARGET_HEIGHT) {
            for (x in 0 until width) {
                val rgb = filteredRgb(intermediate, x, width, vertical[y])
                val r = (rgb ushr 16) and 255
                val g = (rgb ushr 8) and 255
                val b = rgb and 255
                // Pillow RGB->L integer weights and rounding.
                val luminance = (r * 19595 + g * 38470 + b * 7471 + 32768) ushr 16
                normalized[y * width + x] = luminance / 127.5f - 1f
            }
        }
        return Prepared(width, normalized)
    }

    private data class Filter(val first: Int, val weights: DoubleArray)

    private fun coefficients(input: Int, output: Int): Array<Filter> {
        val scale = input.toDouble() / output
        val filterScale = maxOf(1.0, scale)
        val support = 3.0 * filterScale
        return Array(output) { position ->
            val center = (position + 0.5) * scale
            val first = maxOf(0, floor(center - support + 0.5).toInt())
            val end = minOf(input, floor(center + support + 0.5).toInt())
            val weights = DoubleArray(end - first) { offset ->
                lanczos((first + offset - center + 0.5) / filterScale)
            }
            val sum = weights.sum()
            require(sum != 0.0)
            for (index in weights.indices) weights[index] /= sum
            Filter(first, weights)
        }
    }

    private fun lanczos(value: Double): Double {
        val x = abs(value)
        if (x == 0.0) return 1.0
        if (x >= 3.0) return 0.0
        return (sin(PI * x) / (PI * x)) * (sin(PI * x / 3.0) / (PI * x / 3.0))
    }

    private fun filteredRgb(pixels: IntArray, base: Int, stride: Int, filter: Filter): Int {
        var r = 0.0
        var g = 0.0
        var b = 0.0
        filter.weights.forEachIndexed { offset, weight ->
            val pixel = pixels[base + (filter.first + offset) * stride]
            r += ((pixel ushr 16) and 255) * weight
            g += ((pixel ushr 8) and 255) * weight
            b += (pixel and 255) * weight
        }
        return (floor(r + 0.5).toInt().coerceIn(0, 255) shl 16) or
            (floor(g + 0.5).toInt().coerceIn(0, 255) shl 8) or
            floor(b + 0.5).toInt().coerceIn(0, 255)
    }

    companion object {
        private const val TARGET_HEIGHT = 64
        private const val MIN_TARGET_WIDTH = 8
        private const val MAX_TARGET_WIDTH = 1024
        private const val MAX_STEPS = 1024
        private const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
        private const val MAX_MODEL_BYTES = 80 * 1024 * 1024
        private const val MAX_CHARSET_BYTES = 512 * 1024

        private fun validateDimensions(width: Int, height: Int) {
            require(width in 1..2048 && height in 1..2048 &&
                width.toLong() * height <= 4_000_000) { "图片尺寸过大或无效" }
            require(floor(width.toDouble() * TARGET_HEIGHT / height).toInt()
                in MIN_TARGET_WIDTH..MAX_TARGET_WIDTH) { "图片比例不适合识别" }
        }

        private fun readLimited(stream: java.io.InputStream, limit: Int): ByteArray {
            val result = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                require(result.size().toLong() + count <= limit) { "识别资源大小无效" }
                result.write(buffer, 0, count)
            }
            require(result.size() > 0) { "识别资源为空" }
            return result.toByteArray()
        }
    }
}
