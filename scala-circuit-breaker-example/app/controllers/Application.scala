package controllers

import play.api.libs.json._
import play.api.mvc._
import utils.PingService

object Application extends Controller {

  def ping() = Action {
    if (PingService.currentStatus()) Ok(Json.toJson("Pong!"))
    else InternalServerError(s"Error, current state: ${PingService.currentStatus}")
  }

  def changeStateTo(newState: Boolean) = Action {
    PingService.setServiceState(newState)
    Ok(Json.toJson(newState))
  }

  def changeToRandomState(value: Int) = Action {
    PingService.setRandom(Some(value))
    Ok(Json.toJson(s"Random set to: $value%"))
  }
}
