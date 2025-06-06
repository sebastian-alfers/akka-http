/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server

import java.lang.Iterable
import java.util.Optional
import java.util.function.Function

import akka.japi.Util
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{ ByteRange, HttpChallenge, HttpEncoding }
import akka.http.javadsl.{ model, server => jserver }
import akka.http.impl.util.JavaMapping._
import akka.http.impl.util.JavaMapping.Implicits._
import akka.pattern.CircuitBreakerOpenException
import akka.http.javadsl.model.headers.{ HttpOrigin => JHttpOrigin }
import akka.http.scaladsl.model.headers.{ HttpOrigin => SHttpOrigin }

import java.util.{ List => JList }
import scala.collection.immutable
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._
import scala.runtime.AbstractFunction1

/**
 * A rejection encapsulates a specific reason why a Route was not able to handle a request. Rejections are gathered
 * up over the course of a Route evaluation and finally converted to [[akka.http.scaladsl.model.HttpResponse]]s by the
 * `handleRejections` directive, if there was no way for the request to be completed.
 */
trait Rejection extends akka.http.javadsl.server.Rejection

trait RejectionWithOptionalCause extends Rejection {
  final def getCause: Optional[Throwable] = cause.toJava
  def cause: Option[Throwable]
}

/**
 * Rejection created by method filters.
 * Signals that the request was rejected because the HTTP method is unsupported.
 */
final case class MethodRejection(supported: HttpMethod)
  extends jserver.MethodRejection with Rejection

/**
 * Rejection created by scheme filters.
 * Signals that the request was rejected because the Uri scheme is unsupported.
 */
final case class SchemeRejection(supported: String)
  extends jserver.SchemeRejection with Rejection

/**
 * Rejection created by parameter filters.
 * Signals that the request was rejected because a query parameter was not found.
 */
final case class MissingQueryParamRejection(parameterName: String)
  extends jserver.MissingQueryParamRejection with Rejection

/**
 * Rejection created by parameter filters.
 * Signals that the request was rejected because a query parameter value was not equal to required one.
 */
final case class InvalidRequiredValueForQueryParamRejection(parameterName: String, expectedValue: String, actualValue: String)
  extends jserver.InvalidRequiredValueForQueryParamRejection with Rejection

/**
 * Rejection created by parameter filters.
 * Signals that the request was rejected because a query parameter could not be interpreted.
 */
final case class MalformedQueryParamRejection(parameterName: String, errorMsg: String, cause: Option[Throwable] = None)
  extends jserver.MalformedQueryParamRejection with RejectionWithOptionalCause

/**
 * Rejection created by form field filters.
 * Signals that the request was rejected because a form field was not found.
 */
final case class MissingFormFieldRejection(fieldName: String)
  extends jserver.MissingFormFieldRejection with Rejection

/**
 * Rejection created by form field filters.
 * Signals that the request was rejected because a form field could not be interpreted.
 */
final case class MalformedFormFieldRejection(fieldName: String, errorMsg: String, cause: Option[Throwable] = None)
  extends jserver.MalformedFormFieldRejection with RejectionWithOptionalCause

/**
 * Rejection created by header directives.
 * Signals that the request was rejected because a required header could not be found.
 */
final case class MissingHeaderRejection(headerName: String)
  extends jserver.MissingHeaderRejection with Rejection

/**
 * Rejection created by attribute directives.
 * Signals that the request was rejected because a required request attribute could not be found.
 */
final case class MissingAttributeRejection[T](key: AttributeKey[T])
  extends jserver.MissingAttributeRejection[T] with Rejection

/**
 * Rejection created by header directives.
 * Signals that the request was rejected because a header value is malformed.
 */
final case class MalformedHeaderRejection(headerName: String, errorMsg: String, cause: Option[Throwable] = None)
  extends jserver.MalformedHeaderRejection with RejectionWithOptionalCause

/**
 * Rejection created by [[akka.http.scaladsl.server.directives.HeaderDirectives.checkSameOrigin]].
 * Signals that the request was rejected because `Origin` header value is invalid.
 */
final case class InvalidOriginRejection(allowedOrigins: immutable.Seq[SHttpOrigin])
  extends jserver.InvalidOriginRejection with Rejection {
  override def getAllowedOrigins: java.util.List[JHttpOrigin] = allowedOrigins.map(_.asJava).asJava
}

/**
 * Rejection created by unmarshallers.
 * Signals that the request was rejected because the requests content-type is unsupported.
 */
