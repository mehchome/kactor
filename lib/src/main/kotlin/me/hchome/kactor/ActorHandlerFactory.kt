package me.hchome.kactor

import kotlin.reflect.KClass
import kotlin.reflect.full.createInstance

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

object DefaultActorHandlerFactory : ActorHandlerFactory {
    override fun <T : ActorHandler> getBean(kClass: KClass<T>, props: Props): T {
        if (props.values.isEmpty()) {
            val o = kClass.objectInstance
            if (o != null) return o
            return kClass.createInstance()
        }
        val constructor = kClass.constructors.firstOrNull { constructor ->
            constructor.parameters.all { it.isOptional || it.name in props.values }
        } ?: error("No matching constructor for ${kClass.simpleName}")
        val arguments = constructor.parameters
            .filter { it.name in props.values }
            .associateWith { props.values.getValue(it.name!!) }
        return constructor.callBy(arguments)
    }
}