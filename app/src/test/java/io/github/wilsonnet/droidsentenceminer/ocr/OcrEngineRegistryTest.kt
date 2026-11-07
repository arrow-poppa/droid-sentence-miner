package io.github.wilsonnet.droidsentenceminer.ocr

import io.github.wilsonnet.droidsentenceminer.core.DsmPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OcrEngineRegistryTest {

    private fun resolve(engine: String, language: String, mlKit: String = "auto", paddle: String = "auto", det: String = "det_tiny", meiki: Boolean = true) =
        OcrEngineRegistry.resolve(engine, language, mlKit, paddle, det, meiki)

    @Test
    fun defaultsKeepTodaysJapaneseBehaviour() {
        assertEquals(DsmPrefs.ENGINE_MEIKI, resolve(DsmPrefs.ENGINE_MEIKI, "ja").engine)
        val noModels = resolve(DsmPrefs.ENGINE_MEIKI, "ja", meiki = false)
        assertEquals(DsmPrefs.ENGINE_MLKIT, noModels.engine)
        assertEquals(MlKitScript.JAPANESE, noModels.mlKitScript)
        assertEquals(MlKitScript.JAPANESE, resolve(DsmPrefs.ENGINE_MLKIT, "ja").mlKitScript)
    }

    @Test
    fun mlKitPicksTheLanguagesScriptModel() {
        assertEquals(MlKitScript.LATIN, resolve(DsmPrefs.ENGINE_MLKIT, "pt").mlKitScript)
        assertEquals(MlKitScript.CHINESE, resolve(DsmPrefs.ENGINE_MLKIT, "zh-Hant").mlKitScript)
        assertEquals(MlKitScript.KOREAN, resolve(DsmPrefs.ENGINE_MLKIT, "ko").mlKitScript)
        assertEquals(MlKitScript.DEVANAGARI, resolve(DsmPrefs.ENGINE_MLKIT, "mr").mlKitScript)
        assertNull(resolve(DsmPrefs.ENGINE_MLKIT, "en").warning)
    }

    @Test
    fun explicitScriptWinsOverLanguage() {
        assertEquals(MlKitScript.JAPANESE, resolve(DsmPrefs.ENGINE_MLKIT, "en", mlKit = "japanese").mlKitScript)
        assertEquals(PaddleRecognizer.KOREAN, resolve(DsmPrefs.ENGINE_PADDLE, "en", paddle = "rec_korean").paddleRecognizer)
    }

    @Test
    fun mlKitWarnsForScriptsItCannotRead() {
        assertNotNull(resolve(DsmPrefs.ENGINE_MLKIT, "ru").warning)
    }

    @Test
    fun autoRoutesScriptsMlKitLacksToPaddle() {
        assertEquals(PaddleRecognizer.CYRILLIC, resolve(DsmPrefs.ENGINE_AUTO, "ru").paddleRecognizer)
        assertEquals(PaddleRecognizer.ARABIC, resolve(DsmPrefs.ENGINE_AUTO, "ar").paddleRecognizer)
        assertEquals(PaddleRecognizer.GREEK, resolve(DsmPrefs.ENGINE_AUTO, "el").paddleRecognizer)
        assertEquals(DsmPrefs.ENGINE_MLKIT, resolve(DsmPrefs.ENGINE_AUTO, "en").engine)
        assertEquals(DsmPrefs.ENGINE_MEIKI, resolve(DsmPrefs.ENGINE_AUTO, "ja").engine)
    }

    @Test
    fun meikiIsJapaneseOnly() {
        val english = resolve(DsmPrefs.ENGINE_MEIKI, "en")
        assertEquals(DsmPrefs.ENGINE_MLKIT, english.engine)
        assertNotNull(english.warning)
    }

    @Test
    fun detectorPreferenceIsValidated() {
        assertEquals(PaddleComponent.DET_SMALL, resolve(DsmPrefs.ENGINE_PADDLE, "en", det = "det_small").paddleDetector)
        assertEquals(PaddleComponent.DET_TINY, resolve(DsmPrefs.ENGINE_PADDLE, "en", det = "rec_tiny").paddleDetector)
    }
}
