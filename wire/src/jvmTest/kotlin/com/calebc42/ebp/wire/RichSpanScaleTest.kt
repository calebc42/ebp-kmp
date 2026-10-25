// SPDX-License-Identifier: GPL-3.0-or-later
// Amendment #201: a RichSpan MAY carry scale, a number 0.5..2.0 multiplying
// its font size; absent is 1, and anything else is content-invalid.
package com.calebc42.ebp.wire

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertTrue
import org.junit.Test

class RichSpanScaleTest {

    private fun richText(scale: JsonElement?) = buildJsonObject {
        put("t", "rich_text")
        putJsonArray("spans") {
            add(buildJsonObject { put("text", "title") })
            add(buildJsonObject {
                put("text", "  tag")
                if (scale != null) put("scale", scale)
            })
        }
    }

    private fun refusal(scale: JsonElement): ContentInvalid {
        val err = runCatching { SpecValidator.validateSurfaceSpec(richText(scale)) }
            .exceptionOrNull()
        assertTrue("scale $scale must be refused", err is ContentInvalid)
        return err as ContentInvalid
    }

    @Test
    fun scaleInRangeIsAccepted() {
        for (value in listOf(0.5, 0.875, 1.0, 2.0)) {
            SpecValidator.validateSurfaceSpec(richText(JsonPrimitive(value)))
        }
        SpecValidator.validateSurfaceSpec(richText(null))
    }

    @Test
    fun scaleOutOfRangeOrNotANumberIsContentInvalid() {
        for (value in listOf(JsonPrimitive(0.49), JsonPrimitive(2.01), JsonPrimitive("0.9"))) {
            val err = refusal(value)
            assertTrue(err.path, err.path.endsWith("spans[1].scale"))
        }
    }
}
