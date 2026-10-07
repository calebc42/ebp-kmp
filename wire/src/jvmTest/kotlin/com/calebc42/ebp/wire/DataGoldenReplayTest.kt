// SPDX-License-Identifier: GPL-3.0-or-later
// SPEC 24.5 (amendment #194): goldens/data.golden is the materialization
// corpus — each vector is a spec, a data document and either the exact
// resolved document or the error.data.path at which the snapshot is
// rejected. The engine's Materializer plus SpecValidator must reproduce
// every line, exactly as validate.py's reference materializer does.
package com.calebc42.ebp.wire

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DataGoldenReplayTest {

    private fun goldensDir(): File =
        File(System.getProperty("ebp.dir")
            ?: error("ebp.dir system property not set")).resolve("goldens")

    private fun vectors(): List<Pair<String, JsonObject>> =
        File(goldensDir(), "data.golden").readLines()
            .filter { it.isNotBlank() }
            .map { line ->
                line.substringBefore(' ') to
                    (Json.parseToJsonElement(line.substringAfter(' ')) as JsonObject)
            }

    /** The engine's admission order: document bounds, materialization, validation. */
    private fun materialize(spec: JsonObject, data: kotlinx.serialization.json.JsonElement): JsonObject {
        val document = data as? JsonObject
            ?: throw ContentInvalid("data", "data document must be an object")
        Materializer.checkDocument(document, "data")
        val materialized = Materializer.materialize(spec, document, "spec", EBP_NODE_VOCABULARY)
        SpecValidator.validateSurfaceSpec(materialized)
        return materialized
    }

    @Test
    fun everyDataVectorReproduces() {
        val lines = vectors()
        assertTrue("expected the full corpus, got ${lines.size}", lines.size >= 38)
        for ((index, vector) in lines) {
            val expect = vector.reqObj("expect")
            val outcome = runCatching { materialize(vector.reqObj("spec"), vector.getValue("data")) }
            when {
                "materialized" in expect -> {
                    val materialized = outcome.getOrElse {
                        fail("data.golden $index should materialize: $it"); return
                    }
                    assertEquals("data.golden $index", expect.reqObj("materialized"), materialized)
                }
                "reject" in expect -> {
                    val failure = outcome.exceptionOrNull()
                    assertTrue("data.golden $index should be rejected, got ${outcome.getOrNull()}",
                        failure is ContentInvalid)
                    assertEquals("data.golden $index", expect.reqString("reject"),
                        (failure as ContentInvalid).path)
                }
                else -> fail("data.golden $index carries no expectation")
            }
        }
    }
}
