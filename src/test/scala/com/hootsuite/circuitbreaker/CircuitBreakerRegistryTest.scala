package com.hootsuite.circuitbreaker

import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}

import scala.concurrent.duration.Duration
import scala.util.Try
import java.util.concurrent.TimeUnit

class CircuitBreakerRegistryTest extends FlatSpec with Matchers with BeforeAndAfter {

  before {
    CircuitBreakerRegistry.clear()
  }

  val retryDelay = Duration(100, TimeUnit.MILLISECONDS)

  private def waitUntilRetryDelayHasExpired() = Thread.sleep(2 * retryDelay.toMillis)

  "registry" should "be empty on startup" in {
    CircuitBreakerRegistry.getAll.isEmpty shouldEqual true
  }

  it should "store a circuit breaker in the registry" in {
    CircuitBreakerBuilder("test", 1, retryDelay).build()
    CircuitBreakerRegistry.getAll.size shouldEqual 1
  }

  it should "allow retrieval of an already stored circuit breaker" in {
    val name = "the name"
    CircuitBreakerBuilder(name, 1, retryDelay).build()
    val retrieved = CircuitBreakerRegistry.get(name)

    retrieved should be('defined)
  }

  it should "return None when looking up an unknown circuit breaker" in {
    val retrieved = CircuitBreakerRegistry.get("unknown")

    retrieved should not be 'defined
  }

  it should "allow removal of circuit breaker by name" in {
    val name = "the name"
    CircuitBreakerBuilder(name, 1, retryDelay).build()
    CircuitBreakerRegistry.getAll should not be 'empty
    val removed = CircuitBreakerRegistry.remove(name)

    removed should be('defined)
    CircuitBreakerRegistry.getAll should be('empty)
  }

  it should "allow removal of circuit breaker by reference" in {
    val circuitBreaker = CircuitBreakerBuilder("a name", 1, retryDelay).build()
    CircuitBreakerRegistry.getAll should not be 'empty
    val removed = CircuitBreakerRegistry.remove(circuitBreaker)

    removed should be('defined)
    CircuitBreakerRegistry.getAll should be('empty)
  }

  it should "allow registering multiple circuit breakers" in {
    CircuitBreakerBuilder("one", 1, retryDelay).build()
    CircuitBreakerBuilder("two", 1, retryDelay).build()

    CircuitBreakerRegistry.getAll.size should be(2)
    CircuitBreakerRegistry.get("one") should be('defined)
    CircuitBreakerRegistry.get("two") should be('defined)
  }

  it should "return a read-once version of the underlying circuit breaker" in {
    val name = "trip fast"
    val actualCircuitBreaker = CircuitBreakerBuilder(name, 1, retryDelay).build()

    val lookedUpCircuitBreaker =
      CircuitBreakerRegistry.get(name).getOrElse(throw new Exception("should've found this!"))

    //initial state - actual and looked up are the same
    actualCircuitBreaker.isFlowing shouldEqual true
    lookedUpCircuitBreaker.isFlowing shouldEqual actualCircuitBreaker.isFlowing
    lookedUpCircuitBreaker.isBroken shouldEqual actualCircuitBreaker.isBroken
    lookedUpCircuitBreaker.isWaiting shouldEqual actualCircuitBreaker.isWaiting

    //attach an operation so that we can test the circuit breaker
    def myOperation = actualCircuitBreaker() {
      throw new Exception("this is expected")
    }

    //now trip the breaker, and wait until retry delay
    Try { myOperation }
    Try { myOperation }
    waitUntilRetryDelayHasExpired()

    actualCircuitBreaker.isFlowing shouldEqual false
    actualCircuitBreaker.isWaiting shouldEqual true
    lookedUpCircuitBreaker.isFlowing should not be actualCircuitBreaker.isFlowing
    lookedUpCircuitBreaker.isBroken should not be actualCircuitBreaker.isBroken
    lookedUpCircuitBreaker.isWaiting should not be actualCircuitBreaker.isWaiting

    // moral of the story: don't rely on the references returned from the registry; always query the registry
  }
}
