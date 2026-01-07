package me.hchome.kactor.impl

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

interface ActorScope {
    val actorJob: Job

    fun cancel()

    fun launch(
        context: CoroutineContext = EmptyCoroutineContext,
        started: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> Unit
    ): Job

    fun <T> async(
        context: CoroutineContext = EmptyCoroutineContext,
        started: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> T
    ): Deferred<T>

    fun <T> share(flow: Flow<T>, started: SharingStarted, replay: Int): SharedFlow<T>

    fun <T> state(flow: Flow<T>, stated: SharingStarted, initValue: T): StateFlow<T>

    suspend fun <T> state(flow: Flow<T>): StateFlow<T>
}

internal class ActorScopeImpl(
    parentJob: Job,
    dispatcher: CoroutineDispatcher
) : ActorScope {
    override val actorJob: Job = SupervisorJob(parentJob)
    private val scope = CoroutineScope(actorJob + dispatcher)

    override fun cancel() {
        actorJob.cancel()
    }

    override fun launch(
        context: CoroutineContext,
        started: CoroutineStart,
        block: suspend CoroutineScope.() -> Unit
    ): Job = scope.launch(context, started, block)

    override fun <T> async(
        context: CoroutineContext,
        started: CoroutineStart,
        block: suspend CoroutineScope.() -> T
    ): Deferred<T> = scope.async(context, started, block)

    override fun <T> share(
        flow: Flow<T>,
        started: SharingStarted,
        replay: Int
    ): SharedFlow<T> = flow.shareIn(scope, started, replay)

    override fun <T> state(
        flow: Flow<T>,
        stated: SharingStarted,
        initValue: T
    ): StateFlow<T> = flow.stateIn(scope, stated, initValue)

    override suspend fun <T> state(flow: Flow<T>): StateFlow<T> = flow.stateIn(scope)
}
