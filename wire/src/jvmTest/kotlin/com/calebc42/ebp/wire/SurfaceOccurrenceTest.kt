// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.wire

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceOccurrenceTest {
    @Test
    fun offlineWidgetTapPersistsSurfaceRevisionAndAuthoredArgs() {
        val queue = DurableQueue(MemoryQueueStore(), 256, 8_388_608)
        val descriptor = buildJsonObject {
            put("action", "agenda.item")
            put("when_offline", "queue")
            put("ttl_s", 3600)
            put("args", buildJsonObject { put("item", "a") })
        }

        dispatchSurfaceOccurrence(
            queue = queue,
            maxEventBytes = 262_144,
            descriptor = descriptor,
            surface = "widget:agenda",
            revisionSeen = 7,
            live = null,
        )

        assertEquals(1, queue.count())
        val event = queue.events().single()
        assertEquals("widget:agenda", event.reqString("surface"))
        assertEquals(7L, event.reqLong("revision_seen"))
        assertEquals("a", event.reqObj("args").reqString("item"))
        assertTrue("queued_at_ms" in event)
    }
}
