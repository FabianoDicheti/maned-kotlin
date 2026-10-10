package dev.maned

import java.security.SecureRandom

/**
 * `multipart/form-data` for the file routes — `POST /run` and `POST /frame`.
 *
 * `/run.json` renders an `out::` image as a nested integer array and an `out::`
 * csv as a JSON tensor; the only way to get the engine's real file bytes back is
 * the multipart `/run` route, which answers with one part per declared output.
 * That route also takes its *inputs* as file bytes, so a `csv()` / `image()` /
 * `parquet()` `in::` never has to be decoded into JSON by the caller first.
 *
 * The encoding is byte-identical to manedpy's `encode_multipart` and maned-rs's
 * `multipart::encode` — same part order, same header spelling, same CRLF
 * placement. The three bindings are checked against one frozen expectation, and
 * a body that differs by a header is a second contract.
 *
 * Hand-rolled rather than delegated to an HTTP client: the parts are arbitrary
 * binary (PNG, parquet), and a text-oriented MIME parser either re-encodes them
 * or stops at the first NUL. Everything here is bytes.
 */
object Multipart {

    /**
     * One form-data part per entry, **in iteration order** — pass a
     * [LinkedHashMap] (Kotlin's `mapOf` already is one) if that order matters.
     *
     * The key is the `in::` name the bytes are for; it is repeated as the
     * `filename` because the engine's reader keys on `name` and some
     * intermediaries drop a part that has none.
     */
    fun encode(boundary: String, parts: Map<String, ByteArray>): ByteArray {
        val marker = "--$boundary".toByteArray(Charsets.US_ASCII)
        val out = java.io.ByteArrayOutputStream(
            parts.values.sumOf { it.size } + parts.size * 128 + marker.size + 4,
        )
        for ((name, data) in parts) {
            out.write(marker)
            out.write(
                (
                    "\r\nContent-Disposition: form-data; name=\"$name\"; filename=\"$name\"\r\n" +
                        "Content-Type: application/octet-stream\r\n\r\n"
                    ).toByteArray(Charsets.UTF_8),
            )
            out.write(data)
            out.write(CRLF)
        }
        out.write(marker)
        out.write("--\r\n".toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }

    /**
     * A `multipart/form-data` body → one entry per part, keyed by part name,
     * in wire order.
     *
     * A part whose Content-Disposition carries no `name=` is skipped rather than
     * given an invented key: the engine does not emit one, and guessing would
     * hide a future protocol change behind a plausible-looking result.
     *
     * @throws ManedInputException when [contentType] names no boundary.
     */
    fun decode(contentType: String, body: ByteArray): Map<String, ByteArray> {
        val boundary = boundaryOf(contentType)
            ?: throw ManedInputException(
                "no boundary in Content-Type '$contentType'; this is not a multipart body",
                status = null, body = null, script = null,
            )
        val marker = "--$boundary".toByteArray(Charsets.US_ASCII)

        val parts = LinkedHashMap<String, ByteArray>()
        for (raw in splitOn(body, marker)) {
            // The preamble before the first marker, the CRLF between parts, and
            // the "--\r\n" epilogue after the last one.
            val segment = stripLeadingLineBreaks(raw)
            if (segment.isEmpty() || segment.decodeAsciiPrefix(4) == "--" ||
                segment.decodeAsciiPrefix(4) == "--\r\n"
            ) {
                continue
            }
            val split = indexOf(segment, HEADER_END)
            if (split < 0) continue
            val head = segment.copyOfRange(0, split)
            var end = segment.size
            // The CRLF that terminates the part is framing, not payload — and
            // exactly one is removed, so a PNG whose own last bytes are \r\n
            // survives the round trip.
            if (end - (split + 4) >= 2 &&
                segment[end - 2] == CR && segment[end - 1] == LF
            ) {
                end -= 2
            }
            val name = partName(head) ?: continue
            parts[name] = segment.copyOfRange(split + 4, end)
        }
        return parts
    }

    /**
     * A boundary guaranteed not to occur inside any of [parts].
     *
     * manedpy bets 96 bits of entropy on the same thing; this checks, because
     * the check is a scan over bytes already in cache and the failure it
     * prevents — a silently truncated request body — is not an error anyone
     * would see.
     */
    fun boundaryFor(parts: Map<String, ByteArray>): String {
        repeat(8) {
            val candidate = boundary()
            val needle = candidate.toByteArray(Charsets.US_ASCII)
            if (parts.values.none { indexOf(it, needle) >= 0 }) return candidate
        }
        // Unreachable short of a broken SecureRandom: eight independent
        // 128-bit draws would all have to collide with the payload.
        return boundary()
    }

    /** `"manedkt"` + 32 hex digits from [SecureRandom]. */
    fun boundary(): String {
        val bytes = ByteArray(16)
        RANDOM.nextBytes(bytes)
        return buildString(39) {
            append("manedkt")
            for (b in bytes) append("%02x".format(b.toInt() and 0xff))
        }
    }

    /** The `boundary=` parameter of a Content-Type, unquoted. */
    internal fun boundaryOf(contentType: String): String? {
        for (token in contentType.split(';').drop(1)) {
            val key = token.substringBefore('=', "").trim()
            if (!key.equals("boundary", ignoreCase = true)) continue
            if (!token.contains('=')) continue
            return token.substringAfter('=').trim().trim('"')
        }
        return null
    }

    // -- internals --------------------------------------------------------------

    private val RANDOM = SecureRandom()
    private const val CR = '\r'.code.toByte()
    private const val LF = '\n'.code.toByte()
    private val CRLF = byteArrayOf(CR, LF)
    private val HEADER_END = byteArrayOf(CR, LF, CR, LF)

    private fun partName(head: ByteArray): String? {
        for (line in splitOn(head, CRLF)) {
            val text = line.toString(Charsets.UTF_8)
            if (!text.lowercase().startsWith("content-disposition:")) continue
            for (token in text.split(';').drop(1)) {
                if (!token.contains('=')) continue
                val key = token.substringBefore('=').trim()
                if (key.equals("name", ignoreCase = true)) {
                    return token.substringAfter('=').trim().trim('"')
                }
            }
        }
        return null
    }

    private fun stripLeadingLineBreaks(segment: ByteArray): ByteArray {
        var at = 0
        while (at < segment.size && (segment[at] == CR || segment[at] == LF)) at++
        return if (at == 0) segment else segment.copyOfRange(at, segment.size)
    }

    private fun ByteArray.decodeAsciiPrefix(n: Int): String =
        copyOfRange(0, minOf(n, size)).toString(Charsets.US_ASCII)

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun splitOn(body: ByteArray, separator: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var start = 0
        while (true) {
            val at = indexOf(body, separator, start)
            if (at < 0) break
            out.add(body.copyOfRange(start, at))
            start = at + separator.size
        }
        out.add(body.copyOfRange(start, body.size))
        return out
    }
}
