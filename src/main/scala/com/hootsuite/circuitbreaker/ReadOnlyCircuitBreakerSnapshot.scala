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
    * Whether this circuit breaker is closed/flowing.
    *
    * @return true if it is, false otherwise
    */
  def isFlowing: Boolean

  /**
    * Whether this circuit breaker is opened/broken.
    *
    * @return true if it is, false otherwise
    */
  def isBroken: Boolean

  /**
    * Whether this circuit breaker is opened/broken, but enough
    * time has elapsed that the next invocation will cause a retry.
    *
    * Note: `isBroken` will still return `true` when this returns `true`.
    *
    * @return true if it is waiting to retry, false otherwise
    */
  def isWaiting: Boolean
}
