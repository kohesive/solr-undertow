package uy.kohesive.solr.undertow

import com.google.common.base.Stopwatch
import io.undertow.Undertow
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import io.undertow.server.handlers.ResponseCodeHandler
import nl.komponents.kovenant.Kovenant
import nl.komponents.kovenant.all
import nl.komponents.kovenant.buildDispatcher
import nl.komponents.kovenant.task
import org.junit.Ignore
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TestRequestPerSecondRateLimiter {
    class PauseHandler(val pauseTime: Long) : HttpHandler {
        val complete = ResponseCodeHandler(200)
        var reqCount: AtomicInteger = AtomicInteger(0)

        fun resetReqCount() = reqCount.set(0)

        override fun handleRequest(exchange: HttpServerExchange) {
            if (exchange.isInIoThread) {
                exchange.dispatch(this)
            } else {
                reqCount.incrementAndGet()
                if (pauseTime > 0) {
                    println("SIMULATING SLOW SERVER ... pause for $pauseTime")
                    Thread.sleep(pauseTime)

                }
                complete.handleRequest(exchange)
            }
        }
    }

    @Test
    @Ignore("This feels like a fragile way to test this, would be better if Guava let us test the rate limiter the same way they do with a fake system timer")
    fun testRateLimitingHandler() {
        val maxRps = 10L
        val msPerRequest = (1000L / maxRps).coerceAtLeast(1L).coerceAtMost(1000L)
        val minPauseOnBusyMillis = 10L
        val numSlotsToHopeForInFuture = 2
        val maxPauseOnBusyMillis = 25L // (msPerRequest * numSlotsToHopeForInFuture).minimum(minPauseOnBusyMillis).maximum(500L)

        val delayMultiple = 3
        val msSimPause = msPerRequest * delayMultiple // (msPerRequest / 2).toLong()

        println("maxRps:        $maxRps")
        println("msPerRequest:  $msPerRequest")
        println("msSimPause:    $msSimPause")
        println("minPause:      $minPauseOnBusyMillis")
        println("maxPause:      $maxPauseOnBusyMillis")
        val testPort = 11022
        val pauseHandler = PauseHandler(msSimPause)
        val server = Undertow.builder()
                .setWorkerThreads(200) // higher than RPS * 3
                .addHttpListener(testPort, "0.0.0.0",
                        RequestPerSecondRateLimiter(maxRps, pauseHandler,
                                minPauseOnBusyMillis = minPauseOnBusyMillis,
                                maxPauseOnBusyMillis = maxPauseOnBusyMillis))
                .build()
        server.start()

        val reqCount = AtomicInteger(0)

        fun resetReqCount() = reqCount.set(0)

        fun hitServer(): Int {
            return URL("http://localhost:${testPort}").openConnection().let { it as HttpURLConnection }.let {
                it.requestMethod = "GET"
                reqCount.incrementAndGet()
                it.connect()
                try {
                    it.inputStream.close()
                } catch (ex: IOException) {
                    if (" 503 " !in ex.message ?: "") throw ex
                }
                it.responseCode
            }
        }

        val range = 1..maxRps


        pauseHandler.resetReqCount()
        val stopWatch = Stopwatch.createUnstarted()

        fun resetAll() {
            stopWatch.reset()
            stopWatch.start()
            pauseHandler.resetReqCount()
            resetReqCount()
        }

        fun printAll() {
            val elapsed = stopWatch.elapsed(TimeUnit.SECONDS)
            val sentReqs = reqCount.get()
            val receivedReqs = pauseHandler.reqCount.get()
            println("::> [$elapsed secs] Req sent = $sentReqs at ${sentReqs.toDouble() / elapsed}/s, passed = $receivedReqs at ${receivedReqs.toDouble() / elapsed}/s")
        }

        fun <T : Any> withTimeStats(block: () -> T): T {
            resetAll()
            val t = block()
            printAll()
            return t
        }

        withTimeStats {
            println("Running end to end safely:")
            val safeRun = (1..maxRps).map { hitServer() }.map { println("   ${it}"); it }
            assertEquals(range.map { 200 }, safeRun)
        }

        println("Waiting...")
        Thread.sleep(1000L)

        val atTheLimitContext = Kovenant.createContext {
            workerContext.dispatcher = buildDispatcher {
                concurrentTasks = delayMultiple
            }
        }

        val biggerRange = 1..range.last * delayMultiple * 3

        val result2 = withTimeStats {
            println("Running SAFELY $delayMultiple at time concurrently:")
            val running2 = all(biggerRange.map { task(context = atTheLimitContext) { hitServer() } }, cancelOthersOnError = true, context = atTheLimitContext)
            running2.get()
        }

        assertEquals(biggerRange.map { 200 }, result2)

        println("Waiting...")
        Thread.sleep(1000L)

        val overloadDelayMultiple = delayMultiple + 2
        val overloadContext = Kovenant.createContext {
            workerContext.dispatcher = buildDispatcher {
                concurrentTasks = overloadDelayMultiple
            }
        }

        val biggestRange = 1..range.last * delayMultiple * 15

        val result3 = withTimeStats {
            println("Running DANGEROUSLY $overloadDelayMultiple at time concurrently:")
            val running3 = all(biggestRange.map { task(context = overloadContext) { hitServer() } }, cancelOthersOnError = true, context = overloadContext)
            running3.get()
        }

        // we should NOT have all 200's
        assertTrue(result3.count { it != 200 } > 0, "We should have at least a few 503's")

        println()
        println("Final overloaded throttles = ${result3.count { it != 200 }} out of ${result3.size}")
    }
}