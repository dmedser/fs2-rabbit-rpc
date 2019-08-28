package app

import app.amqp.RpcClient
import app.amqp.model.RpcRequest.ExampleRpcRequest
import app.config.AppConfig
import app.util.StreamUtil._
import cats.effect._
import cats.syntax.functor._
import dev.profunktor.fs2rabbit.interpreter.Fs2Rabbit
import fs2.Stream
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.extras.implicits._
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger

final case class Application[F[_] : Sync](rpcClient: RpcClient[F], log: Logger[F]) {

  def run: F[ExitCode] =
    (for {
      _        <- log.mapK[Stream[F, *]](liftK).info("RPC Client")
      response <- rpcClient.call(ExampleRpcRequest(int = 1, strings = List("abc", "def")))
      _        <- rpcClient.handleResponse(response)
    } yield ()).compile.drain as ExitCode.Success
}

object Application {

  private def mkApplication[F[_] : Sync](rpcClient: RpcClient[F], log: Logger[F]): F[Application[F]] =
    Sync[F].delay(Application(rpcClient, log))

  def resource[F[_] : ConcurrentEffect]: Resource[F, Application[F]] =
    for {
      config      <- Resource.liftF(AppConfig.load)
      rabbit      <- Resource.liftF(Fs2Rabbit(config.fs2Rabbit))
      connection  <- rabbit.createConnection
      rpcClient   <- Resource.liftF(RpcClient(config.rpc.queueName, rabbit, connection))
      log         <- Resource.liftF(Slf4jLogger.create)
      application <- Resource.liftF(mkApplication(rpcClient, log))
    } yield application
}
