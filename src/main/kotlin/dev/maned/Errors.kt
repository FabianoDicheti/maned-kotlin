package dev.maned

/**
 * The ManedException hierarchy (MANEDKT_PLAN.md §4.3). All carry status, body,
 * script. error_response bodies are plain text, not JSON — branch on status
 * before parsing.
 *
 * The dual-inheritance rows (Input -> IllegalArgumentException, Runtime ->
 * IllegalStateException, Unsupported -> UnsupportedOperationException) can't be
 * expressed as a single Kotlin hierarchy rooted at ManedException, so the root
 * is an interface and each class extends its JVM-conventional parent.
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
