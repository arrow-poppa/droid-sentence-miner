package io.github.wilsonnet.droidsentenceminer

import android.app.Application
import android.content.Context
import io.github.wilsonnet.droidsentenceminer.anki.AnkiClient
import io.github.wilsonnet.droidsentenceminer.anki.AnkiConnectBridge
import io.github.wilsonnet.droidsentenceminer.anki.AnkiDroidBridge
import io.github.wilsonnet.droidsentenceminer.anki.CardMiner
import io.github.wilsonnet.droidsentenceminer.book.BookOcr
import io.github.wilsonnet.droidsentenceminer.book.BookPage
import io.github.wilsonnet.droidsentenceminer.core.DsmPrefs
import io.github.wilsonnet.droidsentenceminer.dict.DictDatabase
import io.github.wilsonnet.droidsentenceminer.dict.DictDownloader
import io.github.wilsonnet.droidsentenceminer.dict.TermLookup
import io.github.wilsonnet.droidsentenceminer.dict.YomitanImporter
import io.github.wilsonnet.droidsentenceminer.ocr.OcrEngine
import io.github.wilsonnet.droidsentenceminer.ocr.OcrEngineRegistry
import io.github.wilsonnet.droidsentenceminer.ocr.TextProfile
import io.github.wilsonnet.droidsentenceminer.textlog.TextLog
import io.github.wilsonnet.droidsentenceminer.textlog.TextLogDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

class DsmApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Debug builds only: boot the loopback text API (see DebugApi in src/debug).
        runCatching {
            val apiClass = Class.forName("io.github.wilsonnet.droidsentenceminer.debug.DebugApi")
            val api = apiClass.getField("INSTANCE").get(null)
            apiClass.getMethod("boot", Application::class.java).invoke(api, this)
        }
    }
}

/** Hand-rolled service locator; keeps the app dependency-light. */
class AppContainer(private val context: Context) {

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: DictDatabase by lazy { DictDatabase.build(context) }
    val prefs: DsmPrefs by lazy { DsmPrefs(context) }
    val lookup: TermLookup by lazy { TermLookup(db) }
    val importer: YomitanImporter by lazy { YomitanImporter(context, db) }
    val dictDownloader: DictDownloader by lazy { DictDownloader() }
    val appUpdater by lazy { io.github.wilsonnet.droidsentenceminer.update.AppUpdater(context, appScope, prefs) }
    val dictImports by lazy {
        io.github.wilsonnet.droidsentenceminer.dict.DictImportQueue(context, importer, dictDownloader, db, appScope) {
            runCatching { refreshDictUpdates() }
        }
    }

    val ankiDroid: AnkiDroidBridge by lazy { AnkiDroidBridge(context) }
    val ankiConnect: AnkiConnectBridge by lazy { AnkiConnectBridge(prefs) }
    val ankiClient: AnkiClient by lazy { AnkiClient(ankiDroid, ankiConnect, prefs) }
    val textLog: TextLog by lazy { TextLog(context, TextLogDatabase.build(context), prefs) }
    val miner: CardMiner by lazy { CardMiner(context, db, lookup, ankiClient, prefs, textLog) }
    val bookOcr: BookOcr by lazy { BookOcr() }

    /** The last photographed page; kept here so it survives tab switches and rotation. */
    val bookPage = MutableStateFlow<BookPage?>(null)

    /** A photo shared into the app (share sheet → Droid Sentence Miner), waiting for the Book tab to read it. */
    val sharedBookPhoto = MutableStateFlow<android.net.Uri?>(null)

    /** Available dictionary updates keyed by `DictionaryEntity.id`, populated by [refreshDictUpdates]. */
    val dictUpdates = MutableStateFlow<Map<Long, io.github.wilsonnet.droidsentenceminer.dict.DictionaryUpdate>>(emptyMap())

    suspend fun refreshDictUpdates(): Map<Long, io.github.wilsonnet.droidsentenceminer.dict.DictionaryUpdate> {
        val dicts = db.dictionaryDao().getAll()
        val updates = dictDownloader.checkUpdates(dicts)
        dictUpdates.value = updates
        return updates
    }

    val ocrEngines: OcrEngineRegistry by lazy { OcrEngineRegistry(context, prefs) }

    /**
     * The OCR engine for the current settings (engine + script/model + study language, see
     * [OcrEngineRegistry.resolve]). [twoPass]/[dropNoise] only apply to ML Kit. Throws
     * [io.github.wilsonnet.droidsentenceminer.ocr.OcrUnavailableException] when the picked model
     * isn't downloaded yet.
     */
    suspend fun ocrEngine(twoPass: Boolean, dropNoise: Boolean = true, preferred: String? = null): OcrEngine =
        ocrEngines.engine(twoPass, dropNoise, preferred)

    /** What counts as study-language text for OCR filtering and the screen layer. */
    suspend fun textProfile(): TextProfile = ocrEngines.profile()

    companion object {
        fun from(context: Context): AppContainer =
            (context.applicationContext as DsmApp).container
    }
}
