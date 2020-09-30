package controllers

import play.api.libs.json._
import play.api.mvc._
import utils.PingService
import javax.inject.{Inject, Singleton}

@Singleton
class Application @Inject()(val controllerComponents: ControllerComponents) extends BaseController {

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
