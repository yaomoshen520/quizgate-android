package com.quizgate.spike.net

import com.quizgate.proto.AnswerReply
import com.quizgate.proto.AnswerRequest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ApiClientTest {
    @Test fun `actual HTTP request uses protobuf path token and raw bytes`() {
        LocalHttpServer(200, AnswerReply.newBuilder().setCorrect(true).build().toByteArray()).use { server ->
            val response = ApiClient(server.baseUrl).post("answer", "device-token", AnswerRequest.newBuilder()
                .setQuestionId("42").setSelectedIndex(1).build().toByteArray())
            val received = server.received.get(5, TimeUnit.SECONDS)
            val request = AnswerRequest.parseFrom(received.body)
            assertTrue(AnswerReply.parseFrom(response).correct)
            assertEquals("POST /api/device/answer HTTP/1.1", received.startLine)
            assertEquals("device-token", received.headers[ApiClient.TOKEN_HEADER.lowercase()])
            assertEquals("application/x-protobuf", received.headers["content-type"])
            assertEquals("42", request.questionId)
            assertEquals(1, request.selectedIndex)
        }
    }

    @Test fun `rejected token requests reenrollment while server errors remain retryable`() {
        for (status in listOf(401, 403, 500)) {
            LocalHttpServer(status, ByteArray(0)).use { server ->
                val result = runCatching { ApiClient(server.baseUrl)
                    .post("usage", "token", ByteArray(0)) }.exceptionOrNull()
                assertTrue(result is ApiClient.ReportUnavailable)
                assertEquals(status != 500, (result as ApiClient.ReportUnavailable).needsReenroll)
                server.received.get(5, TimeUnit.SECONDS)
            }
        }
    }

    private data class Received(val startLine: String, val headers: Map<String, String>, val body: ByteArray)

    /** 只使用 java.base，Android 测试编译接口不含 com.sun.net.httpserver。 */
    private class LocalHttpServer(status: Int, body: ByteArray) : Closeable {
        private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val baseUrl = "http://127.0.0.1:${socket.localPort}"
        val received = CompletableFuture<Received>()
        private val worker = thread(isDaemon = true, name = "http-client-test") {
            try {
                socket.accept().use { connection ->
                    connection.soTimeout = 5000
                    val input = connection.getInputStream()
                    val startLine = line(input)
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val header = line(input)
                        if (header.isEmpty()) break
                        val split = header.indexOf(':')
                        check(split > 0)
                        headers[header.substring(0, split).lowercase()] = header.substring(split + 1).trim()
                    }
                    val request = ByteArray(headers["content-length"]?.toInt() ?: 0)
                    DataInputStream(input).readFully(request)
                    received.complete(Received(startLine, headers, request))
                    connection.getOutputStream().use { output ->
                        output.write(("HTTP/1.1 $status Test\r\nContent-Length: ${body.size}\r\n" +
                            "Content-Type: application/x-protobuf\r\nConnection: close\r\n\r\n")
                            .toByteArray(Charsets.ISO_8859_1))
                        output.write(body)
                    }
                }
            } catch (e: Exception) {
                received.completeExceptionally(e)
            }
        }

        override fun close() {
            socket.close()
            worker.join(1000)
        }

        private fun line(input: InputStream): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                check(value >= 0) { "Unexpected end of HTTP headers" }
                if (value == 10) break
                if (value != 13) bytes.write(value)
                check(bytes.size() <= 8192)
            }
            return bytes.toString("ISO-8859-1")
        }
    }
}
