package app.amqp

import java.util.UUID

import app.amqp.model.RpcRequest._
import app.amqp.model.RpcResponse.ExampleRpcResponse
import app.amqp.model.{RpcRequest, RpcResponse}
import app.util.AmqpUtil._
import app.util.StreamUtil.liftK
import cats.effect.Sync
import cats.syntax.functor._
import dev.profunktor.fs2rabbit.interpreter.Fs2Rabbit
import dev.profunktor.fs2rabbit.model._
import fs2.Stream
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.extras.implicits._
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.circe.Encoder

class RpcClient[F[_] : Sync](rpcQueue: QueueName, rabbit: Fs2Rabbit[F])(
  implicit log: Logger[F], channel: AMQPChannel
) {

  def call[Request <: RpcRequest : Encoder](request: Request): Stream[F, RpcResponse] =
    for {
      queueToReply <- Stream.eval(rabbit.declareQueue)

      correlationId = UUID.randomUUID().toString

      messageToSend = AmqpMessage(
        payload = Message(data = request, meta = deriveMeta(request)),
        properties = AmqpProperties(replyTo = Some(queueToReply.value), correlationId = Some(correlationId))
      )

      publisher <- Stream.eval(
        rabbit.createPublisher[AmqpMessage[String]](ExchangeName(""), RoutingKey(rpcQueue.value))
      )

      _ <- Stream(messageToSend)
        .covary
        .through(jsonPipe[Message[Request]])
        .evalMap(publisher)

      consumer <- Stream.eval(rabbit.createAutoAckConsumer[String](queueToReply))

      consumedString <- consumer
        .through(logPipe(log))
        .filter(_.properties.correlationId.contains(correlationId))
        .map(_.payload)
        .head

      response <- Stream.eval(decodeData[RpcResponse](consumedString))
    } yield response

  def handleResponse(response: RpcResponse): Stream[F, Unit] =
    log
      .mapK[Stream[F, *]](liftK)
      .info {
        response match {
          case ExampleRpcResponse(taskId, status) => s"task_id: $taskId, status: $status"
        }
      }
}

object RpcClient {
  def create[F[_] : Sync](rpcQueue: QueueName, rabbit: Fs2Rabbit[F])(implicit channel: AMQPChannel): F[RpcClient[F]] =
    for {
      implicit0(log: Logger[F]) <- Slf4jLogger.create
    } yield new RpcClient(rpcQueue, rabbit)
}
