// SPDX-License-Identifier: GPL-3.0-or-later
// SPEC 13.8.5 (amendment #195): a template expands once per element of its
// bound array at admission, a patch to the array re-expands it at a new
// revision with identity following the bound key, the engine can name the
// item a node sits in from its identity path, and a template is admitted
// only under the grant and only in an app snapshot.
package com.calebc42.ebp.wire

import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SurfaceStoreTemplateTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject
    private fun ops(text: String): JsonArray = Json.parseToJsonElement(text) as JsonArray

    private val outline = json(
        """{"t":"lazy_column","bind":{"children":"/rows"},"template":{"t":"row","bind":{"key":"id"},"children":[
            {"t":"text","bind":{"text":"title"}},
            {"t":"button","label":"Open","on_tap":{"action":"note.open"}}]}}""",
    )
    private val rows = json(
        """{"rows":[{"id":"n1","title":"Inbox"},{"id":"n2","title":"Projects"},{"id":"n3","title":"Someday"}]}""",
    )

    private fun rowsOf(store: SurfaceStore, surface: String = "app:o"): List<JsonObject> =
        store.spec(surface)!!.reqArr("children").map { it as JsonObject }

    private fun keysOf(store: SurfaceStore) = rowsOf(store).map { it.reqString("key") }
    private fun titleOf(row: JsonObject) = (row.reqArr("children")[0] as JsonObject).reqString("text")

    /** The identity path of the text inside row [index], as the renderer would report it. */
    private fun textPath(store: SurfaceStore, index: Int): String {
        val root = store.spec("app:o")!!
        val rootPath = IdentityPath.child("", root, "")
        val row = rowsOf(store)[index]
        val rowPath = IdentityPath.child(rootPath, row, "children[$index]")
        return IdentityPath.child(rowPath, row.reqArr("children")[0] as JsonObject, "children[0]")
    }

    @Test
    fun expansionFollowsTheBoundArrayAcrossPatches() {
        val store = SurfaceStore(16, 1024)
        assertEquals("applied",
            store.update("app:o", 1, outline, null, null, null, data = rows, dataModelGranted = true).status)
        assertEquals(listOf("n1", "n2", "n3"), keysOf(store))
        assertEquals("Projects", titleOf(rowsOf(store)[1]))
        // The copies keep their relative bindings, the parent keeps its template.
        assertEquals("title", rowsOf(store)[1].let { (it.reqArr("children")[0] as JsonObject).reqObj("bind").reqString("text") })
        assertEquals(outline.reqObj("template"), store.spec("app:o")!!.reqObj("template"))

        // The engine resolves a node's item from its identity path: the text
        // in row 1 sits in item n2; the root sits in no item.
        val before = textPath(store, 1)
        assertEquals("n2", store.itemKey("app:o", before))
        assertNull(store.itemKey("app:o", IdentityPath.child("", store.spec("app:o")!!, "")))
        assertNull(store.itemKey("app:o", null))

        // Inserting a row re-expands; n2's identity path does not move with
        // its index, because identity follows the key.
        store.patch("app:o", 2, ops("""[{"op":"set","path":"/rows/1","value":{"id":"n9","title":"New"}},
            {"op":"set","path":"/rows/2","value":{"id":"n2","title":"Projects"}},
            {"op":"set","path":"/rows/3","value":{"id":"n3","title":"Someday"}}]"""))
        assertEquals(listOf("n1", "n9", "n2", "n3"), keysOf(store))
        assertEquals("n2", store.itemKey("app:o", before))
        assertEquals(before, textPath(store, 2))

        // Patching one element re-renders that copy only.
        store.patch("app:o", 3, ops("""[{"op":"set","path":"/rows/0/title","value":"Inbox!"}]"""))
        assertEquals("Inbox!", titleOf(rowsOf(store)[0]))
        assertEquals(listOf("n1", "n9", "n2", "n3"), keysOf(store))

        // An empty array leaves an empty list and no items.
        store.patch("app:o", 4, ops("""[{"op":"set","path":"/rows","value":[]}]"""))
        assertEquals(emptyList<String>(), keysOf(store))
        assertNull(store.itemKey("app:o", before))
    }

    @Test
    fun nestedTemplatesNameTheInnermostItem() {
        val nested = json(
            """{"t":"column","bind":{"children":"/rows"},"template":{"t":"column","bind":{"key":"id","children":"subs"},
                "template":{"t":"text","bind":{"key":"id","text":"label"}}}}""",
        )
        val data = json("""{"rows":[{"id":"a","subs":[{"id":"a1","label":"A1"}]},{"id":"b","subs":[]}]}""")
        val store = SurfaceStore(16, 1024)
        store.update("app:o", 1, nested, null, null, null, data = data, dataModelGranted = true)
        val root = store.spec("app:o")!!
        val rootPath = IdentityPath.child("", root, "")
        val outer = root.reqArr("children")[0] as JsonObject
        val outerPath = IdentityPath.child(rootPath, outer, "children[0]")
        val inner = outer.reqArr("children")[0] as JsonObject
        val innerPath = IdentityPath.child(outerPath, inner, "children[0]")
        assertEquals("a1", inner.reqString("key"))
        assertEquals("a1", store.itemKey("app:o", innerPath))
        assertEquals("a", store.itemKey("app:o", outerPath))
        assertEquals(0, (root.reqArr("children")[1] as JsonObject).reqArr("children").size)
    }

    @Test
    fun templatesRequireTheGrantAndAnAppSnapshot() {
        val store = SurfaceStore(16, 1024)
        val ungranted = assertThrows(ContentInvalid::class.java) {
            store.update("app:o", 1, json("""{"t":"column","template":{"t":"text","bind":{"key":""}}}"""),
                null, null, null)
        }
        assertEquals("spec.template", ungranted.path)
        val widget = assertThrows(ContentInvalid::class.java) {
            store.update("widget:w", 1, json("""{"title":"w","body":$outline}"""), null, null, null,
                data = rows, dataModelGranted = true)
        }
        assertEquals("spec.body.template", widget.path)
        val stale = assertThrows(ContentInvalid::class.java) {
            store.update("app:o", 1, json("""{"t":"text","text":"live"}"""), outline, null, null,
                data = rows, dataModelGranted = true)
        }
        assertEquals("stale_spec.template", stale.path)
        assertNull(store.spec("app:o"))
    }

    @Test
    fun restoreReExpandsTheTemplate() {
        val dir = Files.createTempDirectory("ebp-template").toFile()
        val file = dir.resolve("surfaces.json")
        val first = SurfaceStore(16, 1024, backing = FileSurfaceBacking(file))
        first.update("app:o", 1, outline, null, null, null, data = rows, dataModelGranted = true)
        val path = textPath(first, 2)
        val second = SurfaceStore(16, 1024, backing = FileSurfaceBacking(file))
        assertEquals(listOf("n1", "n2", "n3"), keysOf(second))
        assertEquals("n3", second.itemKey("app:o", path))
        assertEquals("applied",
            second.patch("app:o", 2, ops("""[{"op":"remove","path":"/rows/0"}]""")).status)
        assertEquals(listOf("n2", "n3"), keysOf(second))
        dir.deleteRecursively()
    }
}
