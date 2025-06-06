/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.unmarshalling

import akka.http.impl.util._
import akka.http.scaladsl.model.MediaTypes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.util.FastFuture._
import akka.stream.scaladsl._
import akka.testkit._
import akka.util.ByteString
import org.scalatest.matchers.Matcher

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }

trait MultipartUnmarshallersSpec extends AkkaSpecWithMaterializer {
  implicit val ec: ExecutionContext = system.dispatcher

  def lineFeed: String

  "The MultipartUnmarshallers." should {

    "multipartGeneralUnmarshaller should correctly unmarshal 'multipart/*' content with" should {
      "an empty part" in {
        val entity = HttpEntity(
          `multipart/mixed` withBoundary "XYZABC",
          ByteString("""--XYZABC
                       |--XYZABC--""".stripMarginWithNewline(lineFeed)))
        Unmarshal(entity).to[Multipart.General] should haveParts(
          Multipart.General.BodyPart.Strict(HttpEntity.empty(ContentTypes.`text/plain(UTF-8)`)))
      }
      "two empty parts" in {
        Unmarshal(HttpEntity(
          `multipart/mixed` withBoundary "XYZABC",
          ByteString("""--XYZABC
                       |--XYZABC
                       |--XYZABC--""".stripMarginWithNewline(lineFeed)))).to[Multipart.General] should haveParts(
          Multipart.General.BodyPart.Strict(HttpEntity.empty(ContentTypes.`text/plain(UTF-8)`)),
          Multipart.General.BodyPart.Strict(HttpEntity.empty(ContentTypes.`text/plain(UTF-8)`)))
      }
      "a part without entity and missing header separation CRLF" in {
        Unmarshal(HttpEntity(
          `multipart/mixed` withBoundary "XYZABC",
          ByteString("""--XYZABC
                       |Content-type: text/xml; charset=UTF-8
                       |Age: 12
                       |--XYZABC--""".stripMarginWithNewline(lineFeed)))).to[Multipart.General] should haveParts(
          Multipart.General.BodyPart.Strict(HttpEntity.empty(ContentTypes.`text/xml(UTF-8)`), List(Age(12))))
      }
      "an implicitly typed part (without headers) (Strict)" in {
        Unmarshal(HttpEntity(
          `multipart/mixed` withBoundary "XYZABC",
          ByteString("""--XYZABC
                       |
                       |Perfectly fine part content.
                       |--XYZABC--""".stripMarginWithNewline(lineFeed)))).to[Multipart.General] should haveParts(
          Multipart.General.BodyPart.Strict(HttpEntity(ContentTypes.`text/plain(UTF-8)`, "Perfectly fine part content.")))
      }
      "an implicitly typed part (without headers) (Default)" in {
        val content = """--XYZABC
                        |
                        |Perfectly fine part content.
                        |--XYZABC--""".stripMarginWithNewline(lineFeed)
        val byteStrings = content.map(c => ByteString(c.toString)) // one-char ByteStrings
        Unmarshal(HttpEntity.Default(`multipart/mixed` withBoundary "XYZABC", content.length, Source(byteStrings)))
          .to[Multipart.General] should haveParts(
            Multipart.General.BodyPart.Strict(HttpEntity(ContentTypes.`text/plain(UTF-8)`, "Perfectly fine part content.")))
      }
      "one non-empty form-data part" in {
        Unmarshal(HttpEntity(
          `multipart/form-data` withBoundary "-",
          ByteString("""---
                       |Content-type: text/plain; charset=UTF8
                       |content-disposition: form-data; name="email"
                       |
                       |test@there.com
                       |-----""".stripMarginWithNewline(lineFeed)))).to[Multipart.General] should haveParts(
          Multipart.General.BodyPart.Strict(
            HttpEntity(ContentTypes.`text/plain(UTF-8)`, "test@there.com"),
            List(`Content-Disposition`(ContentDispositionTypes.`form-data`, Map("name" -> "email")))))
      }
      "two different parts" in {
        Unmarshal(HttpEntity(
          `multipart/mixed` withBoundary "12345",
          ByteString("""--12345
                       |
                       |first part, with a trailing newline
                       |
                       |--12345
                       |Content-Type: application/octet-stream
                       |Content-Transfer-Encoding: binary
                       |
                       |filecontent
                       |--12345--""".stripMarginWithNewline(lineFeed)))).to[Multipart.General] should haveParts(
          Multipart.General.BodyPart.Strict(HttpEntity(ContentTypes.`text/plain(UTF-8)`, s"first part, with a trailing newline${lineFeed}")),
          Multipart.General.BodyPart.Strict(
            HttpEntity(`application/octet-stream`, ByteString("filecontent")),
            List(RawHeader("Content-Transfer-Encoding", "binary"))))
      }
      "illegal headers" in (
        Unmarshal(HttpEntity(
          `multipart/form-data` withBoundary "XYZABC",
          ByteString("""--XYZABC
                       |Date: unknown
                       |content-disposition: form-data; name=email
                       |
                       |test@there.com
                       |--XYZABC--""".stripMarginWithNewline(lineFeed)))).to[Multipart.General] should haveParts(
          Multipart.General.BodyPart.Strict(
            HttpEntity(ContentTypes.`text/plain(UTF-8)`, "test@there.com"),
            List(
              RawHeader("date", "unknown"),
              `Content-Disposition`(ContentDispositionTypes.`form-data`, Map("name" -> "email"))))))
      "a full example (Strict)" in {
        Unmarshal(HttpEntity(
          `multipart/mixed` withBoundary "12345",
          ByteString("""preamble and
                       |more preamble
                       |--12345
                       |
                       |first part, implicitly typed
                       |--12345
                       |Content-Type: application/octet-stream
                       |
                       |second part, explicitly typed
                       |--12345--
                       |epilogue and
                       |more epilogue""".stripMarginWithNewline(lineFeed)))).to[Multipart.General] should haveParts(
          Multipart.General.BodyPart.Strict(HttpEntity(ContentTypes.`text/plain(UTF-8)`, "first part, implicitly typed")),
          Multipart.General.BodyPart.Strict(HttpEntity(`application/octet-stream`, ByteString("second part, explicitly typed"))))
      }
      "a full example (Strict) containing a CR in the body part" in {
        Unmarshal(HttpEntity(
          `multipart/mixed` withBoundary "12345",
          ByteString("""preamble and
                       |more preamble
                       |--12345
                       |
                       |first part, impliINSERT_CR_HEREcitly typed
                       |--12345
                       |Content-Type: application/octet-stream
                       |
                       |second part, explicitly typed
                       |--12345--
                       |epilogue and
                       |more epilogue""".stripMarginWithNewline(lineFeed).replaceAll("INSERT_CR_HERE", "\r")))).to[Multipart.General] should haveParts(
          Multipart.General.BodyPart.Strict(HttpEntity(ContentTypes.`text/plain(UTF-8)`, "first part, impli\rcitly typed")),
          Multipart.General.BodyPart.Strict(HttpEntity(`application/octet-stream`, ByteString("second part, explicitly typed"))))
      }
      "a full example (Default)" in {
        val content = """preamble and
                        |more preamble
                        |--12345
                        |
                        |first part, implicitly typed
                        |--12345
                        |Content-Type: application/octet-stream
                        |
                        |second part, explicitly typed
                        |--12345--
                        |epilogue and
                        |more epilogue""".stripMarginWithNewline(lineFeed)
        val byteStrings = content.map(c => ByteString(c.toString)) // one-char ByteStrings
        Unmarshal(HttpEntity.Default(`multipart/mixed` withBoundary "12345", content.length, Source(byteStrings)))
          .to[Multipart.General] should haveParts(
            Multipart.General.BodyPart.Strict(HttpEntity(ContentTypes.`text/plain(UTF-8)`, "first part, implicitly typed")),
            Multipart.General.BodyPart.Strict(HttpEntity(`application/octet-stream`, ByteString("second part, explicitly typed"))))
      }
      "a full example (Default) containing a CR in the body part" in {
        val content = """preamble and
                        |more preamble
                        |--12345
                        |
                        |first paINSERT_CR_HERErt, implicitly typed
                        |--12345
                        |Content-Type: application/octet-stream
                        |
                        |second part, explicitly typed
                        |--12345--
                        |epilogue and
                        |more epilogue""".stripMarginWithNewline(lineFeed).replaceAll("INSERT_CR_HERE", "\r")
        val byteStrings = content.map(c => ByteString(c.toString)) // one-char ByteStrings
        Unmarshal(HttpEntity.Default(`multipart/mixed` withBoundary "12345", content.length, Source(byteStrings)))
          .to[Multipart.General] should haveParts(
            Multipart.General.BodyPart.Strict(HttpEntity(ContentTypes.`text/plain(UTF-8)`, "first pa\rrt, implicitly typed")),
            Multipart.General.BodyPart.Strict(HttpEntity(`application/octet-stream`, ByteString("second part, explicitly typed"))))
      }
      "a boundary with spaces" in {
        Unmarshal(HttpEntity(
          `multipart/mixed` withBoundary "simple boundary",
          ByteString("""--simple boundary
                       |--simple boundary--""".stripMarginWithNewline(lineFeed)))).to[Multipart.General] should haveParts(
          Multipart.General.BodyPart.Strict(HttpEntity.empty(ContentTypes.`text/plain(UTF-8)`)))
      }
      "many small parts received in one go" in {
        val num = 5000
        val singlePart =
          """--12345
            |
            |data
            |""".stripMarginWithNewline(lineFeed)

        val manyParts = singlePart * num + "--12345--"

        val content = ByteString(manyParts)

        Unmarshal(HttpEntity.Default(`multipart/mixed` withBoundary "12345", content.length, Source.single(content)))
          .to[Multipart.General]
          .flatMap(_.toStrict(1.second.dilated))
          .awaitResult(5.second.dilated)
          .strictParts.size shouldBe num
      }
    }

    "multipartGeneralUnmarshaller should reject illegal multipart content with" should {
      "an empty entity" in {
        Await.result(Unmarshal(HttpEntity(`multipart/mixed` withBoundary "XYZABC", ByteString.empty))
          .to[Multipart.General].failed, 1.second.dilated).getMessage shouldEqual "Unexpected end of multipart entity"
      }
      "an entity without initial boundary" in {
        Await.result(Unmarshal(HttpEntity(
          `multipart/mixed` withBoundary "XYZABC",
          ByteString("""this is
                       |just preamble text""".stripMarginWithNewline(lineFeed))))
          .to[Multipart.General].failed, 1.second.dilated).getMessage shouldEqual "Unexpected end of multipart entity"
      }
      "a stray boundary" in {
        Await.result(Unmarshal(HttpEntity(
          `multipart/form-data` withBoundary "ABC",
          ByteString("""--ABC
                       |Content-type: text/plain; charset=UTF8
                       |--ABCContent-type: application/json
                       |content-disposition: form-data; name="email"
                       |-----""".stripMarginWithNewline(lineFeed))))
          .to[Multipart.General].failed, 1.second.dilated).getMessage shouldEqual "Illegal multipart boundary in message content"
      }
      "duplicate Content-Type header" in {
        Await.result(Unmarshal(HttpEntity(
          `multipart/form-data` withBoundary "-",
          ByteString("""---
                       |Content-type: text/plain; charset=UTF8
                       |Content-type: application/json
                       |content-disposition: form-data; name="email"
                       |
                       |test@there.com
                       |-----""".stripMarginWithNewline(lineFeed))))
          .to[Multipart.General].failed, 1.second.dilated).getMessage shouldEqual
          "multipart part must not contain more than one Content-Type header"
      }
      "a missing header-separating CRLF (in Strict entity)" in {
        Await.result(Unmarshal(HttpEntity(
          `multipart/form-data` withBoundary "-",
          ByteString("""---
                       |not good here
                       |-----""".stripMarginWithNewline(lineFeed))))
          .to[Multipart.General].failed, 1.second.dilated).getMessage shouldEqual "Illegal character ' ' in header name"
      }
      "a missing header-separating CRLF (in Default entity)" in {
        val content = """---
                        |
                        |ok
                        |---
                        |not ok
                        |-----""".stripMarginWithNewline(lineFeed)
        val byteStrings = content.map(c => ByteString(c.toString)) // one-char ByteStrings
        val contentType = `multipart/form-data` withBoundary "-"
        Await.result(Unmarshal(HttpEntity.Default(contentType, content.length, Source(byteStrings)))
          .to[Multipart.General]
          .flatMap(_ toStrict 1.second.dilated).failed, 1.second.dilated).getMessage shouldEqual "Illegal character ' ' in header name"
      }
      "a boundary with a trailing space" in {
        Await.result(
          Unmarshal(HttpEntity(`multipart/mixed` withBoundary "simple boundary ", ByteString.empty))
            .to[Multipart.General].failed, 1.second.dilated).getMessage shouldEqual
          "requirement failed: 'boundary' parameter of multipart Content-Type must not end with a space char"
      }
      "a boundary with an illegal character" in {
        Await.result(
          Unmarshal(HttpEntity(`multipart/mixed` withBoundary "simple&boundary", ByteString.empty))
            .to[Multipart.General].failed, 1.second.dilated).getMessage shouldEqual
          "requirement failed: 'boundary' parameter of multipart Content-Type contains illegal character '&'"
      }
    }

    "multipartByteRangesUnmarshaller should correctly unmarshal multipart/byteranges content with two different parts" in {
      Unmarshal(HttpEntity(
        `multipart/byteranges` withBoundary "12345",
        ByteString("""--12345
                     |Content-Range: bytes 0-2/26
                     |Content-Type: text/plain; charset=UTF-8
                     |
                     |ABC
                     |--12345
                     |Content-Range: bytes 23-25/26
                     |Content-Type: text/plain; charset=UTF-8
                     |
                     |XYZ
                     |--12345--""".stripMarginWithNewline(lineFeed)))).to[Multipart.ByteRanges] should haveParts(
        Multipart.ByteRanges.BodyPart.Strict(ContentRange(0, 2, 26), HttpEntity(ContentTypes.`text/plain(UTF-8)`, "ABC")),
        Multipart.ByteRanges.BodyPart.Strict(ContentRange(23, 25, 26), HttpEntity(ContentTypes.`text/plain(UTF-8)`, "XYZ")))
    }

    "multipartFormDataUnmarshaller should correctly unmarshal 'multipart/form-data' content" should {
      "with one element and no explicit content-type" in {
        Unmarshal(HttpEntity(
          `multipart/form-data` withBoundary "XYZABC",
          ByteString("""--XYZABC
                       |content-disposition: form-data; name=email
                       |
                       |test@there.com
                       |--XYZABC--""".stripMarginWithNewline(lineFeed)))).to[Multipart.FormData] should haveParts(
          Multipart.FormData.BodyPart.Strict("email", HttpEntity(ContentTypes.`text/plain(UTF-8)`, "test@there.com")))
      }
      "with one element" in {
        Unmarshal(HttpEntity(
          `multipart/form-data` withBoundary "XYZABC",
          ByteString("""--XYZABC
                       |content-disposition: form-data; name=email
                       |Content-Type: application/octet-stream
                       |
                       |test@there.com
                       |--XYZABC--""".stripMarginWithNewline(lineFeed)))).to[Multipart.FormData] should haveParts(
          Multipart.FormData.BodyPart.Strict("email", HttpEntity(`application/octet-stream`, ByteString("test@there.com"))))
      }
      "with one element and name value in quotes" in {
        Unmarshal(HttpEntity(
          `multipart/form-data` withBoundary "XYZABC",
          ByteString("""--XYZABC
            |content-disposition: form-data; name="email"
            |Content-Type: application/octet-stream
            |
            |test@there.com
            |--XYZABC--""".stripMarginWithNewline(lineFeed)))).to[Multipart.FormData] should haveParts(
          Multipart.FormData.BodyPart.Strict("email", HttpEntity(`application/octet-stream`, ByteString("test@there.com"))))
      }
      "with a file" in {
        Unmarshal {
          HttpEntity.Default(
            contentType = `multipart/form-data` withBoundary "XYZABC",
            contentLength = 1, // not verified during unmarshalling
            data = Source {
              List(
                ByteString {
                  """--XYZABC
                    |Content-Disposition: form-data; name="email"
                    |Content-Type: application/octet-stream
                    |
                    |test@there.com
                    |--XYZABC
                    |Content-Dispo""".stripMarginWithNewline(lineFeed)
                },
                ByteString {
                  """sition: form-data; name="userfile"; filename="test€.dat"
                    |Content-Type: application/pdf
                    |Content-Transfer-Encoding: binary
                    |Content-Additional-1: anything
                    |Content-Additional-2: really-anything
                    |
                    |filecontent
                    |--XYZABC--""".stripMarginWithNewline(lineFeed)
                })
            })
        }.to[Multipart.FormData].flatMap(_.toStrict(1.second.dilated)) should haveParts(
          Multipart.FormData.BodyPart.Strict("email", HttpEntity(`application/octet-stream`, ByteString("test@there.com"))),
          Multipart.FormData.BodyPart.Strict("userfile", HttpEntity(`application/pdf`, ByteString("filecontent")), Map("filename" -> "test€.dat"),
            List(
              RawHeader("Content-Transfer-Encoding", "binary"),
              RawHeader("Content-Additional-1", "anything"),
              RawHeader("Content-Additional-2", "really-anything")))) // verifies order of headers is preserved
      }
      // TODO: reactivate after multipart/form-data unmarshalling integrity verification is implemented
      // see https://github.com/akka/akka/issues/18908
      //
      //      "reject illegal multipart content" in {
      //        val Left(MalformedContent(msg, _)) = HttpEntity(`multipart/form-data` withBoundary "XYZABC", "--noboundary--").as[MultipartFormData]
      //        msg shouldEqual "Missing start boundary"
      //      }
      //      "reject illegal form-data content" in {
      //        val Left(MalformedContent(msg, _)) = HttpEntity(`multipart/form-data` withBoundary "XYZABC",
      //          """|--XYZABC
      //            |content-disposition: form-data; named="email"
      //            |
      //            |test@there.com
      //            |--XYZABC--""".stripMargin).as[MultipartFormData]
      //        msg shouldEqual "Illegal multipart/form-data content: unnamed body part (no Content-Disposition header or no 'name' parameter)"
      //      }
    }
  }

  def haveParts[T <: Multipart](parts: Multipart.BodyPart.Strict*): Matcher[Future[T]] =
    equal(parts).matcher[Seq[Multipart.BodyPart.Strict]] compose { x =>
      Await.result(x
        .fast.flatMap {
          _.parts
            .mapAsync(Int.MaxValue)(_ toStrict 1.second.dilated)
            .grouped(100)
            .runWith(Sink.head)
        }
        .fast.recover { case _: NoSuchElementException => Nil }, 1.second.dilated)
    }
}

class MultipartUnmarshallersSpecCRLF extends MultipartUnmarshallersSpec {
  override def lineFeed = "\r\n"
}

class MultipartUnmarshallersSpecLF extends MultipartUnmarshallersSpec {
  override def lineFeed = "\n"
}
