package com.hootsuite.circuitbreakerexample.client

import akka.actor.{Actor, ActorSystem, Props}
import com.hootsuite.circuitbreaker.CircuitBreakerBuilder
import play.api.libs.ws.ahc.AhcWSClient
import org.slf4j.LoggerFactory
import play.api.http.Status

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import java.util.concurrent.TimeUnit

import akka.stream.Materializer

class PingActor extends Actor {
  import context.dispatcher
  import PingActor._

  private val logger = LoggerFactory.getLogger(getClass)
  private val url = "http://localhost:9000/ping"
  implicit val system       = ActorSystem()
  implicit val materializer = Materializer.matFromSystem
  private val client = AhcWSClient()

  private val circuitBreaker = new CircuitBreakerBuilder(
    name = "my-circuit-breaker",
    failLimit = 10,
    retryDelay = FiniteDuration(3, TimeUnit.SECONDS),
    stateChangeListeners = List(CircuitBreakerHelper.defaultLoggingStateChangeListener(logger)),
    invocationListeners = List(CircuitBreakerHelper.defaultLoggingInvocationListener(logger))
  ).build()

  context.system.scheduler.scheduleWithFixedDelay(2.second, 400.milliseconds, self, Tick)

  override def receive: Receive = {
    case Tick =>
      circuitBreaker.async() {
        val request: Future[String] = ping()
        request.onComplete {
          case Success(result) =>
            logger.info(s"Request response: $result")
          case Failure(e) =>
            logger.error("Error in ping request", e.getMessage)
        }
        request
      }
  }

  private def ping(): Future[String] =
    client.url(url).get().map { response =>
      if (response.status == Status.OK) response.body
      else throw new Exception(response.statusText)
    }
}

object PingActor {

  def props(): Props = Props(new PingActor())

  case object Tick
}
