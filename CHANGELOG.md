# Changelog

## 1.0.5

- Remove unused logback-core dependency

## 1.0.4

- Add `isWaiting` to `ReadOnlyCircuitBreakerSnapshot` that will return `true` if the Circuit Breaker is OPEN/BROKEN and the `retryDelay` has passed, but no additional invocation has been made yet that could close the breaker again.
    - This can be useful when using `CircuitBreakerRegistry.get` in something like a status check when there is a very low volume of calls flowing through the Circuit Breaker. A Circuit Breaker that is waiting to try another call may not indicate an alertable error.

## 1.0.3

- No changes were made to the code. JCenter wasn't showing up the new library version.

## 1.0.2
- Cross build library to Scala 2.11.x and 2.12.x thanks to https://github.com/treppo
- scalafmt

## 1.0.1
- Change CircuitBreaker class to public with private constructor

## 1.0.0
- Initial OSS Release
