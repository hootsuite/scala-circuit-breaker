package utils

import scala.util.Random

object PingService {

  private var isServiceStateOk: Boolean = true
  private var maybeRandom: Option[Int] = None

  def currentStatus(): Boolean =
    maybeRandom match {
      case Some(randomPercentage) => Random.nextInt(101) > randomPercentage
      case _ => isServiceStateOk
    }

  def setServiceState(newState: Boolean): Unit = {
    setRandom(None)
    isServiceStateOk = newState
  }

  def setRandom(maybeValue: Option[Int]): Unit =
    maybeRandom = maybeValue
}
