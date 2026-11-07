/*
 * Cache / cancellation / stale-guard behaviour ported from ext/js/display/ai-explanation-generator.js
 * (Yomitan BYOK fork, GPL-3.0-or-later, Copyright (C) 2023-2026 Yomitan Authors), adapted to coroutines.
 */
package io.github.wilsonnet.droidsentenceminer.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Explains a selected word in its sentence with the user's own AI provider.
 *
 * Nothing is sent unless AI is enabled and configured and the caller asks: [explain] is the
 * "Explain"/"Regenerate" tap, [explainAutomatically] honours the `autoExplain` setting.
 *
 * - Collecting the returned flow is the request: cancelling the collector (popup closed, another
 *   word looked up) cancels the OkHttp call once no other collector is attached.
 * - Identical concurrent requests share one in-flight call.
 * - `forceRefresh` bypasses the cache and replaces any in-flight call for the same request;
 *   collectors of the replaced call complete without a result, so an old answer never shows up
 *   after a newer request.
 * - Finished answers go into a bounded LRU keyed by the effective request ([ExplanationCache]).
 */
class AiExplanationService(
    private val settings: AiSettingsSource,
    client: OkHttpClient = OkHttpClient(),
    timeouts: AiTimeouts = AiTimeouts(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    cacheSize: Int = 64,
) {
    private val transport = AiTransport(client, timeouts)
    private val cache = ExplanationCache(cacheSize)
    private val lock = Any()
    private val inflight = HashMap<String, Inflight>()

    private class Inflight(val key: String) {
        /** [AiExplanationState.Idle] doubles as "superseded": the collector ends without a result. */
        val state = MutableStateFlow<AiExplanationState>(AiExplanationState.Loading)
        var job: Job? = null
        var subscribers = 0
        var superseded = false
    }

    suspend fun isConfigured(): Boolean = AiConfigCheck.isConfigured(settings.current())

    suspend fun autoExplainEnabled(): Boolean = settings.current().let { it.enabled && it.autoExplain }

    suspend fun fallbackEnabled(): Boolean = settings.current().let { it.enabled && it.fallbackForUnknownWords }

    /** A finished answer for [request] under the current settings, without sending anything. */
    suspend fun cached(request: AiRequest): AiExplanationState.Done? {
        val s = settings.current()
        if (!AiConfigCheck.isConfigured(s)) return null
        val key = try {
            ExplanationCache.keyOf(AiRequestBuilder.build(s, request, stream = false), request)
        } catch (_: AiException) {
            return null
        }
        return cache.get(key)?.let { AiExplanationState.Done(it, fromCache = true) }
    }

    /** Like [explain], but emits [AiExplanationState.Idle] and sends nothing unless `autoExplain` is on. */
    fun explainAutomatically(request: AiRequest): Flow<AiExplanationState> = flow {
        if (autoExplainEnabled()) {
            explain(request).collect { emit(it) }
        } else {
            emit(AiExplanationState.Idle)
        }
    }

    fun explain(request: AiRequest, forceRefresh: Boolean = false): Flow<AiExplanationState> = flow {
        val s = settings.current()
        if (!AiConfigCheck.isConfigured(s)) {
            emit(AiExplanationState.NotConfigured)
            return@flow
        }
        val key = try {
            ExplanationCache.keyOf(AiRequestBuilder.build(s, request, stream = false), request)
        } catch (e: AiException) {
            emit(e.toState())
            return@flow
        }
        if (!forceRefresh) {
            cache.get(key)?.let {
                emit(AiExplanationState.Done(it, fromCache = true))
                return@flow
            }
        }
        val call = attach(s, request, key, forceRefresh)
        try {
            call.state
                .onEach { if (it !== AiExplanationState.Idle) emit(it) }
                .first { it is AiExplanationState.Done || it is AiExplanationState.Error || it === AiExplanationState.Idle }
        } finally {
            detach(call)
        }
    }

    fun clearCache() = cache.clear()

    /** Cancels every in-flight request (e.g. when the overlay shuts down). */
    fun cancelAll() {
        val calls = synchronized(lock) { inflight.values.toList().also { inflight.clear() } }
        calls.forEach { call ->
            synchronized(lock) { call.superseded = true; call.state.value = AiExplanationState.Idle }
            call.job?.cancel()
        }
    }

    private fun attach(s: AiSettings, request: AiRequest, key: String, force: Boolean): Inflight = synchronized(lock) {
        var call = inflight[key]
        if (call != null && force) {
            call.superseded = true
            call.state.value = AiExplanationState.Idle
            call.job?.cancel()
            inflight.remove(key)
            call = null
        }
        if (call == null) {
            val created = Inflight(key)
            inflight[key] = created
            created.job = scope.launch { run(created, s, request) }
            call = created
        }
        call.subscribers++
        call
    }

    private fun detach(call: Inflight) {
        val cancel = synchronized(lock) {
            call.subscribers--
            val terminal = call.state.value.let { it is AiExplanationState.Done || it is AiExplanationState.Error }
            if (call.subscribers <= 0 && !terminal) {
                if (inflight[call.key] === call) inflight.remove(call.key)
                call.superseded = true
                true
            } else {
                false
            }
        }
        if (cancel) call.job?.cancel()
    }

    private suspend fun run(call: Inflight, s: AiSettings, request: AiRequest) {
        try {
            val text = transport.run(s, request) { partial ->
                synchronized(lock) {
                    if (!call.superseded) call.state.value = AiExplanationState.Streaming(partial)
                }
            }
            finish(call, AiExplanationState.Done(text, fromCache = false), text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiException) {
            finish(call, e.toState(), null)
        } catch (_: Exception) {
            finish(call, AiExplanationState.Error("The AI request failed unexpectedly.", retryable = true), null)
        }
    }

    private fun finish(call: Inflight, state: AiExplanationState, cacheText: String?) {
        synchronized(lock) {
            if (call.superseded) return // replaced or abandoned: its answer must not surface or be cached
            if (inflight[call.key] === call) inflight.remove(call.key)
            if (cacheText != null) cache.put(call.key, cacheText)
            call.state.value = state
        }
    }
}