final class UnsupportedRequestContentTypeRejection(
  val supported:   Set[ContentTypeRange],
  val contentType: Option[ContentType]   = None)
  extends jserver.UnsupportedRequestContentTypeRejection
  with Rejection with Product with Serializable {

  override def getSupported: java.util.Set[model.ContentTypeRange] =
    scala.collection.mutable.Set(supported.map(_.asJava).toVector: _*).asJava // TODO optimise

  @deprecated("for binary compatibility", since = "10.1.9")
  def this(supported: Set[ContentTypeRange]) = this(supported, None)

  @deprecated("for binary compatibility", since = "10.1.9")
  def copy(supported: Set[ContentTypeRange]) =
    new UnsupportedRequestContentTypeRejection(supported, this.contentType)

  @deprecated("for binary compatibility", since = "10.1.9")
  def copy$default$1(supported: Set[ContentTypeRange]) =
    new UnsupportedRequestContentTypeRejection(supported, this.contentType)

  @deprecated("for binary compatibility", since = "10.1.9")
  def copy(
    supported:   Set[ContentTypeRange] = this.supported,
    contentType: Option[ContentType]   = this.contentType) =
    UnsupportedRequestContentTypeRejection(supported, contentType)

  override def canEqual(that: Any): Boolean = that.isInstanceOf[UnsupportedRequestContentTypeRejection]

  override def equals(that: Any): Boolean = that match {
    case that: UnsupportedRequestContentTypeRejection => that.canEqual(this) && that.supported == this.supported && that.contentType == this.contentType
    case _ => false
  }

  override def productArity: Int = 1
  override def productElement(n: Int): Any = supported
}

object UnsupportedRequestContentTypeRejection
  extends AbstractFunction1[Set[ContentTypeRange], UnsupportedRequestContentTypeRejection] {

  def apply(supported: Set[ContentTypeRange], contentType: Option[ContentType]): UnsupportedRequestContentTypeRejection =
    new UnsupportedRequestContentTypeRejection(supported, contentType)

  @deprecated("for binary compatibility", since = "10.1.9")
  def apply(supported: Set[ContentTypeRange]): UnsupportedRequestContentTypeRejection =
    new UnsupportedRequestContentTypeRejection(supported, None)

  def unapply(rejection: UnsupportedRequestContentTypeRejection): Option[Set[ContentTypeRange]] =
    Some(rejection.supported)
}

/**
 * Rejection created by decoding filters.
 * Signals that the request was rejected because the requests content encoding is unsupported.
 */
final case class UnsupportedRequestEncodingRejection(supported: HttpEncoding)
  extends jserver.UnsupportedRequestEncodingRejection with Rejection

/**
 * Rejection created by range directives.
 * Signals that the request was rejected because the requests contains only unsatisfiable ByteRanges.
 * The actualEntityLength gives the client a hint to create satisfiable ByteRanges.
 */
final case class UnsatisfiableRangeRejection(unsatisfiableRanges: immutable.Seq[ByteRange], actualEntityLength: Long)
  extends jserver.UnsatisfiableRangeRejection with Rejection {
  override def getUnsatisfiableRanges: Iterable[model.headers.ByteRange] = unsatisfiableRanges.map(_.asJava).asJava
}

/**
 * Rejection created by range directives.
 * Signals that the request contains too many ranges. An irregular high number of ranges
 * indicates a broken client or a denial of service attack.
 */
final case class TooManyRangesRejection(maxRanges: Int)
  extends jserver.TooManyRangesRejection with Rejection

/**
 * Rejection created by unmarshallers.
 * Signals that the request was rejected because unmarshalling failed with an error that wasn't
 * an `IllegalArgumentException`. Usually that means that the request content was not of the expected format.
 * Note that semantic issues with the request content (e.g. because some parameter was out of range)
 * will usually trigger a `ValidationRejection` instead.
 */
final case class MalformedRequestContentRejection(message: String, cause: Throwable)
  extends jserver.MalformedRequestContentRejection with Rejection { override def getCause: Throwable = cause }

/**
 * Rejection created by unmarshallers.
 * Signals that the request was rejected because an message body entity was expected but not supplied.
 */
case object RequestEntityExpectedRejection
  extends jserver.RequestEntityExpectedRejection with Rejection

/**
 * Rejection created by marshallers.
 * Signals that the request was rejected because the service is not capable of producing a response entity whose
 * content type is accepted by the client
 */
final case class UnacceptedResponseContentTypeRejection(supported: Set[ContentNegotiator.Alternative])
  extends jserver.UnacceptedResponseContentTypeRejection with Rejection

/**
 * Rejection created by encoding filters.
 * Signals that the request was rejected because the service is not capable of producing a response entity whose
 * content encoding is accepted by the client
 */
final case class UnacceptedResponseEncodingRejection(supported: Set[HttpEncoding])
  extends jserver.UnacceptedResponseEncodingRejection with Rejection {
  override def getSupported: java.util.Set[model.headers.HttpEncoding] = supported.map(_.asJava).asJava
}
object UnacceptedResponseEncodingRejection {
  def apply(supported: HttpEncoding): UnacceptedResponseEncodingRejection = UnacceptedResponseEncodingRejection(Set(supported))
}

/**
 * Rejection created by the various [[akka.http.scaladsl.server.directives.SecurityDirectives]].
 * Signals that the request was rejected because the user could not be authenticated. The reason for the rejection is
 * specified in the cause.
 */
