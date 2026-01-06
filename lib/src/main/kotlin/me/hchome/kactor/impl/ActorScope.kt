package me.hchome.kactor.impl

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

internal interface ActorScope {
    val actorJob: Job

    fun cancel()

    fun launch(started: CoroutineStart = CoroutineStart.DEFAULT, block: suspend CoroutineScope.() -> Unit)

    fun <T> async(started: CoroutineStart = CoroutineStart.DEFAULT, block: suspend CoroutineScope.() -> T): Deferred<T>
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
        started: CoroutineStart,
        block: suspend CoroutineScope.() -> Unit
    ) {
        TODO("Not yet implemented")
    }

    override fun <T> async(
        started: CoroutineStart,
        block: suspend CoroutineScope.() -> T
    ): Deferred<T> {
        TODO("Not yet implemented")
    }
}