package example

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}

object PingActor {

  sealed trait Message
  case class Ping(replyTo: ActorRef[Result]) extends Message

  sealed trait Result
  case object Pong extends Result

  def apply(): Behavior[Message] = Behaviors.setup{ implicit context =>
    Behaviors.receiveMessage {
      case m: Ping =>
        m.replyTo ! Pong
        Behaviors.stopped
      case m =>
        context.log.info("Received unexpected Message[{}]", m)
        Behaviors.stopped
    }
  }
}
