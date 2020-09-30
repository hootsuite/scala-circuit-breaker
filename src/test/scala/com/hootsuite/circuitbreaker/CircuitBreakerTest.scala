package com.hootsuite.circuitbreaker

import com.hootsuite.circuitbreaker.listeners.{CircuitBreakerInvocationListener, CircuitBreakerStateChangeListener}
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}
import flatspec._
import matchers._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{Await, Future, Promise}
import scala.util.control.ControlThrowable
import scala.util.{Failure, Success}
import java.util.concurrent.TimeUnit

class CircuitBreakerTest extends AnyFlatSpec with should.Matchers with ScalaFutures {

  object SimpleOperation {

    def operation(x: Int, y: Int): Int = x / y

    def asyncOperation(x: Int, y: Int): Future[Int] = Future { x / y }
  }

  // for whenReady calls
  private implicit val defaultPatience =
    PatienceConfig(timeout = Span(2, Seconds), interval = Span(10, Millis))

  private val numMillisecondsForRetryDelay = 200L
  private val defaultRetryDelay = Duration(numMillisecondsForRetryDelay, TimeUnit.MILLISECONDS)

  private def waitUntilRetryDelayHasExpired() = Thread.sleep(2 * numMillisecondsForRetryDelay)

  private def simpleBuilder(name: String, failLimit: Int, retryDelay: FiniteDuration) =
    CircuitBreakerBuilder(name = name, failLimit = failLimit, retryDelay = retryDelay)

  // CB builders that misbehave on (state change|invocation) listeners - either throwing or blocking
  // + one plain CB for baseline
  private def simpleBuilders(
    failLimit: Int = 2,
    retryDelay: FiniteDuration = defaultRetryDelay
  ): List[CircuitBreakerBuilder] = List(
    simpleBuilder("simple", failLimit, retryDelay),
    simpleBuilder("invocation listeners both block", failLimit, retryDelay)
      .withInvocationListeners(List(new CircuitBreakerInvocationListener {

        override def onInvocationInFlowState(name: String) = Thread.sleep(5000)

        override def onInvocationInBrokenState(name: String) = Thread.sleep(5000)
      })),
    simpleBuilder("invocation flow throws, invocation broken blocks", failLimit, retryDelay)
      .withInvocationListeners(List(new CircuitBreakerInvocationListener {

        override def onInvocationInFlowState(name: String) = throw new Exception("boom")

        override def onInvocationInBrokenState(name: String) = Thread.sleep(5000)
      })),
    simpleBuilder("invocation flow blocks, invocation broken blocks", failLimit, retryDelay)
      .withInvocationListeners(List(new CircuitBreakerInvocationListener {

        override def onInvocationInFlowState(name: String) = Thread.sleep(5000)

        override def onInvocationInBrokenState(name: String) = throw new Exception("boom")
      })),
    simpleBuilder("invocation listeners both throw", failLimit, retryDelay)
      .withInvocationListeners(List(new CircuitBreakerInvocationListener {

        override def onInvocationInFlowState(name: String) = throw new Exception("boom")

        override def onInvocationInBrokenState(name: String) = throw new Exception("boom")
      })),
    simpleBuilder("state change listeners all throw", failLimit, retryDelay)
      .withStateChangeListeners(List(new CircuitBreakerStateChangeListener {

        override def onInit(name: String) = throw new Exception("boom")

        override def onTrip(name: String) = throw new Exception("boom")

        override def onReset(name: String) = throw new Exception("boom")
      })),
    simpleBuilder("state change listeners all block", failLimit, retryDelay)
      .withStateChangeListeners(List(new CircuitBreakerStateChangeListener {

        override def onInit(name: String) = Thread.sleep(5000)

        override def onTrip(name: String) = Thread.sleep(5000)

        override def onReset(name: String) = Thread.sleep(5000)
      })),
    simpleBuilder("state change onInit throws, onTrip, onReset block", failLimit, retryDelay)
      .withStateChangeListeners(List(new CircuitBreakerStateChangeListener {

        override def onInit(name: String) = throw new Exception("boom")

        override def onTrip(name: String) = Thread.sleep(5000)

        override def onReset(name: String) = Thread.sleep(5000)
      })),
    simpleBuilder("state change onTrip throws, onInit, onReset block", failLimit, retryDelay)
      .withStateChangeListeners(List(new CircuitBreakerStateChangeListener {

        override def onInit(name: String) = Thread.sleep(5000)

        override def onTrip(name: String) = throw new Exception("boom")

        override def onReset(name: String) = Thread.sleep(5000)
      })),
    simpleBuilder("state change onReset throws, onInit, onReset block", failLimit, retryDelay)
      .withStateChangeListeners(List(new CircuitBreakerStateChangeListener {

        override def onInit(name: String) = Thread.sleep(5000)

        override def onTrip(name: String) = Thread.sleep(5000)

        override def onReset(name: String) = throw new Exception("boom")
      }))
  )

