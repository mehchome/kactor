package me.hchome.kactor

import kotlin.io.path.Path
import kotlin.io.path.name
import kotlin.reflect.KClass

/**
 * Actor reference
 * @see Actor
 */
data class ActorRef(
    /**
     * the actor id
     */
    val actorId: String,
) {
    private val path = Path(actorId)

    val name: String
        get() = path.name

    val hasParent: Boolean
        get() = path.parent != null

    val lastParentId: String
        get() = path.parent?.name ?: ""

    fun childOf(id: String) = of(path.resolve(id).toString())

    fun parentOf() = of(path.parent?.toString() ?: "")

    fun isChildOf(ref: ActorRef) = ref.path == path.parent

    fun isParentOf(ref: ActorRef) = ref.path.parent == path

    companion object {
        @JvmStatic
        val EMPTY = ActorRef( "")

        @JvmStatic
        fun <T : ActorHandler> ofService(clazz: KClass<T>) = of("$clazz")

        @JvmStatic
        inline fun <reified T : ActorHandler> ofService() = ofService(T::class)

        @JvmStatic
        fun of(actorId: String) = ActorRef(actorId)
    }
}

/**
 * Check if an actor reference is empty
 */

fun ActorRef?.isEmpty(): Boolean = this == null || this == ActorRef.EMPTY || this.actorId.isBlank()

/**
 * Check if an actor reference is not empty
 */
fun ActorRef?.isNotEmpty(): Boolean = !this.isEmpty()