# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
./gradlew build          # Compile and run tests
./gradlew test           # Run all tests
./gradlew clean          # Clean build artifacts
./gradlew publish        # Publish to S3 Maven repository (requires AWS credentials)
```

To run a single test class:
```bash
./gradlew test --tests "me.hchome.kactor.ActorTest"
```

The version is read from `version.txt` at the project root (or `MY_VERSION` env var). AWS publishing requires `.aws/aws-credentials.properties` or environment variables `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_S3_BUCKET_URL`, and `RELEASE_PATH`.

## Architecture

Kactor is a coroutine-native actor system library for Kotlin. The source lives under `lib/src/main/kotlin/me/hchome/kactor/`, with `impl/` holding the concrete implementations of the public interfaces.

### Core Abstractions

**ActorSystem** (`ActorSystem.kt` / `impl/ActorSystemImpl.kt`) — the entry point. Manages actor lifecycle, routes messages, and owns the root supervisor strategy. Create via `ActorSystem.createOrGet(...)`. Before creating any actor, its handler class must be registered:

```kotlin
system.register<MyHandler>(domain = "my-handler", config = ActorConfig(...))
val ref = system.actorOf<MyHandler>()
```

**ActorHandler** (`ActorHandler.kt`) — user-implemented interface defining actor behavior. All methods use Kotlin context receivers (`context(context: ActorContext)`), which requires the `-Xcontext-parameters` compiler flag (already set in `build.gradle.kts`). Key lifecycle hooks: `preStart`, `postStop`, `onMessage`, `onAsk`, `onIdle`, `onTaskException`, and `supervise`.

**ActorContext** (`ActorContext.kt`) — the actor's runtime environment, injected via context receiver. Provides: sending messages (`sendActor`, `sendSelf`, `sendChild`, `sendParent`, `sendChildren`), creating actors (`newChild`, `newActor`), stopping actors, scheduling tasks, and accessing `Attributes` (typed key/value state storage). Implements `CoroutineScope`; all launched coroutines are cancelled when the actor stops.

**ActorRef** (`ActorRef.kt`) — immutable, path-based actor identifier (e.g. `/parent/child`). `ActorRef.EMPTY` represents no actor. Used for all message routing; actors never hold direct references to each other.

**ActorConfig** (`ActorContext.kt`) — per-handler configuration: mailbox capacity, buffer overflow strategy, supervisor strategy, and idle timeout. Registered alongside the handler class.

### Message Flow

Each actor has a `MailBox` with two priority channels (`HIGH` and `NORMAL`). `select` always drains `HIGH` first. Messages are wrapped as `UserMessage.Tell` (fire-and-forget) or `UserMessage.Ask` (request-reply via `CompletableDeferred`). System operations (create, restart, stop) use `SystemMessage` on a separate high-priority channel.

### Supervision

`SupervisorStrategy` is a sealed interface with four built-in implementations:

| Strategy | Behavior |
|---|---|
| `OneForOne` | Recreates the failing actor (default; discards state) |
| `OneForOneRetained` | Restarts the failing actor (preserves `Attributes` state) |
| `AllForOne` | Restarts all siblings of the failing actor |
| `Escalate` | Calls `supervise()` on the parent's `ActorHandler` |

`supervise()` in `ActorHandler` returns a `SupervisorStrategy.Decision` (`Recreate`, `Restart`, `Stop`, or `Resume`) and is only invoked when the strategy is `Escalate`.

### Key Design Notes

- **`compileOnly` dependencies**: `kotlin-stdlib`, `kotlin-reflect`, `kotlinx-coroutines`, and `slf4j-api` are all `compileOnly`. Consumers must include them in their own dependency declarations.
- **Handler registration**: Every `ActorHandler` class must be registered with `ActorSystem.register(...)` before `actorOf` is called. Registration binds the handler to a domain name, dispatcher, config, and optional factory.
- **`ActorHandlerFactory`**: Defaults to `DefaultActorHandlerFactory` (reflective no-arg constructor). Override for DI framework integration.
- **Context receiver pattern**: All `ActorHandler` methods declare `context(context: ActorContext)`. This is a Kotlin 2.2 experimental feature enabled via `-Xcontext-parameters`. When calling handler methods in tests or implementations, you must call them within a `with(context) { ... }` block or pass the context explicitly.