  "simple circuit breaker" should "record failures, trip, then reset after delay time has elapsed" in {

    simpleBuilders().map(_.build()).foreach { cb =>
      def protectedOperation(x: Int, y: Int) = cb() {
        SimpleOperation.operation(x, y)
      }

      // run all protectedOperation invocations async, so we can catch & timeout rogue, blocking listeners
      // 100ms should be more than enough for our SimpleOperation

      // first failure; let it through
      whenReady(Future(protectedOperation(1, 0)).failed, timeout(Span(100, Millis))) { e =>
        withClue(s"${cb.name} : first failure") {
          e shouldBe a[ArithmeticException]
        }
      }

      // second failure; let it through again but this should trip the circuit breaker
      whenReady(Future(protectedOperation(1, 0)).failed, timeout(Span(100, Millis))) { e =>
        withClue(s"${cb.name} : second failure") {
          e shouldBe a[ArithmeticException]
        }
      }

      // now we get a circuit breaker exception because the circuit breaker is open
      whenReady(Future(protectedOperation(1, 0)).failed, timeout(Span(100, Millis))) { e =>
        withClue(s"${cb.name} : CB broken failure") {
          e shouldBe a[CircuitBreakerBrokenException]
        }
      }

      //wait a bit
      waitUntilRetryDelayHasExpired()

      //circuit should now be closed and a valid operation should just work
      whenReady(Future(protectedOperation(2, 1), timeout(Span(100, Millis)))) {
        case (result, timeout) =>
          withClue(s"${cb.name} : after retry delay") {
            result shouldEqual 2
          }
      }
    }
  }

  it should "remain in tripped state on repeated errors" in {
    simpleBuilders(retryDelay = Duration(2, TimeUnit.SECONDS)).map(_.build()).foreach { cb =>
      def protectedOperation(x: Int, y: Int) = cb() {
        SimpleOperation.operation(x, y)
      }

      (1 to 2).foreach { i =>
        whenReady(Future(protectedOperation(1, 0)).failed, timeout(Span(100, Millis))) { e =>
          withClue(s"${cb.name} : failure #$i") {
            e shouldBe a[ArithmeticException]
          }
        }
      }

      // the CB is now tripped

      // repeated calls will just fail immediately, regardless of call
      (1 to 100).foreach { i =>
        // this call is legal but CB is in tripped state
        whenReady(Future(protectedOperation(2, 1)).failed, timeout(Span(10, Millis))) { e =>
          withClue(s"${cb.name} : attempt #$i while CB tripped") {
            e shouldBe a[CircuitBreakerBrokenException]
          }
        }
      }
    }
  }

  it should "be 'waiting' if it is in a tripped state, but the reset time delay has been reached" in {
    simpleBuilders().map(_.build()).foreach { cb =>
      def protectedOperation(x: Int, y: Int) = cb() {
        SimpleOperation.operation(x, y)
      }

      cb.isFlowing shouldBe true
      cb.isBroken shouldBe false
      cb.isWaiting shouldBe false

      (1 to 2).foreach { i =>
        whenReady(Future(protectedOperation(1, 0)).failed, timeout(Span(100, Millis))) { e =>
          withClue(s"${cb.name} : failure #$i") {
            e shouldBe a[ArithmeticException]
          }
        }
      }

      cb.isFlowing shouldBe false
      cb.isBroken shouldBe true
      cb.isWaiting shouldBe false

      //wait until after the reset delay time has elapsed
      waitUntilRetryDelayHasExpired()

      cb.isFlowing shouldBe false
      cb.isBroken shouldBe true
      cb.isWaiting shouldBe true
    }
  }

