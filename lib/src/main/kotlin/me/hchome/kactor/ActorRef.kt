package me.hchome.kactor

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
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

    fun parentOf() = path.parent?.let { of(it.toString()) } ?: EMPTY

    fun isChildOf(ref: ActorRef) = this.isNotEmpty() && ref.isNotEmpty() && ref.path == path.parent

    fun isParentOf(ref: ActorRef) = this.isNotEmpty() && ref.path.parent == path

    companion object {
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