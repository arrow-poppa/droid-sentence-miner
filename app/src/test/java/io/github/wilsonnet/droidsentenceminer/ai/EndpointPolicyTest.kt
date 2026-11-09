package io.github.wilsonnet.droidsentenceminer.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointPolicyTest {
    private fun ok(url: String, allow: Boolean = false) = EndpointPolicy.check(url, allow) as? EndpointPolicy.Result.Ok
    private fun rejected(url: String, allow: Boolean = false) =
        EndpointPolicy.check(url, allow) is EndpointPolicy.Result.Rejected

    @Test
    fun `https is accepted and not flagged insecure`() {
        val r = ok("https://api.example.com/v1/chat/completions")!!
        assertFalse(r.insecureLocal)
    }

    @Test
    fun `http to a public host is always rejected`() {
        assertTrue(rejected("http://api.example.com/v1"))
        assertTrue(rejected("http://api.example.com/v1", allow = true))
        assertTrue(rejected("http://8.8.8.8/v1", allow = true))
        assertTrue(rejected("http://172.32.0.1/v1", allow = true))
        assertTrue(rejected("http://192.169.0.1/v1", allow = true))
    }

    @Test
    fun `http to loopback or LAN needs the explicit acknowledgement`() {
        for (url in listOf(
            "http://localhost:11434/v1", "http://127.0.0.1:8080/x", "http://127.5.5.5/x", "http://[::1]:8080/x",
            "http://192.168.1.20:1234/v1", "http://10.0.0.5/v1", "http://172.16.0.9/v1", "http://172.31.255.1/v1",
            "http://169.254.1.1/v1", "http://my-nas.local:8080/v1", "http://[fd12:3456::1]/v1", "http://[fe80::1]/v1",
        )) {
            assertTrue(url, rejected(url, allow = false))
            assertTrue(url, ok(url, allow = true)!!.insecureLocal)
            assertTrue(url, EndpointPolicy.isInsecureLocal(url))
        }
    }

    @Test
    fun `look-alike hostnames are not local`() {
        assertTrue(rejected("http://localhost.evil.com/x", allow = true))
        assertTrue(rejected("http://192.168.1.1.evil.com/x", allow = true))
        assertTrue(rejected("http://notlocal/x", allow = true))
    }

    @Test
    fun `credentials in the URL, other schemes and garbage are rejected`() {
        assertTrue(rejected("https://user:pass@api.example.com/v1"))
        assertTrue(rejected("ftp://example.com/x"))
        assertTrue(rejected("not a url"))
        assertTrue(rejected(""))
    }

    @Test
    fun `isInsecureLocal is false for https`() {
        assertFalse(EndpointPolicy.isInsecureLocal("https://localhost/x"))
        assertEquals(true, ok("https://localhost/x") != null)
    }
}
