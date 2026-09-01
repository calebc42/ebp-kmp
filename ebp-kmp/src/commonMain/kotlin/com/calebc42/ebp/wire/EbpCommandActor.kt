// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.wire

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** The bounded EBP writer could not accept another command. */
class EbpActorOverloaded : IllegalStateException("EBP command actor is saturated")

/**
 * Caller-scoped single-writer runtime. Every submitted block runs to
 * completion before the next begins, while callers await a durable result
 * without owning the actor's coroutine or thread.
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
    private val worker: Job = scope.launch {
        for (command in commands) runCommand(command)
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

    /** Submit and await the ordered result. */
    suspend fun <T> execute(block: suspend () -> T): T = trySubmit(block).await()

    /** Stop accepting commands and drain already accepted work. */
    fun close() {
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
