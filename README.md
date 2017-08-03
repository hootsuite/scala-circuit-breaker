# Circuit Breaker

## What is a Circuit Breaker?

A circuit breaker monitors the number of failed requests and decides to delay sending further requests
based on configurable threshold. Read more about [circuit breakers](http://martinfowler.com/bliki/CircuitBreaker.html).
Failure threshold, delay time, failure criteria, and event listeners are configurable in config file and code.

Our solution has been powering Scala services in production. It's battle tested and proven.

## Quick start guide

### Installation

build.sbt
```
resolvers += Resolver.jcenterRepo // Adds Bintray to resolvers
libraryDependencies ++= Seq("com.hootsuite" %% "scala-circuit-breaker" % "1.x.x")
```

## Usage

Use `CircuitBreakerBuilder` to initialize circuit breaker:

Configure circuit breaker in reference.conf

```
circuit-breaker {
  fail-limit = 5 # maximum number of consecutive failures before the circuit breaker is tripped (opened)
  retry-delay = 10 seconds # duration until an open/broken circuit breaker lets a call through to verify whether or not it should be reset
}
```

Optionally, define the following:

* a partial function that determines what should be considered as failures

* a partial function that determines what exceptions that should NOT be considered as failures

* listeners that will be notified when the circuit breaker changes state (open <--> closed)

* listeners that will be notified whenever the circuit breaker handles a method/function call

### Example

```scala
def circuitBreakerBuilder(name: String): CircuitBreakerBuilder = {
    new CircuitBreakerBuilder(
      name = name,
      failLimit = config.getInt("circuit-breaker.fail-limit"),
      retryDelay = Duration(config.getDuration("circuit-breaker.retry-delay", TimeUnit.SECONDS), TimeUnit.SECONDS))
      .withNonFailureExceptionCases {
        // Ignore 4xx level responses
        case _: BadRequest => true
        case _: Unauthorized => true
        case _: NotFound => true
      }
      .withStateChangeListeners(stateChangeListeners)
      .withInvocationListeners(invocationListeners)
  }
```

### Demo

See [scala-circuit-breaker-example](scala-circuit-breaker-example/)

## How to contribute

Contribute by submitting a PR and a bug report in GitHub.

## Maintainers

[Diego Alvarez](https://github.com/d1egoaz) [@d1egoaz](https://twitter.com/d1egoaz)

[Andres Rama](https://github.com/andresrama) [@andres_rama_hs](https://twitter.com/andres_rama_hs)

[Steve Song](https://github.com/ssong-van) [@ssongvan](https://twitter.com/ssongvan)

[Tatsuhiro Ujihisa](https://github.com/ujihisa) [@ujm](https://twitter.com/ujm)

[Johnny Bufu](https://github.com/jbufu)

scala-circuit-breaker is Open Source and available under the Apache 2 License.

This project took inspiration from [Sentries](https://github.com/erikvanoosten/sentries) which has a [BSD 2-Clause License](https://raw.githubusercontent.com/erikvanoosten/sentries/master/LICENSE), our solution includes ability to fine-tune what should be considered as failures. It also has hooks when circuit breaker states change.
