// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.wire

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EbpCommandActorTest {
    @Test
    fun commandsRunInAdmissionOrderAndFailuresDoNotStopTheActor() = runTest {
        val actor = EbpCommandActor(this, capacity = 8)
        val order = mutableListOf<Int>()
        val first = actor.trySubmit { order += 1; "one" }
        val failed = actor.trySubmit<String> { order += 2; error("boom") }
        val third = actor.trySubmit { order += 3; "three" }

        advanceUntilIdle()

        assertEquals("one", first.await())
        assertThrows(IllegalStateException::class.java) { failed.getCompleted() }
        assertEquals("three", third.await())
        assertEquals(listOf(1, 2, 3), order)
        actor.close()
    }

    // ------------------------------------------- the action lane (C2b)

    @Test
    fun anActionIsTakenAheadOfQueuedProcessWork() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val actor = EbpCommandActor(scope, capacity = 8)
        val gate = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        actor.trySubmit { gate.await(); order += "running" }
        scope.runCurrent()
        actor.trySubmit { order += "queued-1" }
        actor.trySubmit { order += "queued-2" }
        val action = actor.submitAction { order += "action"; "answered" }
        gate.complete(Unit)
        scope.advanceUntilIdle()
        assertEquals("answered", action.getCompleted())
        assertEquals(listOf("running", "action", "queued-1", "queued-2"), order)
        actor.close()
    }

    @Test
    fun theActionLaneNeverReportsOverloadAndKeepsItsOrder() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val actor = EbpCommandActor(scope, capacity = 1)
        val gate = CompletableDeferred<Unit>()
        actor.trySubmit { gate.await() }
        scope.runCurrent()
        actor.trySubmit { Unit }
        // The process lane is saturated …
        assertThrows(EbpActorOverloaded::class.java) { actor.trySubmit { Unit } }
        // … and the action lane still takes a burst far beyond its size.
        val order = mutableListOf<Int>()
        val results = (1..200).map { i -> actor.submitAction { order += i; i } }
        gate.complete(Unit)
        scope.advanceUntilIdle()
        assertEquals((1..200).toList(), order)
        assertEquals(200, results.last().getCompleted())
        actor.close()
    }

    @Test
    fun closeDrainsBothLanes() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val actor = EbpCommandActor(scope, capacity = 8)
        val order = mutableListOf<String>()
        val a = actor.submitAction { order += "action" }
        val c = actor.trySubmit { order += "command" }
        actor.close()
        scope.advanceUntilIdle()
        a.getCompleted()
        c.getCompleted()
        assertEquals(listOf("action", "command"), order)
        assertThrows(IllegalStateException::class.java) { actor.submitAction { Unit } }
        assertThrows(IllegalStateException::class.java) { actor.trySubmit { Unit } }
    }

    @Test
    fun boundedMailboxReportsOverload() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val actor = EbpCommandActor(scope, capacity = 1)
        val gate = CompletableDeferred<Unit>()
        actor.trySubmit { gate.await() }
        scope.runCurrent()
        actor.trySubmit { Unit }

        assertThrows(EbpActorOverloaded::class.java) {
            actor.trySubmit { Unit }
        }
        gate.complete(Unit)
        scope.advanceUntilIdle()
        actor.close()
    }
}
