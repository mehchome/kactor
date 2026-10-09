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

    private val registry = ConcurrentHashMap<String, ActorHandlerConfigHolder>()

    // reverse index for findName, which runs on every spawn by class
    private val domainsByClass = ConcurrentHashMap<KClass<out ActorHandler>, String>()

    // registration is rare; serialise it so both maps stay consistent
    @Synchronized
    override fun register(
        domain: String,
        dispatcher: CoroutineDispatcher?,
        config: ActorConfig,
        factory: ActorHandlerFactory?,
        kClass: KClass<out ActorHandler>
    ) {
        val factory = factory ?: defaultFactory
        require(factory !== DefaultActorHandlerFactory || kClass.objectInstance == null) {
            "$kClass is an object: every actor of '$domain' would share one handler instance, even across " +
                "restarts. Use a class, or register a factory that deliberately returns the singleton."
        }
        domainsByClass[kClass]?.let { existing ->
            require(existing == domain) {
                "$kClass is already registered as '$existing'. Actors are spawned by handler class, " +
                    "so a class can be registered under only one domain."
            }
        }
        val holder = ActorHandlerConfigHolder(domain, dispatcher ?: defaultDispatcher, config, factory, kClass)
        registry.put(domain, holder)?.let { replaced ->
            if (replaced.kClass != kClass) domainsByClass.remove(replaced.kClass, domain)
        }
        domainsByClass[kClass] = domain
    }

    override fun get(domain: String): ActorHandlerConfigHolder {
        return registry[domain] ?: throw IllegalArgumentException("No handler registered for $domain")
    }

    override fun contains(domain: String): Boolean {
        return registry.containsKey(domain)
    }

    override fun findName(kClass: KClass<out ActorHandler>): String? = domainsByClass[kClass]
}