  it should "let call through to underlying function when reset time delay reached, even with error" in {
    simpleBuilders().map(_.build()).foreach { cb =>
      def protectedOperation(x: Int, y: Int) = cb() {
        SimpleOperation.operation(x, y)
      }

      (1 to 2).foreach { i =>
        whenReady(Future(protectedOperation(1, 0)).failed, timeout(Span(100, Millis))) { e =>
          withClue(s"${cb.name} : failure #$i") {
            e shouldBe a[ArithmeticException]
          }
        }
      }

      // now we get a circuit breaker exception because the circuit breaker is open
      whenReady(Future(protectedOperation(1, 0)).failed, timeout(Span(10, Millis))) { e =>
        withClue(s"${cb.name} : CB broken failure") {
          e shouldBe a[CircuitBreakerBrokenException]
        }
      }

      //wait until after the reset delay time has elapsed
      waitUntilRetryDelayHasExpired()

      // CB should let this call go through to test whether or not we need reset - will fail
      whenReady(Future(protectedOperation(1, 0)).failed, timeout(Span(100, Millis))) { e =>
        withClue(s"${cb.name} : test reset attempt after retry delay") {
          e shouldBe a[ArithmeticException]
        }
      }

      // back to tripped state but with retry delay now reset, fail immediately with CB exception
      whenReady(Future(protectedOperation(1, 0)).failed, timeout(Span(10, Millis))) { e =>
        withClue(s"${cb.name} : CB remain broken after failed reset attempt") {
          e shouldBe a[CircuitBreakerBrokenException]
        }
      }
    }
  }

  "simple async circuit breaker" should "record failures, trip, then reset after delay time has elapsed" in {
    simpleBuilders().map(_.build()).foreach { cb =>
      def protectedOperation(x: Int, y: Int) = cb.async() {
        SimpleOperation.asyncOperation(x, y)
      }

      (1 to 2).foreach { i =>
        whenReady(protectedOperation(1, 0).failed, timeout(Span(100, Millis))) { e =>
          withClue(s"${cb.name} : failure #$i") {
            e shouldBe a[ArithmeticException]
          }
        }
      }

      // now we get a circuit breaker exception because the circuit breaker is open
      whenReady(protectedOperation(1, 0).failed, timeout(Span(10, Millis))) { e =>
        withClue(s"${cb.name} : CB broken failure") {
          e shouldBe a[CircuitBreakerBrokenException]
        }
      }

      //wait a bit
      waitUntilRetryDelayHasExpired()

      //circuit should now be closed and a valid operation should just work
      whenReady(protectedOperation(2, 1), timeout(Span(100, Millis))) { result =>
        withClue(s"${cb.name} : after retry delay") {
          result shouldEqual 2
        }
      }
    }
  }

  it should "remain in tripped state on repeated errors" in {
    simpleBuilders(retryDelay = Duration(2, TimeUnit.SECONDS)).map(_.build()).foreach { cb =>
      def protectedOperation(x: Int, y: Int) = cb.async() {
        SimpleOperation.asyncOperation(x, y)
      }

      def syncProtectedOperation(x: Int, y: Int): Int =
        Await.result(protectedOperation(x, y), Duration(1, TimeUnit.SECONDS))

      (1 to 2).foreach { i =>
        whenReady(Future(syncProtectedOperation(1, 0)).failed, timeout(Span(100, Millis))) { e =>
          withClue(s"${cb.name} : failure #$i") {
            e shouldBe a[ArithmeticException]
          }
        }
      }

      // the CB is now tripped

      // repeated calls will just fail immediately, regardless of call
      (1 to 100).foreach { i =>
        // this call is legal but CB is in tripped state
        whenReady(Future(syncProtectedOperation(2, 1)).failed, timeout(Span(10, Millis))) { e =>
          withClue(s"${cb.name} : attempt #$i while CB tripped") {
            e shouldBe a[CircuitBreakerBrokenException]
          }
        }
      }
    }
  }

  it should "be 'waiting' if it is in a tripped state, but the reset time delay has been reached" in {
    simpleBuilders().map(_.build()).foreach { cb =>
      def protectedOperation(x: Int, y: Int) = cb.async() {
        SimpleOperation.asyncOperation(x, y)
      }

      cb.isFlowing shouldBe true
      cb.isBroken shouldBe false
      cb.isWaiting shouldBe false

      (1 to 2).foreach { i =>
        whenReady(protectedOperation(1, 0).failed, timeout(Span(100, Millis))) { e =>
          withClue(s"${cb.name} : failure #$i") {
            e shouldBe a[ArithmeticException]
          }
        }
      }

      cb.isFlowing shouldBe false
      cb.isBroken shouldBe true
      cb.isWaiting shouldBe false

      //wait until after the reset delay time has elapsed
      waitUntilRetryDelayHasExpired()

      cb.isFlowing shouldBe false
      cb.isBroken shouldBe true
      cb.isWaiting shouldBe true
    }
  }

