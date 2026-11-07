package io.github.wilsonnet.droidsentenceminer.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MlKitLanguageCatalogTest {

    @Test
    fun matchesTheOfficialPageCounts() {
        val bySupport = MlKitLanguageCatalog.all.groupingBy { it.support }.eachCount()
        assertEquals(36, bySupport[MlKitSupport.SUPPORTED])
        assertEquals(16, bySupport[MlKitSupport.EXPERIMENTAL])
        assertEquals(123, bySupport[MlKitSupport.MAPPED])
        assertEquals(MlKitLanguageCatalog.all.size, MlKitLanguageCatalog.all.map { it.code.lowercase() }.toSet().size)
    }

    @Test
    fun languagesShareScriptModels() {
        for (code in listOf("en", "pt", "es", "fr", "de", "pt-PT", "es-419")) {
            assertEquals(code, MlKitScript.LATIN, MlKitLanguageCatalog.find(code)?.recognizer)
        }
        assertEquals(MlKitScript.CHINESE, MlKitLanguageCatalog.find("zh-Hant")?.recognizer)
        assertEquals(MlKitScript.DEVANAGARI, MlKitLanguageCatalog.find("sa")?.recognizer)
        assertEquals(MlKitScript.JAPANESE, MlKitLanguageCatalog.find("ja")?.recognizer)
        assertEquals(MlKitScript.KOREAN, MlKitLanguageCatalog.find("ko")?.recognizer)
    }

    @Test
    fun baseLanguageFallbackAndCyrillicMappings() {
        assertEquals("pt", MlKitLanguageCatalog.find("pt-BR")?.code)
        // Google maps these to a Cyrillic model that the Android v2 API does not ship.
        assertNull(MlKitLanguageCatalog.find("os")?.recognizer)
        assertNull(MlKitLanguageCatalog.find("tk")?.recognizer)
        assertNull(MlKitLanguageCatalog.find("ru"))
    }
}
