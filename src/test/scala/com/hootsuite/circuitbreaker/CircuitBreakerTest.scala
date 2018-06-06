package com.hootsuite.circuitbreaker

import com.hootsuite.circuitbreaker.listeners.{CircuitBreakerInvocationListener, CircuitBreakerStateChangeListener}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{Assertion, FlatSpec, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{Await, Future, Promise}
import scala.util.control.ControlThrowable
import scala.util.{Failure, Success}
import java.util.concurrent.TimeUnit

class CircuitBreakerTest extends FlatSpec with Matchers with ScalaFutures {
  private val numMillisecondsForRetryDelay = 200L

  // for whenReady calls
  private implicit val defaultPatience: PatienceConfig =
    PatienceConfig(timeout = Span(2, Seconds), interval = Span(10, Millis))
  private val defaultRetryDelay =
    Duration(numMillisecondsForRetryDelay, TimeUnit.MILLISECONDS)

  def assertArithException(hint: String, timeoutLength: Int = 100)(implicit cb: CircuitBreaker): Assertion =
    whenReady(Future(protectedOperation(1, 0)).failed, timeout(Span(timeoutLength, Millis))) { e =>
      withClue(s"${cb.name} : $hint") {
        e shouldBe a[ArithmeticException]
      }
    }

  def assertArithExceptionAsync(hint: String, timeoutLength: Int = 100)(
    implicit cb: CircuitBreaker
  ): Assertion =
    whenReady(protectedAsyncOperation(1, 0).failed, timeout(Span(timeoutLength, Millis))) { e =>
      withClue(s"${cb.name} : $hint") {
        e shouldBe a[ArithmeticException]
      }
    }

  def protectedAsyncOperation(x: Int, y: Int)(implicit cb: CircuitBreaker): Future[Int] = cb.async() {
    SimpleOperation.asyncOperation(x, y)
  }

  def assertCircuitException(hint: String, timeoutLength: Int = 100)(
    implicit cb: CircuitBreaker
  ): Assertion =
    whenReady(Future(protectedOperation(1, 0)).failed, timeout(Span(timeoutLength, Millis))) { e =>
      withClue(s"${cb.name} : $hint") {
        e shouldBe a[CircuitBreakerBrokenException]
      }
    }

  def assertCircuitExceptionAsync(hint: String, timeoutLength: Int = 100)(
    implicit cb: CircuitBreaker
  ): Assertion =
    whenReady(protectedAsyncOperation(1, 0).failed, timeout(Span(timeoutLength, Millis))) { e =>
      withClue(s"${cb.name} : $hint") {
        e shouldBe a[CircuitBreakerBrokenException]
      }
    }

  def assertClosed(hint: String, timeoutLength: Int = 100)(implicit cb: CircuitBreaker): Assertion =
    whenReady(Future(protectedOperation(2, 1), timeout(Span(timeoutLength, Millis)))) {
      case (result, _) =>
        withClue(s"${cb.name} : $hint") {
          result shouldEqual 2
        }
    }

  def protectedOperation(x: Int, y: Int)(implicit cb: CircuitBreaker): Int =
    cb() {
      SimpleOperation.operation(x, y)
    }

  private def waitUntilRetryDelayHasExpired(millis: Option[Long] = None): Unit =
    millis match {
      case Some(x) => Thread.sleep(x)
      case None => Thread.sleep(2 * numMillisecondsForRetryDelay)
    }

  // CB builders that misbehave on (state change|invocation) listeners - either throwing or blocking
  // + one plain CB for baseline
  private def simpleBuilders(
    failLimit: Int = 2,
    retryDelay: FiniteDuration = defaultRetryDelay
  ): List[CircuitBreakerBuilder] = {
    val defaultThreadSleep = 5000

    List(
      simpleBuilder("simple", failLimit, retryDelay),
      simpleBuilder("invocation listeners both block", failLimit, retryDelay)
        .withInvocationListeners(List(new CircuitBreakerInvocationListener {

          override def onInvocationInFlowState(name: String): Unit =
            Thread.sleep(defaultThreadSleep)

          override def onInvocationInBrokenState(name: String): Unit =
            Thread.sleep(defaultThreadSleep)
        })),
      simpleBuilder("invocation flow throws, invocation broken blocks", failLimit, retryDelay)
        .withInvocationListeners(List(new CircuitBreakerInvocationListener {

          override def onInvocationInFlowState(name: String): Unit =
            throw new Exception("boom")

          override def onInvocationInBrokenState(name: String): Unit =
            Thread.sleep(defaultThreadSleep)
        })),
      simpleBuilder("invocation flow blocks, invocation broken blocks", failLimit, retryDelay)
        .withInvocationListeners(List(new CircuitBreakerInvocationListener {

          override def onInvocationInFlowState(name: String): Unit =
            Thread.sleep(defaultThreadSleep)

          override def onInvocationInBrokenState(name: String): Unit =
            throw new Exception("boom")
        })),
      simpleBuilder("invocation listeners both throw", failLimit, retryDelay)
        .withInvocationListeners(List(new CircuitBreakerInvocationListener {

          override def onInvocationInFlowState(name: String): Unit =
            throw new Exception("boom")

          override def onInvocationInBrokenState(name: String): Unit =
            throw new Exception("boom")
        })),
      simpleBuilder("state change listeners all throw", failLimit, retryDelay)
        .withStateChangeListeners(List(new CircuitBreakerStateChangeListener {

          override def onInit(name: String): Unit = throw new Exception("boom")

          override def onTrip(name: String): Unit = throw new Exception("boom")

          override def onReset(name: String): Unit = throw new Exception("boom")
        })),
      simpleBuilder("state change listeners all block", failLimit, retryDelay)
        .withStateChangeListeners(List(new CircuitBreakerStateChangeListener {

          override def onInit(name: String): Unit =
            Thread.sleep(defaultThreadSleep)

          override def onTrip(name: String): Unit =
            Thread.sleep(defaultThreadSleep)

          override def onReset(name: String): Unit =
            Thread.sleep(defaultThreadSleep)
        })),
      simpleBuilder("state change onInit throws, onTrip, onReset block", failLimit, retryDelay)
        .withStateChangeListeners(List(new CircuitBreakerStateChangeListener {

          override def onInit(name: String): Unit = throw new Exception("boom")

          override def onTrip(name: String): Unit =
            Thread.sleep(defaultThreadSleep)

          override def onReset(name: String): Unit =
            Thread.sleep(defaultThreadSleep)
        })),
      simpleBuilder("state change onTrip throws, onInit, onReset block", failLimit, retryDelay)
        .withStateChangeListeners(List(new CircuitBreakerStateChangeListener {

          override def onInit(name: String): Unit =
            Thread.sleep(defaultThreadSleep)

          override def onTrip(name: String): Unit = throw new Exception("boom")

          override def onReset(name: String): Unit =
            Thread.sleep(defaultThreadSleep)
        })),
      simpleBuilder("state change onReset throws, onInit, onReset block", failLimit, retryDelay)
        .withStateChangeListeners(List(new CircuitBreakerStateChangeListener {

          override def onInit(name: String): Unit =
            Thread.sleep(defaultThreadSleep)

          override def onTrip(name: String): Unit =
            Thread.sleep(defaultThreadSleep)

          override def onReset(name: String): Unit = throw new Exception("boom")
        }))
    )
  }

  private def simpleBuilder(name: String, failLimit: Int, retryDelay: FiniteDuration) =
    CircuitBreakerBuilder(name = name, failLimit = failLimit, retryDelay = retryDelay)

  private def exponentialBuilders(
    failLimit: Int = 2,
    retryDelay: FiniteDuration = defaultRetryDelay,
    retryCap: Int = 10
  ): List[CircuitBreakerBuilder] =
    List(exponentialBuilder("simple", failLimit, retryDelay, retryCap))

  private def exponentialBuilder(
    name: String,
    failLimit: Int,
    retryDelay: FiniteDuration,
    exponentialRetryCap: Int
  ) =
    CircuitBreakerBuilder(
      name = name,
      failLimit = failLimit,
      retryDelay = retryDelay,
      isExponentialBackoff = true,
      exponentialRetryCap = Some(exponentialRetryCap)
    )

  object SimpleOperation {

    def operation(x: Int, y: Int): Int = x / y

    def asyncOperation(x: Int, y: Int): Future[Int] = Future {
      x / y
    }
  }

  "simple circuit breaker" should "record failures, trip, then reset after delay time has elapsed" in {

    simpleBuilders().map(_.build()).foreach { x =>
      implicit val cb: CircuitBreaker = x
      // run all protectedOperation invocations async, so we can catch & timeout rogue, blocking listeners
      // 100ms should be more than enough for our SimpleOperation

      // first failure; let it through
      assertArithException("first failure")

      // second failure; let it through again but this should trip the circuit breaker
      assertArithException("second failure")

      // now we get a circuit breaker exception because the circuit breaker is open
      assertCircuitException("CB broken failure")

      //wait a bit
      waitUntilRetryDelayHasExpired()

      //circuit should now be closed and a valid operation should just work
      assertClosed("after retry delay")
    }
  }

  it should "wait exponentially longer before retrying if is an exponential backoff CB" in {

    val baseRetryTime = 30
    val baseWaitTime = (baseRetryTime * 1.1).toInt
    exponentialBuilders(retryDelay = Duration(baseRetryTime, TimeUnit.MILLISECONDS)).map(_.build()).foreach {
      x =>
        implicit val cb: CircuitBreaker = x

        //should fail on first exception
        assertArithException("1st should be Arithmetic")

        //should fail on second exception because the circuit has not been tripped
        assertArithException("2nd should be Arithmetic")

        //next attempt should be circuit because we have reached the fail limit of 2
        assertCircuitException("3rd should be circuit")

        //wait the normal amount of time
        waitUntilRetryDelayHasExpired(Some(baseWaitTime))

        //should fail on first exception
        assertArithException("4th should be Arithmetic")

        //next attempt should be circuit we are in a broken state, not a flow state
        assertCircuitException("5th should be circuit")

        //wait a bit (this wont be enough, need to wait twice as long
        waitUntilRetryDelayHasExpired(Some(baseWaitTime))

        //next attempt should fail, we didn't wait long enough
        assertCircuitException("6th should be circuit")

        //wait a lot longer
        waitUntilRetryDelayHasExpired(Some(baseWaitTime * 2))

        //circuit should now be closed and a valid operation should just work
        assertClosed("after exponential retry delay")
    }
  }

  it should "wait exponentially longer before retrying if is an exponential backoff CB but stop " +
    "increasing at limit" in {

    val baseRetryTime = 100
    val baseWaitTime = baseRetryTime * 1.1
    val retryCap = 2
    exponentialBuilders(retryDelay = Duration(baseRetryTime, TimeUnit.MILLISECONDS), retryCap = retryCap)
      .map(_.build())
      .foreach { x =>
        implicit val cb: CircuitBreaker = x

        //should fail on first exception
        assertArithException("1st should be Arithmetic")

        //should fail on second exception because the circuit has not been tripped
        assertArithException("2nd should be Arithmetic")

        //next attempt should be circuit because we have reached the fail limit of 2
        assertCircuitException("3rd should be circuit")

        //wait the normal amount of time
        waitUntilRetryDelayHasExpired(Some(baseRetryTime))

        //should fail on first exception
        assertArithException("4th should be Arithmetic")

        //next attempt should be circuit we are in a broken state, not a flow state
        assertCircuitException("5th should be circuit")

        //wait a bit (this wont be enough, need to wait 2^1x as long
        waitUntilRetryDelayHasExpired(Some((baseWaitTime * 2).toInt))

        //should fail on first exception
        assertArithException("6th should be Arithmetic")

        //next attempt should be circuit we are in a broken state, not a flow state
        assertCircuitException("7th should be circuit")

        //wait a bit longer this time 2^2x
        waitUntilRetryDelayHasExpired(Some((baseWaitTime * 4).toInt))

        //should fail on first exception
        assertArithException("8th should be Arithmetic")

        //next attempt should be circuit we are in a broken state, not a flow state
        assertCircuitException("9th should be circuit")

        //wait a bit longer this time 2^2x
        waitUntilRetryDelayHasExpired(Some((baseWaitTime * 4).toInt))

        //if the cap was not used, we we have to wait 2^3x as long, so only waiting
        //2^2x should be long enough

        //should fail on first exception
        assertArithException("8th should be Arithmetic")

      }
  }

  it should "remain in tripped state on repeated errors" in {
    simpleBuilders(retryDelay = Duration(2, TimeUnit.SECONDS)).map(_.build()).foreach { x =>
      implicit val cb: CircuitBreaker = x

      (1 to 2).foreach { i =>
        assertArithException(s"failure #$i")
      }

      // the CB is now tripped

      // repeated calls will just fail immediately, regardless of call
      (1 to 100).foreach { i =>
        assertCircuitException(s"attempt #$i while CB tripped")
      }
    }
  }

  it should "be 'waiting' if it is in a tripped state, but the reset time delay has been reached" in {
    simpleBuilders().map(_.build()).foreach { x =>
      implicit val cb: CircuitBreaker = x

      cb.isFlowing shouldBe true
      cb.isBroken shouldBe false
      cb.isWaiting shouldBe false

      (1 to 2).foreach { i =>
        assertArithException(s"failure #$i")
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
    simpleBuilders().map(_.build()).foreach { x =>
      implicit val cb: CircuitBreaker = x

      (1 to 2).foreach { i =>
        assertArithException(s"failure #$i")
      }

      // now we get a circuit breaker exception because the circuit breaker is open
      assertCircuitException("CB broken failure")

      //wait until after the reset delay time has elapsed
      waitUntilRetryDelayHasExpired()

      // CB should let this call go through to test whether or not we need reset - will fail
      assertArithException("test reset attempt after retry delay")

      // back to tripped state but with retry delay now reset, fail immediately with CB exception
      assertCircuitException("CB remain broken after failed reset attempt", 10)
    }
  }

  "simple async circuit breaker" should "record failures, trip, then reset after delay time has elapsed" in {
    simpleBuilders().map(_.build()).foreach { x =>
      implicit val cb: CircuitBreaker = x

      (1 to 2).foreach { i =>
        assertArithExceptionAsync(s"failure #$i")
      }

      // now we get a circuit breaker exception because the circuit breaker is open
      assertCircuitExceptionAsync("CB broken failure", 10)

      //wait a bit
      waitUntilRetryDelayHasExpired()

      //circuit should now be closed and a valid operation should just work
      assertClosed("after retry delay")
    }
  }

  it should "remain in tripped state on repeated errors" in {
    simpleBuilders(retryDelay = Duration(2, TimeUnit.SECONDS)).map(_.build()).foreach { x =>
      implicit val cb: CircuitBreaker = x

      def syncProtectedOperation(x: Int, y: Int)(implicit cb: CircuitBreaker): Int =
        Await.result(protectedAsyncOperation(x, y), Duration(1, TimeUnit.SECONDS))

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
    simpleBuilders().map(_.build()).foreach { x =>
      implicit val cb: CircuitBreaker = x

      cb.isFlowing shouldBe true
      cb.isBroken shouldBe false
      cb.isWaiting shouldBe false

      (1 to 2).foreach { i =>
        assertArithExceptionAsync(s"failure #$i")
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

    simpleBuilders().map(_.build()).foreach { x =>
      implicit val cb: CircuitBreaker = x

      (1 to 2).foreach { i =>
        assertArithExceptionAsync(s"failure #$i")
      }

      // now we get a circuit breaker exception because the circuit breaker is open
      assertCircuitExceptionAsync("CB Broken Failure")

      //wait until after the reset delay time has elapsed
      waitUntilRetryDelayHasExpired()

      // CB should let this call go through to test whether or not we need reset - will fail
      assertArithExceptionAsync("test reset attempt after retry delay")

      // back to tripped state but with retry delay now reset, fail immediately with CB exception
      assertCircuitExceptionAsync("CB remain broken after failed reset attempt", 10)
    }
  }

  "circuit breaker with fallback value" should "return the fallback value when the circuit breaker is tripped" in {
    simpleBuilders(failLimit = 5).map(_.build()).foreach { x =>
      implicit val cb: CircuitBreaker = x

      val theDefaultValue = 0

      // a bit of an odd behaviour for the fallback in this case, but tests the basic idea
      def fallbackOperation(x: Int, y: Int) =
        cb(fallback = Some(Success(theDefaultValue))) {
          SimpleOperation.operation(x, y)
        }

      (1 to 5).foreach { i =>
        assertArithException(s"failure #$i")
      }

      // return the default value here, while the circuit is tripped
      whenReady(Future(fallbackOperation(222, 1), timeout(Span(100, Millis)))) {
        case (result, _) =>
          withClue(s"${cb.name} : after retry delay") {
            result shouldEqual theDefaultValue
          }
      }
    }
  }

  it should "throw a custom exception when configured as such and when the circuit breaker is tripped" in {
    simpleBuilders(failLimit = 5).map(_.build()).foreach { x =>
      implicit val cb: CircuitBreaker = x

      class CustomException extends Throwable
      val customFailure = Failure(new CustomException)

      // a bit of an odd behaviour for the fallback in this case, but tests the basic idea
      def customExceptionOperation(x: Int, y: Int) =
        cb(fallback = Some(customFailure)) {
          SimpleOperation.operation(x, y)
        }

      (1 to 5).foreach { i =>
        assertArithException(s"failure #$i")
      }

      whenReady(Future(customExceptionOperation(2, 1)).failed, timeout(Span(10, Millis))) { e =>
        withClue(s"${cb.name} : custom fallback failure") {
          e shouldBe a[CustomException]
        }
      }
    }
  }

  "async circuit breaker with fallback value" should "return the fallback value when the circuit breaker is tripped" in {
    simpleBuilders(failLimit = 5).map(_.build()).foreach { x =>
      implicit val cb: CircuitBreaker = x

      val theDefaultValue = 0

      // a bit of an odd behaviour for the fallback in this case, but tests the basic idea
      def fallbackOperation(x: Int, y: Int) =
        cb.async(fallback = Some(Success(theDefaultValue))) {
          SimpleOperation.asyncOperation(x, y)
        }

      (1 to 5).foreach { i =>
        assertArithExceptionAsync(s"failure #$i")
      }

      // return the default value here, while the circuit is tripped
      whenReady(fallbackOperation(222, 1), timeout(Span(100, Millis))) { result =>
        withClue(s"${cb.name} : after retry delay") {
          result shouldEqual theDefaultValue
        }
      }
    }
  }

  it should "throw a custom exception when configured as such and when the circuit breaker is tripped" in {
    simpleBuilders(failLimit = 5).map(_.build()).foreach { x =>
      implicit val cb: CircuitBreaker = x

      class CustomException extends Throwable
      val customFailure = Failure(new CustomException)

      // a bit of an odd behaviour for the fallback in this case, but tests the basic idea
      def fallbackOperationWithCustomException(x: Int, y: Int) =
        cb.async(fallback = Some(customFailure)) {
          SimpleOperation.asyncOperation(x, y)
        }

      (1 to 5).foreach { i =>
        assertArithExceptionAsync(s"failure #$i")
      }

      whenReady(fallbackOperationWithCustomException(2, 1).failed, timeout(Span(10, Millis))) { e =>
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
      .foreach { x =>
        implicit val cb: CircuitBreaker = x

        whenReady(Future(protectedOperation(2, 1), timeout(Span(100, Millis)))) {
          case (result, _) =>
            withClue(s"${cb.name} : still returns the value but records it as a failure") {
              result shouldEqual 2
            }
        }

        whenReady(Future(protectedOperation(16, 8), timeout(Span(100, Millis)))) {
          case (result, _) =>
            withClue(s"${cb.name} : still returns the value but records it as a failure, trip CB") {
              result shouldEqual 2
            }
        }

        // circuit breaker is now open
        assertCircuitException("custom fallback failure")
      }
  }

  it should "ignore exceptions thrown within the check" in {
    simpleBuilders()
      .map(_.withResultFailureCases {
        case _ => throw new Exception("a dumb thing to do")
      }.build())
      .foreach { x =>
        implicit val cb: CircuitBreaker = x

        def protectedOperation() = cb() {
          1
        }

        // this should not blow up with an exception
        whenReady(Future(protectedOperation(), timeout(Span(100, Millis)))) {
          case (result, _) =>
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
      .foreach { x =>
        implicit val cb: CircuitBreaker = x

        whenReady(protectedAsyncOperation(2, 1), timeout(Span(100, Millis))) { result =>
          withClue(s"${cb.name} : still returns the value but records it as a failure") {
            result shouldEqual 2
          }
        }

        whenReady(protectedAsyncOperation(16, 8), timeout(Span(100, Millis))) { result =>
          withClue(s"${cb.name} : still returns the value but records it as a failure, trip CB") {
            result shouldEqual 2
          }
        }

        // circuit breaker is now open
        assertCircuitExceptionAsync("custom fallback failure", 10)
      }
  }

  it should "ignore exceptions thrown within the check" in {
    simpleBuilders()
      .map(_.withResultFailureCases {
        // the following is a terrible idea -  don't do this; but if someone does, ignore and log
        case _ => throw new Exception("a dumb thing to do")
      }.build())
      .foreach { x =>
        implicit val cb: CircuitBreaker = x

        def protectedOperation(): Future[Int] = cb.async() {
          Future {
            1
          }
        }

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
        case _: ArithmeticException => true
      }.build())
      .foreach { x =>
        implicit val cb: CircuitBreaker = x

        // the circuit is never tripped and just lets these exceptions through
        (1 to 10).foreach { i =>
          assertArithException(s"failure #$i")
        }
      }
  }

  it should "not ignore exceptions that have not be configured to be ignored" in {
    simpleBuilders()
      .map(_.withNonFailureExceptionCases {
        case _: ArithmeticException => true
      }.build())
      .foreach { x =>
        implicit val cb: CircuitBreaker = x

        def protectedOperation(x: Int, y: Int) = cb() {
          val ret = SimpleOperation.operation(x, y)
          if (ret == 3)
            throw new IllegalStateException("just here to verify this exception is not ignored")
          ret
        }

        // the circuit is never tripped and just lets these exceptions through
        (1 to 10).foreach { i =>
          assertArithException(s"(non) failure #$i")
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
        assertCircuitException("tripped failure", 10)
      }
  }

  it should "ignore exceptions thrown within the check" in {
    simpleBuilders()
      .map(_.withNonFailureExceptionCases {
        // the following is a terrible idea -  don't do this; but if someone does, ignore and log
        case _ => throw new Exception("a dumb thing to do")
      }.build())
      .foreach { x =>
        implicit val cb: CircuitBreaker = x

        def protectedOperation() = cb() {
          1
        }

        // this should not blow up with an exception
        whenReady(Future(protectedOperation(), timeout(Span(100, Millis)))) {
          case (result, _) =>
            withClue(s"${cb.name} : still return result when non-failure function throws") {
              result shouldEqual 1
            }
        }
      }
  }

  "async circuit breaker configured to ignore certain exceptions" should "not be tripped when these exceptions occur" in {
    simpleBuilders()
      .map(_.withNonFailureExceptionCases {
        case _: ArithmeticException => true
      }.build())
      .foreach { x =>
        implicit val cb: CircuitBreaker = x

        // the circuit is never tripped and just lets these exceptions through
        (1 to 10).foreach { i =>
          assertArithExceptionAsync(s"failure #$i")
        }
      }
  }

  it should "not ignore exceptions that have not be configured to be ignored" in {
    simpleBuilders()
      .map(_.withNonFailureExceptionCases {
        case _: ArithmeticException => true
      }.build())
      .foreach { x =>
        implicit val cb: CircuitBreaker = x

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
          assertArithExceptionAsync(s"failure #$i")
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
        assertCircuitExceptionAsync("tripped failure", 10)
      }
  }

  it should "ignore exceptions thrown within the check" in {
    simpleBuilders()
      .map(_.withNonFailureExceptionCases {
        // the following is a terrible idea -  don't do this; but if someone does, ignore and log
        case _ => throw new Exception("a dumb thing to do")
      }.build())
      .foreach { x =>
        implicit val cb: CircuitBreaker = x

        def protectedOperation() = cb.async() {
          Future(1)
        }

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

      override def onTrip(resourceName: String): Unit =
        promiseIllTrip.success(true)

      override def onReset(resourceName: String): Unit =
        promiseIllReset.success(true)
    }

    implicit val circuitBreaker: CircuitBreaker =
      CircuitBreakerBuilder("notifyStateChangeListeners", 2, defaultRetryDelay)
        .withStateChangeListeners(List(myStateChangeListener))
        .build()

    (1 to 2).foreach { i =>
      assertArithException(s"failure #$i")
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

    val promiseCheckpoint1 = Promise[(Int, Int, Int)]()
    val promiseCheckpoint2 = Promise[(Int, Int, Int)]()
    val promiseCheckpoint3 = Promise[(Int, Int, Int)]()
    val promiseCheckpoint4 = Promise[(Int, Int, Int)]()

    val myInvocationListener = new CircuitBreakerInvocationListener {
      var flowStateInvocationCount = 0
      var brokenStateInvocationCount = 0
      var attemptResetStateInvocationCount = 0

      private def keepPromises(): Unit =
        (flowStateInvocationCount, brokenStateInvocationCount, attemptResetStateInvocationCount) match {
          case (10, 0, 0) =>
            promiseCheckpoint1.success(
              (flowStateInvocationCount, brokenStateInvocationCount, attemptResetStateInvocationCount)
            )
          case (12, 0, 0) =>
            promiseCheckpoint2.success(
              (flowStateInvocationCount, brokenStateInvocationCount, attemptResetStateInvocationCount)
            )
          case (12, 10, 0) =>
            promiseCheckpoint3.success(
              (flowStateInvocationCount, brokenStateInvocationCount, attemptResetStateInvocationCount)
            )
          case (13, 10, 1) =>
            promiseCheckpoint4.success(
              (flowStateInvocationCount, brokenStateInvocationCount, attemptResetStateInvocationCount)
            )
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

      override def onInvocationInAttemptResetState(resourceName: String): Unit = {
        attemptResetStateInvocationCount += 1
        keepPromises()
      }
    }

    implicit val circuitBreaker: CircuitBreaker =
      CircuitBreakerBuilder("notifyInvocationListeners", 2, defaultRetryDelay)
        .withInvocationListeners(List(myInvocationListener))
        .build()

    // 10 invocations in flow state
    (1 to 10).foreach { _ =>
      protectedOperation(1, 1)
    }

    whenReady(promiseCheckpoint1.future, timeout(Span(50, Millis))) {
      case (invoked, broken, attempt) =>
        withClue("expected (10 invocation, 0 broken) notifications at checkpoint 1: ") {
          invoked shouldEqual 10
          broken shouldEqual 0
          attempt shouldEqual 0
        }
    }

    // 2 more in flow state but will trip the CB
    (1 to 2).foreach { _ =>
      intercept[ArithmeticException] {
        protectedOperation(1, 0)
      }
    }

    whenReady(promiseCheckpoint2.future, timeout(Span(50, Millis))) {
      case (invoked, broken, attempt) =>
        withClue("expected (12 invocation, 0 broken) notifications at checkpoint 2: ") {
          invoked shouldEqual 12
          broken shouldEqual 0
          attempt shouldEqual 0
        }
    }

    // 10 invocations while in broken state
    (1 to 10).foreach { _ =>
      intercept[CircuitBreakerBrokenException] {
        protectedOperation(1, 1)
      }
    }

    whenReady(promiseCheckpoint3.future, timeout(Span(50, Millis))) {
      case (invoked, broken, attempt) =>
        withClue("expected (12 invocation, 10 broken) notifications at checkpoint 3: ") {
          invoked shouldEqual 12
          broken shouldEqual 10
          attempt shouldEqual 0
        }
    }

    // wait beyond retry delay
    waitUntilRetryDelayHasExpired()

    // one more call in broken state, but should reset the CB
    protectedOperation(2, 1)

    // one last call in flow state
    protectedOperation(2, 1)

    whenReady(promiseCheckpoint4.future, timeout(Span(50, Millis))) {
      case (invoked, broken, attempt) =>
        withClue("expected (13 invocation, 11 broken) notifications at checkpoint 4: ") {
          invoked shouldEqual 13
          broken shouldEqual 10
          attempt shouldEqual 1
        }
    }
  }

  "circuit breaker creation" should "register circuit breaker with the shared registry" in {
    val circuitBreaker =
      CircuitBreakerBuilder("register", 2, defaultRetryDelay).build()

    val maybeRegisteredCircuitBreaker =
      CircuitBreakerRegistry.get(circuitBreaker.name)
    maybeRegisteredCircuitBreaker should be('defined)

    val registeredCircuitBreaker = maybeRegisteredCircuitBreaker.get

    registeredCircuitBreaker.name shouldEqual circuitBreaker.name
    registeredCircuitBreaker.isFlowing shouldEqual true
    registeredCircuitBreaker.isBroken shouldEqual false
  }

  "circuit breaker exception checking" should "let ControlThrowable exceptions through without affecting the state of the circuit breaker" in {
    val circuitBreaker =
      CircuitBreakerBuilder("controlThrowable", 2, defaultRetryDelay).build()

    val protectedOperation = circuitBreaker() {
      new ControlThrowable {}
    }

    // the circuit breaker is never tripped
    (1 to 5).foreach { _ =>
      try {
        protectedOperation
      } catch {
        case _: CircuitBreakerBrokenException =>
          fail("the circuit breaker should not have been tripped")
        case _: ControlThrowable => //cool
        case e: Throwable => throw e
      }
    }
  }
}
