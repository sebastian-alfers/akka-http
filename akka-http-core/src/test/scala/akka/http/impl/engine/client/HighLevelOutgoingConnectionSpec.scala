/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.client

import akka.http.impl.util.AkkaSpecWithMaterializer

import scala.concurrent.duration._
import akka.stream.FlowShape
import akka.stream.scaladsl._
import akka.testkit._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.testkit.Utils
import org.scalatest.concurrent.PatienceConfiguration.Timeout

class HighLevelOutgoingConnectionSpec extends AkkaSpecWithMaterializer {
  "The connection-level client implementation" should {

    "be able to handle 100 requests across one connection" in Utils.assertAllStagesStopped {
      val binding = Http().bindAndHandleSync(
        r => HttpResponse(entity = r.uri.toString.reverse.takeWhile(Character.isDigit).reverse),
        "127.0.0.1", 0).futureValue

      val N = 100
      val result = Source.fromIterator(() => Iterator.from(1))
        .take(N)
        .map(id => HttpRequest(uri = s"/r$id"))
        .via(Http().outgoingConnection("127.0.0.1", binding.localAddress.getPort))
        .mapAsync(4)(_.entity.toStrict(1.second.dilated))
        .map { r => val s = r.data.utf8String; log.debug(s); s.toInt }
        .runFold(0)(_ + _)
      result.futureValue(Timeout(10.seconds.dilated)) should ===(N * (N + 1) / 2)
      binding.unbind()
    }

    "be able to handle 100 requests across 4 connections (client-flow is reusable)" in Utils.assertAllStagesStopped {
      val binding = Http().bindAndHandleSync(
        r => HttpResponse(entity = r.uri.toString.reverse.takeWhile(Character.isDigit).reverse),
        "127.0.0.1", 0).futureValue

      val connFlow = Http().outgoingConnection("127.0.0.1", binding.localAddress.getPort)

      val C = 4
      val doubleConnection = Flow.fromGraph(GraphDSL.create() { implicit b =>
        import GraphDSL.Implicits._

        val bcast = b.add(Broadcast[HttpRequest](C))
        val merge = b.add(Merge[HttpResponse](C))

        for (i <- 0 until C)
          bcast.out(i) ~> connFlow ~> merge.in(i)
        FlowShape(bcast.in, merge.out)
      })

      val N = 100
      val result = Source.fromIterator(() => Iterator.from(1))
        .take(N)
        .map(id => HttpRequest(uri = s"/r$id"))
        .via(doubleConnection)
        .mapAsync(4)(_.entity.toStrict(1.second.dilated))
        .map { r => val s = r.data.utf8String; log.debug(s); s.toInt }
        .runFold(0)(_ + _)

      result.futureValue(Timeout(10.seconds.dilated)) should ===(C * N * (N + 1) / 2)
      binding.unbind()
    }

  }
}
