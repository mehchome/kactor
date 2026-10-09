package me.hchome.kactor

/**
 * [ActorHandler.preStart] of the actor [ref] threw [cause]. Reported to the supervisor as the
 * [ActorFailure.cause], with [ActorFailure.message] set to [PRE_START]. The default
 * [ActorHandler.onSupervise] (and the actor system) stops such an actor rather than restarting it,
 * since a restart would usually just fail again.
 */
class ActorInitializationException(val ref: ActorRef, cause: Throwable) :
    ActorException(CODE, "Actor[$ref] failed to start: ${cause.message}", cause) {
    companion object {
        /** [ActorException.code] of an initialization failure */
        const val CODE = "ACTOR_INIT_FAILED"

        /** [ActorFailure.message] of an initialization failure */
        const val PRE_START = "preStart"
    }
}

/**
 * The handler of the actor [ref] does not override [ActorHandler.onAsk], so it cannot answer an ask.
 * The ask fails with this exception; it is not treated as a handler failure, so the actor is not
 * supervised or restarted because of it.
 */
class AskNotHandledException(val ref: ActorRef, handlerClass: String?) :
    UnsupportedOperationException("Actor[$ref] ($handlerClass) does not handle ask")