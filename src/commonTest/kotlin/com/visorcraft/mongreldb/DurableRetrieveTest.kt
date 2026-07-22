// SPDX-License-Identifier: MIT OR Apache-2.0
package com.visorcraft.mongreldb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DurableRetrieveTest {
    private val fixture = """
        {
          "query_id": "abcdefabcdefabcdefabcdefabcdefab",
          "status": "committed",
          "state": "completed",
          "server_state": "completed",
          "terminal_state": "committed",
          "committed": true,
          "last_commit_epoch": 17,
          "last_commit_epoch_text": "17",
          "last_commit_hlc": {
            "physical_micros": 1700000000000000,
            "logical": 3,
            "node_tiebreaker": 7
          },
          "outcome": {
            "committed": true,
            "committed_statements": 1,
            "last_commit_epoch": 17,
            "last_commit_epoch_text": "17",
            "last_commit_hlc": {
              "physical_micros": 1700000000000000,
              "logical": 3,
              "node_tiebreaker": 7
            },
            "first_commit_statement_index": 0,
            "last_commit_statement_index": 0,
            "completed_statements": 1,
            "statement_index": 0,
            "serialization": "succeeded",
            "serialization_state": "succeeded"
          },
          "durable": {
            "committed": true,
            "committed_statements": 1,
            "last_commit_epoch": 17,
            "last_commit_epoch_text": "17",
            "last_commit_hlc": {
              "physical_micros": 1700000000000000,
              "logical": 3,
              "node_tiebreaker": 7
            },
            "first_commit_statement_index": 0,
            "last_commit_statement_index": 0,
            "completed_statements": 1,
            "statement_index": 0,
            "serialization": "succeeded",
            "serialization_state": "succeeded"
          }
        }
    """.trimIndent()

    @Test
    fun queryStatusParsesStructuralHlcWithoutStringParsing() {
        val status = QueryStatus.fromJsonString(fixture)
        assertEquals(true, status.committed)
        val hlc = status.commitHlc()
        assertNotNull(hlc)
        assertEquals(1_700_000_000_000_000L, hlc.physicalMicros)
        assertEquals(3, hlc.logical)
        assertEquals(7, hlc.nodeTiebreaker)
        assertEquals("succeeded", status.serializationState())
        assertEquals(17L, status.outcome.lastCommitEpoch)
    }

    @Test
    fun commitHlcAbsentWhenMissing() {
        assertNull(CommitHlc.fromJson(null))
        val empty = QueryStatus.fromJsonString("""{"query_id":"a","status":"running","state":"executing","committed":false,"outcome":{"committed":false,"serialization":"in_progress"}}""")
        assertNull(empty.commitHlc())
    }
}
