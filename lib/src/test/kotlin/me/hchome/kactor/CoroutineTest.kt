package me.hchome.kactor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class CoroutineTest {


    @Test
    fun `test channel`(): Unit = runBlocking {

        val channel = Channel<Int>(100, onBufferOverflow = BufferOverflow.SUSPEND)
        val job = launch {
            channel.consumeEach {
                delay(1000)
                println("Received: $it")
            }
        }

        launch {
            repeat(1000) { i ->
//                val random = (1..1000).random()
                channel.send(i)
                println("Sent: $i")
                delay(1)
            }
            job.cancel()
        }

        job.join()
    }

    @Test
    fun `test send many messages`(): Unit = runBlocking {
        val ref = SYSTEM.actorOf<TestActor>("test1")
        val job2 = launch(SupervisorJob()) {
            launch {
                (0..1_000_000).forEach {
                    delay(20)
                    SYSTEM.send(ref, it)
                }
            }
            launch {
                (2_000_000..3_000_000).forEach {
                    delay(10)
                    SYSTEM.send(ref, it)
                }
            }
        }
        val job = launch {
            delay(3.seconds)
            job2.cancel()
        }
        job.join()
        SYSTEM.destroyActor(ref)
    }


    companion object : CoroutineScope by CoroutineScope(Dispatchers.Default) {

        lateinit var SYSTEM: ActorSystem
        private var LOGGER = LoggerFactory.getLogger(CoroutineTest::class.java)

        @JvmStatic
        @BeforeAll
        fun createSystem() {
            SYSTEM = ActorSystem.createOrGet(Dispatchers.IO)
            SYSTEM.register<TestActor>(config = ActorConfig(10, BufferOverflow.SUSPEND))
            launch {
                SYSTEM.notifications.collect { notification ->
//                    println(notification)
                    LOGGER.debug("{}", notification)
                }
            }
        }

        @AfterAll
        @JvmStatic
        fun cleanup() {
            SYSTEM.dispose()
            cancel()
        }
    }

    class TestActor : ActorHandler {
        context(context: ActorContext)
        override suspend fun onMessage(message: Any, sender: ActorRef) {
            delay(100)
            println("Received: $message")
        }
    }
}