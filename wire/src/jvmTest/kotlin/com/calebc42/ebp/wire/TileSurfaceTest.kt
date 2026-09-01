// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.wire

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** SPEC 13.4 conformance for fixed host Quick Settings tile slots. */
class TileSurfaceTest {
    private fun store(
        backing: SurfaceBacking = MemorySurfaceBacking(),
    ) = SurfaceStore(
        16,
        1024,
        tileBuiltins = setOf("trigger.fire", "companion.settings.open"),
        tileFeatures = emptySet(),
        backing = backing,
    )

    private fun tile(action: JsonObject? = null) = buildJsonObject {
        put("label", "Focus")
        put("icon", "focus")
        put("subtitle", "Work mode")
        put("active", true)
        action?.let { put("on_tap", it) }
    }

    private fun rejects(spec: JsonObject, fragment: String) {
        try {
            store().update("tile:one", 1, spec, null, null, null)
            fail("accepted: $fragment")
        } catch (failure: ContentInvalid) {
            assertTrue(
                "${failure.reason} @ ${failure.path} !~ $fragment",
                failure.reason.contains(fragment) || failure.path.contains(fragment),
            )
        }
    }

    @Test
    fun exactTileShapeAndContextlessActionsAreAccepted() {
        val remote = buildJsonObject {
            put("action", "focus.toggle")
            putJsonObject("args") { put("source", "quick-settings") }
            put("when_offline", "queue")
            put("ttl_s", 300)
        }
        val store = store()
        assertEquals(
            "applied",
            store.update("tile:one", 1, tile(remote), tile(), null, null).status,
        )
        assertEquals(true, store.spec("tile:one")?.boolOr("active"))
        val builtin = buildJsonObject {
            put("label", "Manual")
            putJsonObject("on_tap") {
                put("builtin", "trigger.fire")
                put("id", "focus.manual")
            }
        }
        assertEquals(
            "applied",
            store.update("tile:two", 1, builtin, null, null, null).status,
        )
        assertEquals(0, store.inputState().size)
    }

    @Test
    fun nodeMultiViewAndUnknownMembersAreRejected() {
        rejects(buildJsonObject { put("t", "text"); put("text", "not a tile") }, "unknown")
        rejects(buildJsonObject {
            put("label", "x")
            putJsonObject("views") { putJsonObject("a") { put("t", "text") } }
        }, "views")
        rejects(buildJsonObject { put("label", "x"); put("mystery", true) }, "mystery")
        rejects(buildJsonObject { put("label", 1) }, "label")
        rejects(buildJsonObject { put("label", "x"); put("active", "yes") }, "active")
        rejects(buildJsonObject { put("label", "x"); put("icon", "not an icon") }, "icon")
    }

    @Test
    fun tileInjectionAndContextGatesAreEnforced() {
        rejects(tile(buildJsonObject {
            put("action", "focus.toggle")
            putJsonObject("args") { put("tile", "authored") }
        }), "conflicts")
        rejects(tile(buildJsonObject {
            put("action", "focus.toggle")
            putJsonArray("capture_fields") { add("field") }
        }), "without a surface")
        rejects(tile(buildJsonObject {
            put("action", "focus.toggle")
            put("open_surface", "app:focus")
        }), "without an app surface")
        rejects(tile(buildJsonObject {
            put("builtin", "view.switch")
            put("view", "other")
        }), "requires a surface")
        rejects(tile(buildJsonObject {
            put("builtin", "clipboard.copy")
            put("text", "x")
        }), "not valid in this context")
        val wake = tile(buildJsonObject {
            put("action", "focus.toggle")
            put("when_offline", "wake")
            put("ttl_s", 60)
        })
        rejects(wake, "offline.wake")
        assertEquals(
            "applied",
            store().update(
                "tile:one",
                1,
                wake,
                null,
                null,
                null,
                offlineWakeGranted = true,
            ).status,
        )
    }

    @Test
    fun tileHasNoViewOrInputResetAndSurvivesReload() {
        val store = store()
        try {
            store.update("tile:one", 1, tile(), null, "view", null)
            fail("current_view accepted")
        } catch (failure: ContentInvalid) {
            assertTrue(failure.path.contains("current_view"))
        }
        try {
            store.update(
                "tile:one",
                1,
                tile(),
                null,
                null,
                buildJsonArray { add("field") },
            )
            fail("reset_input_ids accepted")
        } catch (failure: ContentInvalid) {
            assertTrue(failure.path.contains("reset_input_ids"))
        }

        val backing = MemorySurfaceBacking()
        store(backing).update("tile:one", 1, tile(), null, null, null)
        assertEquals("Focus", store(backing).spec("tile:one")?.reqString("label"))
    }
}
