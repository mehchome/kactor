package me.hchome.kactor

import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter

/**
 * Actor handler factory, response for create actor handler
 *
 * @see ActorHandlerFactory.getBean
 */
interface ActorHandlerFactory {

    /**
     * Get an actor handler by class
     *
     * @param kClass actor handler class
     * @return actor handler
     */
    fun <T> getBean(kClass: KClass<T>): T where T : ActorHandler = getBean(kClass, Props.EMPTY)

    /**
     * Get an actor handler by class, matching [props] to constructor parameters by name
     *
     * @param kClass actor handler class
     * @param props named startup parameters
     * @return actor handler
     */
    fun <T> getBean(kClass: KClass<T>, props: Props): T where T : ActorHandler
}

inline fun <reified T> ActorHandlerFactory.getBean(): T where T : ActorHandler = getBean(T::class)

/**
 * Creates handlers by reflection: the constructor whose parameters are all either optional or
 * named in the [Props]. The chosen constructor is resolved once per class and set of prop names,
 * then cached. Prefer the lambda `register` overload to avoid reflection altogether.
 */
object DefaultActorHandlerFactory : ActorHandlerFactory {

    private class Resolved(val constructor: KFunction<*>, val parameters: List<KParameter>)

    private data class Key(val kClass: KClass<*>, val propNames: Set<String>)

    private val resolved = ConcurrentHashMap<Key, Resolved>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : ActorHandler> getBean(kClass: KClass<T>, props: Props): T {
        val values = props.values
        val target = resolved.computeIfAbsent(Key(kClass, values.keys)) { resolve(kClass, values.keys) }
        val arguments = HashMap<KParameter, Any?>(target.parameters.size)
        target.parameters.forEach { arguments[it] = values[it.name] }
        return try {
            target.constructor.callBy(arguments) as T
        } catch (e: IllegalArgumentException) {
            // typically a prop whose type does not match the constructor parameter
            throw IllegalArgumentException(
                "Cannot create ${kClass.simpleName} with props ${values.mapValues { it.value::class.simpleName }}: ${e.message}",
                e
            )
        }
    }

    private fun resolve(kClass: KClass<*>, propNames: Set<String>): Resolved {
        val constructor = kClass.constructors.firstOrNull { constructor ->
            constructor.parameters.all { it.isOptional || it.name in propNames }
        } ?: error("No constructor of ${kClass.simpleName} matches props $propNames")
        return Resolved(constructor, constructor.parameters.filter { it.name in propNames })
    }
}
