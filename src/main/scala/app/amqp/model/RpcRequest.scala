package app.amqp.model

import app.amqp.{DataDecoder, Meta}
import app.util.ClassUtil.nameOf
import io.circe.Decoder
import io.circe.generic.JsonCodec

sealed trait RpcRequest

object RpcRequest {

  @JsonCodec
  case class ExampleRpcRequest(int: Int, strings: List[String]) extends RpcRequest

  object ExampleRpcRequest {
    val `type`: String = nameOf[ExampleRpcRequest]
  }

  implicit val rpcRequestDecoder: DataDecoder[RpcRequest] =
    (json, `type`) =>
      `type` match {
        case ExampleRpcRequest.`type` => Decoder[ExampleRpcRequest].decodeJson(json)
      }

  def deriveMeta(request: RpcRequest): Meta =
    request match {
      case _: ExampleRpcRequest => Meta(ExampleRpcRequest.`type`)
    }
}
