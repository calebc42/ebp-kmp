// SPDX-License-Identifier: GPL-3.0-or-later
// Structural sharing across surface revisions: after a patch, or a
// whole-document update whose tree is partly unchanged, every untouched
// subtree of the stored snapshot is the previous revision's instance, while
// the snapshot stays structurally what SPEC 13.8.3/13.8.4 materialize.
package com.calebc42.ebp.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class MaterializerShareTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject
    private fun ops(text: String): JsonArray = Json.parseToJsonElement(text) as JsonArray

    private val column = json(
        """{"t":"column","children":[
            {"t":"text","bind":{"text":"/a"}},
            {"t":"text","bind":{"text":"/b"}},
            {"t":"text","bind":{"text":"/c"}}]}""",
    )
    private val abc = json("""{"a":"A","b":"B","c":"C"}""")

    private val outline = json(
        """{"t":"lazy_column","bind":{"children":"/rows"},"template":{"t":"row","bind":{"key":"id"},"children":[
            {"t":"text","bind":{"text":"title"}},
            {"t":"button","label":"Open","on_tap":{"action":"note.open"}}]}}""",
    )
    private val rows = json(
        """{"rows":[{"id":"n1","title":"Inbox"},{"id":"n2","title":"Projects"},{"id":"n3","title":"Someday"}]}""",
    )

    private fun children(spec: JsonObject?): List<JsonObject> = spec!!.reqArr("children").map { it as JsonObject }
    private fun byKey(spec: JsonObject?): Map<String, JsonObject> = children(spec).associateBy { it.reqString("key") }

    private fun store(surface: String, authored: JsonObject, data: JsonObject): SurfaceStore =
        SurfaceStore(16, 1024).also {
            assertEquals("applied", it.update(surface, 1, authored, null, null, null,
                data = data, dataModelGranted = true).status)
        }

    @Test
    fun setSharesUntouchedSiblings() {
        val store = store("app:s", column, abc)
        val before = store.spec("app:s")!!
        store.patch("app:s", 2, ops("""[{"op":"set","path":"/a","value":"A2"}]"""))
        val after = store.spec("app:s")!!
        assertNotSame(before, after)
        assertNotSame(children(before)[0], children(after)[0])
        assertSame(children(before)[1], children(after)[1])
        assertSame(children(before)[2], children(after)[2])
        // The shared tree is what a fresh materialization produces.
        assertEquals(Materializer.materialize(column, store.data("app:s")!!, "spec", EBP_NODE_VOCABULARY), after)
    }

    @Test
    fun keyedInsertAtZeroKeepsLaterRows() {
        val store = store("app:o", outline, rows)
        val before = store.spec("app:o")!!
        store.patch("app:o", 2, ops("""[{"op":"insert","path":"/rows/0","value":{"id":"n0","title":"New"}}]"""))
        val after = store.spec("app:o")!!
        assertEquals(listOf("n0", "n1", "n2", "n3"), children(after).map { it.reqString("key") })
        assertNotSame(before.reqArr("children"), after.reqArr("children"))
        for ((key, row) in byKey(before)) assertSame(key, row, byKey(after).getValue(key))
        assertSame(before.reqObj("template"), after.reqObj("template"))
        assertEquals(Materializer.materialize(outline, store.data("app:o")!!, "spec", EBP_NODE_VOCABULARY), after)
    }

    @Test
    fun removeKeepsTheOtherRows() {
        val store = store("app:o", outline, rows)
        val before = store.spec("app:o")!!
        store.patch("app:o", 2, ops("""[{"op":"remove","path":"/rows/1"}]"""))
        val after = store.spec("app:o")!!
        assertEquals(listOf("n1", "n3"), children(after).map { it.reqString("key") })
        assertSame(byKey(before).getValue("n1"), byKey(after).getValue("n1"))
        assertSame(byKey(before).getValue("n3"), byKey(after).getValue("n3"))
        assertEquals(Materializer.materialize(outline, store.data("app:o")!!, "spec", EBP_NODE_VOCABULARY), after)
    }

    @Test
    fun equalUpdateReturnsThePreviousInstance() {
        val store = store("app:s", column, abc)
        val before = store.spec("app:s")!!
        // The same authored JSON parsed afresh, over the same document.
        store.update("app:s", 2, json(column.toString()), null, null, null,
            data = json(abc.toString()), dataModelGranted = true)
        assertSame(before, store.spec("app:s"))

        // A key-reordered parse is structurally another member order: the
        // root is fresh, so the stored order is the authored one, but the
        // untouched children keep their instances.
        val reordered = json(
            """{"children":[
                {"t":"text","bind":{"text":"/a"}},
                {"t":"text","bind":{"text":"/b"}},
                {"t":"text","bind":{"text":"/c"}}],"t":"column"}""",
        )
        store.update("app:s", 3, reordered, null, null, null, data = abc, dataModelGranted = true)
        val after = store.spec("app:s")!!
        assertNotSame(before, after)
        assertEquals(listOf("children", "t"), after.keys.toList())
        assertSame(before.reqArr("children"), after.reqArr("children"))
        assertEquals(before, after)
    }

    @Test
    fun noDataUpdateSharesUnchangedSubtrees() {
        val store = SurfaceStore(16, 1024)
        val first = """{"t":"column","children":[
            {"t":"text","text":"one"},{"t":"text","text":"two"},{"t":"row","children":[{"t":"text","text":"three"}]}]}"""
        store.update("app:p", 1, json(first), null, null, null)
        val before = store.spec("app:p")!!
        val second = json(first.replace("\"two\"", "\"TWO\""))
        store.update("app:p", 2, second, null, null, null)
        val after = store.spec("app:p")!!
        assertNotSame(before, after)
        assertSame(children(before)[0], children(after)[0])
        assertNotSame(children(before)[1], children(after)[1])
        assertSame(children(before)[2], children(after)[2])
        assertEquals(second, after)
        // Nothing was resolved, so the record holds ONE tree for both roles —
        // the shared instance, which it also stores once — rather than the
        // parsed instance beside a structurally identical copy.
        assertEquals(second, store.authoredSpec("app:p"))
        assertSame(after, store.authoredSpec("app:p"))
    }

    @Test
    fun itemKeysUnchangedBySharing() {
        val data = json("""{"rows":[{"id":"n0","title":"New"},{"id":"n1","title":"Inbox"},{"id":"n2","title":"Projects"}]}""")
        val previous = Materializer.materialize(outline, rows, "spec", EBP_NODE_VOCABULARY)
        val plain = Materializer.materializeWithItems(outline, data, "spec", EBP_NODE_VOCABULARY)
        val shared = Materializer.materializeWithItems(outline, data, "spec", EBP_NODE_VOCABULARY, previous = previous)
        assertEquals(plain.itemKeys, shared.itemKeys)
        assertEquals(listOf("n0", "n1", "n2"), shared.itemKeys.values.toList())
        assertEquals(plain.spec, shared.spec)
        assertSame(byKey(previous).getValue("n1"), byKey(shared.spec).getValue("n1"))
    }
}
