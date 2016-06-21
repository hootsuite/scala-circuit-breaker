package com.hootsuite.circuitbreaker

import com.hootsuite.circuitbreaker.listeners.{CircuitBreakerInvocationListener, CircuitBreakerStateChangeListener}
import org.slf4j.LoggerFactory

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}
import java.util.concurrent.Executors
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

/**
 * A configurable circuit breaker. For the time being, the only state change detection strategy implemented is based on
 * the number of consecutive failures.
 *
 * @param name the name of the circuit breaker
 * @param failLimit maximum number of consecutive failures before the circuit breaker is tripped (opened)
 * @param retryDelay duration until an open/broken circuit breaker lets a call through to verify whether or not it should be reset
 * @param isResultFailure partial function to allow users to determine return cases which should be considered as failures
 * @param isExceptionNotFailure partial function to allow users to determine exceptions which should not be considered failures
 * @param stateChangeListeners listeners that will be notified when the circuit breaker changes state (open <--> closed)
 * @param invocationListeners listeners that will be notified whenever the circuit breaker handles a method/function call
 */
class CircuitBreaker private[circuitbreaker] (
  val name: String,
  val failLimit: Int,
  val retryDelay: FiniteDuration,
  val isResultFailure: PartialFunction[Any,Boolean] = { case _ => false },
  val isExceptionNotFailure: PartialFunction[Throwable, Boolean] = { case _ => false },
  val stateChangeListeners: List[CircuitBreakerStateChangeListener] = List(),
  val invocationListeners: List[CircuitBreakerInvocationListener] = List(),
  val notificationsExecutionContext: ExecutionContext
) extends ReadOnlyCircuitBreakerSnapshot {

  private[circuitbreaker] def this(builder: CircuitBreakerBuilder) =
    this(builder.name, builder.failLimit, builder.retryDelay, builder.isResultFailure, builder.isExceptionNotFailure,
      builder.stateChangeListeners, builder.invocationListeners,
      builder.notificationsExecutionContext.getOrElse(ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor()))
    )

  import com.hootsuite.circuitbreaker.CircuitBreaker._

  // keep state
  private[this] val state = new AtomicReference[State](new FlowState(this))

  // register self with circuit breaker registry
  CircuitBreakerRegistry.register(this)

  // notify of initialization
  stateChangeListeners.foreach { listener =>
    safely(notificationsExecutionContext)(listener.onInit, name, "notify listener of being initialized")
  }

  def apply[T](fallback: Option[Try[T]] = None)(block: => T): T =
    Try {
      state.get.preInvoke()
    } match {
      //check if we should return a fallback
      case Failure(e: CircuitBreakerBrokenException) if fallback.isDefined =>
        logger.debug(s"Circuit breaker \'$name\' in broken/open state, returning fallback value: ${fallback.get}")
        fallback.get.get
      case Failure(e) =>
        throw e
      case Success(_) =>
        try {
          val ret = block
          handleCallReturnedValue(ret)
        } catch {
          handleFailedCall
        }
    }

  def async[T](fallback: Option[Try[T]] = None)(block: => Future[T])(implicit ec: ExecutionContext): Future[T] =
    Try {
      state.get.preInvoke()
    } match {
      //check if we should return a fallback
      case Failure(e: CircuitBreakerBrokenException) if fallback.isDefined =>
        logger.debug(s"Circuit breaker \'$name\' in broken/open state, returning fallback value: ${fallback.get}")
        fallback.get.toFuture
      case Failure(e) =>
        Future.failed(e)
      case Success(_) =>
        block.map { ret =>
          handleCallReturnedValue(ret)
        }.recover {
          handleFailedCall
        }
    }

  private def handleCallReturnedValue[T](ret: T): T = {

    def shouldInterpretAsFailure(): Boolean =
      Try {
        isResultFailure.isDefinedAt(ret) && isResultFailure(ret)
      }.recover {
        case e: Throwable =>
          logger.warn(s"Circuit breaker \'$name\' is mis-configured for isResultFailure, an exception was illegally " +
            s"thrown from the partial function: ${e.getClass.getSimpleName}")
          false
      }.get

    if (shouldInterpretAsFailure()) {
      logger.debug(s"Circuit breaker \'$name\' registering custom defined failure for return value $ret")
      state.get.onFailure()
    } else {
      state.get.postInvoke()
    }

    ret
  }

  private def handleFailedCall[T]: PartialFunction[Throwable, T] = {
    case NonFatal(e) if shouldNotCountAsFailure(e) =>
      logger.debug(s"Circuit breaker \'$name\' ignoring exception marked as non-failure: ${e.getClass.getSimpleName}")
      state.get.postInvoke()
      throw e
    case NonFatal(e) =>
      state.get.onThrowable(e)
      throw e
  }

  private def shouldNotCountAsFailure(e: Throwable): Boolean = Try {
    isExceptionNotFailure.isDefinedAt(e) && isExceptionNotFailure(e)
  }.recover {
    case e: Throwable =>
      logger.warn(s"Circuit breaker \'$name\' is mis-configured for isExceptionNotFailure, an exception was illegally " +
        s"thrown from the partial function: ${e.getClass.getSimpleName}")
      false
  }.get

  /**
   * Switch to open/broken state.
   */
  def trip(): Unit = {
    logger.warn(s"Circuit breaker \'$name\' is being TRIPPED.  Moving to OPEN/BROKEN state.")
    state.set(new BrokenState(this))
    stateChangeListeners.foreach { listener =>
      safely(notificationsExecutionContext)(listener.onTrip, name, "notify listener of being tripped")
    }
  }

  /**
   * Switch to closed/flow state.
   */
  def reset(): Unit = {
    logger.warn(s"Circuit breaker \'$name\' is being RESET.  Moving to CLOSED/FLOWING state.")
    state.set(new FlowState(this))
    stateChangeListeners.foreach { listener =>
      safely(notificationsExecutionContext)(listener.onReset, name, "notify listener of being reset")
    }
  }

  /**
   * Try to restart the open/broken state.
   * @param currentState the expected current state
   * @return true when the state was changed, false when the given state was not the current state
   */
  def attemptResetBrokenState(currentState: BrokenState): Boolean = {
    logger.debug(s"Circuit breaker \'$name\', attempting to reset open/broken state")
    state.compareAndSet(currentState, new BrokenState(this))
  }

  /**
   * @inheritdoc
   */
  override def isFlowing: Boolean = state.get().isInstanceOf[FlowState]

  /**
   * @inheritdoc
   */
  override def isBroken: Boolean = state.get().isInstanceOf[BrokenState]
}

