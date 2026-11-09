package io.github.wilsonnet.droidsentenceminer.ocr

import android.content.Context
import io.github.wilsonnet.droidsentenceminer.core.DsmPrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Which engine/model reads the screen, and what text it keeps. Pure data, see [OcrEngineRegistry.resolve]. */
data class OcrChoice(
    val engine: String,
    val mlKitScript: MlKitScript? = null,
    val paddleRecognizer: PaddleRecognizer? = null,
    val paddleDetector: PaddleComponent = PaddleComponent.DET_TINY,
    val profile: TextProfile,
    /** Shown in Settings: e.g. the picked engine has no model for this language's script. */
    val warning: String? = null,
)

/**
 * Creates, caches and closes OCR engines. The OCR engine and the study language are independent
 * settings: English can be read by ML Kit Latin or PaddleOCR, Korean by ML Kit Korean or PaddleOCR
 * Korean, Russian only by PaddleOCR Cyrillic. "Auto" picks per language (rules in [resolve]).
 *
 * At most one PaddleOCR engine (two ONNX sessions) is kept alive; switching models closes the old one.
 */
class OcrEngineRegistry(private val context: Context, private val prefs: DsmPrefs) {

    private data class MlKitKey(val script: MlKitScript, val twoPass: Boolean, val dropNoise: Boolean, val profile: TextProfile)

    // Two-pass/noise/profile wrappers share one actual recognizer per script.
    private val mlKitClients = ConcurrentHashMap<MlKitScript, com.google.mlkit.vision.text.TextRecognizer>()
    private val mlKitEngines = ConcurrentHashMap<MlKitKey, MlKitOcr>()

    @Volatile
    private var paddle: PaddleOcr? = null

    suspend fun choice(preferred: String? = null): OcrChoice {
        val language = prefs.studyLanguage.first()
        return resolve(
            engine = preferred ?: prefs.ocrEngine.first(),
            language = language,
            mlKitScript = prefs.ocrMlKitScript.first(),
            paddleRecognizer = prefs.ocrPaddleRecognizer.first(),
            paddleDetector = prefs.ocrPaddleDetector.first(),
            meikiInstalled = MeikiModels.installed(context),
        )
    }

    suspend fun profile(): TextProfile = TextProfile.forLanguage(prefs.studyLanguage.first())

    /**
     * The engine for the current settings. Throws [OcrUnavailableException] when the chosen engine
     * cannot run yet (PaddleOCR model not downloaded), so callers show why instead of reading nothing.
     */
    suspend fun engine(twoPass: Boolean, dropNoise: Boolean = true, preferred: String? = null): OcrEngine = withContext(Dispatchers.IO) {
        val choice = choice(preferred)
        // Diagnostic overrides must not close the engine retained by the live OCR loop.
        if (preferred == null) {
            if (choice.engine != DsmPrefs.ENGINE_PADDLE) releasePaddle()
            if (choice.engine != DsmPrefs.ENGINE_MEIKI) MeikiOcr.unload()
        }
        when (choice.engine) {
            DsmPrefs.ENGINE_MEIKI -> {
                MeikiModels.installVariants(context)
                MeikiOcr.load(MeikiModels.dir(context)) ?: mlKit(MlKitScript.JAPANESE, twoPass, dropNoise, choice.profile)
            }
            DsmPrefs.ENGINE_PADDLE -> paddle(choice.paddleRecognizer ?: PaddleRecognizer.LATIN, choice.paddleDetector, choice.profile)
            else -> {
                val script = choice.mlKitScript ?: MlKitScript.JAPANESE
                if (!script.bundled && MlKitModules.stateFlow(script).value != MlKitModules.State.Installed) {
                    when (val state = MlKitModules.check(context, script)) {
                        MlKitModules.State.Installed -> Unit
                        is MlKitModules.State.Unavailable -> throw OcrUnavailableException(state.reason)
                        else -> throw OcrUnavailableException("Download the ML Kit ${script.label} model in Settings → OCR first")
                    }
                }
                mlKit(script, twoPass, dropNoise, choice.profile)
            }
        }
    }

    private fun mlKit(script: MlKitScript, twoPass: Boolean, dropNoise: Boolean, profile: TextProfile): MlKitOcr =
        mlKitEngines.getOrPut(MlKitKey(script, twoPass, dropNoise, profile)) {
            MlKitOcr(script, twoPass = twoPass, dropNoise = dropNoise, profile = profile) {
                mlKitClients.computeIfAbsent(script) { it.newClient() }
            }
        }

