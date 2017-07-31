package com.hootsuite.circuitbreaker

/**
  * Circuit breaker is in broken state, invocations are failing immediately. The only exception
  * consumers of circuit breaker protected functions/methods need to handle
  */
class CircuitBreakerBrokenException(
  val name: String,
  val message: String = "<none>",
  val cause: Option[Throwable] = None
) extends RuntimeException(cause.orNull)
