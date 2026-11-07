package io.github.wilsonnet.droidsentenceminer.ocr

import android.content.Context
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Availability of the ML Kit script models. Latin and Japanese are bundled in the APK; Chinese,
 * Korean and Devanagari are delivered by Google Play services, so on a device without Play services
 * (de-Googled ROMs) they cannot work at all — this is reported, never a silent empty OCR result.
 */
object MlKitModules {

    sealed interface State {
        /** Shipped in the APK: always ready, offline, no Google Play needed. */
        data object Bundled : State
        data object Installed : State
        data object NotInstalled : State
        data class Installing(val percent: Int?) : State
        data class Unavailable(val reason: String) : State
    }

    private const val TAG = "DSM-MlKit"

    private val states = MlKitScript.entries.associateWith { MutableStateFlow<State?>(null) }

    fun stateFlow(script: MlKitScript): StateFlow<State?> = states.getValue(script).asStateFlow()

    fun playServicesAvailable(context: Context): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    /** Checks (and caches in [stateFlow]) whether [script]'s model can run now. */
    suspend fun check(context: Context, script: MlKitScript): State {
        val state = if (script.bundled) {
            State.Bundled
        } else if (!playServicesAvailable(context)) {
            State.Unavailable("Needs Google Play services, which this device does not have. Use PaddleOCR instead.")
        } else {
            val client = script.newClient()
            try {
                val response = ModuleInstall.getClient(context).areModulesAvailable(client).awaitResult()
                if (response.areModulesAvailable()) State.Installed else State.NotInstalled
            } catch (e: Exception) {
                Log.w(TAG, "availability check failed for ${script.id}", e)
                State.Unavailable(e.message ?: "Google Play services could not check the model")
            } finally {
                client.close()
            }
        }
        states.getValue(script).value = state
        return state
    }

    /** Asks Google Play services to download [script]'s model; progress goes to [stateFlow]. */
    suspend fun install(context: Context, script: MlKitScript): State {
        if (script.bundled) return State.Bundled.also { states.getValue(script).value = it }
        if (!playServicesAvailable(context)) return check(context, script)
        val flow = states.getValue(script)
        val client = script.newClient()
        val listener = InstallStatusListener { update ->
            val progress = update.progressInfo
            flow.value = when (update.installState) {
                ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> State.Installed
                ModuleInstallStatusUpdate.InstallState.STATE_FAILED,
                ModuleInstallStatusUpdate.InstallState.STATE_CANCELED,
                -> State.Unavailable("Google Play services could not download the ${script.label} model (code ${update.errorCode})")
                else -> State.Installing(progress?.let { if (it.totalBytesToDownload > 0) (it.bytesDownloaded * 100 / it.totalBytesToDownload).toInt() else null })
            }
        }
        return try {
            flow.value = State.Installing(null)
            val request = ModuleInstallRequest.newBuilder().addApi(client).setListener(listener).build()
            val response = ModuleInstall.getClient(context).installModules(request).awaitResult()
            if (response.areModulesAlreadyInstalled()) flow.value = State.Installed
            flow.value ?: State.Installing(null)
        } catch (e: Exception) {
            Log.w(TAG, "install failed for ${script.id}", e)
            State.Unavailable(e.message ?: "Download failed").also { flow.value = it }
        } finally {
            client.close()
        }
    }
}

/** Thrown by an engine that cannot run yet (model not downloaded, needs Play services…); shown to the user. */
class OcrUnavailableException(message: String) : IllegalStateException(message)
