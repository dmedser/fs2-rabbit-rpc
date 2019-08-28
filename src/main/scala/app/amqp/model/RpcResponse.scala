package app.amqp.model

import java.util.UUID

import app.amqp.{DataDecoder, Meta}
import app.util.ClassUtil.nameOf
import io.circe.Decoder
import io.circe.generic.JsonCodec

sealed trait RpcResponse

object RpcResponse {

  @JsonCodec
  case class ExampleRpcResponse(taskId: UUID, status: String) extends RpcResponse

  object ExampleRpcResponse {
    val `type`: String = nameOf[ExampleRpcResponse]
  }

  implicit val rpcResponseDecoder: DataDecoder[RpcResponse] =
    (json, `type`) =>
      `type` match {
        case ExampleRpcResponse.`type` => Decoder[ExampleRpcResponse].decodeJson(json)
      }

  def deriveMeta(response: RpcResponse): Meta =
    response match {
      case _: ExampleRpcResponse => Meta(ExampleRpcResponse.`type`)
    }
}
