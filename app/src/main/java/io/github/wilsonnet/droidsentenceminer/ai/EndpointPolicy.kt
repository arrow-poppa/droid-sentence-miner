package io.github.wilsonnet.droidsentenceminer.ai

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Where a request may be sent. HTTPS always; plain `http://` only for loopback / private-LAN
 * hosts and only after the user ticked "allow insecure local endpoint" (the key travels in clear).
 * There is no certificate bypass anywhere.
 */
object EndpointPolicy {
    sealed interface Result {
        data class Ok(val url: HttpUrl, val insecureLocal: Boolean) : Result
        data class Rejected(val reason: String) : Result
    }

    fun check(endpoint: String, allowInsecureLocal: Boolean): Result {
        val url = endpoint.trim().toHttpUrlOrNull()
            ?: return Result.Rejected("The endpoint is not a valid URL.")
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            return Result.Rejected("Put the API key in the key field, not in the endpoint URL.")
        }
        if (url.isHttps) return Result.Ok(url, insecureLocal = false)
        if (!isLocalHost(url.host)) {
            return Result.Rejected("Endpoints must use HTTPS (plain HTTP is only allowed for local network hosts).")
        }
        if (!allowInsecureLocal) {
            return Result.Rejected(
                "Plain HTTP to a local host sends your key unencrypted. Enable \"Allow insecure local endpoint\" to use it.",
            )
        }
        return Result.Ok(url, insecureLocal = true)
    }

    /** True for the endpoint kinds that need the insecure-local acknowledgement (UI warning). */
    fun isInsecureLocal(endpoint: String): Boolean {
        val url = endpoint.trim().toHttpUrlOrNull() ?: return false
        return !url.isHttps && isLocalHost(url.host)
    }

    /** Loopback, private-LAN and link-local literals, `localhost` and mDNS `.local` names. No DNS lookups. */
    internal fun isLocalHost(rawHost: String): Boolean {
        val host = rawHost.trim().trimStart('[').trimEnd(']').lowercase()
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return true
        parseIpv4(host)?.let { (a, b) ->
            return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
        }
        if (host.contains(':')) { // IPv6 literal
            if (host == "::1" || host == "0:0:0:0:0:0:0:1") return true
            val first = host.substringBefore(':')
            val head = first.toIntOrNull(16) ?: return false
            return (head and 0xfe00) == 0xfc00 || (head and 0xffc0) == 0xfe80 // fc00::/7, fe80::/10
        }
        return false
    }

    private fun parseIpv4(host: String): Pair<Int, Int>? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val nums = parts.map { p -> p.toIntOrNull()?.takeIf { it in 0..255 && p.length <= 3 } ?: return null }
        return nums[0] to nums[1]
    }
}
