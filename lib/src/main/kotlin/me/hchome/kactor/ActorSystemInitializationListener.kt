package me.hchome.kactor

import kotlinx.coroutines.Job

/**
 * Listener for actor system initialization
 */
interface ActorSystemInitializationListener {

    fun afterInit(
        system: ActorSystem,
        systemJob: Job,
        systemSupervisor: Supervisor
    )
}