final case class AuthenticationFailedRejection(cause: AuthenticationFailedRejection.Cause, challenge: HttpChallenge)
  extends jserver.AuthenticationFailedRejection with Rejection

object AuthenticationFailedRejection {
  /**
   * Signals the cause of the failed authentication.
   */
  sealed trait Cause extends jserver.AuthenticationFailedRejection.Cause

  /**
   * Signals the cause of the rejecting was that the user could not be authenticated, because the `WWW-Authenticate`
   * header was not supplied.
   */
  case object CredentialsMissing extends jserver.AuthenticationFailedRejection.CredentialsMissing with Cause

  /**
   * Signals the cause of the rejecting was that the user could not be authenticated, because the supplied credentials
   * are invalid.
   */
  case object CredentialsRejected extends jserver.AuthenticationFailedRejection.CredentialsRejected with Cause
}

/**
 * Rejection created by the 'authorize' directive.
 * Signals that the request was rejected because the user is not authorized.
 */
case object AuthorizationFailedRejection
  extends jserver.AuthorizationFailedRejection with Rejection

/**
 * Rejection created by the `cookie` directive.
 * Signals that the request was rejected because a cookie was not found.
 */
final case class MissingCookieRejection(cookieName: String)
  extends jserver.MissingCookieRejection with Rejection

/**
 * Rejection created when a websocket request was expected but none was found.
 */
case object ExpectedWebSocketRequestRejection
  extends jserver.ExpectedWebSocketRequestRejection with Rejection

/**
 * Rejection created when a websocket request was not handled because none of the given subprotocols
 * was supported.
 */
final case class UnsupportedWebSocketSubprotocolRejection(supportedProtocol: String)
  extends jserver.UnsupportedWebSocketSubprotocolRejection with Rejection

/**
 * Rejection created by the `validation` directive as well as for `IllegalArgumentExceptions`
 * thrown by domain model constructors (e.g. via `require`).
 * It signals that an expected value was semantically invalid.
 */
final case class ValidationRejection(message: String, cause: Option[Throwable] = None)
  extends jserver.ValidationRejection with RejectionWithOptionalCause

/**
 * A special Rejection that serves as a container for a transformation function on rejections.
 * It is used by some directives to "cancel" rejections that are added by later directives of a similar type.
 *
 * Consider this route structure for example:
 *
 * {{{
 *     put { reject(ValidationRejection("no") } ~ get { ... }
 * }}}
 *
 * If this structure is applied to a PUT request the list of rejections coming back contains three elements:
 *
 * 1. A ValidationRejection
 * 2. A MethodRejection
 * 3. A TransformationRejection holding a function filtering out the MethodRejection
 *
 * so that in the end the RejectionHandler will only see one rejection (the ValidationRejection), because the
 * MethodRejection added by the `get` directive is canceled by the `put` directive (since the HTTP method
 * did indeed match eventually).
 */
final case class TransformationRejection(transform: immutable.Seq[Rejection] => immutable.Seq[Rejection])
  extends jserver.TransformationRejection with Rejection {
  override def getTransform = new Function[Iterable[jserver.Rejection], Iterable[jserver.Rejection]] {
    override def apply(t: Iterable[jserver.Rejection]): Iterable[jserver.Rejection] = {
      // explicit collects assignment is because of unidoc failing compilation on .asScala and .asJava here
      val transformed: Seq[jserver.Rejection] = transform(Util.immutableSeq(t).collect { case r: Rejection => r }).collect { case j: jserver.Rejection => j }
      transformed.asJava // TODO "asJavaDeep" and optimise?
    }
  }
}

/**
 * Rejection created by the `onCompleteWithBreaker` directive.
 * Signals that the request was rejected because the supplied circuit breaker is open and requests are failing fast.
 */
final case class CircuitBreakerOpenRejection(cause: CircuitBreakerOpenException)
  extends jserver.CircuitBreakerOpenRejection with Rejection

/**
 * A Throwable wrapping a Rejection.
 * Can be used for marshalling `Future[T]` or `Try[T]` instances, whose failure side is supposed to trigger a route
 * rejection rather than an Exception that is handled by the nearest ExceptionHandler.
 * (Custom marshallers can of course use it as well.)
 */
final case class RejectionError(rejection: Rejection) extends RuntimeException(rejection.toString)

/**
 * Rejection created by the CORS directives.
 */
final case class CorsRejection(description: String) extends jserver.CorsRejection with Rejection

final case class TlsClientUnverifiedRejection(description: String) extends jserver.TlsClientUnverifiedRejection with Rejection
final case class TlsClientIdentityRejection(description: String, requiredExpression: String, certificateCN: Option[String], certificateSANs: Seq[String]) extends jserver.TlsClientIdentityRejection with Rejection {
  override def getCertificateCN: Optional[String] = certificateCN.toJava

  override def getCertificateSANs: JList[String] = certificateSANs.asJava
}
