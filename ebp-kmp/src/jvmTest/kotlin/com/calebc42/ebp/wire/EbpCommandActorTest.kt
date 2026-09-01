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