/**
 * Circuit breaker's companion object.  Primarily, contains states of the circuit breaker's state machine and state
 * transition logic.
 */
private object CircuitBreaker {

  /**
   * Wrapper to convert a Try[A] into a completed Future[A]. Note that this does not do anything asynchronously.
   */
  private implicit class TryToFuture[A](t: Try[A]) {

    /**
     * Convert this Try[A] to a Future[A].
     *
     * If it is Success(v) => Future.successful(v)
     * If it is Failed(v) => Future.failed(e)
     */
    def toFuture: Future[A] = t match {
      case Success(v) => Future.successful(v)
      case Failure(e) => Future.failed(e)
    }
  }

  private def safely(ec: ExecutionContext)
    (op: => (String) => Any, str: String, opName: String = "<no operation name specified>"): Unit = {
    implicit val implicitEc = ec
    Future(op(str)).recover {
      case NonFatal(e) => logger.warn(s"Circuit breaker \'$str\' caught non fatal exception while " +
        s"attempting to $opName.  Exception ignored: ${e.getClass.getSimpleName}")
    }
  }

  private val logger = LoggerFactory.getLogger(getClass)

  abstract class State(cb: CircuitBreaker) {

    def preInvoke(): Unit

    def postInvoke(): Unit

    def onThrowable(e: Throwable): Unit

    def onFailure(): Unit
  }

  /**
   * CircuitBreaker is closed/flowing, normal operation.
   */
  class FlowState(cb: CircuitBreaker) extends State(cb) {
    private[this] val failureCount = new AtomicInteger

    override def preInvoke(): Unit = {
      cb.invocationListeners.foreach { listener =>
        safely(cb.notificationsExecutionContext)(listener.onInvocationInFlowState, cb.name, "notify listener of invocation in flow state")
      }
    }

    override def postInvoke(): Unit = {
      val prev = failureCount.getAndSet(0)
      if (prev != 0) {
        logger.debug(s"Circuit breaker ${cb.name} reset failure count to 0")
      }
    }

    override def onThrowable(e: Throwable): Unit = incrementFailure()

    override def onFailure(): Unit = {
      incrementFailure()
    }

    private[this] def incrementFailure() = {
      val currentCount = failureCount.incrementAndGet
      logger.debug(s"Circuit breaker ${cb.name} increment failure count to $currentCount; fail limit is ${cb.failLimit}")
      if (currentCount >= cb.failLimit) cb.trip() // BOOM!
    }
  }

  /**
   * CircuitBreaker is opened/broken. Invocations fail immediately.
   */
  class BrokenState(cb: CircuitBreaker) extends State(cb) {
    private[this] val retryAt: Long = System.currentTimeMillis() + cb.retryDelay.toMillis

    override def preInvoke(): Unit = {
      cb.invocationListeners.foreach{ listener =>
        safely(cb.notificationsExecutionContext)(listener.onInvocationInBrokenState, cb.name, "notify listener of invocation in broken state")
      }

      val retry = System.currentTimeMillis > retryAt
      if (!(retry && cb.attemptResetBrokenState(this))) {
        throw new CircuitBreakerBrokenException(cb.name, s"Making ${cb.name} unavailable after ${cb.failLimit} errors")
        // If no exception is thrown, a retry is started.
      }
    }

