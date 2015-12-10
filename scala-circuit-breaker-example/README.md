# Demo

Demo consist in a client making ping requests to a server, which can be configured to return 200 or 500 resp.

When client using scala-circuit-breaker detects that threshold has been reached, it will open the circuit and will not send more requests.

After a period of time, Circuit breaker will attemp to make a request to the server and if it is successful it will close the circuit and will start sending again all the requests to server.

After receiving a defined amount of faultless requests, the circuit breaker will open the circuit.

## Instructions

### Run server

```
runServer
```
or
```
~run
```

### Run client
```
runClient
```
or
```
runMain com.hootsuite.circuitbreakerexample.client.CircuitBreakerClient
```

### Change server state on fly

### To make server return error Http 500
```
curl -X POST http://localhost:9000/state/false
```
### To make server return pong Http 200
```
curl -X POST http://localhost:9000/state/true
```

### To make server return random Http 200 or 500

For 30% (default):
```
curl -X POST http://localhost:9000/random
```
For a defined percentage, e.g. 60%:
```
curl -X POST http://localhost:9000/random?value=60
```

## Demo

![Demo](https://cldup.com/-6NeCJs4Zm.gif)

## Demo explanation

- Server starts in terminal1. ```sbt runServer```
- Client starts in terminal2. ```sbt runClient```

Client starts sending ping requests to server ```http://localhost:9000/ping``` and server returns ```Pong!```

As Circuit breaker is monitoring all request, it will log a message telling that circuit is closed (Everything it's ok)

```
DEBUG [com.hootsuite.circuitbreakerexample.client.PingActor] - Circuit breaker 'my-circuit-breaker' invoked in closed/flow state
INFO  [com.hootsuite.circuitbreakerexample.client.PingActor] - Request response: "Pong!"
```

- In terminal3 we change the server status to start returning Error (500) to all requests.
```curl -X POST http://localhost:9000/state/false```

Circuit breaker it's monitoring the requests and noticed that requests are failing, it will start counting the requests until the threshold (10) is reached.
```
DEBUG [com.hootsuite.circuitbreakerexample.client.PingActor] - Circuit breaker 'my-circuit-breaker' invoked in closed/flow state
ERROR [com.hootsuite.circuitbreakerexample.client.PingActor] - Error in ping request
DEBUG [com.hootsuite.circuitbreaker.CircuitBreaker$] - Circuit breaker my-circuit-breaker increment failure count to 9; fail limit is 10
DEBUG [com.hootsuite.circuitbreakerexample.client.PingActor] - Circuit breaker 'my-circuit-breaker' invoked in closed/flow state
ERROR [com.hootsuite.circuitbreakerexample.client.PingActor] - Error in ping request
DEBUG [com.hootsuite.circuitbreaker.CircuitBreaker$] - Circuit breaker my-circuit-breaker increment failure count to 10; fail limit is 10
```

When threshold is reached, it will open the circuit and will not send additional requests to the server.
```
WARN  [com.hootsuite.circuitbreaker.CircuitBreaker$] - Circuit breaker 'my-circuit-breaker' is being TRIPPED.  Moving to OPEN/BROKEN state.
WARN  [com.hootsuite.circuitbreakerexample.client.PingActor] - Circuit breaker 'my-circuit-breaker' was TRIPPED; circuit is now open/broken
DEBUG [com.hootsuite.circuitbreakerexample.client.PingActor] - Circuit breaker 'my-circuit-breaker' invoked in open/broken state
DEBUG [com.hootsuite.circuitbreakerexample.client.PingActor] - Circuit breaker 'my-circuit-breaker' invoked in open/broken state
```

Circuit breaker will attempt to contact server and will let pass a request to the server, if the request is successful it will close the circuit and if it isn't it will keep circuit open.
```
DEBUG [com.hootsuite.circuitbreaker.CircuitBreaker$] - Circuit breaker 'my-circuit-breaker', attempting to reset open/broken state
DEBUG [com.hootsuite.circuitbreakerexample.client.PingActor] - Circuit breaker 'my-circuit-breaker' invoked in open/broken state
ERROR [com.hootsuite.circuitbreakerexample.client.PingActor] - Error in ping request
DEBUG [com.hootsuite.circuitbreakerexample.client.PingActor] - Circuit breaker 'my-circuit-breaker' invoked in open/broken state
```

- In terminal3 we change the server status to return Ok (200) to all requests.

Circuit breaker will detect that requests are now returning Ok and it will close the circuit letting pass again all requests to server.
```
DEBUG [com.hootsuite.circuitbreaker.CircuitBreaker$] - Circuit breaker 'my-circuit-breaker', attempting to reset open/broken state
DEBUG [com.hootsuite.circuitbreakerexample.client.PingActor] - Circuit breaker 'my-circuit-breaker' invoked in open/broken state
WARN  [com.hootsuite.circuitbreaker.CircuitBreaker$] - Circuit breaker 'my-circuit-breaker' is being RESET.  Moving to CLOSED/FLOWING state.
INFO  [com.hootsuite.circuitbreakerexample.client.PingActor] - Request response: "Pong!"
WARN  [com.hootsuite.circuitbreakerexample.client.PingActor] - Circuit breaker 'my-circuit-breaker' was RESET; circuit is now closed/flowing
DEBUG [com.hootsuite.circuitbreakerexample.client.PingActor] - Circuit breaker 'my-circuit-breaker' invoked in closed/flow state
INFO  [com.hootsuite.circuitbreakerexample.client.PingActor] - Request response: "Pong!"
```

- In terminal3 we change the server to start failing randomly.