  it should "let call through to underlying function when reset time delay reached, even with error" in {

    simpleBuilders().map(_.build()).foreach { cb =>
      def protectedOperation(x: Int, y: Int): Future[Int] = cb.async() {
        SimpleOperation.asyncOperation(x, y)
      }

      (1 to 2).foreach { i =>
        whenReady(protectedOperation(1, 0).failed, timeout(Span(100, Millis))) { e =>
          withClue(s"${cb.name} : failure #$i") {
            e shouldBe a[ArithmeticException]
          }
        }
      }

      // now we get a circuit breaker exception because the circuit breaker is open
      whenReady(protectedOperation(1, 0).failed, timeout(Span(10, Millis))) { e =>
        withClue(s"${cb.name} : CB broken failure") {
          e shouldBe a[CircuitBreakerBrokenException]
        }
      }

      //wait until after the reset delay time has elapsed
      waitUntilRetryDelayHasExpired()

      // CB should let this call go through to test whether or not we need reset - will fail
      whenReady(protectedOperation(1, 0).failed, timeout(Span(100, Millis))) { e =>
        withClue(s"${cb.name} : test reset attempt after retry delay") {
          e shouldBe a[ArithmeticException]
        }
      }

      // back to tripped state but with retry delay now reset, fail immediately with CB exception
      whenReady(protectedOperation(1, 0).failed, timeout(Span(10, Millis))) { e =>
        withClue(s"${cb.name} : CB remain broken after failed reset attempt") {
          e shouldBe a[CircuitBreakerBrokenException]
        }
      }
    }
  }

  "circuit breaker with fallback value" should "return the fallback value when the circuit breaker is tripped" in {
    simpleBuilders(failLimit = 5).map(_.build()).foreach { cb =>
      val theDefaultValue = 0

      // a bit of an odd behaviour for the fallback in this case, but tests the basic idea
      def protectedOperation(x: Int, y: Int) = cb(fallback = Some(Success(theDefaultValue))) {
        SimpleOperation.operation(x, y)
      }

      (1 to 5).foreach { i =>
        whenReady(Future(protectedOperation(1, 0)).failed, timeout(Span(100, Millis))) { e =>
          withClue(s"${cb.name} : failure #$i") {
            e shouldBe a[ArithmeticException]
          }
        }
      }

      // return the default value here, while the circuit is tripped
      whenReady(Future(protectedOperation(222, 1), timeout(Span(100, Millis)))) {
        case (result, timeout) =>
          withClue(s"${cb.name} : after retry delay") {
            result shouldEqual theDefaultValue
          }
      }
    }
  }

  it should "throw a custom exception when configured as such and when the circuit breaker is tripped" in {
    simpleBuilders(failLimit = 5).map(_.build()).foreach { cb =>
      class CustomException extends Throwable
      val customFailure = Failure(new CustomException)

      // a bit of an odd behaviour for the fallback in this case, but tests the basic idea
      def protectedOperation(x: Int, y: Int) = cb(fallback = Some(customFailure)) {
        SimpleOperation.operation(x, y)
      }

      (1 to 5).foreach { i =>
        whenReady(Future(protectedOperation(1, 0)).failed, timeout(Span(100, Millis))) { e =>
          withClue(s"${cb.name} : failure #$i") {
            e shouldBe a[ArithmeticException]
          }
        }
      }

      whenReady(Future(protectedOperation(2, 1)).failed, timeout(Span(10, Millis))) { e =>
        withClue(s"${cb.name} : custom fallback failure") {
          e shouldBe a[CustomException]
        }
      }
    }
  }

  "async circuit breaker with fallback value" should "return the fallback value when the circuit breaker is tripped" in {
    simpleBuilders(failLimit = 5).map(_.build()).foreach { cb =>
      val theDefaultValue = 0

      // a bit of an odd behaviour for the fallback in this case, but tests the basic idea
      def protectedOperation(x: Int, y: Int) = cb.async(fallback = Some(Success(theDefaultValue))) {
        SimpleOperation.asyncOperation(x, y)
      }

      (1 to 5).foreach { i =>
        whenReady(protectedOperation(1, 0).failed, timeout(Span(100, Millis))) { e =>
          withClue(s"${cb.name} : failure #$i") {
            e shouldBe a[ArithmeticException]
          }
        }
      }

      // return the default value here, while the circuit is tripped
      whenReady(protectedOperation(222, 1), timeout(Span(100, Millis))) { result =>
        withClue(s"${cb.name} : after retry delay") {
          result shouldEqual theDefaultValue
        }
      }
    }
  }

