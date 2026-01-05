package me.hchome.kactor

/**
 * Registry for managing actors in the actor system. This interface provides methods to
 * register, retrieve, check, and remove actors using their references.
 *
 * The registry ensures that actors can be referenced and interacted with efficiently
 * based on their unique identifiers (`ActorRef`).
 */
interface ActorRegistry {

    /**
     * Get all actor references in the registry
     */
    fun allReferences(): Set<ActorRef>

    /**
     * Get all child actor references of a parent actor
     */
    fun childReferences(parent: ActorRef): Set<ActorRef> = allReferences().filter(parent::isParentOf).toSet()

    /**
     * Get all actors in the registry
     */
    fun all(): Set<Actor>

    operator fun set(ref: ActorRef, actor: Actor)

    operator fun get(ref: ActorRef): Actor

    operator fun contains(ref: ActorRef): Boolean

    fun remove(ref: ActorRef): Actor?

    fun clear()


}