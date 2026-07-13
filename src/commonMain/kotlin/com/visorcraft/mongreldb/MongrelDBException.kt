// SPDX-License-Identifier: MIT OR Apache-2.0
package com.visorcraft.mongreldb

/**
 * Base exception for all MongrelDB client errors.
 *
 * Mirrors the exception hierarchy of the C++ and Kotlin/JVM clients:
 *   - [AuthException] — HTTP 401/403
 *   - [NotFoundException] — HTTP 404
 *   - [ConflictException] — HTTP 409 (unique/fk/check violation)
 *   - [QueryException] — everything else (400, 5xx, network, malformed JSON)
 */
open class MongrelDBException : RuntimeException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
}

/** Authentication or authorization failure (HTTP 401 or 403). */
class AuthException(message: String) : MongrelDBException(message)

/** The requested resource was not found (HTTP 404). */
class NotFoundException(message: String) : MongrelDBException(message)

/**
 * A constraint violation at commit time (HTTP 409).
 *
 * @param code the structured server error code, if available.
 * @param opIndex the index of the offending operation in the batch, if available.
 */
class ConflictException(
    message: String,
    val code: String? = null,
    val opIndex: Int? = null,
) : MongrelDBException(message)

/** Query, network, or malformed-response failure (HTTP 400, 5xx, or transport). */
class QueryException(message: String, cause: Throwable? = null) :
    MongrelDBException(message, cause)