  it should "throw a custom exception when configured as such and when the circuit breaker is tripped" in {
    simpleBuilders(failLimit = 5).map(_.build()).foreach { cb =>
      class CustomException extends Throwable
      val customFailure = Failure(new CustomException)

      // a bit of an odd behaviour for the fallback in this case, but tests the basic idea
      def protectedOperation(x: Int, y: Int) = cb.async(fallback = Some(customFailure)) {
        SimpleOperation.asyncOperation(x, y)
      }

      (1 to 5).foreach { i =>
        whenReady(protectedOperation(1, 0).failed, timeout(Span(100, Millis))) { e =>
          withClue(s"${cb.name} : failure #$i") {
            e shouldBe a[ArithmeticException]
          }
        }
      }

      whenReady(protectedOperation(2, 1).failed, timeout(Span(10, Millis))) { e =>
        withClue(s"${cb.name} : custom fallback failure") {
          e shouldBe a[CustomException]
        }
      }
    }
  }

  "circuit breaker with custom failure definition" should "be tripped when custom failures are detected" in {
    simpleBuilders()
      .map(_.withResultFailureCases {
        case i: Int if i == 2 =>
          true // whenever the returned value is 2, the circuit breaker should mark as a failure
      }.build())
      .foreach { cb =>
        def protectedOperation(x: Int, y: Int) = cb() {
          SimpleOperation.operation(x, y)
        }

        whenReady(Future(protectedOperation(2, 1), timeout(Span(100, Millis)))) {
          case (result, timeout) =>
            withClue(s"${cb.name} : still returns the value but records it as a failure") {
              result shouldEqual 2
            }
        }

        whenReady(Future(protectedOperation(16, 8), timeout(Span(100, Millis)))) {
          case (result, timeout) =>
            withClue(s"${cb.name} : still returns the value but records it as a failure, trip CB") {
              result shouldEqual 2
            }
        }

        // circuit breaker is now open
        whenReady(Future(protectedOperation(3, 1)).failed, timeout(Span(10, Millis))) { e =>
          // the underlying function is never called, so the return value doesn't matter at this point
          withClue(s"${cb.name} : custom fallback failure") {
            e shouldBe a[CircuitBreakerBrokenException]
          }
        }
      }
  }

  it should "ignore exceptions thrown within the check" in {
    simpleBuilders()
      .map(_.withResultFailureCases {
        case _ => throw new Exception("a dumb thing to do")
      }.build())
      .foreach { cb =>
        def protectedOperation() = cb() { 1 }

        // this should not blow up with an exception
        whenReady(Future(protectedOperation(), timeout(Span(100, Millis)))) {
          case (result, timeout) =>
            withClue(s"${cb.name} : still return result when failure function throws") {
              result shouldEqual 1
            }
        }
      }
  }

  "async circuit breaker with custom failure definition" should "be tripped when custom failures are detected" in {
    simpleBuilders()
      .map(_.withResultFailureCases {
        case i: Int if i == 2 =>
          true // whenever the returned value is 2, the circuit breaker should mark as a failure
      }.build())
      .foreach { cb =>
        def protectedOperation(x: Int, y: Int) = cb.async() {
          SimpleOperation.asyncOperation(x, y)
        }

        whenReady(protectedOperation(2, 1), timeout(Span(100, Millis))) { result =>
          withClue(s"${cb.name} : still returns the value but records it as a failure") {
            result shouldEqual 2
          }
        }

        whenReady(protectedOperation(16, 8), timeout(Span(100, Millis))) { result =>
          withClue(s"${cb.name} : still returns the value but records it as a failure, trip CB") {
            result shouldEqual 2
          }
        }

        // circuit breaker is now open
        whenReady(protectedOperation(3, 1).failed, timeout(Span(10, Millis))) { e =>
          // the underlying function is never called, so the return value doesn't matter at this point
          withClue(s"${cb.name} : custom fallback failure") {
            e shouldBe a[CircuitBreakerBrokenException]
          }
        }
      }
  }

