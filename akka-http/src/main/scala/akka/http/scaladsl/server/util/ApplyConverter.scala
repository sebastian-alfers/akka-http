/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server.util

import akka.http.scaladsl.server._

/**
 * ApplyConverter allows generic conversion of functions of type `(T1, T2, ...) => Route` to
 * `(TupleX(T1, T2, ...)) => Route`.
 */
abstract class ApplyConverter[L] {
  type In
  def apply(f: In): L => Route
}

object ApplyConverter extends ApplyConverterInstances
