package io.github.wilsonnet.droidsentenceminer.ocr

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * PaddleOCR models (Apache-2.0, PaddlePaddle on Hugging Face), pinned to the same immutable revisions
 * as Chimahon's `tools/ocr-models/models.json` (arrow-poppa/chimahon, branch
 * agent/byok-ai-v2.4.8-ocr-models). DSM runs the original FP32 ONNX graphs with ONNX Runtime; Chimahon
 * converts the same sources to NCNN FP16 instead. None of them is INT8 (checked: every initializer is
 * FLOAT/INT64, no Quantize ops).
 */
enum class PaddleComponent(
    val id: String,
    val repo: String,
    val revision: String,
    val onnxSha256: String,
    val onnxBytes: Long,
    val ymlSha256: String,
    val label: String,
) {
    DET_TINY("det_tiny", "PaddlePaddle/PP-OCRv6_tiny_det_onnx", "2ba1506c0380b8f0b03dd142459aac66d4421f6c",
        "193bab7a04fca699a6c82e6abb5b81bdb28177f0abd4062552b04908dafb19f8", 1_780_590,
        "3ac018be6f97499a08faa3bbdeb33640968d9307f6736d152902747a9f259593", "PP-OCRv6 Tiny detector"),
    DET_SMALL("det_small", "PaddlePaddle/PP-OCRv6_small_det_onnx", "28fe5895c24fd108c19eb3e8479f4ab385fbfc62",
        "d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e", 9_880_512,
        "193f435274bf9f0b5f71a929bbfbcf148282df7e633b34e7c373e8f44741b516", "PP-OCRv6 Small detector"),
    REC_TINY("rec_tiny", "PaddlePaddle/PP-OCRv6_tiny_rec_onnx", "2612ab37152ae0a677521bae4e1e3d4fb4cf7c30",
        "9ef676d6ed3c88256a2d92c640c44f25b0c40947e111b14b8be8f594091563e6", 4_462_639,
        "66170210bad538e83fff3c4a3867e547d6bf20b50d64b20347c4b913f3034ea1", "PP-OCRv6 Tiny recognizer (Latin)"),
    REC_SMALL("rec_small", "PaddlePaddle/PP-OCRv6_small_rec_onnx", "b8f84f0b80c529de40b4fbb3544b84fa7233a513",
        "5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634", 21_159_378,
        "ab078671bb49f06228eadccd34f1bb501e157f7a047095ffb943ba81512c77d1", "PP-OCRv6 Small recognizer (Chinese / Japanese / Latin)"),
    REC_CYRILLIC("rec_cyrillic", "PaddlePaddle/cyrillic_PP-OCRv5_mobile_rec_onnx", "2cef88145434beb8afa9dd82d77d799eb1ad7b29",
        "5371ee1ddaa7983cc62d0818d99e982b6804638c85e4f960d59a574094e172e5", 8_048_799,
        "5c76cc91fa98410178a09f498db10050d0ec1634a660053d3005ab7be581f501", "PP-OCRv5 Cyrillic recognizer"),
    REC_ARABIC("rec_arabic", "PaddlePaddle/arabic_PP-OCRv5_mobile_rec_onnx", "14aaedcd75825982689ecf5cd64ab33ee083215a",
        "799113ebf267fbe742deb99eb36e8d42c9ddc5291ceacf92add41b4d52a59110", 7_998_947,
        "21368419e6c016c31db55d316d59e11c128e1913e6e6fe10287084710043d3a6", "PP-OCRv5 Arabic recognizer"),
    REC_GREEK("rec_el", "PaddlePaddle/el_PP-OCRv5_mobile_rec_onnx", "8152b89d2ee0e1d4c92decab75fe75e5e3d836b6",
        "2acf17fcaea2bc81b878e311e6263b8885f48bb03796f75f9f30ed3242bbaa6d", 7_808_735,
        "17d85b2fe2d2f24cd4ab07bcbc33e0c126859b956ced36e281dc65e2d0c1f0bf", "PP-OCRv5 Greek recognizer"),
    REC_KOREAN("rec_korean", "PaddlePaddle/korean_PP-OCRv5_mobile_rec_onnx", "5c6f574b8e2230adf4287b33e736d71b9fabd28e",
        "92f0b7785e64fc9090106a241cf4c1eb97472824558272751b88a2a4476d3a08", 13_418_787,
        "f757fa1c40e99edcf27e9cce879b93eb2a51fa46f5ef39095689b8c37dd75998", "PP-OCRv5 Korean recognizer"),
    ;

    val isDetector: Boolean get() = id.startsWith("det_")

    fun url(file: String) = "https://huggingface.co/$repo/resolve/$revision/$file"

    companion object {
        fun fromId(id: String?): PaddleComponent? = entries.firstOrNull { it.id == id }
    }
}

/** A recognition model choice and the scripts it reads ([profile] = what auto OCR keeps). */
enum class PaddleRecognizer(val component: PaddleComponent, val profile: TextProfile, val rightToLeft: Boolean = false) {
    LATIN(PaddleComponent.REC_TINY, TextProfile.LATIN),
    MULTILINGUAL(PaddleComponent.REC_SMALL, TextProfile.CHINESE),
    CYRILLIC(PaddleComponent.REC_CYRILLIC, TextProfile.CYRILLIC),
    ARABIC(PaddleComponent.REC_ARABIC, TextProfile.ARABIC, rightToLeft = true),
    GREEK(PaddleComponent.REC_GREEK, TextProfile.GREEK),
    KOREAN(PaddleComponent.REC_KOREAN, TextProfile.KOREAN),
    ;

