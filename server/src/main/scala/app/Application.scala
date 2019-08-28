package app

import app.amqp.RpcServer
import app.config.AppConfig
import cats.effect._
import cats.syntax.flatMap._
import cats.syntax.functor._
import dev.profunktor.fs2rabbit.interpreter.Fs2Rabbit
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger

final case class Application[F[_] : Sync](rpcServer: RpcServer[F], log: Logger[F]) {
  def run: F[ExitCode] =
    for {
      _ <- log.info("RPC Server")
      _ <- rpcServer.serve.compile.drain
    } yield ExitCode.Success
}

object Application {

  private def mkApplication[F[_] : Sync](rpcServer: RpcServer[F], log: Logger[F]): F[Application[F]] =
    Sync[F].delay(Application(rpcServer, log))

  def resource[F[_] : ConcurrentEffect]: Resource[F, Application[F]] =
    for {
      config      <- Resource.liftF(AppConfig.load)
      rabbit      <- Resource.liftF(Fs2Rabbit(config.fs2Rabbit))
      connection  <- rabbit.createConnection
      rpcClient   <- Resource.liftF(RpcServer(config.rpc.queueName, rabbit, connection))
      log         <- Resource.liftF(Slf4jLogger.create)
      application <- Resource.liftF(mkApplication(rpcClient, log))
    } yield application
}
