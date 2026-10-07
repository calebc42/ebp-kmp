// SPDX-License-Identifier: GPL-3.0-or-later
// SPEC 24.5 (amendment #196): goldens/data-ops.golden is the DataOp corpus —
// each vector is a document, an ops array, and either the exact resulting
// document or the path at which the request is rejected. DataOps must
// reproduce every line, exactly as validate.py's reference applier does.
package com.calebc42.ebp.wire

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DataOpsGoldenReplayTest {
    private fun vectors(): List<Pair<String, JsonObject>> =
        File(System.getProperty("ebp.dir") ?: error("ebp.dir system property not set"))
            .resolve("goldens/data-ops.golden").readLines()
            .filter { it.isNotBlank() }
            .map { line ->
                line.substringBefore(' ') to
                    (Json.parseToJsonElement(line.substringAfter(' ')) as JsonObject)
            }

    @Test
    fun everyDataOpVectorReproduces() {
        val lines = vectors()
        assertTrue("expected the full corpus, got ${lines.size}", lines.size >= 20)
        for ((index, vector) in lines) {
            val expect = vector.reqObj("expect")
            val outcome = runCatching {
                DataOps.applyAll(vector.reqObj("data"), vector.getValue("ops") as JsonArray)
            }
            when {
                "data" in expect -> {
                    val result = outcome.getOrElse { fail("data-ops.golden $index should apply: $it"); return }
                    assertEquals("data-ops.golden $index", expect.reqObj("data"), result)
                }
                "reject" in expect -> {
                    val failure = outcome.exceptionOrNull()
                    assertTrue("data-ops.golden $index should be rejected, got ${outcome.getOrNull()}",
                        failure is ContentInvalid)
                    assertEquals("data-ops.golden $index", expect.reqString("reject"),
                        (failure as ContentInvalid).path)
                }
                else -> fail("data-ops.golden $index carries no expectation")
            }
        }
    }
}
