// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.wire

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/** The bounded EBP writer could not accept another command. */
class EbpActorOverloaded : IllegalStateException("EBP command actor is saturated")

/**
 * Caller-scoped single-writer runtime. Every submitted block runs to
 * completion before the next begins, while callers await a durable result
 * without owning the actor's coroutine or thread.
 *
 * Two lanes feed the one worker (jetpacs-poc PLAN-responsiveness C2b). The
 * process lane ([trySubmit]) is bounded: inbound frames, bridge lifecycle,
 * alarms and notifications queue there and saturation is an explicit
 * result. The action lane ([submitAction]) carries what the user just did
 * — a tap, a keystroke, a dialog answer — and is unbounded: the worker
 * takes from it first, so an action never waits behind queued process
 * work, and it never reports overload, so no human-rate burst can surface
 * as a toast. Each lane is FIFO; the single worker keeps every command,
 * from either lane, serial.
 */
class EbpCommandActor(
    scope: CoroutineScope,
    capacity: Int = 64,
) {
    init { require(capacity > 0) }

    private class Command<T>(
        val block: suspend () -> T,
        val result: CompletableDeferred<T>,
    )

    private val commands = Channel<Command<*>>(capacity)
    private val actions = Channel<Command<*>>(Channel.UNLIMITED)
    private val worker: Job = scope.launch {
        var actionsOpen = true
        var commandsOpen = true
        while (actionsOpen || commandsOpen) {
            // `select` is biased to its first clause when several are ready:
            // the action lane wins whenever it has anything.
            val next = select<Command<*>?> {
                if (actionsOpen) {
                    actions.onReceiveCatching { taken ->
                        if (taken.isClosed) { actionsOpen = false; null } else taken.getOrThrow()
                    }
                }
                if (commandsOpen) {
                    commands.onReceiveCatching { taken ->
                        if (taken.isClosed) { commandsOpen = false; null } else taken.getOrThrow()
                    }
                }
            }
            if (next != null) runCommand(next)
        }
    }

    /** Submit without unbounded suspension; saturation is an explicit result. */
    fun <T> trySubmit(block: suspend () -> T): CompletableDeferred<T> {
        check(worker.isActive) { "EBP command actor is closed" }
        val result = CompletableDeferred<T>()
        if (!commands.trySend(Command(block, result)).isSuccess) {
            val failure = EbpActorOverloaded()
            result.completeExceptionally(failure)
            throw failure
        }
        return result
    }

    /**
     * Submit on the action lane: taken ahead of queued process work, never
     * saturated. For what the user did; everything else uses [trySubmit].
     */
    fun <T> submitAction(block: suspend () -> T): CompletableDeferred<T> {
        check(worker.isActive) { "EBP command actor is closed" }
        val result = CompletableDeferred<T>()
        check(actions.trySend(Command(block, result)).isSuccess) { "EBP command actor is closed" }
        return result
    }

    /** Submit and await the ordered result. */
    suspend fun <T> execute(block: suspend () -> T): T = trySubmit(block).await()

    /** Stop accepting commands on both lanes and drain already accepted work. */
    fun close() {
        actions.close()
        commands.close()
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun runCommand(command: Command<*>) {
        val typed = command as Command<Any?>
        try {
            typed.result.complete(typed.block())
        } catch (failure: Throwable) {
            typed.result.completeExceptionally(failure)
        }
    }
}
