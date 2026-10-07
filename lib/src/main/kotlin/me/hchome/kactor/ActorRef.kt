package me.hchome.kactor

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
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
    // Path segments are derived with plain string ops; java.nio Path allocated a UnixPath (+ byte[])
    // on every construction and every `parent` call, which dominated allocation on hot message paths.
    private val separatorIndex = actorId.lastIndexOf(SEPARATOR)

    /** parent actor id, or null for a root actor */
    private val parentId: String? = if (separatorIndex > 0) actorId.substring(0, separatorIndex) else null

    val name: String = if (separatorIndex >= 0) actorId.substring(separatorIndex + 1) else actorId

    val hasParent: Boolean
        get() = parentId != null

    val lastParentId: String
        get() = parentId?.substringAfterLast(SEPARATOR) ?: ""

    fun childOf(id: String) = if (actorId.isEmpty()) of(id) else of("$actorId$SEPARATOR$id")

    fun parentOf() = parentId?.let(::of) ?: EMPTY

    fun isChildOf(ref: ActorRef) = this.isNotEmpty() && ref.isNotEmpty() && ref.actorId == parentId

    fun isParentOf(ref: ActorRef) = this.isNotEmpty() && ref.parentId == actorId

    companion object {
        private const val SEPARATOR = '/'

        @JvmStatic
        val EMPTY = ActorRef("")

        @JvmStatic
        fun of(actorId: String) = ActorRef(actorId)
    }
}

/**
 * Check if an actor reference is empty
 */
@OptIn(ExperimentalContracts::class)
fun ActorRef?.isNullOrEmpty(): Boolean {
    contract {
        returns(false) implies (this@isNullOrEmpty != null)
    }
    return this == null || this.isEmpty()
}

fun ActorRef.isEmpty() = this == ActorRef.EMPTY

/**
 * Check if an actor reference is not empty
 */
fun ActorRef.isNotEmpty(): Boolean = !isEmpty()