package me.hchome.kactor

import me.hchome.kactor.SystemMessage.CreateActor

/**
 * Registry for managing actors in the actor system. This interface provides methods to
 * register, retrieve, check, and remove actors using their references.
 *
 * The registry ensures that actors can be referenced and interacted with efficiently
 * based on their unique identifiers (`ActorRef`).
 */
interface ActorRegistry: ActorSystemInitializationListener {

    /**
     * get all actors references
     */
    val all: Set<ActorRef>

    /**
     * get all singleton actors references
     */

    /**
     * check if an actor exists
     */
    operator fun contains(ref: ActorRef): Boolean = all.contains(ref)

    /**
     * get child actor references
     */
    fun childReferences(parent: ActorRef): Set<ActorRef> = all.filter(parent::isParentOf).toSet()

    /**
     * create an actor
     */
    fun createActor(message: CreateActor)

    /**
     * stop an actor
     */
    fun stopActor(ref: ActorRef)

    /**
     * restart an actor
     */
    fun restartActor(ref: ActorRef, recreate: Boolean)

    /**
     * stop all actors
     */
    fun stopAllActors()

    /**
     * tell actor
     */
    suspend fun tell(tell: UserMessage.Tell)

    /**
     * Ask actor
     */
    suspend fun ask(ask: UserMessage.Ask)
}