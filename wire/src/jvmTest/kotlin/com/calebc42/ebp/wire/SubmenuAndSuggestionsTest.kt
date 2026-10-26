// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Amendment #203: a `MenuItem` may carry `items` in place of `on_tap`, one
 * level deep; and #202: `text_input.suggestions` are whitespace-free words.
 */
class SubmenuAndSuggestionsTest {
    private fun menu(items: String): JsonObject =
        Json.parseToJsonElement("""{"t":"menu","icon":"more_vert","items":$items}""") as JsonObject

    private fun rejection(node: JsonObject): String? = try {
        SpecValidator.validateSurfaceSpec(node, "root")
        null
    } catch (e: ContentInvalid) {
        e.reason
    }

    @Test
    fun aSubmenuRowOpensItsItemsInPlaceOfAnAction() {
        val reason = rejection(
            menu("""[{"label":"Clock","items":[{"label":"Clock in","on_tap":{"action":"clock.in"}}]},
                     {"label":"Sync","on_tap":{"action":"demo.sync"}}]"""),
        )
        assertTrue(reason.orEmpty(), reason == null)
    }

    @Test
    fun aSubmenuRowCarriesNoActionNorTrailingMember() {
        val reason = rejection(
            menu("""[{"label":"Clock","on_tap":{"action":"demo.clock"},
                      "items":[{"label":"Clock in","on_tap":{"action":"clock.in"}}]}]"""),
        )
        assertTrue(reason.orEmpty(), reason.orEmpty().contains("carries no on_tap"))
        val trailing = rejection(
            menu("""[{"label":"Clock","trailing_text":"⌘K",
                      "items":[{"label":"Clock in","on_tap":{"action":"clock.in"}}]}]"""),
        )
        assertTrue(trailing.orEmpty(), trailing.orEmpty().contains("carries no trailing_text"))
    }

    @Test
    fun submenusNestOneLevelAndAreNeverEmpty() {
        val deep = rejection(
            menu("""[{"label":"A","items":[{"label":"B","items":[
                      {"label":"C","on_tap":{"action":"demo.c"}}]}]}]"""),
        )
        assertTrue(deep.orEmpty(), deep.orEmpty().contains("nest one level"))
        val empty = rejection(menu("""[{"label":"A","items":[]}]"""))
        assertTrue(empty.orEmpty(), empty.orEmpty().contains("non-empty"))
    }

    @Test
    fun aSubmenuItemStillNeedsItsOwnAction() {
        val reason = rejection(menu("""[{"label":"A","items":[{"label":"B"}]}]"""))
        assertTrue(reason.orEmpty(), reason.orEmpty().contains("needs an ActionDescriptor"))
    }

    @Test
    fun suggestionsAreWholeWordsAndNeverASecret() {
        fun input(extra: String) =
            Json.parseToJsonElement("""{"t":"text_input","id":"tags",$extra}""") as JsonObject
        assertTrue(rejection(input(""""suggestions":["tag1","work"]""")) == null)
        assertFalse(rejection(input(""""suggestions":["two words"]""")) == null)
        assertFalse(rejection(input(""""suggestions":[]""")) == null)
        assertFalse(rejection(input(""""password":true,"suggestions":["x"]""")) == null)
    }
}
