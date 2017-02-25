package uy.kohesive.solr.undertow

import com.google.common.util.concurrent.RateLimiter
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import io.undertow.server.handlers.ResponseCodeHandler
import uy.klutter.core.jdk.maximum
import uy.klutter.core.jdk.minimum
import java.util.concurrent.TimeUnit

class RequestPerSecondRateLimiter(val maxRPS: Long,
                                  val nextHandler: HttpHandler,
                                  val minPauseOnBusyMillis: Long = 10L,
                                  val maxPauseOnBusyMillis: Long = 0,
                                  val httpCodeWhenBusy: Int = 503) : HttpHandler {
    val burstLimiter = RateLimiter.create(maxRPS.toDouble())
    val failureHandler: HttpHandler = ResponseCodeHandler(httpCodeWhenBusy)

    override fun handleRequest(exchange: HttpServerExchange) {
        if (exchange.isInIoThread) {
            if (burstLimiter.tryAcquire(1)) {
                nextHandler.handleRequest(exchange)
                return
            } else {
                if (maxPauseOnBusyMillis <= 0) {
                    failureHandler.handleRequest(exchange)
                    return
                } else {
                    exchange.dispatch(this)
                    return
                }
            }
        } else {
            if (burstLimiter.tryAcquire(1, maxPauseOnBusyMillis, TimeUnit.MILLISECONDS)) {
                nextHandler.handleRequest(exchange)
            } else {
                // purely to back off the CPU a bit
                if (minPauseOnBusyMillis > 0) {
                    try {
                        Thread.sleep(minPauseOnBusyMillis.minimum(maxPauseOnBusyMillis))
                    } catch (ex: InterruptedException) { /* noop */
                    }
                }
                failureHandler.handleRequest(exchange)
                return
            }
        }
    }
}
