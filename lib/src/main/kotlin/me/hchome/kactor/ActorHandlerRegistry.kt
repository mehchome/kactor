package me.hchome.kactor

import kotlinx.coroutines.CoroutineDispatcher
import kotlin.reflect.KClass

/**
 * Actor Registry
 */
interface ActorHandlerRegistry {

    /**
     * register actor handler, with a meaningful domain name
     */
    fun <T> register(
        domain: String,
        dispatcher: CoroutineDispatcher? = null,
        config: ActorConfig = ActorConfig.DEFAULT,
        factory: ActorHandlerFactory? = null,
        kClass: KClass<T>
    ) where T : ActorHandler

    /**
     * find the domain name by actor handler class
     */
    fun <T> findName(kClass: KClass<T>): String? where T : ActorHandler

    /**
     * Get config holder by domain
     */
    operator fun get(domain: String): ActorHandlerConfigHolder

    operator fun get(kClass: KClass<out ActorHandler>): ActorHandlerConfigHolder = get("$kClass")


    /**
     * Is the domain registered?
     */
    operator fun contains(domain: String): Boolean

    operator fun contains(kClass: KClass<out ActorHandler>): Boolean = contains("$kClass")

}

inline fun <reified T> ActorHandlerRegistry.get(): ActorHandlerConfigHolder where T : ActorHandler = get(T::class)


inline fun <reified T> ActorHandlerRegistry.register(
    domain: String,
    dispatcher: CoroutineDispatcher? = null,
    config: ActorConfig = ActorConfig.DEFAULT,
    factory: ActorHandlerFactory? = null,
) where  T : ActorHandler {
    register(domain, dispatcher, config, factory, T::class)
}

data class ActorHandlerConfigHolder(
    val domain: String,
    val dispatcher: CoroutineDispatcher,
    val config: ActorConfig,
    val factory: ActorHandlerFactory,
    val kClass: KClass<out ActorHandler>
)