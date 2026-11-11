package com.multiapps.app

import com.multiapps.app.backup.BackupCrypto
import com.multiapps.app.net.UpdateCheck
import com.multiapps.app.proxy.ProxyTester
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

class BackupCryptoTest {
    private val data = """{"format":"multi-apps-backup","clones":[]}""".toByteArray()

    @Test fun roundTrip() {
        val enc = BackupCrypto.encrypt(data, "correct horse".toCharArray(), iterations = 20_000)
        assertArrayEquals(data, BackupCrypto.decrypt(enc, "correct horse".toCharArray()))
    }

    @Test fun wrongPasswordIsDetected() {
        val enc = BackupCrypto.encrypt(data, "correct horse".toCharArray(), iterations = 20_000)
        try { BackupCrypto.decrypt(enc, "wrong password".toCharArray()); fail() } catch (e: BackupCrypto.WrongPasswordException) { }
    }

    @Test fun tamperingIsDetected() {
        val enc = BackupCrypto.encrypt(data, "correct horse".toCharArray(), iterations = 20_000)
        enc[enc.size - 5] = (enc[enc.size - 5].toInt() xor 1).toByte()
        try { BackupCrypto.decrypt(enc, "correct horse".toCharArray()); fail() } catch (e: BackupCrypto.WrongPasswordException) { }
    }

    @Test fun headerTamperingIsDetected() {
        val enc = BackupCrypto.encrypt(data, "correct horse".toCharArray(), iterations = 20_000)
        enc[20] = (enc[20].toInt() xor 1).toByte() // inside the salt
        try { BackupCrypto.decrypt(enc, "correct horse".toCharArray()); fail() } catch (e: BackupCrypto.WrongPasswordException) { }
    }

    @Test fun rejectsOtherFiles() {
        try { BackupCrypto.decrypt("hello world, this is not a backup file at all".toByteArray(), "x".toCharArray()); fail() }
        catch (e: BackupCrypto.NotABackupException) { }
    }

    @Test fun shortPasswordRefused() {
        try { BackupCrypto.encrypt(data, "short".toCharArray()); fail() } catch (e: IllegalArgumentException) { }
    }
}

class UpdateCheckParseTest {
    @Test fun parsesServerResponse() {
        val json = JSONObject("""{"updateAvailable":true,"mandatory":true,"tester":false,"currentWithdrawn":false,
            "release":{"id":7,"versionCode":12,"versionName":"1.2.0","channel":"stable","updateType":"mandatory","changelog":"Fixes",
            "fileSize":12345,"sha256":"abc","publishedAt":1,"downloadUrl":"https://x/download/release/7"}}""")
        val c = UpdateCheck.parse(json)
        assertTrue(c.updateAvailable); assertTrue(c.mandatory)
        assertEquals(12L, c.release!!.versionCode)
        assertEquals("https://x/download/release/7", c.release!!.downloadUrl)
    }

    @Test fun parsesNoUpdate() {
        val c = UpdateCheck.parse(JSONObject("""{"updateAvailable":false,"tester":true,"currentWithdrawn":true}"""))
        assertFalse(c.updateAvailable); assertTrue(c.currentWithdrawn); assertNull(c.release)
    }
}

class ProxyTesterTest {
    @Test fun socks5RequestsAreWellFormed() {
        assertArrayEquals(byteArrayOf(5, 1, 0), ProxyTester.socks5Greeting(false))
        assertArrayEquals(byteArrayOf(5, 2, 0, 2), ProxyTester.socks5Greeting(true))
        assertArrayEquals(byteArrayOf(1, 1, 'u'.code.toByte(), 2, 'p'.code.toByte(), 'w'.code.toByte()), ProxyTester.socks5AuthRequest("u", "pw"))
        val req = ProxyTester.socks5ConnectRequest("ab.c", 443)
        assertArrayEquals(byteArrayOf(5, 1, 0, 3, 4, 'a'.code.toByte(), 'b'.code.toByte(), '.'.code.toByte(), 'c'.code.toByte(), 1, 0xBB.toByte()), req)
    }

    /** A fake SOCKS5 server that requires username/password and checks every byte. */
    @Test fun socks5HandshakeWithAuthAgainstFakeServer() {
        ServerSocket(0).use { server ->
            val t = thread {
                server.accept().use { s ->
                    val inp = DataInputStream(s.getInputStream()); val out = s.getOutputStream()
                    val greet = ByteArray(4).also { inp.readFully(it) }
                    assertArrayEquals(byteArrayOf(5, 2, 0, 2), greet)
                    out.write(byteArrayOf(5, 2))
                    val auth = ByteArray(2 + 4 + 1 + 6).also { inp.readFully(it) }
                    assertEquals("user", String(auth, 2, 4)); assertEquals("secret", String(auth, 7, 6))
                    out.write(byteArrayOf(1, 0))
                    val head = ByteArray(5).also { inp.readFully(it) }
                    val host = ByteArray(head[4].toInt()).also { inp.readFully(it) }
                    val port = ByteArray(2).also { inp.readFully(it) }
                    assertEquals("cloudflare.com", String(host))
                    assertEquals(443, ((port[0].toInt() and 0xff) shl 8) or (port[1].toInt() and 0xff))
                    out.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 80)) // success, IPv4 bound address
                    out.flush()
                }
            }
            java.net.Socket("127.0.0.1", server.localPort).use { s ->
                ProxyTester.socks5Connect(s.getInputStream(), s.getOutputStream(), "cloudflare.com", 443, "user", "secret")
            }
            t.join(5000)
        }
    }

    @Test fun socks5WrongPasswordIsReported() {
        val serverReplies = byteArrayOf(5, 2, 1, 1) // choose user/pass, then auth failure
        try {
            ProxyTester.socks5Connect(ByteArrayInputStream(serverReplies), ByteArrayOutputStream(), "x.com", 443, "u", "bad")
            fail()
        } catch (e: java.io.IOException) { assertEquals("Wrong proxy username or password", e.message) }
    }

    @Test fun httpConnectSendsAuthAndAcceptsTunnel() {
        val out = ByteArrayOutputStream()
        ProxyTester.httpConnect(ByteArrayInputStream("HTTP/1.1 200 Connection established\r\n\r\nTLSDATA".toByteArray()), out, "cloudflare.com", 443, "u", "p")
        val sent = out.toString()
        assertTrue(sent.startsWith("CONNECT cloudflare.com:443 HTTP/1.1\r\n"))
        assertTrue(sent.contains("Proxy-Authorization: Basic dTpw\r\n")) // base64("u:p")
    }

    @Test fun httpConnect407IsReported() {
        try {
            ProxyTester.httpConnect(ByteArrayInputStream("HTTP/1.1 407 Proxy Authentication Required\r\n\r\n".toByteArray()), ByteArrayOutputStream(), "x", 443, "", "")
            fail()
        } catch (e: java.io.IOException) { assertEquals("Wrong proxy username or password", e.message) }
    }

    @Test fun parsesTracePage() {
        val f = ProxyTester.parseTrace("fl=1\nh=cloudflare.com\nip=203.0.113.7\nloc=BD\n")
        assertEquals("203.0.113.7", f["ip"]); assertEquals("BD", f["loc"])
    }
}
