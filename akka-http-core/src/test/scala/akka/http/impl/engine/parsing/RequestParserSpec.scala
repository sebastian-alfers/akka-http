/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.parsing

import akka.NotUsed

import scala.concurrent.Future
import scala.concurrent.duration._
import com.typesafe.config.{ Config, ConfigFactory }
import akka.util.ByteString
import akka.actor.ActorSystem
import akka.stream.scaladsl._
import akka.stream.TLSProtocol._
import org.scalatest.matchers.Matcher
import org.scalatest.BeforeAndAfterAll
import akka.http.scaladsl.settings.{ ParserSettings, WebSocketSettings }
import akka.http.impl.engine.parsing.ParserOutput._
import akka.http.impl.settings.WebSocketSettingsImpl
import akka.http.impl.util._
import akka.http.scaladsl.model.ContentTypes.{ NoContentType, `text/plain(UTF-8)` }
import akka.http.scaladsl.model.HttpEntity._
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.HttpProtocols._
import akka.http.scaladsl.model.MediaType.{ WithFixedCharset, WithOpenCharset }
import akka.http.scaladsl.model.MediaTypes._
import akka.http.scaladsl.model.RequestEntityAcceptance.Expected
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.settings.ParserSettings.ConflictingContentTypeHeaderProcessingMode
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.util.FastFuture._
import akka.testkit._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

abstract class RequestParserSpec(mode: String, newLine: String) extends AnyFreeSpec with Matchers with BeforeAndAfterAll {
  val testConf: Config = ConfigFactory.parseString("""
    akka.event-handlers = ["akka.testkit.TestEventListener"]
    akka.loglevel = WARNING
    akka.http.parsing.max-header-value-length = 32
    akka.http.parsing.max-uri-length = 40
    akka.http.parsing.max-content-length = infinite""")
  implicit val system: ActorSystem = ActorSystem(getClass.getSimpleName, testConf)
  import system.dispatcher

  val BOLT = HttpMethod.custom("BOLT", safe = false, idempotent = true, requestEntityAcceptance = Expected, contentLengthAllowed = true)

