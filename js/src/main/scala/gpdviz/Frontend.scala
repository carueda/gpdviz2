package gpdviz

import autowire._
import gpdviz.pusher.PusherListener
import gpdviz.websocket.WsListener

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

object Frontend extends js.JSApp {

  def main(): Unit = {
    AutowireClient[Api].clientConfig().call() foreach gotClientConfig
  }

  private def gotClientConfig(clientConfig: ClientConfig): Unit = {
    println("clientConfig = " + clientConfig)

    val sysid: js.Dynamic = js.Dynamic.global.sysid
    val pusherChannel = s"${clientConfig.serverName}-$sysid"

    clientConfig.pusher match {
      case None     ⇒ new WsListener
      case Some(pc) ⇒ new PusherListener(pc, pusherChannel)
    }
  }
}
