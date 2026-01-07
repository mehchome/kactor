package me.hchome.kactor

interface ActorSystemMessageListener {

    fun onMessage(message: ActorSystemNotificationMessage)
}