  s"The request parsing logic should (mode: $mode)" - {
    "properly parse a request" - {
      "with no headers and no body" in new Test {
        """GET / HTTP/1.0
          |
          |""" should parseTo(HttpRequest(protocol = `HTTP/1.0`))
        closeAfterResponseCompletion shouldEqual Seq(true)
      }

      "with no headers and no body but remaining content" in new Test {
        Seq("""GET / HTTP/1.0
          |
          |POST /foo HTTP/1.0
          |
          |TRA""") /* beginning of TRACE request */ should generalMultiParseTo(
          Right(HttpRequest(GET, "/", protocol = `HTTP/1.0`)),
          Right(HttpRequest(POST, "/foo", protocol = `HTTP/1.0`)),
          Left(MessageStartError(StatusCodes.BadRequest, ErrorInfo("Illegal HTTP message start"))))
        closeAfterResponseCompletion shouldEqual Seq(true, true)
      }

      "with one header" in new Test {
        """GET / HTTP/1.1
          |Host: example.com
          |
          |""" should parseTo(HttpRequest(headers = List(Host("example.com"))))
        closeAfterResponseCompletion shouldEqual Seq(false)
      }

      "with absolute uri in request-target" in new Test {
        """GET http://127.0.0.1:8080/hello HTTP/1.1
          |Host: 127.0.0.1:8080
          |
          |""" should parseTo(HttpRequest(uri = "http://127.0.0.1:8080/hello", headers = List(Host("127.0.0.1", 8080))))
        closeAfterResponseCompletion shouldEqual Seq(false)
      }

      "with 3 headers and a body" in new Test {
        """POST /resource/yes HTTP/1.0
          |User-Agent: curl/7.19.7 xyz
          |Connection:keep-alive
          |Content-Type: text/plain; charset=UTF-8
          |Content-length:    17
          |
          |Shake your BOODY!""" should parseTo {
          HttpRequest(POST, "/resource/yes", List(`User-Agent`("curl/7.19.7 xyz"), Connection("keep-alive")),
            "Shake your BOODY!", `HTTP/1.0`)
        }
        closeAfterResponseCompletion shouldEqual Seq(false)
      }

      "with 3 headers, a body and remaining content" in new Test {
        """POST /resource/yes HTTP/1.0
          |User-Agent: curl/7.19.7 xyz
          |Connection:keep-alive
          |Content-length:    17
          |
          |Shake your BOODY!GET / HTTP/1.0
          |
          |""" should parseTo(
          HttpRequest(POST, "/resource/yes", List(`User-Agent`("curl/7.19.7 xyz"), Connection("keep-alive")),
            "Shake your BOODY!".getBytes, `HTTP/1.0`),
          HttpRequest(protocol = `HTTP/1.0`))
        closeAfterResponseCompletion shouldEqual Seq(false, true)
      }

      "with multi-line headers" in new Test {
        """DELETE /abc HTTP/1.0
          |User-Agent: curl/7.19.7
          | abc
          |    xyz
          |Accept: */*
          |Connection: close,
          | fancy
          |
          |""" should parseTo {
          HttpRequest(DELETE, "/abc", List(`User-Agent`("curl/7.19.7 abc xyz"), Accept(MediaRanges.`*/*`),
            Connection("close", "fancy")), protocol = `HTTP/1.0`)
        }
        closeAfterResponseCompletion shouldEqual Seq(true)
      }

      "byte-by-byte" in new Test {
        prep {
          """PUT /resource/yes HTTP/1.1
            |Content-length:    4
            |Host: x
            |
            |ABCDPATCH"""
        }.toCharArray.map(_.toString).toSeq should generalRawMultiParseTo(
          Right(HttpRequest(PUT, "/resource/yes", List(Host("x")), "ABCD".getBytes)),
          Left(MessageStartError(400, ErrorInfo("Illegal HTTP message start"))))
        closeAfterResponseCompletion shouldEqual Seq(false)
      }

      "with a custom HTTP method" in new Test {
        override protected def parserSettings: ParserSettings =
          super.parserSettings.withCustomMethods(BOLT)

        """BOLT / HTTP/1.0
          |
          |""" should parseTo(HttpRequest(BOLT, "/", protocol = `HTTP/1.0`))
        closeAfterResponseCompletion shouldEqual Seq(true)
      }

      "with several identical `Content-Type` headers" in new Test {
        """GET /data HTTP/1.1
          |Host: x
          |Content-Type: application/pdf
          |Content-Type: application/pdf
          |Content-Length: 0
          |
          |""" should parseTo(HttpRequest(GET, "/data", List(Host("x")), HttpEntity.empty(`application/pdf`)))
        closeAfterResponseCompletion shouldEqual Seq(false)
      }

      "with several conflicting `Content-Type` headers with conflicting-content-type-header-processing-mode = first" in new Test {
        override def parserSettings: ParserSettings =
          super.parserSettings.withConflictingContentTypeHeaderProcessingMode(ConflictingContentTypeHeaderProcessingMode.First)
        """GET /data HTTP/1.1
          |Host: x
          |Content-Type: application/pdf
          |Content-Type: text/plain; charset=UTF-8
          |Content-Length: 0
          |
          |""" should parseTo(HttpRequest(GET, "/data", List(Host("x"), `Content-Type`(`text/plain(UTF-8)`)), HttpEntity.empty(`application/pdf`)))
        closeAfterResponseCompletion shouldEqual Seq(false)
      }

      "with several conflicting `Content-Type` headers with conflicting-content-type-header-processing-mode = last" in new Test {
        override def parserSettings: ParserSettings =
          super.parserSettings.withConflictingContentTypeHeaderProcessingMode(ConflictingContentTypeHeaderProcessingMode.Last)
        """GET /data HTTP/1.1
          |Host: x
          |Content-Type: application/pdf
          |Content-Type: text/plain; charset=UTF-8
          |Content-Length: 0
          |
          |""" should parseTo(HttpRequest(GET, "/data", List(Host("x"), `Content-Type`(`application/pdf`)), HttpEntity.empty(`text/plain(UTF-8)`)))
        closeAfterResponseCompletion shouldEqual Seq(false)
      }

      "with several conflicting `Content-Type` headers with conflicting-content-type-header-processing-mode = no-content-type" in new Test {
        override def parserSettings: ParserSettings =
          super.parserSettings.withConflictingContentTypeHeaderProcessingMode(ConflictingContentTypeHeaderProcessingMode.NoContentType)
        """GET /data HTTP/1.1
          |Host: x
          |Content-Type: application/pdf
          |Content-Type: text/plain; charset=UTF-8
          |Content-Length: 0
          |
          |""" should parseTo(HttpRequest(GET, "/data", List(Host("x"), `Content-Type`(`application/pdf`), `Content-Type`(`text/plain(UTF-8)`)), HttpEntity.empty(NoContentType)))
        closeAfterResponseCompletion shouldEqual Seq(false)
      }

      "with a request target starting with a double-slash" in new Test {
        """GET //foo HTTP/1.0
          |
          |""" should parseTo(HttpRequest(GET, Uri("http://x//foo").toHttpRequestTargetOriginForm, protocol = `HTTP/1.0`))
        closeAfterResponseCompletion shouldEqual Seq(true)
      }

      "with additional fields in Strict-Transport-Security header" in new Test {
        """GET /hsts HTTP/1.1
          |Host: x
          |Strict-Transport-Security: max-age=1; preload; dummy
          |
          |""" should parseTo(HttpRequest(
          GET,
          "/hsts",
          headers = List(Host("x"), `Strict-Transport-Security`(1, None)),
          protocol = `HTTP/1.1`))

        """GET /hsts HTTP/1.1
          |Host: x
          |Strict-Transport-Security: max-age=1; dummy; preload
          |
          |""" should parseTo(HttpRequest(
          GET,
          "/hsts",
          headers = List(Host("x"), `Strict-Transport-Security`(1, None)),
          protocol = `HTTP/1.1`))
      }
    }

    "properly parse a chunked request" - {
      val start =
        """PATCH /data HTTP/1.1
          |Transfer-Encoding: chunked
          |Connection: lalelu
          |Content-Type: application/pdf
          |Host: ping
          |
          |"""
      val baseRequest = HttpRequest(PATCH, "/data", List(Connection("lalelu"), Host("ping")))

      "request start" in new Test {
        Seq(start, "rest") should generalMultiParseTo(
          Right(baseRequest.withEntity(HttpEntity.Chunked(`application/pdf`, source()))),
          Left(EntityStreamError(ErrorInfo("Illegal character 'r' in chunk start"))))
        closeAfterResponseCompletion shouldEqual Seq(false)
      }

      "message chunk with and without extension" in new Test {
        Seq(
          start +
            """3
            |abc
            |10;some=stuff;bla
            |0123456789ABCDEF
            |""",
          "10;foo=",
          """bar
            |0123456789ABCDEF
            |A
            |0123456789""",
          """
            |0
            |
            |""") should generalMultiParseTo(
            Right(baseRequest.withEntity(Chunked(`application/pdf`, source(
              Chunk(ByteString("abc")),
              Chunk(ByteString("0123456789ABCDEF"), "some=stuff;bla"),
              Chunk(ByteString("0123456789ABCDEF"), "foo=bar"),
              Chunk(ByteString("0123456789"), ""),
              LastChunk)))))
        closeAfterResponseCompletion shouldEqual Seq(false)
      }

      "message end" in new Test {
        Seq(
          start,
          """0
            |
            |""") should generalMultiParseTo(
            Right(baseRequest.withEntity(Chunked(`application/pdf`, source(LastChunk)))))
        closeAfterResponseCompletion shouldEqual Seq(false)
      }

      "with incorrect but harmless whitespace after chunk size" in new Test {
        Seq(
          start,
          s"""|0${"  " /* explicit trailing spaces */ }
              |
              |""") should generalMultiParseTo(
            Right(baseRequest.withEntity(Chunked(`application/pdf`, source(LastChunk)))))
        closeAfterResponseCompletion shouldEqual Seq(false)
      }

      "message end with extension and trailer" in new Test {
        Seq(
          start,
          """000;nice=true
            |Foo: pip
            | apo
            |Bar: xyz
            |
            |""") should generalMultiParseTo(
            Right(baseRequest.withEntity(Chunked(
              `application/pdf`,
              source(LastChunk("nice=true", List(RawHeader("Foo", "pip apo"), RawHeader("Bar", "xyz"))))))))
        closeAfterResponseCompletion shouldEqual Seq(false)
      }

      "don't overflow the stack for large buffers of chunks" in new Test {
        override val awaitAtMost = 10000.millis.dilated

        val numChunks = 12000 // failed starting from 4000 with sbt started with `-Xss2m`
        val oneChunk = s"1${newLine}z\n"
        val manyChunks = (oneChunk * numChunks) + s"0${newLine}"

        val result = multiParse(newParser)(Seq(prep(start + manyChunks)))
        val HttpEntity.Chunked(_, chunks) = result.head.right.get.req.entity
        val strictChunks = chunks.limit(100000).runWith(Sink.seq).awaitResult(awaitAtMost)
        strictChunks.size shouldEqual numChunks
      }
    }

    "properly parse a chunked transfer encoding request" in new Test {
      """PATCH /data HTTP/1.1
        |Transfer-Encoding: chunked
        |Content-Type: application/pdf
        |Host: ping
        |
        |0
        |
        |""" should parseTo(HttpRequest(PATCH, "/data", List(Host("ping")), HttpEntity.Chunked(`application/pdf`, source(LastChunk))))
      closeAfterResponseCompletion shouldEqual Seq(false)
    }

    "support `rawRequestUriHeader` setting" in new Test {
      override protected def newParser: HttpRequestParser =
        new HttpRequestParser(parserSettings, websocketSettings, rawRequestUriHeader = true, headerParser = HttpHeaderParser(parserSettings, system.log))

      """GET /f%6f%6fbar?q=b%61z HTTP/1.1
        |Host: ping
        |Content-Type: application/pdf
        |
        |""" should parseTo(
        HttpRequest(
          GET,
          "/foobar?q=b%61z",
          List(
            `Raw-Request-URI`("/f%6f%6fbar?q=b%61z"),
            Host("ping")),
          HttpEntity.empty(`application/pdf`)))
    }

    "support custom media type parsing" in new Test {
      val `application/custom`: WithFixedCharset =
        MediaType.customWithFixedCharset("application", "custom", HttpCharsets.`UTF-8`)

      val `APPLICATION/CuStOm+JsOn`: WithFixedCharset =
        MediaType.customWithFixedCharset("APPLICATION", "CuStOm+JsOn", HttpCharsets.`UTF-8`)

      override protected def parserSettings: ParserSettings =
        super.parserSettings.withCustomMediaTypes(`application/custom`, `APPLICATION/CuStOm+JsOn`)

      """POST / HTTP/1.1
        |Host: ping
        |Content-Type: application/custom
        |Content-Length: 0
        |
        |""" should parseTo(
        HttpRequest(
          POST,
          "/",
          List(Host("ping")),
          HttpEntity.empty(`application/custom`)))

      """POST / HTTP/1.1
        |Host: ping
        |Content-Type: application/custom+json
        |Content-Length: 0
        |
        |""" should parseTo(
        HttpRequest(
          POST,
          "/",
          List(Host("ping")),
          HttpEntity.empty(`APPLICATION/CuStOm+JsOn`)))

      """POST / HTTP/1.1
        |Host: ping
        |Content-Type: APPLICATION/CUSTOM+JSON
        |Content-Length: 0
        |
        |""" should parseTo(
        HttpRequest(
          POST,
          "/",
          List(Host("ping")),
          HttpEntity.empty(`APPLICATION/CuStOm+JsOn`)))

      """POST / HTTP/1.1
        |Host: ping
        |Content-Type: application/json
        |Content-Length: 3
        |
        |123""" should parseTo(
        HttpRequest(
          POST,
          "/",
          List(Host("ping")),
          HttpEntity(ContentTypes.`application/json`, "123")))

      """POST / HTTP/1.1
        |Host: ping
        |Content-Type: text/plain; charset=UTF-8
        |Content-Length: 8
        |
        |abcdefgh""" should parseTo(
        HttpRequest(
          POST,
          "/",
          List(Host("ping")),
          HttpEntity(ContentTypes.`text/plain(UTF-8)`, "abcdefgh")))
    }

    "support custom media types that override existing media types" in new Test {
      // Override the application/json media type and give it an open instead of fixed charset.
      // This allows us to support various third-party agents which use an explicit charset.
      val openJson: WithOpenCharset =
        MediaType.customWithOpenCharset("application", "json")

      override protected def parserSettings: ParserSettings =
        super.parserSettings.withCustomMediaTypes(openJson).withMaxHeaderValueLength(64)

      """POST /abc HTTP/1.1
        |Host: ping
        |Content-Type: application/json
        |Content-Length: 0
        |
        |""" should parseTo(
        HttpRequest(
          POST,
          "/abc",
          List(Host("ping")),
          HttpEntity.empty(ContentType.WithMissingCharset(openJson))))

      """POST /def HTTP/1.1
        |Host: ping
        |Content-Type: application/json; charset=utf-8
        |Content-Length: 3
        |
        |123""" should parseTo(
        HttpRequest(
          POST,
          "/def",
          List(Host("ping")),
          HttpEntity(ContentType(openJson, HttpCharsets.`UTF-8`), "123")))

      """POST /ghi HTTP/1.1
        |Host: ping
        |Content-Type: application/json; charset=us-ascii
        |Content-Length: 8
        |
        |abcdefgh""" should parseTo(
        HttpRequest(
          POST,
          "/ghi",
          List(Host("ping")),
          HttpEntity(ContentType(openJson, HttpCharsets.`US-ASCII`), "abcdefgh")))
    }

    "reject a message chunk with" - {
      val start =
        """PATCH /data HTTP/1.1
          |Transfer-Encoding: chunked
          |Connection: lalelu
          |Host: ping
          |
          |"""
      val baseRequest = HttpRequest(PATCH, "/data", List(Connection("lalelu"), Host("ping")),
        HttpEntity.Chunked(`application/octet-stream`, source()))

      "an illegal char after chunk size" in new Test {
        Seq(
          start,
          """15_;
            |""") should generalMultiParseTo(
            Right(baseRequest),
            Left(EntityStreamError(ErrorInfo("Illegal character '_' in chunk start"))))
        closeAfterResponseCompletion shouldEqual Seq(false)
      }

      "an illegal char in chunk size" in new Test {
        Seq(start, "bla") should generalMultiParseTo(
          Right(baseRequest),
          Left(EntityStreamError(ErrorInfo("Illegal character 'l' in chunk start"))))
        closeAfterResponseCompletion shouldEqual Seq(false)
      }

      "too-long chunk extension" in new Test {
        Seq(start, "3;" + ("x" * 257)) should generalMultiParseTo(
          Right(baseRequest),
          Left(EntityStreamError(ErrorInfo("HTTP chunk extension length exceeds configured limit of 256 characters"))))
        closeAfterResponseCompletion shouldEqual Seq(false)
      }

      "too-large chunk size" in new Test {
        Seq(
          start,
          """1a2b3c4d5e
            |""") should generalMultiParseTo(
            Right(baseRequest),
            Left(EntityStreamError(ErrorInfo("HTTP chunk size exceeds the configured limit of 1048576 bytes"))))
        closeAfterResponseCompletion shouldEqual Seq(false)
      }

      "an illegal chunk termination" in new Test {
        Seq(
          start,
          """3
            |abcde""") should generalMultiParseTo(
            Right(baseRequest),
            Left(EntityStreamError(ErrorInfo("Illegal chunk termination"))))
        closeAfterResponseCompletion shouldEqual Seq(false)
      }

      "an illegal header in the trailer" in new Test {
        Seq(
          start,
          """0
            |F@oo: pip""") should generalMultiParseTo(
            Right(baseRequest),
            Left(EntityStreamError(ErrorInfo("Illegal character '@' in header name"))))
        closeAfterResponseCompletion shouldEqual Seq(false)
      }
    }

    "reject a request with" - {
      "an illegal HTTP method" in new Test {
        "get " should parseToError(NotImplemented, ErrorInfo("Unsupported HTTP method", "get"))
        "GETX " should parseToError(NotImplemented, ErrorInfo("Unsupported HTTP method", "GETX"))
      }

      "a too long HTTP method" in new Test {
        "ABCDEFGHIJKLMNOPQ " should
          parseToError(
            BadRequest,
            ErrorInfo(
              "Unsupported HTTP method",
              "HTTP method too long (started with 'ABCDEFGHIJKLMNOP'). Increase `akka.http.server.parsing.max-method-length` to support HTTP methods with more characters."))
      }

      "two Content-Length headers" in new Test {
        """GET / HTTP/1.1
          |Content-Length: 3
          |Content-Length: 4
          |
          |foo""" should parseToError(
          BadRequest,
          ErrorInfo("HTTP message must not contain more than one Content-Length header"))
      }

      "two Host headers" in new Test {
        """GET / HTTP/1.1
          |Host: api.example.com
          |Host: akka.io
          |
          |foo""" should parseToError(
          BadRequest,
          ErrorInfo("HTTP message must not contain more than one Host header"))
      }

      "a too-long URI" in new Test {
        "GET /2345678901234567890123456789012345678901 HTTP/1.1" should parseToError(
          UriTooLong,
          ErrorInfo("URI length exceeds the configured limit of 40 characters"))
      }

      "HTTP version 1.2" in new Test {
        """GET / HTTP/1.2
          |""" should parseToError(
          HttpVersionNotSupported,
          ErrorInfo("The server does not support the HTTP protocol version used in the request."))
      }

      "with an illegal char in a header name" in new Test {
        """GET / HTTP/1.1
          |User@Agent: curl/7.19.7""" should parseToError(BadRequest, ErrorInfo("Illegal character '@' in header name"))
      }

      "with a too-long header name" in new Test {
        """|GET / HTTP/1.1
          |UserxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxAgent: curl/7.19.7""" should parseToError(
          RequestHeaderFieldsTooLarge, ErrorInfo("HTTP header name exceeds the configured limit of 64 characters"))
      }

      "with a too-long header-value" in new Test {
        """|GET / HTTP/1.1
          |Fancy: 123456789012345678901234567890123""" should parseToError(
          RequestHeaderFieldsTooLarge,
          ErrorInfo("HTTP header value exceeds the configured limit of 32 characters"))
      }

      "with an invalid Content-Length header value" in new Test {
        """GET / HTTP/1.0
          |Content-Length: 1.5
          |
          |abc""" should parseToError(BadRequest, ErrorInfo("Illegal `Content-Length` header value"))
      }

      "with Content-Length > Long.MaxSize" in new Test {
        // content-length = (Long.MaxValue + 1) * 10, which is 0 when calculated overflow
        """PUT /resource/yes HTTP/1.1
          |Content-length: 92233720368547758080
          |Host: x
          |
          |""" should parseToError(400: StatusCode, ErrorInfo("`Content-Length` header value must not exceed 63-bit integer range"))
      }

      "with several conflicting `Content-Type` headers" in new Test {
        """GET /data HTTP/1.1
          |Host: x
          |Content-Type: application/pdf
          |Content-Type: text/plain; charset=UTF-8
          |Content-Length: 0
          |
          |""" should parseToError(400: StatusCode, ErrorInfo("HTTP message must not contain more than one Content-Type header"))
      }

      "with an illegal entity using CONNECT" in new Test {
        """CONNECT /resource/yes HTTP/1.1
          |Transfer-Encoding: chunked
          |Host: x
          |
          |""" should parseToError(422: StatusCode, ErrorInfo("CONNECT requests must not have an entity"))
      }
      "with an illegal entity using HEAD" in new Test {
        """HEAD /resource/yes HTTP/1.1
          |Content-length: 3
          |Host: x
          |
          |foo""" should parseToError(422: StatusCode, ErrorInfo("HEAD requests must not have an entity"))
      }
      "with an illegal entity using TRACE" in new Test {
        """TRACE /resource/yes HTTP/1.1
          |Transfer-Encoding: chunked
          |Host: x
          |
          |""" should parseToError(422: StatusCode, ErrorInfo("TRACE requests must not have an entity"))
      }

      "a request with an unsupported transfer encoding" in new Test {
        """PATCH /data HTTP/1.1
          |Transfer-Encoding: fancy
          |Content-Type: application/pdf
          |Host: ping
          |
          |0
          |
          |""" should parseToError(BadRequest, ErrorInfo("Unsupported Transfer-Encoding 'fancy'"))
      }

      "a chunked request with additional transfer encodings" in new Test {
        """PATCH /data HTTP/1.1
          |Transfer-Encoding: fancy, chunked
          |Content-Type: application/pdf
          |Host: ping
          |
          |0
          |
          |""" should parseToError(BadRequest, ErrorInfo("Multiple Transfer-Encoding entries not supported"))
      }

      "a chunked request with additional transfer encodings after chunked in multiple headers" in new Test {
        """PATCH /data HTTP/1.1
          |Transfer-Encoding: chunked
          |Transfer-Encoding: fancy
          |Content-Type: application/pdf
          |Host: ping
          |
          |0
          |
          |""" should parseToError(BadRequest, ErrorInfo("Multiple Transfer-Encoding entries not supported"))
      }

      "a chunked request with additional transfer encodings after chunked in one header" in new Test {
        """PATCH /data HTTP/1.1
          |Transfer-Encoding: chunked, fancy
          |Content-Type: application/pdf
          |Host: ping
          |
          |0
          |
          |""" should parseToError(BadRequest, ErrorInfo("Multiple Transfer-Encoding entries not supported"))
      }

      "a chunked request with a content length" in new Test {
        """PATCH /data HTTP/1.1
          |Transfer-Encoding: chunked
          |Content-Type: application/pdf
          |Content-Length: 7
          |Host: ping
          |
          |0
          |
          |""" should parseToError(BadRequest, ErrorInfo("A chunked request must not contain a Content-Length header"))
      }
    }
  }

