package com.hootsuite.circuitbreakerexample.client

import akka.actor.Actor
import akka.actor.Props
import com.hootsuite.circuitbreaker.CircuitBreakerBuilder
import com.ning.http.client.AsyncHttpClientConfig
import org.slf4j.LoggerFactory
import play.api.libs.ws.ning.NingWSClient
import play.api.http.Status

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import java.util.concurrent.TimeUnit

class PingActor extends Actor {
  import context.dispatcher
  import PingActor._

  private val logger = LoggerFactory.getLogger(getClass)
  private val url = "http://localhost:9000/ping"
  private val builder = new AsyncHttpClientConfig.Builder()
  private val client = new NingWSClient(builder.build())

  private val circuitBreaker = new CircuitBreakerBuilder(
    name = "my-circuit-breaker",
    failLimit = 10,
    retryDelay = FiniteDuration(3, TimeUnit.SECONDS),
    stateChangeListeners = List(CircuitBreakerHelper.defaultLoggingStateChangeListener(logger)),
    invocationListeners = List(CircuitBreakerHelper.defaultLoggingInvocationListener(logger))
  ).build()

  context.system.scheduler.schedule(2.second, 400.milliseconds, self, Tick)

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

  private def ping(): Future[String] = {
    client.url(url).get().map { response =>
      if (response.status == Status.OK) response.body
      else throw new Exception(response.statusText)
    }
  }
}

object PingActor {

  def props(): Props = Props(new PingActor())

  case object Tick
}
