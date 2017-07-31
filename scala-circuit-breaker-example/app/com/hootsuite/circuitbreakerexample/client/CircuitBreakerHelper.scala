package com.hootsuite.circuitbreakerexample.client

import com.hootsuite.circuitbreaker.listeners.CircuitBreakerInvocationListener
import com.hootsuite.circuitbreaker.listeners.CircuitBreakerStateChangeListener
import org.slf4j.Logger

object CircuitBreakerHelper {

  def defaultLoggingInvocationListener(logger: Logger): CircuitBreakerInvocationListener =
    new CircuitBreakerInvocationListener {

      override def onInvocationInFlowState(name: String): Unit =
        logger.debug(s"Circuit breaker \'$name\' invoked in closed/flow state")

      override def onInvocationInBrokenState(name: String): Unit =
        logger.debug(s"Circuit breaker \'$name\' invoked in open/broken state")
    }

  def defaultLoggingStateChangeListener(logger: Logger): CircuitBreakerStateChangeListener =
    new CircuitBreakerStateChangeListener {

      override def onInit(name: String): Unit =
        logger.info(s"Initializing circuit breaker \'$name\'")

      override def onTrip(name: String): Unit =
        logger.warn(s"Circuit breaker \'$name\' was TRIPPED; circuit is now open/broken")

      override def onReset(name: String): Unit =
        logger.warn(s"Circuit breaker \'$name\' was RESET; circuit is now closed/flowing")
    }
}
