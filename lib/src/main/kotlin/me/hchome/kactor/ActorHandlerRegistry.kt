package me.hchome.kactor

import kotlinx.coroutines.CoroutineDispatcher
import kotlin.reflect.KClass

/**
 * Actor Registry
 */
interface ActorHandlerRegistry {

    /**
     * register actor handler, with a meaningful domain name
     *
     * A handler class can be registered under only one domain, since actors are spawned by class.
     * Registering a domain again replaces it. With the default factory, [kClass] must not be an
     * `object` (all actors would share one handler instance).
     *
     * @throws IllegalArgumentException if [kClass] is already registered under another domain,
     *   or is an `object` registered with the default factory
     */
    fun register(
        domain: String,
        dispatcher: CoroutineDispatcher? = null,
        config: ActorConfig = ActorConfig.DEFAULT,
        factory: ActorHandlerFactory? = null,
        kClass: KClass<out ActorHandler>
    )

    /**
     * find the domain name by actor handler class
     */
    fun findName(kClass: KClass<out ActorHandler>): String?

    /**
     * Get config holder by domain
     */
    operator fun get(domain: String): ActorHandlerConfigHolder

    operator fun get(kClass: KClass<out ActorHandler>): ActorHandlerConfigHolder =
        get(findName(kClass) ?: throw IllegalArgumentException("No handler registered for $kClass"))


    /**
     * Is the domain registered?
     */
    operator fun contains(domain: String): Boolean

    operator fun contains(kClass: KClass<out ActorHandler>): Boolean = findName(kClass) != null

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

/**
 * Register a handler created by [create] instead of by reflection: no constructor lookup per spawn,
 * and constructor arguments are type-checked at compile time.
 *
 * ```
 * system.register<DeviceActor>("device") { props -> DeviceActor(props.get("deviceId")) }
 * ```
 */
inline fun <reified T> ActorHandlerRegistry.register(
    domain: String,
    dispatcher: CoroutineDispatcher? = null,
    config: ActorConfig = ActorConfig.DEFAULT,
    noinline create: (Props) -> T,
) where T : ActorHandler {
    register(domain, dispatcher, config, LambdaActorHandlerFactory(T::class, create), T::class)
}

/** Adapts a creation lambda to [ActorHandlerFactory], for the lambda `register` overload. */
@PublishedApi
internal class LambdaActorHandlerFactory<H : ActorHandler>(
    private val kClass: KClass<H>,
    private val create: (Props) -> H,
) : ActorHandlerFactory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ActorHandler> getBean(kClass: KClass<T>, props: Props): T {
        require(kClass == this.kClass) { "Factory for ${this.kClass} cannot create $kClass" }
        return create(props) as T
    }
}

data class ActorHandlerConfigHolder(
    val domain: String,
    val dispatcher: CoroutineDispatcher,
    val config: ActorConfig,
    val factory: ActorHandlerFactory,
    val kClass: KClass<out ActorHandler>
) {
    /**
     * create a new actor handler instance
     */
    fun newActorHandler(props: Props = Props.EMPTY) = factory.getBean(kClass, props)
}