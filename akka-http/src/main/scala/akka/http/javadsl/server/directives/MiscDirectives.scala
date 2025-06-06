/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.server
package directives

import java.lang.{ Iterable => JIterable }
import java.util.function.BooleanSupplier
import java.util.function.{ Function => JFunction }
import java.util.function.Supplier

import scala.jdk.CollectionConverters._

import akka.http.javadsl.model.RemoteAddress
import akka.http.javadsl.model.headers.Language
import akka.http.impl.util.JavaMapping.Implicits._

import akka.http.scaladsl.server.{ Directives => D }

abstract class MiscDirectives extends MethodDirectives {

  /**
   * Checks the given condition before running its inner route.
   * If the condition fails the route is rejected with a [[ValidationRejection]].
   */
  def validate(check: BooleanSupplier, errorMsg: String, inner: Supplier[Route]): Route = RouteAdapter {
    D.validate(check.getAsBoolean(), errorMsg) { inner.get.delegate }
  }

  /**
   * Extracts the client's IP from either the X-Forwarded-For, Remote-Address, X-Real-IP header
   * or [[akka.http.javadsl.model.AttributeKeys.remoteAddress]] attribute
   * (in that order of priority).
   */
  def extractClientIP(inner: JFunction[RemoteAddress, Route]): Route = RouteAdapter {
    D.extractClientIP { ip => inner.apply(ip).delegate }
  }

  /**
   * Rejects if the request entity is non-empty.
   */
  def requestEntityEmpty(inner: Supplier[Route]): Route = RouteAdapter {
    D.requestEntityEmpty { inner.get.delegate }
  }

  /**
   * Rejects with a [[RequestEntityExpectedRejection]] if the request entity is empty.
   * Non-empty requests are passed on unchanged to the inner route.
   */
  def requestEntityPresent(inner: Supplier[Route]): Route = RouteAdapter {
    D.requestEntityPresent { inner.get.delegate }
  }

  /**
   * Converts responses with an empty entity into (empty) rejections.
   * This way you can, for example, have the marshalling of a ''None'' option
   * be treated as if the request could not be matched.
   */
  def rejectEmptyResponse(inner: Supplier[Route]): Route = RouteAdapter {
    D.rejectEmptyResponse { inner.get.delegate }
  }

  /**
   * Fails the stream with [[akka.http.scaladsl.model.EntityStreamSizeException]] if its request entity size exceeds
   * given limit. Limit given as parameter overrides limit configured with ``akka.http.parsing.max-content-length``.
   *
   * Beware that request entity size check is executed when entity is consumed.
   */
  def withSizeLimit(maxBytes: Long, inner: Supplier[Route]): Route = RouteAdapter {
    D.withSizeLimit(maxBytes) { inner.get.delegate }
  }

  /**
   * Disables the size limit (configured by `akka.http.parsing.max-content-length` by default) checking on the incoming
   * [[akka.http.javadsl.model.HttpRequest]] entity.
   * Can be useful when handling arbitrarily large data uploads in specific parts of your routes.
   *
   * @note  Usage of `withoutSizeLimit` is not recommended as it turns off the too large payload protection. Therefore,
   *        we highly encourage using `withSizeLimit` instead, providing it with a value high enough to successfully
   *        handle the route in need of big entities.
   */
  def withoutSizeLimit(inner: Supplier[Route]): Route = RouteAdapter {
    D.withoutSizeLimit { inner.get.delegate }
  }

  /**
   * Inspects the request's `Accept-Language` header and determines,
   * which of the given language alternatives is preferred by the client.
   * (See http://tools.ietf.org/html/rfc7231#section-5.3.5 for more details on the
   * negotiation logic.)
   * If there are several best language alternatives that the client
   * has equal preference for (even if this preference is zero!)
   * the order of the arguments is used as a tie breaker (First one wins).
   *
   * If [languages] is empty, the route is rejected.
   */
  def selectPreferredLanguage(languages: JIterable[Language], inner: JFunction[Language, Route]): Route = RouteAdapter {
    languages.asScala.toList match {
      case head :: tail =>
        D.selectPreferredLanguage(head.asScala, tail.map(_.asScala): _*) { lang => inner.apply(lang).delegate }
      case _ =>
        D.reject()
    }
  }

}
