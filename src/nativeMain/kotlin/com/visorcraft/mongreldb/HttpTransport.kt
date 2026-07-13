// SPDX-License-Identifier: MIT OR Apache-2.0
package com.visorcraft.mongreldb

import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The low-level HTTP transport layer.
 *
 * Wraps ktor-client-curl (which uses libcurl under the hood, matching the C
 * client's HTTP implementation). Handles auth headers, JSON encoding, and
 * typed error mapping to the [MongrelDBException] hierarchy.
 */
internal class HttpTransport(
    private val baseUrl: String,
    private val authHeader: Pair<String, String>? = null,
) : AutoCloseable {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    private val client = HttpClient(Curl) {
        install(ContentNegotiation) {
            json(this@HttpTransport.json)
        }
    }

    private fun HttpRequestBuilder.applyAuth() {
        if (authHeader != null) header(authHeader.first, authHeader.second)
    }

    suspend fun getBody(path: String): String {
        val response = client.get {
            url { takeFrom(fullUrl(path)) }
            applyAuth()
        }
        return handleResponse(response)
    }

    suspend fun postBody(path: String, body: String): String {
        val response = client.post {
            url { takeFrom(fullUrl(path)) }
            contentType(ContentType.Application.Json)
            applyAuth()
            setBody(body)
        }
        return handleResponse(response)
    }

    suspend fun putBody(path: String, body: String): String {
        val response = client.put {
            url { takeFrom(fullUrl(path)) }
            contentType(ContentType.Application.Json)
            applyAuth()
            setBody(body)
        }
        return handleResponse(response)
    }

    suspend fun deleteBody(path: String): String {
        val response = client.delete {
            url { takeFrom(fullUrl(path)) }
            applyAuth()
        }
        return handleResponse(response)
    }

    suspend fun getOk(path: String): Boolean = try {
        client.get { url { takeFrom(fullUrl(path)) }; applyAuth() }.status.isSuccess()
    } catch (e: Exception) {
        false
    }

    private fun fullUrl(path: String): String =
        baseUrl.trimEnd('/') + if (path.startsWith('/')) path else "/$path"

    private suspend fun handleResponse(response: HttpResponse): String {
        val body = response.bodyAsText()
        val status = response.status
        if (status.isSuccess()) return body

        val message = parseErrorMessage(body, status)
        val code = parseErrorCode(body)
        val opIndex = parseOpIndex(body)

        when (status) {
            HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden ->
                throw AuthException(message)
            HttpStatusCode.NotFound ->
                throw NotFoundException(message)
            HttpStatusCode.Conflict ->
                throw ConflictException(message, code, opIndex)
            else ->
                throw QueryException(message)
        }
    }

    private fun parseErrorMessage(body: String, status: HttpStatusCode): String = try {
        val obj = json.parseToJsonElement(body) as? JsonObject
        obj?.get("message")?.jsonPrimitive?.contentOrNull ?: body.ifEmpty { "HTTP ${status.value}" }
    } catch (e: Exception) {
        body.ifEmpty { "HTTP ${status.value}" }
    }

    private fun parseErrorCode(body: String): String? = try {
        val obj = json.parseToJsonElement(body) as? JsonObject
        obj?.get("code")?.jsonPrimitive?.contentOrNull
    } catch (e: Exception) { null }

    private fun parseOpIndex(body: String): Int? = try {
        val obj = json.parseToJsonElement(body) as? JsonObject
        obj?.get("op_index")?.jsonPrimitive?.longOrNull?.toInt()
    } catch (e: Exception) { null }

    override fun close() {
        client.close()
    }
}
