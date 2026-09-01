// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.wire

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WidgetSurfaceTest {
    private val types = setOf(
        "text", "icon", "row", "column", "box", "spacer", "divider",
        "lazy_column",
    )
    private val members = ProfileMemberSupport(
        universal = setOf("key", "id", "padding", "width", "height", "bg", "corner"),
        nodes = mapOf(
            "text" to setOf("text", "style", "font_weight", "color", "max_lines"),
            "icon" to setOf("name", "size", "color", "content_description"),
            "row" to setOf("children", "spacing", "align", "arrange"),
            "column" to setOf("children", "spacing", "align", "arrange"),
            "box" to setOf("children", "alignment", "on_tap"),
            "spacer" to emptySet(),
            "divider" to setOf("color", "thickness"),
            "lazy_column" to setOf("children", "spacing", "content_padding"),
        ),
        semantics = emptySet(),
        surface = setOf("title", "body", "empty", "header_action", "size_variants"),
    )

    private fun text(value: String, id: String? = null) = buildJsonObject {
        put("t", "text")
        put("text", value)
        id?.let { put("id", it) }
    }

    @Test
    fun adaptiveBodiesValidateAsOneDocument() {
        val spec = buildJsonObject {
            put("title", "Agenda")
            put("body", text("base", "same"))
            putJsonArray("size_variants") {
                add(buildJsonObject {
                    put("min_width", 180)
                    put("min_height", 80)
                    put("body", text("wide", "same"))
                })
            }
        }

        val failure = assertThrows(ContentInvalid::class.java) {
            SpecValidator.validateWidgetSpec(
                spec,
                advertisedTypes = types,
                advertisedMembers = members,
            )
        }
        assertEquals("duplicate node ID", failure.reason)
    }

    @Test
    fun exactMemberProfileRejectsUnsupportedOptionalMember() {
        val restricted = members.copy(
            nodes = members.nodes + ("text" to setOf("text")),
        )
        val spec = buildJsonObject {
            put("title", "Capture")
            put("body", buildJsonObject {
                put("t", "text")
                put("text", "Capture")
                put("style", "title")
            })
        }

        val failure = assertThrows(ContentInvalid::class.java) {
            SpecValidator.validateWidgetSpec(
                spec,
                advertisedTypes = types,
                advertisedMembers = restricted,
            )
        }
        assertEquals("spec.body.style", failure.path)
    }

    @Test
    fun richSwipeRequiresDistinctLabelsAndBoundsDeepCommit() {
        val duplicate = buildJsonObject {
            put("t", "card")
            put("children", buildJsonArray { add(text("row")) })
            putJsonObject("swipe_end") {
                put("commit", true)
                putJsonArray("actions") {
                    repeat(3) {
                        add(buildJsonObject {
                            put("label", "Delete")
                            putJsonObject("on_trigger") { put("action", "demo.delete") }
                        })
                    }
                }
            }
        }

        val failure = assertThrows(ContentInvalid::class.java) {
            SpecValidator.validateSurfaceSpec(duplicate)
        }
        assertEquals("spec.swipe_end.commit", failure.path)
    }

    @Test
    fun widgetSurfaceStoreAcceptsWrapperAndRetainsRevision() {
        val store = SurfaceStore(
            maxSurfaces = 16,
            maxSurfaceIds = 1024,
            widgetNodeTypes = types,
            widgetBuiltins = setOf("surface.open"),
            widgetMembers = members,
        )
        val spec = buildJsonObject {
            put("title", "Capture")
            put("body", buildJsonObject {
                put("t", "box")
                put("children", buildJsonArray { add(text("Capture")) })
                putJsonObject("on_tap") { put("action", "grove.capture") }
            })
        }

        val result = store.update("widget:capture", 7, spec, null, null, null)
        assertEquals(SurfaceResult("applied", 7, true), result)
    }
}
