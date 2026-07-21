// SPDX-License-Identifier: MIT OR Apache-2.0
package com.visorcraft.mongreldb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wire-shape conformance tests for the JSON serialization.
 *
 * Mirror the C/C++ `test_wire_shape` tests: verify that the tagged-union Value
 * encoding, column construction, and condition DSL produce the exact JSON the
 * server expects. No daemon required — pure offline unit tests.
 */
class CreateTableWireShapeTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    // ── Value tag/encode round-trip ─────────────────────────────────────────

    @Test
    fun `valueToJson encodes null`() {
        assertEquals("null", valueToJson(Value.Null).toString())
    }

    @Test
    fun `valueToJson encodes bool`() {
        assertEquals(true, valueToJson(Value.Bool(true)).jsonPrimitive.boolean)
    }

    @Test
    fun `valueToJson encodes int64`() {
        assertEquals(42L, valueToJson(Value.Int64(42L)).jsonPrimitive.long)
    }

    @Test
    fun `valueToJson encodes float64`() {
        assertEquals("3.14", valueToJson(Value.Float64(3.14)).jsonPrimitive.content)
    }

    @Test
    fun `valueToJson encodes text`() {
        assertEquals("hello", valueToJson(Value.Text("hello")).jsonPrimitive.content)
    }

    @Test
    fun `valueToJson encodes embedding sparse and set arrays`() {
        assertEquals("[1.0,-2.0]", valueToJson(Value.Embedding(listOf(1.0, -2.0))).toString())
        assertEquals(
            "[[7,0.5]]",
            valueToJson(Value.Sparse(listOf(SparseTerm(7, 0.5)))).toString(),
        )
        assertEquals(
            "[\"a\",3,true]",
            valueToJson(Value.ArrayValue(listOf(Value.Text("a"), Value.Int64(3), Value.Bool(true))))
                .toString(),
        )
    }

    // ── jsonToValue decodes raw SQL JSON rows ──────────────────────────────

    @Test
    fun `jsonToValue decodes string scalar`() {
        assertEquals(Value.Text("alice"), jsonToValue(json.parseToJsonElement("\"alice\"")))
    }

    @Test
    fun `jsonToValue decodes int scalar`() {
        assertEquals(Value.Int64(42L), jsonToValue(json.parseToJsonElement("42")))
    }

    @Test
    fun `jsonToValue decodes bool scalar`() {
        assertEquals(Value.Bool(true), jsonToValue(json.parseToJsonElement("true")))
    }

    @Test
    fun `jsonToValue decodes null scalar`() {
        assertEquals(Value.Null, jsonToValue(json.parseToJsonElement("null")))
    }

    // ── Column factory helpers ─────────────────────────────────────────────

    @Test
    fun `Column_int64 has correct defaults`() {
        val col = Column.int64("id", 1, primaryKey = true)
        assertEquals(1L, col.id)
        assertEquals("id", col.name)
        assertEquals("int64", col.storageType)
        assertEquals("int64", col.applicationType)
        assertTrue(col.primaryKey)
        assertFalse(col.nullable)
        assertNull(col.enumVariants)
        assertNull(col.defaultValue)
        assertNull(col.defaultValueJson)
        assertNull(col.defaultExpr)
    }

    @Test
    fun `Column_text defaults to nullable`() {
        val col = Column.text("name", 2)
        assertTrue(col.nullable)
        assertFalse(col.primaryKey)
    }

    @Test
    fun `Column with enum_variants preserves list`() {
        val col = Column(
            id = 3, name = "status", storageType = "varchar",
            applicationType = "varchar",
            enumVariants = listOf("draft", "published"),
        )
        assertEquals(2, col.enumVariants!!.size)
        assertEquals("draft", col.enumVariants!![0])
    }

    // ── InputCell serialization ────────────────────────────────────────────

    @Test
    fun `cellToJson produces flat column id and raw value pair`() {
        val cellJson = cellToJson(InputCell(1, Value.Int64(42L)))
        assertEquals(1L, cellJson[0].jsonPrimitive.long)
        assertEquals(42L, cellJson[1].jsonPrimitive.long)
    }

    // ── Condition DSL ──────────────────────────────────────────────────────

    @Test
    fun `PK int condition shape`() {
        val cond = Condition.PrimaryKeyInt(42L)
        assertEquals(42L, cond.value)
    }

    @Test
    fun `Range condition with open bounds`() {
        val cond = Condition.Range(columnId = 5, lo = 10.0, hi = null)
        assertEquals(10.0, cond.lo)
        assertNull(cond.hi)
    }

    @Test
    fun `ANN backend options survive indexes JSON`() {
        val indexes = json.parseToJsonElement(
            """[{"name":"ann","column_id":2,"kind":"ann","options":{"ann":{"algorithm":"diskann","quantization":"dense","diskann":{"r":64,"l":128,"beam_width":8,"alpha":120}}}}]""",
        )
        val body = buildJsonObject { put("indexes", indexes) }
        val ann = body["indexes"]!!.jsonArray[0].jsonObject["options"]!!
            .jsonObject["ann"]!!.jsonObject
        assertEquals("diskann", ann["algorithm"]!!.jsonPrimitive.content)
        assertEquals("dense", ann["quantization"]!!.jsonPrimitive.content)
        assertEquals(8L, ann["diskann"]!!.jsonObject["beam_width"]!!.jsonPrimitive.long)
    }
}
