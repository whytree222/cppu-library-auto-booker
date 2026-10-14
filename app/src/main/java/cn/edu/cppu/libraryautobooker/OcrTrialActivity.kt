package cn.edu.cppu.libraryautobooker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cn.edu.cppu.libraryautobooker.ocr.DdddOcrEngine
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** An opt-in, local image experiment. It does not read a website or submit a login. */
class OcrTrialActivity : ComponentActivity() {
    companion object {
        const val EXTRA_RETURN_RESULT = "return_ocr_result"
        const val EXTRA_RESULT_TEXT = "ocr_result_text"
        private const val MAX_SOURCE_PIXELS = 80_000_000L
        private const val MAX_PREVIEW_EDGE = 1024
    }

    private var state by mutableStateOf(OcrTrialState())
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private var requestGeneration = 0L
    private val imagePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && !closed.get()) importImage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Imported authentication images and recognized text stay in memory only.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setResult(RESULT_CANCELED)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    OcrTrialScreen(
                        state = state,
                        canReturnResult = intent.getBooleanExtra(EXTRA_RETURN_RESULT, false),
                        onPickImage = {
                            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        onRecognize = ::recognize,
                        onTextChange = { state = state.copy(text = it, confidence = null) },
                        onClear = {
                            requestGeneration++
                            state = OcrTrialState(message = "图片和结果已清除，请重新选择图片。")
                        },
                        onCopy = ::copyResult,
                        onUseResult = ::returnResult,
                        onClose = ::finish
                    )
                }
            }
        }
    }

    private fun importImage(uri: Uri) {
        val generation = ++requestGeneration
        state = OcrTrialState(busy = true, message = "正在读取你选择的图片…")
        worker.execute {
            if (closed.get()) return@execute
            try {
                val bitmap = decodeSelectedImage(uri)
                deliver(generation) {
                    state = OcrTrialState(bitmap = bitmap, message = "图片已导入。请核对裁剪范围，再点击识别。")
                }
            } catch (_: Exception) {
                deliver(generation) {
                    state = OcrTrialState(message = "无法读取图片，请重新选择清晰的数字局部图片。")
                }
            } catch (_: OutOfMemoryError) {
                deliver(generation) {
                    state = OcrTrialState(message = "图片过大，请先裁剪为只包含数字的局部图片。")
                }
            }
        }
    }

    private fun decodeSelectedImage(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("Unreadable image")
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        require(bounds.outWidth.toLong() * bounds.outHeight <= MAX_SOURCE_PIXELS)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_PREVIEW_EDGE) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalArgumentException("Unsupported image")
    }

    private fun recognize() {
        if (state.busy || closed.get()) return
        val bitmap = state.bitmap ?: return
        val generation = ++requestGeneration
        state = state.copy(busy = true, text = "", confidence = null, message = "正在本机识别，首次载入模型可能稍慢…")
        worker.execute {
            if (closed.get()) return@execute
            try {
                val result = DdddOcrEngine(applicationContext).use { it.recognize(bitmap, digitsOnly = true) }
                deliver(generation) {
                    state = state.copy(
                        busy = false,
                        text = result.text,
                        confidence = result.confidence,
                        message = if (isFourDigitOcrResult(result.text)) {
                            "识别完成，用时 ${result.elapsedMillis} 毫秒。请核对图片后使用；识别结果可能有误。"
                        } else {
                            "未得到完整的四位数字。请检查裁剪范围，或手动输入正确内容。"
                        }
                    )
                }
            } catch (error: Exception) {
                deliver(generation) {
                    // Do not expose a file URI, image, recognized text, or exception body in logs/history.
                    state = state.copy(busy = false, message = "本地识别失败（${error.javaClass.simpleName}），可重新选择图片或手动输入。")
                }
            } catch (_: OutOfMemoryError) {
                deliver(generation) {
                    state = state.copy(busy = false, message = "本机内存不足，请关闭其它应用或选择更小的数字局部图片。")
                }
            } catch (_: LinkageError) {
                deliver(generation) {
                    state = state.copy(busy = false, message = "此设备未能加载本地识别组件，可暂时手动输入。")
                }
            }
        }
    }

    private fun deliver(generation: Long, action: () -> Unit) {
        if (closed.get()) return
        mainHandler.post {
            if (!closed.get() && !isFinishing && !isDestroyed && requestGeneration == generation) action()
        }
    }

    private fun copyResult() {
        if (state.busy || !isFourDigitOcrResult(state.text)) return
        val clip = ClipData.newPlainText("你确认的四位数字", state.text).apply {
            description.extras = android.os.PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        Toast.makeText(this, "已按你的操作复制，请及时粘贴并核对。", Toast.LENGTH_SHORT).show()
    }

    private fun returnResult() {
        if (state.busy || !isFourDigitOcrResult(state.text)) return
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_TEXT, state.text))
        finish()
    }

    override fun onDestroy() {
        closed.set(true)
        requestGeneration++
        mainHandler.removeCallbacksAndMessages(null)
        // Do not interrupt an in-flight native inference; use{} closes its engine on completion.
        worker.shutdown()
        state = OcrTrialState()
        super.onDestroy()
    }
}

