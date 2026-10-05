package dev.moduforge.sandbox

import dev.moduforge.sdk.NetworkGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI

/**
 * Minimal HTTP/1.1 client for script runtimes, running inside the sandbox on top of
 * [NetworkGateway.connect]. One request per connection; redirects are returned, not followed.
 */
internal object SimpleHttp {
    private const val MAX_HEAD_BYTES = 64 * 1024
    const val MAX_BODY_BYTES = 8 * 1024 * 1024

    class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    /** @throws IOException for an unusable URL, a malformed response or an oversized body. */
    suspend fun request(
        network: NetworkGateway,
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
    ): Response {
        val uri = try {
            URI(url)
        } catch (e: java.net.URISyntaxException) {
            throw IOException("malformed URL")
        }
        val tls = when (uri.scheme?.lowercase()) {
            "https" -> true
            "http" -> false
            else -> throw IOException("only http and https URLs are supported")
        }
        val host = uri.host ?: throw IOException("URL has no host")
        val port = if (uri.port > 0) uri.port else if (tls) 443 else 80
        val verb = method.uppercase()
        if (!verb.all { it in 'A'..'Z' }) throw IOException("invalid method")
        (headers.keys + headers.values).firstOrNull { '\r' in it || '\n' in it }?.let { throw IOException("invalid header") }

        val head = buildString {
            append(verb).append(' ').append(uri.rawPath.orEmpty().ifEmpty { "/" })
            uri.rawQuery?.let { append('?').append(it) }
            append(" HTTP/1.1\r\n")
            append("Host: ").append(if (uri.port > 0) "$host:$port" else host).append("\r\n")
            append("Connection: close\r\nAccept-Encoding: identity\r\n")
            if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) append("User-Agent: ModuForge\r\n")
            if (body != null) append("Content-Length: ").append(body.size).append("\r\n")
            headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("\r\n")
        }

        return network.connect(host, port, tls).use { connection ->
            withContext(Dispatchers.IO) {
                connection.output.write(head.toByteArray(Charsets.ISO_8859_1))
                if (body != null) connection.output.write(body)
                connection.output.flush()
                read(connection.input, expectBody = verb != "HEAD")
            }
        }
    }

    private fun read(input: InputStream, expectBody: Boolean): Response {
        val lines = readHead(input)
        val status = lines.firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: throw IOException("malformed HTTP response")
        val headers = lines.drop(1).mapNotNull { line ->
            line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase() to line.substring(it + 1).trim() }
        }.toMap()
        val body = when {
            !expectBody || status in 100..199 || status == 204 || status == 304 -> ByteArray(0)
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> readChunked(input)
            headers["content-length"] != null -> {
                val length = headers.getValue("content-length").toLongOrNull() ?: throw IOException("malformed Content-Length")
                if (length > MAX_BODY_BYTES) throw IOException("response body is too large")
                readExactly(input, length.toInt())
            }
            else -> readToEnd(input)
        }
        return Response(status, headers, body)
    }

    private fun readHead(input: InputStream): List<String> {
        val head = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val byte = input.read()
            if (byte < 0) throw IOException("connection closed before the response")
            head.write(byte)
            if (head.size() > MAX_HEAD_BYTES) throw IOException("response headers are too large")
            matched = if (byte == "\r\n\r\n"[matched].code) matched + 1 else if (byte == '\r'.code) 1 else 0
        }
        return head.toString(Charsets.ISO_8859_1.name()).trimEnd().split("\r\n")
    }

    private fun readLine(input: InputStream): String {
        val line = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) throw IOException("connection closed inside the response")
            if (byte == '\n'.code) return line.toString().trimEnd('\r')
            line.append(byte.toChar())
            if (line.length > MAX_HEAD_BYTES) throw IOException("malformed chunk")
        }
    }

    private fun readChunked(input: InputStream): ByteArray {
        val body = ByteArrayOutputStream()
        while (true) {
            val size = readLine(input).substringBefore(';').trim().toIntOrNull(16) ?: throw IOException("malformed chunk")
            if (size == 0) return body.toByteArray()
            if (body.size() + size > MAX_BODY_BYTES) throw IOException("response body is too large")
            body.write(readExactly(input, size))
            readLine(input)
        }
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(bytes, offset, length - offset)
            if (read < 0) throw IOException("connection closed inside the response")
            offset += read
        }
        return bytes
    }

    private fun readToEnd(input: InputStream): ByteArray {
        val body = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return body.toByteArray()
            body.write(buffer, 0, read)
            if (body.size() > MAX_BODY_BYTES) throw IOException("response body is too large")
        }
    }
}