  it should "ignore exceptions thrown within the check" in {
    simpleBuilders()
      .map(_.withResultFailureCases {
        // the following is a terrible idea -  don't do this; but if someone does, ignore and log
        case _ => throw new Exception("a dumb thing to do")
      }.build())
      .foreach { cb =>
        def protectedOperation(): Future[Int] = cb.async() { Future { 1 } }

        // this should not blow up with an exception
        whenReady(protectedOperation(), timeout(Span(100, Millis))) { result =>
          withClue(s"${cb.name} : still return result when failure function throws") {
            result shouldEqual 1
          }
        }
      }
  }

  "circuit breaker configured to ignore certain exceptions" should "not be tripped when these exceptions occur" in {
    simpleBuilders()
      .map(_.withNonFailureExceptionCases {
        case e: ArithmeticException => true
      }.build())
      .foreach { cb =>
        def protectedOperation(x: Int, y: Int) = cb() {
          SimpleOperation.operation(x, y)
        }

        // the circuit is never tripped and just lets these exceptions through
        (1 to 10).foreach { i =>
          whenReady(Future(protectedOperation(1, 0)).failed, timeout(Span(100, Millis))) { e =>
            withClue(s"${cb.name} : failure #$i") {
              e shouldBe a[ArithmeticException]
            }
          }
        }
      }
  }

  it should "not ignore exceptions that have not be configured to be ignored" in {
    simpleBuilders()
      .map(_.withNonFailureExceptionCases {
        case e: ArithmeticException => true
      }.build())
      .foreach { cb =>
        def protectedOperation(x: Int, y: Int) = cb() {
          val ret = SimpleOperation.operation(x, y)
          if (ret == 3) throw new IllegalStateException("just here to verify this exception is not ignored")
          ret
        }

        // the circuit is never tripped and just lets these exceptions through
        (1 to 10).foreach { i =>
          whenReady(Future(protectedOperation(1, 0)).failed, timeout(Span(100, Millis))) { e =>
            withClue(s"${cb.name} : (non) failure #$i") {
              e shouldBe a[ArithmeticException]
            }
          }
        }

        whenReady(Future(protectedOperation(3, 1)).failed, timeout(Span(100, Millis))) { e =>
          withClue(s"${cb.name} : failure #1") {
            e shouldBe a[IllegalStateException]
          }
        }
        whenReady(Future(protectedOperation(6, 2)).failed, timeout(Span(100, Millis))) { e =>
          withClue(s"${cb.name} : failure #2") {
            e shouldBe a[IllegalStateException]
          }
        }

        // CB is now tripped
        whenReady(Future(protectedOperation(2, 1)).failed, timeout(Span(10, Millis))) { e =>
          withClue(s"${cb.name} : tripped failure") {
            e shouldBe a[CircuitBreakerBrokenException]
          }
        }
      }
  }

  it should "ignore exceptions thrown within the check" in {
    simpleBuilders()
      .map(_.withNonFailureExceptionCases {
        // the following is a terrible idea -  don't do this; but if someone does, ignore and log
        case _ => throw new Exception("a dumb thing to do")
      }.build())
      .foreach { cb =>
        def protectedOperation() = cb() { 1 }

        // this should not blow up with an exception
        whenReady(Future(protectedOperation(), timeout(Span(100, Millis)))) {
          case (result, timeout) =>
            withClue(s"${cb.name} : still return result when non-failure function throws") {
              result shouldEqual 1
            }
        }
      }
  }

  "async circuit breaker configured to ignore certain exceptions" should "not be tripped when these exceptions occur" in {
    simpleBuilders()
      .map(_.withNonFailureExceptionCases {
        case e: ArithmeticException => true
      }.build())
      .foreach { cb =>
        def protectedOperation(x: Int, y: Int) = cb.async() {
          SimpleOperation.asyncOperation(x, y)
        }

        // the circuit is never tripped and just lets these exceptions through
        (1 to 10).foreach { i =>
          whenReady(protectedOperation(1, 0).failed, timeout(Span(100, Millis))) { e =>
            withClue(s"${cb.name} : failure #$i") {
              e shouldBe a[ArithmeticException]
            }
          }
        }
      }
  }

