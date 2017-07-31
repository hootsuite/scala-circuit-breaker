package com.hootsuite.circuitbreaker

import org.slf4j.LoggerFactory

import scala.collection.concurrent.TrieMap.{empty => emptyCMap}

/**
  * In-memory shared registry for all circuit breakers in a process. Only ever returns copies of internally held
  * circuit breakers.
  */
object CircuitBreakerRegistry {

  private val logger = LoggerFactory.getLogger(getClass)

  private val circuitBreakerStore = emptyCMap[String, ReadOnlyCircuitBreakerSnapshot]

  /**
    * Registers a circuit breaker
    * @param circuitBreaker the circuit breaker to register
    * @return an option value containing the CircuitBreaker associated with the key before the put operation was
    *         executed, or None if CircuitBreaker was not defined in the map before
    */
  def register(circuitBreaker: ReadOnlyCircuitBreakerSnapshot): Option[ReadOnlyCircuitBreakerSnapshot] = {
    if (circuitBreakerStore.contains(circuitBreaker.name)) {
      logger.warn(
        s"Circuit breaker with name ${circuitBreaker.name} already exists and will be replaced because " +
          s"another circuit breaker with the same name is already registered"
      )
    }
    circuitBreakerStore.put(circuitBreaker.name, circuitBreaker)
  }

  /**
    * Removes the CircuitBreaker by reference if present in the registry
    * @param circuitBreaker the circuit breaker to remove
    * @return an option value containing the CircuitBreaker, or None if CircuitBreaker was not present in the map before
    */
  def remove(circuitBreaker: ReadOnlyCircuitBreakerSnapshot): Option[ReadOnlyCircuitBreakerSnapshot] =
    circuitBreakerStore.remove(circuitBreaker.name)

  /**
    * Removes the CircuitBreaker by name if present in the registry
    * @param name name of the circuit breaker to remove
    * @return an option value containing the CircuitBreaker, or None if CircuitBreaker was not present in the map before
    */
  def remove(name: String): Option[ReadOnlyCircuitBreakerSnapshot] = circuitBreakerStore.remove(name)

  /**
    * Gets a read-only snapshot of all CircuitBreakers stored in this registry
    * @return a Map containing a read-only snapshot of all CircuitBreakers stored in this registry
    */
  def getAll: collection.Map[String, ReadOnlyCircuitBreakerSnapshot] = circuitBreakerStore.readOnlySnapshot()

  /**
    * Gets a read-only snapshot of a CircuitBreaker stored in this registry, by its name
    * @param name name of the circuit breaker to fetch
    * @return an option value containing the CircuitBreaker, or None if CircuitBreaker was not present in the map before
    */
  def get(name: String): Option[ReadOnlyCircuitBreakerSnapshot] =
    circuitBreakerStore.get(name).map(ClonedCircuitBreaker(_))

  //for testing purposes
  private[circuitbreaker] def clear(): Unit = circuitBreakerStore.clear()
}

private case class ClonedCircuitBreaker(name: String, isFlowing: Boolean, isBroken: Boolean)
  extends ReadOnlyCircuitBreakerSnapshot

private object ClonedCircuitBreaker {

  def apply(cb: ReadOnlyCircuitBreakerSnapshot): ClonedCircuitBreaker =
    new ClonedCircuitBreaker(cb.name, cb.isFlowing, cb.isBroken)
}
