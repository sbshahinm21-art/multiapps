package com.multiapps.app.proxy

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Tests a proxy by opening an HTTPS connection through it to Cloudflare's trace page and reading
 * the public IP the internet sees. Implements the SOCKS5 (RFC 1928 + RFC 1929 auth) and HTTP CONNECT
 * handshakes directly, so the test does not change any app-wide network settings.
 * Pure JVM code (no Android classes) so it is covered by unit tests.
 */
object ProxyTester {
    data class Result(val ok: Boolean, val exitIp: String?, val country: String?, val millis: Long, val error: String?)

    private const val TEST_HOST = "cloudflare.com"
    private const val TEST_PORT = 443
    private const val TEST_PATH = "/cdn-cgi/trace"

    fun test(type: String, host: String, port: Int, username: String, password: String, timeoutMs: Int = 10_000): Result {
        val start = System.currentTimeMillis()
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                s.soTimeout = timeoutMs
                when (type) {
                    "SOCKS5" -> socks5Connect(s.getInputStream(), s.getOutputStream(), TEST_HOST, TEST_PORT, username, password)
                    "HTTP" -> httpConnect(s.getInputStream(), s.getOutputStream(), TEST_HOST, TEST_PORT, username, password)
                    else -> throw IOException("Unknown proxy type")
                }
                // Talk HTTPS through the tunnel, with normal certificate and host name checks.
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                (factory.createSocket(s, TEST_HOST, TEST_PORT, false) as SSLSocket).use { tls ->
                    tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    tls.startHandshake()
                    val out = tls.getOutputStream()
                    out.write("GET $TEST_PATH HTTP/1.1\r\nHost: $TEST_HOST\r\nUser-Agent: MultiApps\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    val fields = parseTrace(readHttpBody(tls.getInputStream()))
                    val ip = fields["ip"] ?: throw IOException("The proxy works, but the test page gave an unexpected answer")
                    Result(true, ip, fields["loc"], System.currentTimeMillis() - start, null)
                }
            }
        } catch (e: Exception) {
            Result(false, null, null, System.currentTimeMillis() - start, e.message ?: e.javaClass.simpleName)
        }
    }

    /** HTTP proxy tunnel (CONNECT method). Reads the proxy's answer byte by byte so no TLS bytes are consumed. */
    fun httpConnect(input: InputStream, out: OutputStream, host: String, port: Int, username: String, password: String) {
        val auth = if (username.isNotEmpty())
            "Proxy-Authorization: Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray()) + "\r\n" else ""
        out.write("CONNECT $host:$port HTTP/1.1\r\nHost: $host:$port\r\n${auth}User-Agent: MultiApps\r\n\r\n".toByteArray())
        out.flush()
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) throw IOException("Proxy closed the connection")
            head.append(b.toChar())
            if (head.length > 16 * 1024) throw IOException("Proxy sent an invalid answer")
        }
        val code = head.toString().substringBefore("\r\n").split(" ").getOrNull(1)?.toIntOrNull() ?: throw IOException("Proxy sent an invalid answer")
        if (code == 407) throw IOException("Wrong proxy username or password")
        if (code !in 200..299) throw IOException("Proxy refused the connection (HTTP $code)")
    }

    /** Builds the SOCKS5 greeting. Offers "no auth" and, when credentials exist, username/password. */
    fun socks5Greeting(withAuth: Boolean): ByteArray =
        if (withAuth) byteArrayOf(0x05, 0x02, 0x00, 0x02) else byteArrayOf(0x05, 0x01, 0x00)

    /** RFC 1929 username/password request. */
    fun socks5AuthRequest(username: String, password: String): ByteArray {
        val u = username.toByteArray(); val p = password.toByteArray()
        require(u.size in 1..255 && p.size <= 255) { "Username or password is too long" }
        return byteArrayOf(0x01, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p
    }

    /** CONNECT request with a domain name (address type 0x03), so DNS is resolved by the proxy. */
    fun socks5ConnectRequest(host: String, port: Int): ByteArray {
        val h = host.toByteArray()
        require(h.size in 1..255) { "Host name is too long" }
        return byteArrayOf(0x05, 0x01, 0x00, 0x03, h.size.toByte()) + h + byteArrayOf((port shr 8).toByte(), (port and 0xff).toByte())
    }

    private fun readFully(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n); var off = 0
        while (off < n) { val r = input.read(buf, off, n - off); if (r < 0) throw IOException("Proxy closed the connection"); off += r }
        return buf
    }

    fun socks5Connect(input: InputStream, out: OutputStream, host: String, port: Int, username: String, password: String) {
        val withAuth = username.isNotEmpty()
        out.write(socks5Greeting(withAuth)); out.flush()
        val choice = readFully(input, 2)
        if (choice[0] != 0x05.toByte()) throw IOException("Not a SOCKS5 proxy")
        when (choice[1].toInt() and 0xff) {
            0x00 -> Unit
            0x02 -> {
                if (!withAuth) throw IOException("Proxy requires a username and password")
                out.write(socks5AuthRequest(username, password)); out.flush()
                val res = readFully(input, 2)
                if (res[1] != 0x00.toByte()) throw IOException("Wrong proxy username or password")
            }
            0xff -> throw IOException("Proxy rejected the authentication methods")
            else -> throw IOException("Unsupported proxy authentication method")
        }
        out.write(socks5ConnectRequest(host, port)); out.flush()
        val head = readFully(input, 4)
        if (head[1] != 0x00.toByte()) throw IOException(socksError(head[1].toInt() and 0xff))
        val skip = when (head[3].toInt()) {
            0x01 -> 4
            0x04 -> 16
            0x03 -> (readFully(input, 1)[0].toInt() and 0xff)
            else -> throw IOException("Bad proxy reply")
        }
        readFully(input, skip + 2) // bound address + port
    }

    private fun socksError(code: Int) = when (code) {
        0x01 -> "Proxy server failure"
        0x02 -> "Connection not allowed by the proxy"
        0x03 -> "Network unreachable from the proxy"
        0x04 -> "Host unreachable from the proxy"
        0x05 -> "Connection refused"
        0x06 -> "Proxy TTL expired"
        else -> "Proxy error ($code)"
    }

    private fun readHttpBody(input: InputStream): String {
        val all = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (all.size() < 64 * 1024) { val n = input.read(buf); if (n < 0) break; all.write(buf, 0, n) }
        val text = all.toString(Charsets.UTF_8.name())
        val statusLine = text.substringBefore("\r\n")
        val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: throw IOException("Proxy sent an invalid answer")
        if (code == 407) throw IOException("Wrong proxy username or password")
        if (code != 200) throw IOException("Proxy answered with HTTP $code")
        return text.substringAfter("\r\n\r\n", "")
    }

    fun parseTrace(body: String): Map<String, String> =
        body.lineSequence().mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it).trim() to line.substring(it + 1).trim() } }.toMap()
}