  it should "not ignore exceptions that have not be configured to be ignored" in {
    simpleBuilders()
      .map(_.withNonFailureExceptionCases {
        case e: ArithmeticException => true
      }.build())
      .foreach { cb =>
        def protectedOperation(x: Int, y: Int) = cb.async() {
          Future {
            val ret = SimpleOperation.operation(x, y)
            if (ret == 3)
              throw new IllegalStateException("just here to verify this exception is not ignored")
            ret
          }
        }

        // the circuit is never tripped and just lets these exceptions through
        (1 to 10).foreach { i =>
          whenReady(protectedOperation(1, 0).failed, timeout(Span(100, Millis))) { e =>
            withClue(s"${cb.name} : (non) failure #$i") {
              e shouldBe a[ArithmeticException]
            }
          }
        }

        whenReady(protectedOperation(3, 1).failed, timeout(Span(100, Millis))) { e =>
          withClue(s"${cb.name} : failure #1") {
            e shouldBe a[IllegalStateException]
          }
        }
        whenReady(protectedOperation(6, 2).failed, timeout(Span(100, Millis))) { e =>
          withClue(s"${cb.name} : failure #2") {
            e shouldBe a[IllegalStateException]
          }
        }

        // CB is now tripped
        whenReady(protectedOperation(2, 1).failed, timeout(Span(10, Millis))) { e =>
          withClue(s"${cb.name} : tripped failure") {
            e shouldBe a[CircuitBreakerBrokenException]
          }
        }
      }
  }

  it should "ignore exceptions thrown within the check" in {
    simpleBuilders()
      .map(_.withNonFailureExceptionCases {
        // the following is a terrible idea -  don't do this; but if someone does, ignore and log
        case _ => throw new Exception("a dumb thing to do")
      }.build())
      .foreach { cb =>
        def protectedOperation() = cb.async() { Future(1) }

        // this should not blow up with an exception
        whenReady(protectedOperation(), timeout(Span(100, Millis))) { result =>
          withClue(s"${cb.name} : still return result when non-failure function throws") {
            result shouldEqual 1
          }
        }
      }
  }

  "circuit breaker configured with state change listeners" should "notify listeners on state changes" in {

    val promiseIllTrip = Promise[Boolean]()
    val promiseIllReset = Promise[Boolean]()

    // create a state change listener - we'll monitor its onTrip() and onReset() invocations
    val myStateChangeListener = new CircuitBreakerStateChangeListener {

      override def onTrip(resourceName: String): Unit = promiseIllTrip.success(true)

      override def onReset(resourceName: String): Unit = promiseIllReset.success(true)
    }

    val circuitBreaker = CircuitBreakerBuilder("notifyStateChangeListeners", 2, defaultRetryDelay)
      .withStateChangeListeners(List(myStateChangeListener))
      .build()

    def protectedOperation(x: Int, y: Int) = circuitBreaker() {
      SimpleOperation.operation(x, y)
    }

    (1 to 2).foreach { i =>
      whenReady(Future(protectedOperation(1, 0)).failed, timeout(Span(100, Millis))) { e =>
        withClue(s"${circuitBreaker.name} : failure #$i") {
          e shouldBe a[ArithmeticException]
        }
      }
    }

    // notified async / on separate thread of CB being *tripped*, wait a bit for it
    whenReady(promiseIllTrip.future, timeout(Span(50, Millis))) { tripped =>
      withClue("state listener was promised a 'tripped' notification") {
        tripped shouldBe true
      }
    }

    // wait beyond retry delay
    waitUntilRetryDelayHasExpired()

    // this should reset the CB
    whenReady(Future(protectedOperation(2, 1)), timeout(Span(100, Millis))) { result =>
      withClue(s"${circuitBreaker.name} : reset") {
        result shouldEqual 2
      }
    }

    // notified async / on separate thread of CB being *reset*, wait a bit for it
    whenReady(promiseIllReset.future, timeout(Span(50, Millis))) { reset =>
      withClue("state listener was promised a 'reset' notification") {
        reset shouldBe true
      }
    }
  }

