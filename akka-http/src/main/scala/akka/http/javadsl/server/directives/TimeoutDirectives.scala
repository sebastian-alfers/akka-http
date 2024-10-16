/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.server.directives

import java.util.function.{ Function => JFunction, Supplier }

import scala.concurrent.duration.Duration

import akka.http.javadsl.model.{ HttpRequest, HttpResponse }
import akka.http.javadsl.server.Route
import akka.http.scaladsl.server.{ Directives => D }

import akka.http.impl.util.JavaMapping.Implicits._

abstract class TimeoutDirectives extends WebSocketDirectives {

  def extractRequestTimeout(inner: JFunction[Duration, Route]): RouteAdapter = RouteAdapter {
    D.extractRequestTimeout { timeout => inner.apply(timeout).delegate }
  }

  /**
   * Tries to set a new request timeout and handler (if provided) at the same time.
   *
   * Due to the inherent raciness it is not guaranteed that the update will be applied before
   * the previously set timeout has expired!
   */
  def withRequestTimeout(timeout: scala.concurrent.duration.Duration, inner: Supplier[Route]): RouteAdapter = RouteAdapter {
    D.withRequestTimeout(timeout) { inner.get.delegate }
  }

  /**
   * Tries to set a new request timeout and handler (if provided) at the same time.
   *
   * Due to the inherent raciness it is not guaranteed that the update will be applied before
   * the previously set timeout has expired!
   */
  def withRequestTimeout(timeout: scala.concurrent.duration.Duration, timeoutHandler: JFunction[HttpRequest, HttpResponse],
                         inner: Supplier[Route]): RouteAdapter = RouteAdapter {
    D.withRequestTimeout(timeout, in => timeoutHandler(in.asJava).asScala) { inner.get.delegate }
  }

  def withoutRequestTimeout(inner: Supplier[Route]): RouteAdapter = RouteAdapter {
    D.withoutRequestTimeout { inner.get.delegate }
  }

  /**
   * Tries to set a new request timeout handler, which produces the timeout response for a
   * given request. Note that the handler must produce the response synchronously and shouldn't block!
   *
   * Due to the inherent raciness it is not guaranteed that the update will be applied before
   * the previously set timeout has expired!
   */
  def withRequestTimeoutResponse(timeoutHandler: JFunction[HttpRequest, HttpResponse], inner: Supplier[Route]): RouteAdapter = RouteAdapter {
    D.withRequestTimeoutResponse(in => timeoutHandler(in.asJava).asScala) { inner.get.delegate }
  }

}
