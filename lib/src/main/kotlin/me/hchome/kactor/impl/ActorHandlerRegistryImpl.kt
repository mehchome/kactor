package me.hchome.kactor.impl

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import me.hchome.kactor.ActorConfig
import me.hchome.kactor.ActorHandler
import me.hchome.kactor.ActorHandlerConfigHolder
import me.hchome.kactor.ActorHandlerFactory
import me.hchome.kactor.ActorHandlerRegistry
import me.hchome.kactor.DefaultActorHandlerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

internal class ActorHandlerRegistryImpl(
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val defaultFactory: ActorHandlerFactory = DefaultActorHandlerFactory
) : ActorHandlerRegistry {

    private val registry: MutableMap<String, ActorHandlerConfigHolder> =
        ConcurrentHashMap<String, ActorHandlerConfigHolder>()

    override fun register(
        domain: String,
        dispatcher: CoroutineDispatcher?,
        config: ActorConfig,
        factory: ActorHandlerFactory?,
        kClass: KClass<out ActorHandler>
    )  {
        val dispatcher = dispatcher ?: defaultDispatcher
        val factory = factory ?: defaultFactory
        registry[domain] = ActorHandlerConfigHolder(domain, dispatcher, config, factory, kClass)
    }

    override fun get(domain: String): ActorHandlerConfigHolder {
        return registry[domain] ?: throw IllegalArgumentException("No handler registered for $domain")
    }

    override fun contains(domain: String): Boolean {
        return registry.containsKey(domain)
    }

    override fun  findName(kClass: KClass<out ActorHandler>): String? {
        return registry.filter { it.value.kClass == kClass }.keys.firstOrNull()
    }
}