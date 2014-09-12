/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.testkit

import scala.collection.immutable
import scala.concurrent.{ Await, ExecutionContext }
import scala.concurrent.duration._
import scala.reflect.ClassTag
import akka.util.Timeout
import akka.event.{ Logging, LoggingAdapter }
import akka.http.model.parser.HeaderParser
import akka.http.marshalling._
import akka.http.model._
import headers.{ HttpCredentials, RawHeader }
import HttpMethods._

trait RequestBuilding extends TransformerPipelineSupport {
  type RequestTransformer = HttpRequest ⇒ HttpRequest

  class RequestBuilder(val method: HttpMethod) {
    def apply(): HttpRequest =
      apply("/")

    def apply(uri: String): HttpRequest =
      apply(uri, HttpEntity.Empty)

    def apply[T](uri: String, content: T)(implicit m: ToEntityMarshallers[T], ec: ExecutionContext): HttpRequest =
      apply(uri, Some(content))

    def apply[T](uri: String, content: Option[T])(implicit m: ToEntityMarshallers[T], ec: ExecutionContext): HttpRequest =
      apply(Uri(uri), content)

    def apply(uri: String, entity: HttpEntity.Regular): HttpRequest =
      apply(Uri(uri), entity)

    def apply(uri: Uri): HttpRequest =
      apply(uri, HttpEntity.Empty)

    def apply[T](uri: Uri, content: T)(implicit m: ToEntityMarshallers[T], ec: ExecutionContext): HttpRequest =
      apply(uri, Some(content))

    def apply[T](uri: Uri, content: Option[T])(implicit m: ToEntityMarshallers[T], timeout: Timeout = Timeout(1.second), ec: ExecutionContext): HttpRequest =
      content match {
        case None ⇒ apply(uri, HttpEntity.Empty)
        case Some(value) ⇒
          val entity = Marshal(value).to[HttpEntity.Regular].await(timeout.duration)
          apply(uri, entity)
      }

    def apply(uri: Uri, entity: HttpEntity.Regular): HttpRequest =
      HttpRequest(method, uri, Nil, entity)
  }

  val Get = new RequestBuilder(GET)
  val Post = new RequestBuilder(POST)
  val Put = new RequestBuilder(PUT)
  val Patch = new RequestBuilder(PATCH)
  val Delete = new RequestBuilder(DELETE)
  val Options = new RequestBuilder(OPTIONS)
  val Head = new RequestBuilder(HEAD)

  // TODO: reactivate after HTTP message encoding has been ported
  //def encode(encoder: Encoder, flow: FlowMaterializer): RequestTransformer = encoder.encode(_, flow)

  def addHeader(header: HttpHeader): RequestTransformer = _.mapHeaders(header +: _)

  def addHeader(headerName: String, headerValue: String): RequestTransformer = {
    val rawHeader = RawHeader(headerName, headerValue)
    addHeader(HeaderParser.parseHeader(rawHeader).left.flatMap(_ ⇒ Right(rawHeader)).right.get)
  }

  def addHeaders(first: HttpHeader, more: HttpHeader*): RequestTransformer = _.mapHeaders(_ ++ (first +: more))

  def mapHeaders(f: immutable.Seq[HttpHeader] ⇒ immutable.Seq[HttpHeader]): RequestTransformer = _.mapHeaders(f)

  def removeHeader(headerName: String): RequestTransformer =
    _ mapHeaders (_ filterNot (_.name equalsIgnoreCase headerName))

  def removeHeader[T <: HttpHeader: ClassTag]: RequestTransformer =
    removeHeader(implicitly[ClassTag[T]].runtimeClass)

  def removeHeader(clazz: Class[_]): RequestTransformer =
    _ mapHeaders (_ filterNot clazz.isInstance)

  def removeHeaders(names: String*): RequestTransformer =
    _ mapHeaders (_ filterNot (header ⇒ names exists (_ equalsIgnoreCase header.name)))

  def addCredentials(credentials: HttpCredentials) = addHeader(headers.Authorization(credentials))

  def logRequest(log: LoggingAdapter, level: Logging.LogLevel = Logging.DebugLevel) = logValue[HttpRequest](log, level)

  def logRequest(logFun: HttpRequest ⇒ Unit) = logValue[HttpRequest](logFun)

  implicit def header2AddHeader(header: HttpHeader): RequestTransformer = addHeader(header)
}

object RequestBuilding extends RequestBuilding