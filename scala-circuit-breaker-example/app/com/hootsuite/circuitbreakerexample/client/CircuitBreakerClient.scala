package com.hootsuite.circuitbreakerexample.client

import akka.actor.ActorSystem

object CircuitBreakerClient extends App {
  private val system = ActorSystem("circuit-breaker-example-client")
  system.actorOf(PingActor.props())
}