  override def afterAll() = TestKit.shutdownActorSystem(system)

  private class Test {
    def awaitAtMost: FiniteDuration = 3.seconds.dilated
    var closeAfterResponseCompletion = Seq.empty[Boolean]

    class StrictEqualHttpRequest(val req: HttpRequest) {
      override def equals(other: scala.Any): Boolean = other match {
        case other: StrictEqualHttpRequest =>
          this.req.withEntity(HttpEntity.Empty) == other.req.withEntity(HttpEntity.Empty) &&
            this.req.entity.toStrict(awaitAtMost).awaitResult(awaitAtMost) ==
            other.req.entity.toStrict(awaitAtMost).awaitResult(awaitAtMost)
      }

      override def toString = req.toString
    }

    def strictEqualify[T](x: Either[T, HttpRequest]): Either[T, StrictEqualHttpRequest] =
      x.right.map(new StrictEqualHttpRequest(_))

    def parseTo(expected: HttpRequest*): Matcher[String] =
      multiParseTo(expected: _*).compose(_ :: Nil)

    def multiParseTo(expected: HttpRequest*): Matcher[Seq[String]] = multiParseTo(newParser, expected: _*)
    def multiParseTo(parser: HttpRequestParser, expected: HttpRequest*): Matcher[Seq[String]] =
      rawMultiParseTo(parser, expected: _*).compose(_ map prep)

