// SPDX-License-Identifier: MIT OR Apache-2.0
package com.visorcraft.mongreldb

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Serializer for [Value] using the raw JSON scalar/array shape accepted by
 * `/kit/txn` and `/kit/query`.
 */
object ValueSerializer : KSerializer<Value> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Value", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Value) {
        require(encoder is JsonEncoder) { "Value can only be serialized as JSON" }
        encoder.encodeJsonElement(valueToJson(value))
    }

    override fun deserialize(decoder: Decoder): Value {
        require(decoder is JsonDecoder) { "Value can only be deserialized from JSON" }
        val element = decoder.decodeJsonElement()
        return jsonToValue(element)
    }
}

/** Convert a [Value] to its JSON wire representation. */
fun valueToJson(value: Value): JsonElement = when (value) {
    is Value.Null -> JsonNull
    is Value.Bool -> JsonPrimitive(value.value)
    is Value.Int64 -> JsonPrimitive(value.value)
    is Value.Float64 -> JsonPrimitive(value.value)
    is Value.Text -> JsonPrimitive(value.value)
    is Value.Embedding -> buildJsonArray {
        value.values.forEach { add(JsonPrimitive(it)) }
    }
    is Value.Sparse -> buildJsonArray {
        value.terms.forEach { term ->
            add(buildJsonArray {
                add(JsonPrimitive(term.token))
                add(JsonPrimitive(term.weight))
            })
        }
    }
    is Value.ArrayValue -> buildJsonArray { value.values.forEach { add(valueToJson(it)) } }
}

/** Parse a JSON element into a [Value]. Accepts both tagged-union and raw scalar forms. */
fun jsonToValue(element: kotlinx.serialization.json.JsonElement): Value {
    if (element is JsonArray) {
        return Value.ArrayValue(element.map(::jsonToValue))
    }
    // Legacy tagged-union form remains accepted on reads.
    if (element is JsonObject) {
        val tag = element["tag"]?.jsonPrimitive?.contentOrNull
        val v = element["v"]
        return when (tag) {
            "null", null -> Value.Null
            "bool" -> Value.Bool(v?.jsonPrimitive?.booleanOrNull ?: false)
            "int64" -> Value.Int64(v?.jsonPrimitive?.longOrNull ?: 0L)
            "float64" -> Value.Float64(v?.jsonPrimitive?.doubleOrNull ?: 0.0)
            "string" -> Value.Text(v?.jsonPrimitive?.content ?: "")
            else -> Value.Null
        }
    }
    // Raw scalar form (from SQL JSON rows)
    if (element is JsonPrimitive) {
        return when {
            element.isString -> Value.Text(element.content)
            element.booleanOrNull != null -> Value.Bool(element.booleanOrNull!!)
            element.longOrNull != null -> Value.Int64(element.longOrNull!!)
            element.doubleOrNull != null -> Value.Float64(element.doubleOrNull!!)
            else -> Value.Null
        }
    }
    return Value.Null
}

/** Serialize one cell as the flat pair expected inside `/kit/txn` cells. */
fun cellToJson(cell: InputCell): JsonArray = buildJsonArray {
    add(JsonPrimitive(cell.columnId))
    add(valueToJson(cell.value))
}