    @Synchronized
    private fun paddle(recognizer: PaddleRecognizer, detector: PaddleComponent, profile: TextProfile): PaddleOcr {
        paddle?.let { if (it.recognizerModel == recognizer && it.detectorModel == detector && it.profile === profile) return it }
        paddle?.close()
        paddle = null
        return PaddleOcr.load(context, recognizer, detector, profile = profile).also { paddle = it }
    }

    /** Frees the PaddleOCR sessions (e.g. after deleting its models). */
    @Synchronized
    fun releasePaddle() {
        paddle?.close()
        paddle = null
    }

    companion object {
        /**
         * Pure resolution of the OCR settings (unit tested).
         * - meiki: Japanese only (its recognizer is trained on Japanese game text); for any other
         *   study language it behaves like auto. Not downloaded → ML Kit Japanese, as before.
         * - mlkit: the picked script, else the language's script model ([MlKitLanguageCatalog]).
         * - paddle: the picked recognizer, else the language's.
         * - auto: Japanese → meiki when downloaded, else ML Kit Japanese; scripts ML Kit reads
         *   (Latin, Chinese, Korean, Devanagari) → ML Kit; Cyrillic, Arabic, Greek → PaddleOCR.
         */
        fun resolve(
            engine: String,
            language: String,
            mlKitScript: String,
            paddleRecognizer: String,
            paddleDetector: String,
            meikiInstalled: Boolean,
        ): OcrChoice {
            val profile = TextProfile.forLanguage(language)
            val detector = PaddleComponent.fromId(paddleDetector)?.takeIf { it.isDetector } ?: PaddleComponent.DET_TINY
            val mlScript = MlKitScript.fromId(mlKitScript) ?: MlKitScript.forLanguage(language)
            val paddleRec = PaddleRecognizer.fromId(paddleRecognizer) ?: PaddleRecognizer.forLanguage(language)
            val catalog = MlKitLanguageCatalog.find(language)
            val mlKitReads = if (catalog != null) catalog.recognizer != null else
                profile in setOf(TextProfile.JAPANESE, TextProfile.CHINESE, TextProfile.KOREAN, TextProfile.DEVANAGARI, TextProfile.LATIN)
            fun mlKit(warning: String? = null) = OcrChoice(DsmPrefs.ENGINE_MLKIT, mlKitScript = mlScript, profile = profile, warning = warning)
            fun paddle(warning: String? = null) = OcrChoice(DsmPrefs.ENGINE_PADDLE, paddleRecognizer = paddleRec, paddleDetector = detector, profile = profile, warning = warning)
            fun auto(): OcrChoice = when {
                profile.isJapanese && meikiInstalled -> OcrChoice(DsmPrefs.ENGINE_MEIKI, profile = profile)
                profile.isJapanese -> OcrChoice(DsmPrefs.ENGINE_MLKIT, mlKitScript = MlKitScript.JAPANESE, profile = profile)
                profile == TextProfile.CYRILLIC || profile == TextProfile.ARABIC || profile == TextProfile.GREEK -> paddle()
                profile == TextProfile.THAI || profile == TextProfile.HEBREW ->
                    paddle("No pinned OCR model reads this script yet; PaddleOCR ${paddleRec.id} will read only Latin text")
                else -> mlKit(if (mlKitReads) null else "The official language mapping has no Android ML Kit recognizer; this uses the Latin script model")
            }
            return when (engine) {
                DsmPrefs.ENGINE_MEIKI -> if (profile.isJapanese) {
                    if (meikiInstalled) OcrChoice(DsmPrefs.ENGINE_MEIKI, profile = profile) else OcrChoice(DsmPrefs.ENGINE_MLKIT, mlKitScript = MlKitScript.JAPANESE, profile = profile)
                } else {
                    auto().copy(warning = "meikiocr reads Japanese only; using ${auto().engine} for this language")
                }
                DsmPrefs.ENGINE_MLKIT -> mlKit(if (mlKitReads || MlKitScript.fromId(mlKitScript) != null) null else "ML Kit has no recognizer for this language's script; use PaddleOCR")
                DsmPrefs.ENGINE_PADDLE -> paddle()
                else -> auto()
            }
        }
    }
}
