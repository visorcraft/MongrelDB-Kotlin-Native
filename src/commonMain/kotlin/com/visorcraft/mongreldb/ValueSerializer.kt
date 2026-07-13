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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull

/**
 * Serializer for [Value]: encodes/decodes the tagged-union wire format.
 *
 * On the wire, each value is a JSON object with a `tag` discriminator:
 *   - `{"tag":"null"}`
 *   - `{"tag":"bool","v":true}`
 *   - `{"tag":"int64","v":42}`
 *   - `{"tag":"float64","v":3.14}`
 *   - `{"tag":"string","v":"hello"}`
 *
 * When a Value appears as a top-level serialized field (annotated with
 * @Serializable(with = ValueSerializer::class)), this serializer handles the
 * conversion. When used inside [WireJson] (the raw JSON builder for HTTP
 * requests), values are serialized directly via [valueToJson].
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
fun valueToJson(value: Value): JsonObject = when (value) {
    is Value.Null -> buildJsonObject { put("tag", "null") }
    is Value.Bool -> buildJsonObject { put("tag", "bool"); put("v", value.value) }
    is Value.Int64 -> buildJsonObject { put("tag", "int64"); put("v", value.value) }
    is Value.Float64 -> buildJsonObject { put("tag", "float64"); put("v", value.value) }
    is Value.Text -> buildJsonObject { put("tag", "string"); put("v", value.value) }
}

/** Parse a JSON element into a [Value]. Accepts both tagged-union and raw scalar forms. */
fun jsonToValue(element: kotlinx.serialization.json.JsonElement): Value {
    // Tagged-union form: {"tag": "...", "v": ...}
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

/** Serialize a cell for the wire format: `{"column_id": N, "value": {...}}`. */
fun cellToJson(cell: InputCell): JsonObject = buildJsonObject {
    put("column_id", cell.columnId)
    put("value", valueToJson(cell.value))
}
