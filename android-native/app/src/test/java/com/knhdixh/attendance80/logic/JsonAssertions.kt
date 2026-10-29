package com.knhdixh.attendance80.logic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.abs

fun assertJsonEquals(
    expected: JsonElement,
    actual: JsonElement,
    path: String = "$",
    ignoredKeys: Set<String> = emptySet(),
) {
    val leafKey = path.substringAfterLast('.')
    if (leafKey in ignoredKeys) return

    if (expected is JsonNull && actual is JsonNull) return

    // JS encodes Infinity/NaN as null in JSON
    if (expected is JsonNull && actual is JsonPrimitive) {
        val d = actual.doubleOrNull
        if (d != null && (d.isInfinite() || d.isNaN())) return
    }
    if (actual is JsonNull && expected is JsonPrimitive) {
        val d = expected.doubleOrNull
        if (d != null && (d.isInfinite() || d.isNaN())) return
    }

    if (expected is JsonPrimitive && actual is JsonPrimitive) {
        val expBool = expected.booleanOrNull
        val actBool = actual.booleanOrNull
        if (expBool != null && actBool != null) {
            assertEquals("$path: boolean mismatch", expBool, actBool)
            return
        }

        val expD = expected.doubleOrNull
        val actD = actual.doubleOrNull
        if (expD != null && actD != null) {
            assertTrue(
                "$path: expected $expD but got $actD (diff ${abs(expD - actD)} > 1e-9)",
                abs(expD - actD) <= 1e-9
            )
            return
        }

        assertEquals("$path: primitive mismatch", expected.content, actual.content)
        return
    }

    if (expected is JsonArray && actual is JsonArray) {
        assertEquals("$path: array size mismatch", expected.size, actual.size)
        for (i in 0 until expected.size) {
            assertJsonEquals(expected[i], actual[i], "$path[$i]", ignoredKeys)
        }
        return
    }

    if (expected is JsonObject && actual is JsonObject) {
        for ((key, expVal) in expected) {
            if (key in ignoredKeys) continue
            assertTrue("$path: missing expected key '$key' in actual object keys: ${actual.keys}", actual.containsKey(key))
            assertJsonEquals(expVal, actual.getValue(key), "$path.$key", ignoredKeys)
        }
        for (key in actual.keys) {
            if (key in ignoredKeys) continue
            assertTrue("$path: unexpected key '$key' found in actual object", expected.containsKey(key))
        }
        return
    }

    throw AssertionError("$path: type mismatch between expected (${expected::class.simpleName}) and actual (${actual::class.simpleName})")
}
