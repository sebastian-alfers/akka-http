/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl

import java.net.InetSocketAddress

import akka.http.scaladsl

class OutgoingConnection private[http] (val delegate: scaladsl.Http.OutgoingConnection) {
  /**
   * The local address of this connection.
   */
  def localAddress: InetSocketAddress = delegate.localAddress

  /**
   * The address of the remote peer.
   */
  def remoteAddress: InetSocketAddress = delegate.remoteAddress
}
