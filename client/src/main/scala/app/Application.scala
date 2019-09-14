package app

import app.amqp.RpcClient
import app.amqp.model.RpcRequest.ExampleRpcRequest
import app.config.AppConfig
import app.util.StreamUtil._
import cats.effect._
import cats.syntax.functor._
import dev.profunktor.fs2rabbit.interpreter.Fs2Rabbit
import dev.profunktor.fs2rabbit.model.AMQPChannel
import fs2.Stream
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.extras.implicits._
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import monix.eval.{Task, TaskApp}

final case class Application[F[_] : Sync](rpcClient: RpcClient[F], log: Logger[F]) {
  def run: F[ExitCode] =
    (for {
      _        <- log.mapK[Stream[F, *]](liftK).info("RPC Client")
      response <- rpcClient.call(ExampleRpcRequest(int = 1, strings = List("abc", "def")))
      _        <- rpcClient.handleResponse(response)
    } yield ()).compile.drain as ExitCode.Success
}

object Application extends TaskApp {

  def run(args: List[String]): Task[ExitCode] = Application.resource.use(_.run)

  private def mkApplication[F[_]](rpcClient: RpcClient[F], log: Logger[F])(implicit F: Sync[F]): F[Application[F]] =
    F.delay(Application(rpcClient, log))

  private def resource[F[_] : ConcurrentEffect]: Resource[F, Application[F]] =
    for {
      config      <- Resource.liftF(AppConfig.load[F])
      rabbit      <- Resource.liftF(Fs2Rabbit[F](config.fs2Rabbit))
      connection  <- rabbit.createConnection
      implicit0(channel: AMQPChannel) <- rabbit.createChannel(connection)
      rpcClient   <- Resource.liftF(RpcClient.create[F](config.rpc.queueName, rabbit))
      log         <- Resource.liftF(Slf4jLogger.create[F])
      application <- Resource.liftF(mkApplication[F](rpcClient, log))
    } yield application
}
