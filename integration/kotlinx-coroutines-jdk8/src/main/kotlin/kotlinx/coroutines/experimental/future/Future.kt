/*
 * Copyright 2016-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kotlinx.coroutines.experimental.future

import kotlinx.coroutines.experimental.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.function.BiConsumer
import kotlin.coroutines.experimental.Continuation
import kotlin.coroutines.experimental.CoroutineContext
import kotlin.coroutines.experimental.suspendCoroutine

/**
 * Starts new coroutine and returns its results an an implementation of [CompletableFuture].
 * This coroutine builder uses [CommonPool] context by default and is conceptually similar to [CompletableFuture.supplyAsync].
 *
 * The running coroutine is cancelled when the resulting future is cancelled or otherwise completed.
 * If the [context] for the new coroutine is omitted or is explicitly specified but does not include a
 * coroutine interceptor, then [CommonPool] is used.
 * See [CoroutineDispatcher] for other standard [context] implementations that are provided by `kotlinx.coroutines`.
 * The [context][CoroutineScope.context] of the parent coroutine from its [scope][CoroutineScope] may be used,
 * in which case the [Job] of the resulting coroutine is a child of the job of the parent coroutine.
 *
 * By default, the coroutine is immediately scheduled for execution.
 * Other options can be specified via `start` parameter. See [CoroutineStart] for details.
 * A value of [CoroutineStart.LAZY] is not supported
 * (since `CompletableFuture` framework does not provide the corresponding capability) and
 * produces [IllegalArgumentException].
 *
 * See [newCoroutineContext] for a description of debugging facilities that are available for newly created coroutine.
 *
 * @param context context of the coroutine
 * @param start coroutine start option
 * @param block the coroutine code
 */
public fun <T> future(
    context: CoroutineContext = CommonPool,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    block: suspend CoroutineScope.() -> T
): CompletableFuture<T> {
    require(!start.isLazy) { "$start start is not supported" }
    val newContext = newCoroutineContext(CommonPool + context)
    val job = Job(newContext[Job])
    val future = CompletableFutureCoroutine<T>(newContext + job)
    job.cancelFutureOnCompletion(future)
    future.whenComplete { _, exception -> job.cancel(exception) }
    start(block, receiver=future, completion=future) // use the specified start strategy
    return future
}

private class CompletableFutureCoroutine<T>(
    override val context: CoroutineContext
) : CompletableFuture<T>(), Continuation<T>, CoroutineScope {
    override val coroutineContext: CoroutineContext get() = context
    override val isActive: Boolean get() = context[Job]!!.isActive
    override fun resume(value: T) { complete(value) }
    override fun resumeWithException(exception: Throwable) { completeExceptionally(exception) }
}

/**
 * Converts this deferred value to the instance of [CompletableFuture].
 * The deferred value is cancelled when the resulting future is cancelled or otherwise completed.
 */
public fun <T> Deferred<T>.asCompletableFuture(): CompletableFuture<T> {
    val future = CompletableFuture<T>()
    future.whenComplete { _, exception -> cancel(exception) }
    invokeOnCompletion {
        try {
            future.complete(getCompleted())
        } catch (exception: Exception) {
            future.completeExceptionally(exception)
        }
    }
    return future
}

/**
 * Awaits for completion of the completion stage without blocking a thread.
 *
 * This suspending function is not cancellable, because there is no way to cancel a `CompletionStage`.
 * Use `CompletableFuture.await()` for cancellable wait.
 */
public suspend fun <T> CompletionStage<T>.await(): T = suspendCoroutine { cont: Continuation<T> ->
    whenComplete(ContinuationConsumer(cont))
}

/**
 * Awaits for completion of the future without blocking a thread.
 *
 * This suspending function is cancellable.
 * If the [Job] of the current coroutine is cancelled or completed while this suspending function is waiting, this function
 * stops waiting for the future and immediately resumes with [CancellationException].
 *
 * Note, that `CompletableFuture` does not support prompt removal of installed listeners, so on cancellation of this wait
 * a few small objects will remain in the `CompletableFuture` stack of completion actions until the future completes.
 * However, the care is taken to clear the reference to the waiting coroutine itself, so that its memory can be
 * released even if the future never completes.
 */
public suspend fun <T> CompletableFuture<T>.await(): T {
    // fast path when CompletableFuture is already done (does not suspend)
    if (isDone) {
        try {
            return get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e // unwrap original cause from ExecutionException
        }
    }
    // slow path -- suspend
    return suspendCancellableCoroutine { cont: CancellableContinuation<T> ->
        val consumer = ContinuationConsumer(cont)
        whenComplete(consumer)
        cont.invokeOnCompletion {
            consumer.cont = null // shall clear reference to continuation
        }
    }
}

private class ContinuationConsumer<T>(
    @Volatile @JvmField var cont: Continuation<T>?
) : BiConsumer<T?, Throwable?> {
    @Suppress("UNCHECKED_CAST")
    override fun accept(result: T?, exception: Throwable?) {
        val cont = this.cont ?: return // atomically read current value unless null
        if (exception == null) // the future has been completed normally
            cont.resume(result as T)
        else // the future has completed with an exception
            cont.resumeWithException(exception)
    }
}

// --------------------------------------- DEPRECATED APIs ---------------------------------------
// We keep it only for backwards compatibility with old versions of this integration library.
// Do not copy when using this file an example for other integration.

/**
 * Converts this deferred value to the instance of [CompletableFuture].
 * The deferred value is cancelled when the resulting future is cancelled or otherwise completed.
 * @suppress: **Deprecated**: Renamed to [asCompletableFuture]
 */
@Deprecated("Renamed to `asCompletableFuture`",
    replaceWith = ReplaceWith("asCompletableFuture()"))
public fun <T> Deferred<T>.toCompletableFuture(): CompletableFuture<T> = asCompletableFuture()

/** @suppress **Deprecated** */
@Suppress("DeprecatedCallableAddReplaceWith") // todo: the warning is incorrectly shown, see KT-17917
@Deprecated("Use the other version. This one is for binary compatibility only.", level=DeprecationLevel.HIDDEN)
public fun <T> future(
    context: CoroutineContext = CommonPool,
    block: suspend () -> T
): CompletableFuture<T> = future(context=context) { block() }