internal data class OcrTrialState(
    val bitmap: Bitmap? = null,
    val busy: Boolean = false,
    val text: String = "",
    val confidence: Double? = null,
    val message: String = "请选择仅包含四位数字的图片，尽量不包含账号、密码或其它界面内容。"
)

internal fun isFourDigitOcrResult(text: String): Boolean = text.length == 4 && text.all { it in '0'..'9' }

@Composable
internal fun OcrTrialScreen(
    state: OcrTrialState,
    canReturnResult: Boolean,
    onPickImage: () -> Unit,
    onRecognize: () -> Unit,
    onTextChange: (String) -> Unit,
    onClear: () -> Unit,
    onCopy: () -> Unit,
    onUseResult: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("本地 OCR 试验", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F5FC), contentColor = Color(0xFF223047))) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ddddocr · 四位数字图片", fontWeight = FontWeight.SemiBold)
                Text("仅在本机识别你选择的图片，不读取网站、不上传图片、不自动登录。退出后不保存图片或结果。")
                Text("这是试验功能，不保证识别正确；验证码可能因网页刷新而失效。", fontWeight = FontWeight.SemiBold)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onPickImage, enabled = !state.busy) { Text("选择数字图片") }
            OutlinedButton(onClick = onClear, enabled = !state.busy && (state.bitmap != null || state.text.isNotEmpty())) {
                Text("清除")
            }
        }
        state.bitmap?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "你导入的待识别图片，请核对其中的四位数字",
                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 180.dp),
                contentScale = ContentScale.Fit
            )
        }
        Button(onClick = onRecognize, enabled = state.bitmap != null && !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text("识别四位数字")
        }
        if (state.busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        Text(state.message, style = MaterialTheme.typography.bodyMedium)
        state.confidence?.let {
            Text(
                "模型最弱字符置信度：${(it.coerceIn(0.0, 1.0) * 100).roundToInt()}%（不代表识别正确率）",
                style = MaterialTheme.typography.bodySmall
            )
        }
        OutlinedTextField(
            value = state.text,
            onValueChange = onTextChange,
            label = { Text("识别结果 / 手动修正") },
            singleLine = true,
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = state.text.isNotEmpty() && !isFourDigitOcrResult(state.text),
            supportingText = { Text("需要四位数字，前导零会保留。请与图片逐位核对。") },
            modifier = Modifier.fillMaxWidth()
        )
        if (canReturnResult) {
            Button(onClick = onUseResult, enabled = !state.busy && isFourDigitOcrResult(state.text), modifier = Modifier.fillMaxWidth()) {
                Text("使用我确认的四位数字")
            }
            Text("只返回你确认的文字，不会点击网页登录按钮。", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = onCopy, enabled = !state.busy && isFourDigitOcrResult(state.text), modifier = Modifier.fillMaxWidth()) {
            Text("复制我确认的结果")
        }
        Text("仅点击复制时才写入剪贴板。剪贴板可能被系统或其它应用读取，请按需使用。", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("关闭试验") }
    }
}
