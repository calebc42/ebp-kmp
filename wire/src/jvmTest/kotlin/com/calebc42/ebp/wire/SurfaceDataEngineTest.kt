// SPDX-License-Identifier: GPL-3.0-or-later
// SPEC 13.8 (amendment #194) through the wire: `data.model` is granted by
// negotiation, a snapshot carries its document, `surface.data` patches at
// a new revision with the §13.2 results, and without the grant neither the
// document nor the method is admitted.
package com.calebc42.ebp.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceDataEngineTest {
    private fun profiles(): JsonObject = buildJsonObject {
        putJsonObject("app") {
            put("node_types", JsonArray(listOf("text", "column", "text_input").map(::JsonPrimitive)))
            put("builtins", JsonArray(emptyList()))
            put("features", JsonArray(emptyList()))
            put("extensions", JsonArray(emptyList()))
        }
    }

    private class Harness(supported: Set<String>, wants: List<String>) {
        val out = mutableListOf<JsonObject>()
        val store = SurfaceStore(16, 1024)
        val engine = CompanionEngine(
            CompanionConfig(
                serverName = "kat-companion", serverVersion = "1.0.0",
                pairings = mapOf(katPid to katToken),
                supportedCapabilities = supported,
                surfaceProfiles = SurfaceDataEngineTest().profiles(),
                limits = testLimits(),
                nonceSource = { katSn },
            ),
            store,
        ) { bytes -> FrameDecoder().let { d -> d.feed(bytes).forEach(out::add); d.finish() } }

        init {
            engine.feed(frame(request("h1", "session.hello",
                EbpAuth.helloParams("test-client", "0.0.1", katPid, katCn, wants))))
            engine.feed(frame(request("h2", "auth.response",
                EbpAuth.authParams(katPid, katCn, katSn, katToken))))
        }

        fun send(id: String, method: String, params: JsonObject): JsonObject {
            engine.feed(frame(request(id, method, params)))
            return out.last()
        }
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    private val spec = json(
        """{"t":"column","children":[{"t":"text","bind":{"text":"/journal/title"}}]}""")

    @Test
    fun aGrantedSessionCarriesDocumentsAndPatches() {
        val h = Harness(setOf("data.model"), listOf("data.model"))
        assertEquals(listOf("data.model"),
            h.out.last().reqObj("result").reqArr("granted").map { it.asStringOrNull() })

        val applied = h.send("s1", "surface.update", buildJsonObject {
            put("surface", "app:j"); put("revision", 1); put("spec", spec)
            put("data", json("""{"journal":{"title":"Morning"}}"""))
        }).reqObj("result")
        assertEquals("applied", applied.reqString("status"))
        assertEquals("Morning",
            (h.store.spec("app:j")!!.reqArr("children")[0] as JsonObject).reqString("text"))

        val patched = h.send("d1", "surface.data", buildJsonObject {
            put("surface", "app:j"); put("revision", 2)
            put("ops", Json.parseToJsonElement("""[{"op":"set","path":"/journal/title","value":"Evening"}]"""))
        }).reqObj("result")
        assertEquals(buildJsonObject { put("status", "applied"); put("revision", 2); put("present", true) }, patched)
        assertEquals("Evening",
            (h.store.spec("app:j")!!.reqArr("children")[0] as JsonObject).reqString("text"))

        // At or below the floor: stale, not an error.
        val stale = h.send("d2", "surface.data", buildJsonObject {
            put("surface", "app:j"); put("revision", 2)
            put("ops", Json.parseToJsonElement("""[{"op":"set","path":"/journal/title","value":"x"}]"""))
        }).reqObj("result")
        assertEquals("stale", stale.reqString("status"))
        assertEquals(2L, stale.reqLong("revision"))

        // A malformed op is 1201 at its path and leaves the surface unchanged.
        val invalid = h.send("d3", "surface.data", buildJsonObject {
            put("surface", "app:j"); put("revision", 3)
            put("ops", Json.parseToJsonElement("""[{"op":"remove","path":"/journal/none"}]"""))
        }).reqObj("error")
        assertEquals(1201L, invalid.reqLong("code"))
        assertEquals("ops[0].path", invalid.reqObj("data").reqString("path"))
        assertEquals(2L, h.store.revisionOf("app:j"))

        // Structural trouble is -32602.
        val params = h.send("d4", "surface.data", buildJsonObject {
            put("surface", "app:j"); put("revision", 3)
        }).reqObj("error")
        assertEquals(-32602L, params.reqLong("code"))
    }

    @Test
    fun withoutTheGrantNeitherTheDocumentNorTheMethodIsAdmitted() {
        val h = Harness(setOf("theme"), listOf("theme", "data.model"))
        assertTrue("data.model" !in h.out.last().reqObj("result").reqArr("granted").map { it.asStringOrNull() })
        val withData = h.send("s1", "surface.update", buildJsonObject {
            put("surface", "app:j"); put("revision", 1)
            put("spec", json("""{"t":"text","text":"x"}"""))
            put("data", json("""{"journal":{}}"""))
        }).reqObj("error")
        assertEquals(1201L, withData.reqLong("code"))
        assertEquals("data", withData.reqObj("data").reqString("path"))
        val withBind = h.send("s2", "surface.update", buildJsonObject {
            put("surface", "app:j"); put("revision", 1); put("spec", spec)
        }).reqObj("error")
        assertEquals("spec.children[0].bind", withBind.reqObj("data").reqString("path"))
        val method = h.send("d1", "surface.data", buildJsonObject {
            put("surface", "app:j"); put("revision", 1)
            put("ops", Json.parseToJsonElement("""[{"op":"set","path":"/a","value":1}]"""))
        }).reqObj("error")
        assertEquals(-32601L, method.reqLong("code"))
    }
}
