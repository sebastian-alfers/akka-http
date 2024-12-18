/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl

import akka.http.scaladsl.common.StrictForm
import akka.http.scaladsl.model._
import akka.util.ByteString

package object unmarshalling {
  //#unmarshaller-aliases
  type FromEntityUnmarshaller[T] = Unmarshaller[HttpEntity, T]
  type FromMessageUnmarshaller[T] = Unmarshaller[HttpMessage, T]
  type FromResponseUnmarshaller[T] = Unmarshaller[HttpResponse, T]
  type FromRequestUnmarshaller[T] = Unmarshaller[HttpRequest, T]
  type FromByteStringUnmarshaller[T] = Unmarshaller[ByteString, T]
  type FromStringUnmarshaller[T] = Unmarshaller[String, T]
  type FromStrictFormFieldUnmarshaller[T] = Unmarshaller[StrictForm.Field, T]
  //#unmarshaller-aliases
}
