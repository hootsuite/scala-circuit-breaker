package com.hootsuite.circuitbreaker.listeners

/**
  * A listener for circuit breaker invocations of functions/methods wrapped by the circuit breaker
  */
trait CircuitBreakerInvocationListener {

  /**
    * Called when a wrapped function/method is called while the circuit breaker is closed (flowing)
    *
    * @param name - name of the circuit breaker
    */
  def onInvocationInFlowState(name: String): Unit = { /* empty */ }

  /**
    * Called when a wrapped function/method is called while the circuit breaker is opened (broken)
    *
    * @param name - name of the circuit breaker
    */
  def onInvocationInBrokenState(name: String): Unit = { /* empty */ }
}