  "circuit breaker configured with invocation listeners" should "notify listeners on invocations" in {

    val promiseCheckpoint1 = Promise[(Int, Int)]()
    val promiseCheckpoint2 = Promise[(Int, Int)]()
    val promiseCheckpoint3 = Promise[(Int, Int)]()
    val promiseCheckpoint4 = Promise[(Int, Int)]()

    val myInvocationListener = new CircuitBreakerInvocationListener {
      var flowStateInvocationCount = 0
      var brokenStateInvocationCount = 0

      private def keepPromises(): Unit = (flowStateInvocationCount, brokenStateInvocationCount) match {
        case (10, 0) => promiseCheckpoint1.success((flowStateInvocationCount, brokenStateInvocationCount))
        case (12, 0) => promiseCheckpoint2.success((flowStateInvocationCount, brokenStateInvocationCount))
        case (12, 10) => promiseCheckpoint3.success((flowStateInvocationCount, brokenStateInvocationCount))
        case (13, 11) => promiseCheckpoint4.success((flowStateInvocationCount, brokenStateInvocationCount))
        case _ =>
      }

      override def onInvocationInFlowState(resourceName: String): Unit = {
        flowStateInvocationCount = flowStateInvocationCount + 1
        keepPromises()
      }

      override def onInvocationInBrokenState(resourceName: String): Unit = {
        brokenStateInvocationCount += 1
        keepPromises()
      }
    }

    val circuitBreaker = CircuitBreakerBuilder("notifyInvocationListeners", 2, defaultRetryDelay)
      .withInvocationListeners(List(myInvocationListener))
      .build()

    def protectedOperation(x: Int, y: Int) = circuitBreaker() {
      SimpleOperation.operation(x, y)
    }

    // 10 invocations in flow state
    (1 to 10).foreach { _ =>
      protectedOperation(1, 1)
    }

    whenReady(promiseCheckpoint1.future, timeout(Span(50, Millis))) {
      case (invoked, broken) =>
        withClue("expected (10 invocation, 0 broken) notifications at checkpoint 1: ") {
          invoked shouldEqual 10
          broken shouldEqual 0
        }
    }

    // 2 more in flow state but will trip the CB
    (1 to 2).foreach { _ =>
      intercept[ArithmeticException] {
        protectedOperation(1, 0)
      }
    }

    whenReady(promiseCheckpoint2.future, timeout(Span(50, Millis))) {
      case (invoked, broken) =>
        withClue("expected (12 invocation, 0 broken) notifications at checkpoint 2: ") {
          invoked shouldEqual 12
          broken shouldEqual 0
        }
    }

    // 10 invocations while in broken state
    (1 to 10).foreach { _ =>
      intercept[CircuitBreakerBrokenException] {
        protectedOperation(1, 1)
      }
    }

    whenReady(promiseCheckpoint3.future, timeout(Span(50, Millis))) {
      case (invoked, broken) =>
        withClue("expected (12 invocation, 10 broken) notifications at checkpoint 3: ") {
          invoked shouldEqual 12
          broken shouldEqual 10
        }
    }

    // wait beyond retry delay
    waitUntilRetryDelayHasExpired()

    // one more call in broken state, but should reset the CB
    protectedOperation(2, 1)

    // one last call in flow state
    protectedOperation(2, 1)

    whenReady(promiseCheckpoint4.future, timeout(Span(50, Millis))) {
      case (invoked, broken) =>
        withClue("expected (13 invocation, 11 broken) notifications at checkpoint 4: ") {
          invoked shouldEqual 13
          broken shouldEqual 11
        }
    }
  }

  "circuit breaker creation" should "register circuit breaker with the shared registry" in {
    val circuitBreaker = CircuitBreakerBuilder("register", 2, defaultRetryDelay).build()

    val maybeRegisteredCircuitBreaker = CircuitBreakerRegistry.get(circuitBreaker.name)
    maybeRegisteredCircuitBreaker shouldBe Symbol("defined")

    val registeredCircuitBreaker = maybeRegisteredCircuitBreaker.get

    registeredCircuitBreaker.name shouldEqual circuitBreaker.name
    registeredCircuitBreaker.isFlowing shouldEqual true
    registeredCircuitBreaker.isBroken shouldEqual false
  }

  "circuit breaker exception checking" should "let ControlThrowable exceptions through without affecting the state of the circuit breaker" in {
    val circuitBreaker = CircuitBreakerBuilder("controlThrowable", 2, defaultRetryDelay).build()

    val protectedOperation = circuitBreaker() { new ControlThrowable {} }

    // the circuit breaker is never tripped
    (1 to 5).foreach { _ =>
      try {
        protectedOperation
      } catch {
        case e: CircuitBreakerBrokenException => fail("the circuit breaker should not have been tripped")
        case e: ControlThrowable => //cool
        case e: Throwable => throw e
      }
    }
  }
}
