// SPDX-License-Identifier: MIT OR Apache-2.0
package com.visorcraft.mongreldb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Structural hybrid logical clock from durable recovery (0.64+).
 * Parsed from nested `last_commit_hlc` — never from free-form status text.
 */
data class CommitHlc(
    val physicalMicros: Long,
    val logical: Int = 0,
    val nodeTiebreaker: Int = 0,
) {
    companion object {
        fun fromJson(raw: JsonElement?): CommitHlc? {
            val obj = raw as? JsonObject ?: return null
            val phys = obj["physical_micros"]?.jsonPrimitive?.longOrNull ?: return null
            val logical = obj["logical"]?.jsonPrimitive?.intOrNull ?: 0
            val node = obj["node_tiebreaker"]?.jsonPrimitive?.intOrNull ?: 0
            return CommitHlc(phys, logical, node)
        }
    }
}

/** Nested durable recovery payload (`outcome` / `durable` JSON object). */
data class DurableOutcome(
    val committed: Boolean? = null,
    val committedStatements: Int? = null,
    val lastCommitEpoch: Long? = null,
    val lastCommitEpochText: String? = null,
    val lastCommitHlc: CommitHlc? = null,
    val firstCommitStatementIndex: Int? = null,
    val lastCommitStatementIndex: Int? = null,
    val completedStatements: Int? = null,
    val statementIndex: Int? = null,
    val serialization: String = "",
    val serializationState: String? = null,
    val terminalState: String? = null,
) {
    companion object {
        fun fromJson(raw: JsonElement?): DurableOutcome {
            val obj = raw as? JsonObject ?: return DurableOutcome()
            return DurableOutcome(
                committed = obj["committed"]?.jsonPrimitive?.booleanOrNull,
                committedStatements = obj["committed_statements"]?.jsonPrimitive?.intOrNull,
                lastCommitEpoch = obj["last_commit_epoch"]?.jsonPrimitive?.longOrNull,
                lastCommitEpochText = obj["last_commit_epoch_text"]?.jsonPrimitive?.contentOrNull,
                lastCommitHlc = CommitHlc.fromJson(obj["last_commit_hlc"]),
                firstCommitStatementIndex =
                    obj["first_commit_statement_index"]?.jsonPrimitive?.intOrNull,
                lastCommitStatementIndex =
                    obj["last_commit_statement_index"]?.jsonPrimitive?.intOrNull,
                completedStatements = obj["completed_statements"]?.jsonPrimitive?.intOrNull,
                statementIndex = obj["statement_index"]?.jsonPrimitive?.intOrNull,
                serialization = obj["serialization"]?.jsonPrimitive?.contentOrNull ?: "",
                serializationState = obj["serialization_state"]?.jsonPrimitive?.contentOrNull,
                terminalState = obj["terminal_state"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }
}

/** Decoded GET /queries/{query_id} status for durable recovery. */
data class QueryStatus(
    val queryId: String = "",
    val status: String = "",
    val state: String = "",
    val serverState: String = "",
    val terminalState: String? = null,
    val committed: Boolean? = null,
    val outcome: DurableOutcome = DurableOutcome(),
    val durable: DurableOutcome? = null,
    val lastCommitHlc: CommitHlc? = null,
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    /** Prefer nested durable → outcome → top-level last_commit_hlc. */
    fun commitHlc(): CommitHlc? =
        durable?.lastCommitHlc ?: outcome.lastCommitHlc ?: lastCommitHlc

    /** Prefer nested serialization_state, then serialization. */
    fun serializationState(): String {
        durable?.serializationState?.takeIf { it.isNotEmpty() }?.let { return it }
        durable?.serialization?.takeIf { it.isNotEmpty() }?.let { return it }
        outcome.serializationState?.takeIf { it.isNotEmpty() }?.let { return it }
        return outcome.serialization
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJsonString(body: String): QueryStatus {
            val element = json.parseToJsonElement(body)
            val obj = element as? JsonObject
                ?: throw QueryException("query status response was not a JSON object")
            return fromJsonObject(obj)
        }

        fun fromJsonObject(obj: JsonObject): QueryStatus {
            val outcome = DurableOutcome.fromJson(obj["outcome"])
            val durableEl = obj["durable"]
            val durable =
                if (durableEl is JsonObject) DurableOutcome.fromJson(durableEl) else null
            val serverState =
                obj["server_state"]?.jsonPrimitive?.contentOrNull
                    ?: obj["state"]?.jsonPrimitive?.contentOrNull
                    ?: ""
            return QueryStatus(
                queryId = obj["query_id"]?.jsonPrimitive?.contentOrNull ?: "",
                status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "",
                state = obj["state"]?.jsonPrimitive?.contentOrNull ?: "",
                serverState = serverState,
                terminalState = obj["terminal_state"]?.jsonPrimitive?.contentOrNull,
                committed = obj["committed"]?.jsonPrimitive?.booleanOrNull,
                outcome = outcome,
                durable = durable,
                lastCommitHlc = CommitHlc.fromJson(obj["last_commit_hlc"]),
                raw = obj,
            )
        }
    }
}
