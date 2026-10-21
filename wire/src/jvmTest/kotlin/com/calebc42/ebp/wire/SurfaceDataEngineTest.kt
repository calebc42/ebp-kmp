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
            put("node_types", JsonArray(listOf("text", "column", "text_input", "lazy_column", "row", "button").map(::JsonPrimitive)))
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

        // SPEC 13.8.4 (amendment #196): insert shifts the later elements.
        val listed = h.send("s2", "surface.update", buildJsonObject {
            put("surface", "app:l"); put("revision", 1)
            put("spec", json("""{"t":"column","bind":{"children":"/rows"},"template":{"t":"text","bind":{"key":"","text":""}}}"""))
            put("data", json("""{"rows":["a","c"]}"""))
        }).reqObj("result")
        assertEquals("applied", listed.reqString("status"))
        val inserted = h.send("d0", "surface.data", buildJsonObject {
            put("surface", "app:l"); put("revision", 2)
            put("ops", Json.parseToJsonElement("""[{"op":"insert","path":"/rows/1","value":"b"}]"""))
        }).reqObj("result")
        assertEquals("applied", inserted.reqString("status"))
        assertEquals(listOf("a", "b", "c"),
            h.store.spec("app:l")!!.reqArr("children").map { (it as JsonObject).reqString("text") })

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
    fun aHookInsideATemplateItemCarriesTheItemKey() {
        val h = Harness(setOf("data.model"), listOf("data.model"))
        val outline = json(
            """{"t":"lazy_column","bind":{"children":"/rows"},"template":{"t":"row","bind":{"key":"id"},"children":[
                {"t":"button","label":"Open","on_tap":{"action":"note.open","args":{"source":"outline"}}}]}}""")
        val applied = h.send("s1", "surface.update", buildJsonObject {
            put("surface", "app:o"); put("revision", 1); put("spec", outline)
            put("data", json("""{"rows":[{"id":"n1"},{"id":"n2"}]}"""))
        }).reqObj("result")
        assertEquals("applied", applied.reqString("status"))
        h.send("r1", "session.ready", JsonObject(emptyMap()))
        assertEquals(SessionState.READY, h.engine.state)

        val root = h.store.spec("app:o")!!
        val rootPath = IdentityPath.child("", root, "")
        val row = root.reqArr("children")[1] as JsonObject
        val rowPath = IdentityPath.child(rootPath, row, "children[1]")
        val button = row.reqArr("children")[0] as JsonObject
        val buttonPath = IdentityPath.child(rowPath, button, "children[0]")
        val descriptor = button.reqObj("on_tap")

        h.engine.dispatchAction("app:o", descriptor, null, sourcePath = buttonPath)
        val event = h.out.last()
        assertEquals("event.action", event.reqString("method"))
        assertEquals(buildJsonObject { put("source", "outline"); put("item", "n2") },
            event.reqObj("params").reqObj("args"))

        // Outside any item — or with no path at all — nothing is injected.
        h.engine.dispatchAction("app:o", descriptor, null, sourcePath = rootPath)
        assertEquals(buildJsonObject { put("source", "outline") }, h.out.last().reqObj("params").reqObj("args"))
        h.engine.dispatchAction("app:o", descriptor, null)
        assertEquals(buildJsonObject { put("source", "outline") }, h.out.last().reqObj("params").reqObj("args"))
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

    @Test
    fun aStageObserverSeesEveryStageOfAnUpdateAndAPatchAndNothingOtherwise() {
        val h = Harness(setOf("data.model"), listOf("data.model"))
        // Nothing is timed while no observer listens: the store has no sink.
        assertEquals(null, h.store.stageSink)
        val seen = mutableListOf<Triple<String, String, Map<String, Long>>>()
        h.engine.stageObserver = { method, surface, stages -> seen += Triple(method, surface, stages) }
        h.engine.surfaceListener = { }
        h.send("s1", "surface.update", buildJsonObject {
            put("surface", "app:j"); put("revision", 1); put("spec", spec)
            put("data", json("""{"journal":{"title":"Morning"}}"""))
        })
        assertEquals(1, seen.size)
        assertEquals("surface.update", seen[0].first)
        assertEquals("app:j", seen[0].second)
        assertEquals(
            listOf("decode", "apply", "materialize", "admit", "commit", "reply", "listener"),
            seen[0].third.keys.toList(),
        )
        assertTrue(seen[0].third.values.all { it >= 0L })
        h.send("d1", "surface.data", buildJsonObject {
            put("surface", "app:j"); put("revision", 2)
            put("ops", Json.parseToJsonElement("""[{"op":"set","path":"/journal/title","value":"Evening"}]"""))
        })
        assertEquals(2, seen.size)
        assertEquals("surface.data", seen[1].first)
        assertEquals(
            listOf("decode", "apply", "materialize", "admit", "commit", "reply", "listener"),
            seen[1].third.keys.toList(),
        )
        // A stale patch is answered without a commit or a listener run, and
        // the sink is gone again once the observer has been told.
        h.send("d2", "surface.data", buildJsonObject {
            put("surface", "app:j"); put("revision", 2)
            put("ops", Json.parseToJsonElement("""[{"op":"set","path":"/journal/title","value":"x"}]"""))
        })
        assertEquals(3, seen.size)
        assertTrue("commit" !in seen[2].third)
        assertTrue("listener" !in seen[2].third)
        assertEquals(null, h.store.stageSink)
        h.engine.stageObserver = null
        h.send("d3", "surface.data", buildJsonObject {
            put("surface", "app:j"); put("revision", 3)
            put("ops", Json.parseToJsonElement("""[{"op":"set","path":"/journal/title","value":"Night"}]"""))
        })
        assertEquals(3, seen.size)
    }
}
