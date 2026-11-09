package com.quizgate.spike.update

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.nio.file.Files
import java.security.MessageDigest

class UpdateTest {
    private val bytes = "not-an-apk-only-transfer-fixture".toByteArray()
    private val hash = UpdateTransfer.hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun spec(url: String = "https://example.com/app.apk") = UpdateManifest(7, "0.6", 26, bytes.size.toLong(), hash, url, "更新说明")
    private fun json() = JSONObject().put("schemaVersion", 1).put("versionCode", 7).put("versionName", "0.6").put("minSdk", 26)
        .put("sizeBytes", bytes.size).put("sha256", hash).put("apkUrl", "https://example.com/app.apk").put("notes", "更新说明")
    private fun rejects(action: () -> Unit) { try { action(); fail("Should reject") } catch (_: IllegalArgumentException) {} }
    @Test fun parsesVersionedManifest() { assertEquals(spec(), ManifestParser.parse(json().toString(), "https://example.com/update.json", false)) }
    @Test fun rejectsWrongSchemaAndCoercedNumbers() {
        rejects { ManifestParser.parse(json().put("schemaVersion", 2).toString(), "https://example.com/update.json", false) }
        rejects { ManifestParser.parse(json().put("versionCode", "7").toString(), "https://example.com/update.json", false) }
        rejects { ManifestParser.parse(json().put("sizeBytes", 1.5).toString(), "https://example.com/update.json", false) }
    }
    @Test fun rejectsOversizedAndInvalidManifest() {
        rejects { ManifestParser.parse(" ".repeat(16385), "https://example.com/update.json", false) }
        rejects { spec().copy(sizeBytes = UpdateManifest.MAX_APK_BYTES + 1).validate("https://example.com/update.json", false) }
        rejects { spec().copy(sha256 = "bad").validate("https://example.com/update.json", false) }
    }
    @Test fun rejectsCrossOriginAndUntrustedSchemes() {
        rejects { spec("https://other.example/app.apk").validate("https://example.com/update.json", false) }
        rejects { UpdatePolicy.url("http://example.com/app.apk", true) }
        rejects { UpdatePolicy.url("file:///tmp/app.apk", true) }
        rejects { UpdatePolicy.url("https://user:password@example.com/app.apk", false) }
        rejects { UpdatePolicy.url("https://example.com/app.apk?token=x", false) }
    }
    @Test fun debugAllowsOnlyDeclaredLocalHosts() {
        assertEquals("127.0.0.1", UpdatePolicy.url("http://127.0.0.1:18084/app.apk", true).host)
        rejects { UpdatePolicy.url("http://127.0.0.1:18084/app.apk", false) }
        rejects { UpdatePolicy.url("http://10.0.0.1.attacker.example/app.apk", true) }
    }
    @Test fun rejectsDowngradesSameVersionAndIncompatibleSdk() {
        rejects { UpdatePolicy.eligible(spec(), 7, 34) }; rejects { UpdatePolicy.eligible(spec(), 8, 34) }
        rejects { UpdatePolicy.eligible(spec().copy(minSdk = 35), 6, 34) }
        UpdatePolicy.eligible(spec(), 6, 34)
    }
    @Test fun rejectsWrongPackageVersionOrSigner() {
        rejects { UpdatePolicy.identity(spec(), "com.quizgate.spike", "com.bad.app", 7, setOf("a"), setOf("a")) }
        rejects { UpdatePolicy.identity(spec(), "com.quizgate.spike", "com.quizgate.spike", 8, setOf("a"), setOf("a")) }
        rejects { UpdatePolicy.identity(spec(), "com.quizgate.spike", "com.quizgate.spike", 7, setOf("a"), setOf("b")) }
        rejects { UpdatePolicy.identity(spec(), "com.quizgate.spike", "com.quizgate.spike", 7, emptySet(), emptySet()) }
        UpdatePolicy.identity(spec(), "com.quizgate.spike", "com.quizgate.spike", 7, setOf("a"), setOf("a"))
    }
    private fun server(body: ByteArray, status: Int = 200, contentLength: Long? = body.size.toLong(), action: (String) -> Unit) {
        val server = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true, name = "update-http-fixture") {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { /* consume request headers */ }
                    val redirect = if (status == 302) "Location: https://example.com/redirect\r\n" else ""
                    val length = contentLength?.let { "Content-Length: $it\r\n" } ?: ""
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 $status Fixture\r\n$length${redirect}Connection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        write(body); flush()
                    }
                }
            } catch (e: java.net.SocketException) { if (!server.isClosed) throw e }
        }
        try { action("http://127.0.0.1:${server.localPort}/app.apk") } finally { server.close(); worker.join(3000) }
    }
    @Test fun streamsAndVerifiesBytesAndDigest() = server(bytes) { url ->
        val dir = Files.createTempDirectory("quizgate-update").toFile(); val part = java.io.File(dir, "part")
        try { var progress = 0L; UpdateTransfer(true).download(spec(url), part) { progress = it }; assertArrayEquals(bytes, part.readBytes()); assertEquals(bytes.size.toLong(), progress) }
        finally { part.delete(); dir.delete() }
    }
    @Test fun corruptDownloadsDoNotLeaveAReusablePartialFile() = server(bytes) { url ->
        val dir = Files.createTempDirectory("quizgate-update-bad").toFile(); val part = java.io.File(dir, "part")
        try { rejects { UpdateTransfer(true).download(spec(url).copy(sha256 = "0".repeat(64)), part) {} }; assertFalse(part.exists()) }
        finally { part.delete(); dir.delete() }
    }
    @Test fun oversizedAndTruncatedDownloadsAreRejected() = server(bytes) { url ->
        val dir = Files.createTempDirectory("quizgate-update-size").toFile(); val part = java.io.File(dir, "part")
        try {
            rejects { UpdateTransfer(true).download(spec(url).copy(sizeBytes = 1), part) {} }; assertFalse(part.exists())
            rejects { UpdateTransfer(true).download(spec(url).copy(sizeBytes = bytes.size + 1L), part) {} }; assertFalse(part.exists())
        } finally { part.delete(); dir.delete() }
    }
    @Test fun redirectsAreNeverFollowed() = server(bytes, 302) { url -> rejects { UpdateTransfer(true).manifest(url) } }
    @Test fun manifestDownloadHasHardSizeBound() = server(ByteArray(16385)) { url -> rejects { UpdateTransfer(true).manifest(url) } }
    @Test fun manifestWithoutLengthIsStillBounded() = server(ByteArray(16385), contentLength = null) { url -> rejects { UpdateTransfer(true).manifest(url) } }
    @Test fun streamingLimitDoesNotDependOnContentLength() = server(bytes, contentLength = null) { url ->
        val dir = Files.createTempDirectory("quizgate-update-stream").toFile(); val part = java.io.File(dir, "part")
        try {
            UpdateTransfer(true).download(spec(url), part) {}; assertArrayEquals(bytes, part.readBytes())
            rejects { UpdateTransfer(true).download(spec(url).copy(sizeBytes = 1), part) {} }; assertFalse(part.exists())
        } finally { part.delete(); dir.delete() }
    }
    @Test fun connectionClosedMidDownloadDeletesPartialFile() = server(bytes.copyOf(bytes.size / 2), contentLength = bytes.size.toLong()) { url ->
        val dir = Files.createTempDirectory("quizgate-update-cut").toFile(); val part = java.io.File(dir, "part")
        try { rejects { UpdateTransfer(true).download(spec(url), part) {} }; assertFalse(part.exists()) }
        finally { part.delete(); dir.delete() }
    }
}
