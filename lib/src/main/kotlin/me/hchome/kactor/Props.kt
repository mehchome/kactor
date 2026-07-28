package me.hchome.kactor

/**
 * Named startup parameters for an actor.
 *
 * Passed through `actorOf`/`newActor`/`newChild` down to the [ActorHandlerFactory] that
 * creates the [ActorHandler] instance: values are matched to constructor parameters by name
 * (via [kotlin.reflect.KCallable.callBy]) instead of positionally, so an actor handler class
 * can declare constructor parameters in any order and still be spawned with [Props].
 *
 * Kept alongside the actor and reused whenever it is recreated (see
 * [SupervisorStrategy.Decision.Recreate]), so restarts see the same startup parameters as the
 * original spawn.
 *
 * The backing map is not part of the public API — build a [Props] with [Props.of] and read it
 * back with the type-safe [get]/[getOrNull] functions.
 *
 * @see ActorHandlerFactory.getBean
 */
class Props private constructor(@PublishedApi internal val values: Map<String, Any>) {

    /**
     * Returns `true` if a value is present under [name], regardless of its type.
     */
    operator fun contains(name: String): Boolean = name in values

    companion object {
        val EMPTY = Props(emptyMap())

        @JvmStatic
        fun of(vararg values: Pair<String, Any>): Props = if (values.isEmpty()) EMPTY else Props(values.toMap())

        @JvmStatic
        fun of(values: Map<String, Any>): Props = if (values.isEmpty()) EMPTY else Props(values.toMap())
    }
}

/**
 * Returns the value under [name] cast to [T], or `null` if absent or of a different type.
 */
inline fun <reified T : Any> Props.getOrNull(name: String): T? = values[name] as? T

/**
 * Returns the value under [name] cast to [T].
 *
 * @throws NoSuchElementException if [name] is absent or holds a value that isn't a [T]
 */
inline fun <reified T : Any> Props.get(name: String): T =
    getOrNull(name) ?: throw NoSuchElementException("Props value '$name' not found or not a ${T::class.simpleName}")