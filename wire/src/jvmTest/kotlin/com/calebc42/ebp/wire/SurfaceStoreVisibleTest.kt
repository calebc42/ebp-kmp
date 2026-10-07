// SPDX-License-Identifier: GPL-3.0-or-later
// SPEC 13.8.6 (amendment #198): conditional nodes are evaluated at
// materialization, a patch shows or hides them at a new revision, and the
// admission rules — capability, app namespace, stale_spec, the snapshot
// limit — reject at the authored path. The operator semantics themselves are
// pinned by DataGoldenReplayTest over goldens/data.golden.
package com.calebc42.ebp.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceStoreVisibleTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject
    private fun ops(text: String): JsonArray = Json.parseToJsonElement(text) as JsonArray

    private val feedback = json(
        """{"t":"column","children":[
            {"t":"text","key":"warn","bind":{"text":"/planning/feedback"},
             "visible_when":[{"path":"/planning/feedback_kind","op":"eq","value":"warning"}]},
            {"t":"text","key":"info","bind":{"text":"/planning/feedback"},
             "visible_when":[{"path":"/planning/feedback_kind","op":"eq","value":"info"}]},
            {"t":"text","key":"always","text":"Planning"}]}""",
    )
    private val document = json("""{"planning":{"feedback":"Deadline before schedule","feedback_kind":"warning"}}""")

    private fun keys(spec: JsonObject?): List<String> =
        spec!!.reqArr("children").map { (it as JsonObject).reqString("key") }

    @Test
    fun aPatchShowsAndHidesWithoutASnapshot() {
        val store = SurfaceStore(16, 1024)
        store.update("app:p", 1, feedback, null, null, null, data = document, dataModelGranted = true)
        assertEquals(listOf("warn", "always"), keys(store.spec("app:p")))
        // The hidden node's binding was never resolved; the shown one was.
        val warn = store.spec("app:p")!!.reqArr("children")[0] as JsonObject
        assertEquals("Deadline before schedule", warn.reqString("text"))
        assertTrue("visible_when stays on the materialized node", "visible_when" in warn)
        assertEquals(feedback, store.authoredSpec("app:p"))

        store.patch("app:p", 2, ops("""[{"op":"set","path":"/planning/feedback_kind","value":"info"}]"""))
        assertEquals(listOf("info", "always"), keys(store.spec("app:p")))
        store.patch("app:p", 3, ops("""[{"op":"remove","path":"/planning/feedback_kind"}]"""))
        assertEquals(listOf("always"), keys(store.spec("app:p")))
        // A wrong type hides, never rejects.
        store.patch("app:p", 4, ops("""[{"op":"set","path":"/planning/feedback_kind","value":7}]"""))
        assertEquals(listOf("always"), keys(store.spec("app:p")))
        assertEquals(4L, store.revisionOf("app:p"))
    }

    @Test
    fun admissionRulesRejectAtTheAuthoredPath() {
        val store = SurfaceStore(16, 1024)
        // Without the capability nothing conditional is admitted.
        assertEquals("spec.children[0].visible_when", assertThrows(ContentInvalid::class.java) {
            store.update("app:p", 1, feedback, null, null, null, data = null, dataModelGranted = false)
        }.path)
        // App snapshots only.
        val widget = json(
            """{"title":"T","body":{"t":"column","children":[
                {"t":"text","key":"a","text":"x","visible_when":[{"path":"/a","op":"present"}]}]}}""",
        )
        val widgetFailure = assertThrows(ContentInvalid::class.java) {
            store.update("widget:w", 1, widget, null, null, null, data = json("{}"), dataModelGranted = true)
        }
        assertTrue(widgetFailure.path, widgetFailure.path.endsWith(".visible_when"))
        // The degraded offline view is literal.
        val stale = json("""{"t":"column","children":[{"t":"text","key":"a","text":"x","visible_when":[{"path":"/a","op":"present"}]}]}""")
        assertEquals("stale_spec.children[0].visible_when", assertThrows(ContentInvalid::class.java) {
            store.update("app:p", 1, json("""{"t":"text","text":"ok"}"""), stale, null, null,
                data = json("{}"), dataModelGranted = true)
        }.path)
    }

    @Test
    fun theSnapshotPredicateLimitIsExact() {
        fun snapshot(nodes: Int): JsonObject = buildJsonObject {
            put("t", "column")
            put("children", buildJsonArray {
                repeat(nodes) { i ->
                    add(buildJsonObject {
                        put("t", "text")
                        put("key", "k$i")
                        put("text", "x")
                        put("visible_when", buildJsonArray {
                            repeat(WireLimits.MAX_VISIBLE_PREDICATES_PER_NODE) {
                                add(buildJsonObject { put("path", "/a"); put("op", "present") })
                            }
                        })
                    })
                }
            })
        }
        val perNode = WireLimits.MAX_VISIBLE_PREDICATES_PER_NODE
        val atLimit = WireLimits.MAX_VISIBLE_PREDICATES_PER_SNAPSHOT / perNode
        val store = SurfaceStore(16, 1024)
        store.update("app:big", 1, snapshot(atLimit), null, null, null, data = json("""{"a":1}"""), dataModelGranted = true)
        assertEquals(atLimit, store.spec("app:big")!!.reqArr("children").size)
        assertEquals("spec.children[$atLimit].visible_when[0]", assertThrows(ContentInvalid::class.java) {
            store.update("app:big", 2, snapshot(atLimit + 1), null, null, null,
                data = json("""{"a":1}"""), dataModelGranted = true)
        }.path)
        // Nine predicates on one node reject at the node's attribute.
        val nine = json("""{"t":"column","children":[{"t":"text","key":"a","text":"x","visible_when":[
            {"path":"/a","op":"present"},{"path":"/a","op":"present"},{"path":"/a","op":"present"},
            {"path":"/a","op":"present"},{"path":"/a","op":"present"},{"path":"/a","op":"present"},
            {"path":"/a","op":"present"},{"path":"/a","op":"present"},{"path":"/a","op":"present"}]}]}""")
        assertEquals("spec.children[0].visible_when", assertThrows(ContentInvalid::class.java) {
            store.update("app:p", 1, nine, null, null, null, data = json("{}"), dataModelGranted = true)
        }.path)
    }
}