    val id: String get() = component.id

    companion object {
        fun fromId(id: String?): PaddleRecognizer? = entries.firstOrNull { it.id == id }

        /** The recognizer for a study language. */
        fun forLanguage(code: String): PaddleRecognizer = when (TextProfile.forLanguage(code)) {
            TextProfile.JAPANESE, TextProfile.CHINESE -> MULTILINGUAL
            TextProfile.KOREAN -> KOREAN
            TextProfile.CYRILLIC -> CYRILLIC
            TextProfile.ARABIC -> ARABIC
            TextProfile.GREEK -> GREEK
            else -> LATIN
        }
    }
}

/**
 * On-demand PaddleOCR model manager: downloads one component at a time from its pinned revision,
 * verifies SHA-256, writes the recognizer's character list, and only then renames the staging
 * directory into place, so a cancelled or failed download never leaves a half-installed model.
 */
object PaddleModels {

    sealed interface State {
        data object NotInstalled : State
        data class Downloading(val percent: Int) : State
        data object Installed : State
        data class Failed(val message: String) : State
    }

    private const val ONNX = "inference.onnx"
    private const val YML = "inference.yml"
    const val DICT = "dict.txt"
    private const val INFO = "MODEL_INFO"

    private val states = PaddleComponent.entries.associateWith { MutableStateFlow<State?>(null) }

    fun stateFlow(component: PaddleComponent): StateFlow<State?> = states.getValue(component).asStateFlow()

    fun root(context: Context): File = File(context.filesDir, "models/paddle").apply { mkdirs() }

    fun dir(context: Context, component: PaddleComponent): File = File(root(context), component.id)

    /**
     * Installed = the info file records the pinned hash and the ONNX file has the pinned size (a full
     * re-hash of 21 MB on every app start would cost more than it protects; install verified it).
     */
    fun installed(context: Context, component: PaddleComponent): Boolean {
        val dir = dir(context, component)
        val info = File(dir, INFO)
        val onnx = File(dir, ONNX)
        val ok = info.isFile && info.readText().trim() == "${component.revision} ${component.onnxSha256}" &&
            onnx.isFile && onnx.length() == component.onnxBytes &&
            (component.isDetector || File(dir, DICT).let { it.isFile && it.length() > 0 })
        return ok
    }

    fun state(context: Context, component: PaddleComponent): State {
        val current = states.getValue(component).value
        if (current is State.Downloading) return current
        val state = if (installed(context, component)) State.Installed else (current as? State.Failed ?: State.NotInstalled)
        states.getValue(component).value = state
        return state
    }

    fun modelFile(context: Context, component: PaddleComponent) = File(dir(context, component), ONNX)

    fun dictFile(context: Context, component: PaddleComponent) = File(dir(context, component), DICT)

    fun delete(context: Context, component: PaddleComponent) {
        dir(context, component).deleteRecursively()
        File(root(context), "${component.id}.part").deleteRecursively()
        states.getValue(component).value = State.NotInstalled
    }

    /** Downloads every missing component of [components]. Returns null on success, else the error. */
    suspend fun download(context: Context, components: List<PaddleComponent>): String? {
        for (component in components) {
            if (installed(context, component)) {
                states.getValue(component).value = State.Installed
                continue
            }
            downloadOne(context, component)?.let { return it }
        }
        return null
    }

    private suspend fun downloadOne(context: Context, component: PaddleComponent): String? = withContext(Dispatchers.IO) {
        val flow = states.getValue(component)
        val staging = File(root(context), "${component.id}.part")
        staging.deleteRecursively()
        staging.mkdirs()
        var done = false
        try {
            flow.value = State.Downloading(0)
            val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
            val yml = File(staging, YML)
            fetch(client, component.url(YML), yml) { }
            check(sha256(yml) == component.ymlSha256) { "${component.id}: $YML hash mismatch" }
            val onnx = File(staging, ONNX)
            fetch(client, component.url(ONNX), onnx) { read -> flow.value = State.Downloading((read * 100 / component.onnxBytes).toInt().coerceIn(0, 99)) }
            check(onnx.length() == component.onnxBytes && sha256(onnx) == component.onnxSha256) { "${component.id}: $ONNX hash mismatch" }
            if (!component.isDetector) {
                val chars = PaddleDecoder.parseCharacterDict(yml.readText())
                check(chars.isNotEmpty()) { "${component.id}: no character_dict in $YML" }
                File(staging, DICT).writeText(chars.joinToString("\n"))
            }
            yml.delete()
            File(staging, INFO).writeText("${component.revision} ${component.onnxSha256}\n")
            val target = dir(context, component)
            target.deleteRecursively()
            check(staging.renameTo(target)) { "Could not install ${component.id}" }
            done = true
            flow.value = State.Installed
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            flow.value = State.NotInstalled
            throw e
        } catch (e: Exception) {
            val message = e.message ?: "Download failed"
            flow.value = State.Failed(message)
            message
        } finally {
            if (!done) staging.deleteRecursively()
        }
    }

    private suspend fun fetch(client: OkHttpClient, url: String, target: File, onBytes: (Long) -> Unit) {
        val call = client.newCall(Request.Builder().url(url).build())
        val context = kotlin.coroutines.coroutineContext
        call.execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code} for ${target.name}" }
            val body = checkNotNull(response.body) { "Empty response for ${target.name}" }
            var read = 0L
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        context.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        onBytes(read)
                    }
                }
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
