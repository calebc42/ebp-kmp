// SPDX-License-Identifier: GPL-3.0-or-later
// Amendment #194: the hand-mirrored halves of the data-model contract —
// the unbindable member and type lists and the six fixed limits — are pinned
// to contract.json so drift is a test failure, as the method registry is.
package com.calebc42.ebp.wire

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataModelContractTest {
    private val contract = Json.parseToJsonElement(
        File(System.getProperty("ebp.dir") ?: error("ebp.dir system property not set"))
            .resolve("contract.json").readText(),
    ) as JsonObject

    @Test
    fun unbindableMembersAndTypesMatchTheContract() {
        val schema = contract.reqObj("data_model_schema")
        assertEquals(
            schema.reqArr("unbindable_members").map { it.asStringOrNull()!! }.toSet(),
            Materializer.UNBINDABLE_MEMBERS,
        )
        assertEquals(
            schema.reqArr("unbindable_tags").map { it.asStringOrNull()!! }.toSet(),
            Materializer.UNBINDABLE_TAGS,
        )
        assertEquals("data.model", schema.reqString("capability"))
    }

    @Test
    fun fixedDataLimitsMatchTheContract() {
        val fixed = contract.reqObj("limits").reqObj("fixed")
        assertEquals(fixed.reqLong("max_data_bytes"), WireLimits.MAX_DATA_OCTETS.toLong())
        assertEquals(fixed.reqLong("max_data_depth"), WireLimits.MAX_DATA_DEPTH.toLong())
        assertEquals(fixed.reqLong("max_pointer_bytes"), WireLimits.MAX_POINTER_OCTETS.toLong())
        assertEquals(fixed.reqLong("max_pointer_segments"), WireLimits.MAX_POINTER_SEGMENTS.toLong())
        assertEquals(fixed.reqLong("max_bindings_per_snapshot"), WireLimits.MAX_BINDINGS_PER_SNAPSHOT.toLong())
        assertEquals(fixed.reqLong("max_data_ops"), WireLimits.MAX_DATA_OPS.toLong())
        assertEquals(fixed.reqLong("max_template_items"), WireLimits.MAX_TEMPLATE_ITEMS.toLong())
    }

    @Test
    fun dataOpKindsMatchTheContract() {
        val ops = contract.reqObj("data_model_schema").reqObj("ops")
        val kinds = ops.filterValues { it is JsonObject }
        assertEquals(kinds.keys, DataOps.SHAPES.keys)
        for ((kind, shape) in kinds) {
            val row = shape as JsonObject
            assertEquals(kind, (row.reqArr("required") + row.reqArr("optional"))
                .map { it.asStringOrNull()!! }.toSet(), DataOps.SHAPES.getValue(kind))
        }
        assertEquals("data.insert", ops.reqString("insert_feature"))
        assertTrue("data.insert" in contract.reqArr("features").map { it.asStringOrNull() })
    }

    @Test
    fun templateRulesMatchTheContract() {
        val template = contract.reqObj("data_model_schema").reqObj("template")
        assertEquals("data.template", template.reqString("feature"))
        assertEquals("max_template_items", template.reqString("max_items_limit"))
        assertEquals(template.reqLong("max_nesting"), Materializer.MAX_TEMPLATE_NESTING.toLong())
        assertEquals(template.reqArr("root_must_bind").map { it.asStringOrNull()!! }.toSet(),
            Materializer.TEMPLATE_ROOT_MUST_BIND)
        assertEquals(template.reqArr("root_forbidden_members").map { it.asStringOrNull()!! }.toSet(),
            Materializer.TEMPLATE_ROOT_FORBIDDEN)
        assertEquals(template.reqObj("forbidden_inside").reqArr("node_types").map { it.asStringOrNull()!! }.toSet(),
            Materializer.TEMPLATE_FORBIDDEN_TYPES)
        assertEquals("item", template.reqString("injection"))
        assertEquals(listOf("item"),
            contract.reqObj("actions").reqObj("scope_injections").reqArr("template_item").map { it.asStringOrNull() })
    }
}
