package app

import java.util.UUID

import app.amqp.Message
import app.amqp.model.RpcRequest.ExampleRpcRequest
import app.amqp.model.RpcResponse._
import app.amqp.model.{RpcRequest, RpcResponse}
import app.util.AmqpUtil._
import cats.effect.Sync
import cats.syntax.functor._
import cats.syntax.option._
import dev.profunktor.fs2rabbit.config.declaration.DeclarationQueueConfig
import dev.profunktor.fs2rabbit.interpreter.Fs2Rabbit
import dev.profunktor.fs2rabbit.model._
import fs2.Stream
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.circe.Encoder

import scala.util.control.NoStackTrace

class RpcServer[F[_] : Sync](rpcQueue: QueueName, rabbit: Fs2Rabbit[F])(implicit log: Logger[F], channel: AMQPChannel) {

  def serve(): Stream[F, Unit] =
    for {
      (request, properties) <- decodeRequest()
      _                     <- handleRequest(request, properties)
    } yield ()

  private def decodeRequest(): Stream[F, (RpcRequest, AmqpProperties)] =
    for {
      _        <- Stream.eval(rabbit.declareQueue(DeclarationQueueConfig.default(rpcQueue)))
      consumer <- Stream.eval(rabbit.createAutoAckConsumer[String](rpcQueue))
      envelope <- consumer.through(logPipe(log))
      request  <- Stream.eval(decodeData[RpcRequest](envelope.payload))
    } yield (request, envelope.properties)

  private def handleRequest(request: RpcRequest, properties: AmqpProperties): Stream[F, Unit] = {
    val response =
      request match {
        case _: ExampleRpcRequest =>
          ExampleRpcResponse(taskId = UUID.randomUUID(), status = "Success")
      }
    sendResponse(response, properties)
  }

  private def sendResponse[Response <: RpcResponse : Encoder](
    response: Response,
    properties: AmqpProperties
  ): Stream[F, Unit] =
    for {
      replyTo       <- Stream.eval(properties.replyTo.liftTo(ReplyToNotSpecifiedException))
      correlationId <- Stream.eval(properties.correlationId.liftTo(CorrelationIdNotSpecifiedException))
      publisher     <- Stream.eval(rabbit.createPublisher[AmqpMessage[String]](ExchangeName(""), RoutingKey(replyTo)))
      message = AmqpMessage(
        Message(data = response, meta = deriveMeta(response)),
        AmqpProperties(correlationId = Some(correlationId))
      )
      _ <- Stream(message).covary.through(jsonPipe[Message[Response]]).evalMap(publisher)
    } yield ()

  case object ReplyToNotSpecifiedException
    extends RuntimeException("property reply_to is not specified")
    with NoStackTrace

  case object CorrelationIdNotSpecifiedException
    extends RuntimeException("property correlation_id is not specified")
    with NoStackTrace
}

object RpcServer {
  def create[F[_] : Sync](rpcQueue: QueueName, rabbit: Fs2Rabbit[F])(implicit channel: AMQPChannel): F[RpcServer[F]] =
    for {
      implicit0(log: Logger[F]) <- Slf4jLogger.create
    } yield new RpcServer(rpcQueue, rabbit)
}