    override def postInvoke(): Unit = {
      // Called after a successful retry.
      cb.reset()
    }

    override def onThrowable(e: Throwable): Unit = { /* do nothing */ }

    override def onFailure(): Unit = { /* do nothing */ }
  }
}

/**
 * Builder for [[CircuitBreaker]]
 *
 * @param name the name of the circuit breaker
 * @param failLimit maximum number of consecutive failures before the circuit breaker is tripped (opened)
 * @param retryDelay duration until an open/broken circuit breaker lets a call through to verify whether or not it should be reset
 * @param isResultFailure partial function to allow users to determine return cases which should be considered as failures
 * @param isExceptionNotFailure partial function to allow users to determine exceptions which should not be considered failures
 * @param stateChangeListeners listeners that will be notified when the circuit breaker changes state (open <--> closed)
 * @param invocationListeners listeners that will be notified whenever the circuit breaker handles a method/function call
 */
case class CircuitBreakerBuilder(
  name: String,
  failLimit: Int,
  retryDelay: FiniteDuration,
  isResultFailure: PartialFunction[Any,Boolean] = { case _ => false },
  isExceptionNotFailure: PartialFunction[Throwable, Boolean] = { case _ => false },
  stateChangeListeners: List[CircuitBreakerStateChangeListener] = List(),
  invocationListeners: List[CircuitBreakerInvocationListener] = List(),
  notificationsExecutionContext: Option[ExecutionContext] = None
){

  /**
   * Sets a partial function used to determine whether or not the returned values should be recorded as a failure by the
   * circuit breaker. This can be useful when the wrapped/underlying call does not use exceptions to communicate failures.
   *
   * @param resultFailureCases partial function used to determine whether or not the returned values should be recorded as a
   *                     failure by the circuit breaker
   * @return a builder, for chaining configuration calls
   */
  def withResultFailureCases(resultFailureCases: PartialFunction[Any,Boolean]): CircuitBreakerBuilder =
    this.copy(isResultFailure = resultFailureCases)

  /**
   * Sets a partial function used to filter out [[Throwable]]s so that the circuit breaker does not register them as
   * failures.  Useful when the wrapped/underlying call uses exceptions to communicate various cases which should not
   * influence the state of the circuit breaker.
   *
   * @param nonFailureExceptionCases partial function used to filter out [[Throwable]]s so that the circuit breaker
   *                                 does not register them as failures
   * @return a builder, for chaining configuration calls
   */
  def withNonFailureExceptionCases(nonFailureExceptionCases: PartialFunction[Throwable, Boolean]): CircuitBreakerBuilder =
    this.copy(isExceptionNotFailure = nonFailureExceptionCases)

  /**
   * Sets a list of listeners to be notified of the circuit breaker's state changes.
   *
   * The listeners are invoked using a dedicated ExecutionContext in order to protect
   * the circuit breaker and request processing logic from adverse side effects (e.g. high latency or blocking)
   * that may occur on the listeners' registered functions.
   * A dedicated, single-thread execution context is used by default.
   * @see withNotificationsExecutionContext
   *
   * @param stateChangeListeners the list of listeners
   * @return a builder, for chaining configuration calls
   */
  def withStateChangeListeners(stateChangeListeners: List[CircuitBreakerStateChangeListener]): CircuitBreakerBuilder =
    this.copy(stateChangeListeners = stateChangeListeners)

  /**
   * Set a list of listeners to be notified of the circuit breaker handling incoming calls
   *
   * The listeners are invoked using a dedicated ExecutionContext in order to protect
   * the circuit breaker and request processing logic from adverse side effects (e.g. high latency or blocking)
   * that may occur on the listeners' registered functions.
   * A dedicated, single-thread execution context is used by default.
   * @see withNotificationsExecutionContext
   *
   * @param invocationListeners the list of listeners
   * @return a builder, for chaining configuration calls
   */
  def withInvocationListeners(invocationListeners: List[CircuitBreakerInvocationListener]): CircuitBreakerBuilder =
    this.copy(invocationListeners = invocationListeners)

  /**
   * Set the ExecutionContext to be used for notifying registered invocation and state change listeners.
   * If not explicitly provided then a Executors.newSingleThreadExecutor() is used as a default.
   *
   * @param ec the ExecutionContext for sending notifications to registered listeners
   * @return a builder, for chaining configuration calls
   */
  def withNotificationsExecutionContext(ec: ExecutionContext): CircuitBreakerBuilder =
    this.copy(notificationsExecutionContext = Option(ec))

  /**
   * Builds the circuit breaker.
   *
   * @return a builder, for chaining configuration calls
   */
  def build(): CircuitBreaker = new CircuitBreaker(this)

}
