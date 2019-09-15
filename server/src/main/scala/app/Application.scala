package app

import app.config.AppConfig
import cats.effect._
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import dev.profunktor.fs2rabbit.interpreter.Fs2Rabbit
import dev.profunktor.fs2rabbit.model.AMQPChannel
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import monix.eval.{Task, TaskApp}

final case class Application[F[_] : Sync](rpcServer: RpcServer[F], log: Logger[F]) {
  def run: F[ExitCode] =
    for {
      _ <- log.info("RPC Server")
      _ <- rpcServer.serve().compile.drain
    } yield ExitCode.Success
}

object Application extends TaskApp {

  def run(args: List[String]): Task[ExitCode] = Application.resource.use(_.run)

  private def mkApplication[F[_] : Sync](rpcServer: RpcServer[F], log: Logger[F]): F[Application[F]] =
    Application(rpcServer, log).pure[F]

  private def resource[F[_] : ConcurrentEffect]: Resource[F, Application[F]] =
    for {
      config                          <- Resource.liftF(AppConfig.load[F])
      rabbit                          <- Resource.liftF(Fs2Rabbit[F](config.fs2Rabbit))
      connection                      <- rabbit.createConnection
      implicit0(channel: AMQPChannel) <- rabbit.createChannel(connection)
      rpcServer                       <- Resource.liftF(RpcServer.create[F](config.rpc.queueName, rabbit))
      log                             <- Resource.liftF(Slf4jLogger.create[F])
      application                     <- Resource.liftF(mkApplication[F](rpcServer, log))
    } yield application
}
