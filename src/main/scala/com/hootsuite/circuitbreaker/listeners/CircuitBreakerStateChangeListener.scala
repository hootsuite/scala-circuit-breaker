package com.hootsuite.circuitbreaker.listeners

/**
 * A listener for circuit breaker state changes
 */
trait CircuitBreakerStateChangeListener {

  /**
   * Called when a the circuit breaker is initialized.  Useful for setting gauges and logging initialization.
   *
   * @param name - name of the circuit breaker
   */
  def onInit(name: String): Unit = { /* blank default implementation */ }

  /**
   * Called when a the circuit breaker is tripped (i.e. closed -> open)
   *
   * @param name - name of the circuit breaker
   */
  def onTrip(name: String): Unit = { /* blank default implementation */ }

  /**
   * Called when a the circuit breaker is closed (i.e. open -> closed)
   *
   * @param name - name of the circuit breaker
   */
  def onReset(name: String): Unit = { /* blank default implementation */ }
}
