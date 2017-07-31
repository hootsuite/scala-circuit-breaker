package com.hootsuite.circuitbreaker

/**
  * A read-only snapshot of the state of a circuit breaker.
  */
trait ReadOnlyCircuitBreakerSnapshot {

  /**
    * Name of the circuit breaker, aka the resource name
    */
  def name: String

  /**
    * Whether or not this circuit breaker is closed/flowing
    *
    * @return true if it is, false otherwise
    */
  def isFlowing: Boolean

  /**
    * Whether or not the circuit breaker is opened/broken
    *
    * @return true if it is, false otherwise
    */
  def isBroken: Boolean
}