    def rawMultiParseTo(expected: HttpRequest*): Matcher[Seq[String]] =
      rawMultiParseTo(newParser, expected: _*)
    def rawMultiParseTo(parser: HttpRequestParser, expected: HttpRequest*): Matcher[Seq[String]] =
      generalRawMultiParseTo(parser, expected.map(Right(_)): _*)

    def parseToError(status: StatusCode, info: ErrorInfo): Matcher[String] =
      generalMultiParseTo(Left(MessageStartError(status, info))).compose(_ :: Nil)

    def generalMultiParseTo(expected: Either[RequestOutput, HttpRequest]*): Matcher[Seq[String]] =
      generalRawMultiParseTo(expected: _*).compose(_ map prep)

    def generalRawMultiParseTo(expected: Either[RequestOutput, HttpRequest]*): Matcher[Seq[String]] =
      generalRawMultiParseTo(newParser, expected: _*)
    def generalRawMultiParseTo(
      parser:   HttpRequestParser,
      expected: Either[RequestOutput, HttpRequest]*): Matcher[Seq[String]] =
      equal(expected.map(strictEqualify))
        .matcher[Seq[Either[RequestOutput, StrictEqualHttpRequest]]] compose multiParse(parser)

    def multiParse(parser: HttpRequestParser)(input: Seq[String]): Seq[Either[RequestOutput, StrictEqualHttpRequest]] =
      Source(input.toList)
        .map(bytes => SessionBytes(TLSPlacebo.dummySession, ByteString(bytes)))
        .via(parser).named("parser")
        .splitWhen(x => x.isInstanceOf[MessageStart] || x.isInstanceOf[EntityStreamError])
        .prefixAndTail(1)
        .collect {
          case (Seq(RequestStart(method, uri, protocol, attrs, headers, createEntity, _, close)), entityParts) =>
            closeAfterResponseCompletion :+= close
            Right(HttpRequest(method, uri, headers, createEntity(entityParts), protocol))
          case (Seq(x @ (MessageStartError(_, _) | EntityStreamError(_))), rest) =>
            rest.runWith(Sink.cancelled)
            Left(x)
        }
        .concatSubstreams
        .flatMapConcat { x =>
          Source.future {
            x match {
              case Right(request) => compactEntity(request.entity).fast.map(x => Right(request.withEntity(x)))
              case Left(error)    => FastFuture.successful(Left(error))
            }
          }
        }
        .map(strictEqualify)
        .limit(100000).runWith(Sink.seq)
        .awaitResult(awaitAtMost)

    protected def parserSettings: ParserSettings = ParserSettings(system)
    protected def websocketSettings: WebSocketSettings = WebSocketSettingsImpl.serverFromRoot(system.settings.config)
    protected def newParser = new HttpRequestParser(parserSettings, websocketSettings, false, HttpHeaderParser(parserSettings, system.log))

    private def compactEntity(entity: RequestEntity): Future[RequestEntity] =
      entity match {
        case x: Chunked => compactEntityChunks(x.chunks).fast.map(compacted => x.copy(chunks = source(compacted: _*)))
        case _          => entity.toStrict(awaitAtMost)
      }

    private def compactEntityChunks(data: Source[ChunkStreamPart, Any]): Future[Seq[ChunkStreamPart]] =
      data.limit(100000).runWith(Sink.seq)
        .fast.recover { case _: NoSuchElementException => Nil }

    def prep(response: String) = response.stripMarginWithNewline(newLine)
  }

  def source[T](elems: T*): Source[T, NotUsed] = Source(elems.toList)
}

class RequestParserCRLFSpec extends RequestParserSpec("CRLF", "\r\n")

class RequestParserLFSpec extends RequestParserSpec("LF", "\n")
