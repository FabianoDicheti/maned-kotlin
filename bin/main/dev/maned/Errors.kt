package dev.maned

/**
 * The ManedException hierarchy (MANEDKT_PLAN.md §4.3). All carry status, body,
 * script. error_response bodies are plain text, not JSON — branch on status
 * before parsing.
 *
 * The dual-inheritance rows (Input -> IllegalArgumentException, Runtime ->
 * IllegalStateException, Unsupported -> UnsupportedOperationException) can't be
 * expressed as a single Kotlin hierarchy rooted at ManedException, so the root
 * is an interface and each class extends its JVM-conventional parent. That is
 * the same bet manedpy makes: `catch (e: IllegalArgumentException)` should do
 * something sensible for someone who never read the docs.
 */
interface ManedException {
    val status: Int?
    val body: String?
    val script: String?
}

class ManedLoadException(message: String, override val script: String? = null) :
    RuntimeException(message), ManedException {
    override val status: Int? = null
    override val body: String? = null
}

class ManedInputException(
    message: String,
    override val status: Int?,
    override val body: String?,
    override val script: String? = null,
) : IllegalArgumentException(message), ManedException

class ManedRouteException(
    message: String,
    override val status: Int?,
    override val body: String?,
    override val script: String? = null,
) : RuntimeException(message), ManedException

class ManedRuntimeException(
    message: String,
    override val status: Int?,
    override val body: String?,
    override val script: String? = null,
) : IllegalStateException(message), ManedException

class ManedUnsupportedException(
    message: String,
    override val status: Int?,
    override val body: String?,
    override val script: String? = null,
) : UnsupportedOperationException(message), ManedException

/** status == 0 — an escaped C++ exception. */
class ManedInternalException(
    message: String,
    override val body: String?,
    override val script: String? = null,
) : RuntimeException(message), ManedException {
    override val status: Int = 0
}

/**
 * The §4.3 table, as code. Returns `null` for a 2xx.
 *
 * `error_response` (`src/serve/server.cpp:154`) returns **plain text**, not
 * JSON, so the body is used verbatim as the message and never parsed.
 *
 * Statuses outside the table are mapped by class rather than dropped: any other
 * 4xx is caller error ([ManedInputException]) and any other 5xx is engine error
 * ([ManedRuntimeException]), which keeps the JVM-conventional supertypes honest
 * for routes added after this was written. (Adding a route never bumps the ABI
 * version, so new statuses are expected, not exceptional.)
 */
internal fun manedExceptionFor(status: Int, body: String, script: String?): RuntimeException? {
    val message = describe(status, body, script)
    return when {
        status == 0 -> ManedInternalException(message, body, script)
        status in 200..299 -> null
        status == 400 || status == 413 || status == 431 ->
            ManedInputException(message, status, body, script)
        status == 404 || status == 405 -> ManedRouteException(message, status, body, script)
        status == 501 -> ManedUnsupportedException(message, status, body, script)
        status == 500 -> ManedRuntimeException(message, status, body, script)
        status in 400..499 -> ManedInputException(message, status, body, script)
        else -> ManedRuntimeException(message, status, body, script)
    }
}

private fun describe(status: Int, body: String, script: String?): String {
    val where = if (script == null) "" else " [$script]"
    val text = body.trim().ifEmpty { "(no message)" }
    return if (status == 0) {
        // Distinct from every HTTP code, including the engine's own 5xx: the call
        // itself failed rather than the request.
        "maned call failed (status 0, an escaped implementation exception)$where: $text"
    } else {
        "maned returned $status$where: $text"
    }
}
