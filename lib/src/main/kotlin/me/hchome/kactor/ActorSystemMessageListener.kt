package me.hchome.kactor

@FunctionalInterface
fun interface ActorSystemMessageListener {

    fun onMessage(message: ActorSystemNotificationMessage)
}