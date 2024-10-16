/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl;

import akka.actor.ActorSystem;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.model.HttpCharsets;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.MediaType;
import akka.http.javadsl.model.MediaTypes;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.settings.ParserSettings;
import akka.http.javadsl.settings.ServerSettings;
import akka.http.javadsl.testkit.JUnitRouteTest;
import org.junit.Test;

import java.util.HashMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertEquals;
import static akka.util.ByteString.emptyByteString;

//#application-custom-java
import static akka.http.javadsl.server.Directives.complete;
import static akka.http.javadsl.server.Directives.extractRequest;

//#application-custom-java

public class CustomMediaTypesExampleTest extends JUnitRouteTest {

  @Test
  public void customMediaTypes() throws ExecutionException, InterruptedException {

    final ActorSystem system = system();
    final String host = "127.0.0.1";

    //#application-custom-java
    // Define custom media type:
    final MediaType.WithFixedCharset applicationCustom =
      MediaTypes.customWithFixedCharset("application", "custom", // The new Media Type name
        HttpCharsets.UTF_8, // The charset used
        new HashMap<>(), // Empty parameters
        false); // No arbitrary subtypes are allowed

    // Add custom media type to parser settings:
    final ParserSettings parserSettings = ParserSettings.forServer(system)
      .withCustomMediaTypes(applicationCustom);
    final ServerSettings serverSettings = ServerSettings.create(system)
      .withParserSettings(parserSettings);

    final Route route = extractRequest(req ->
      complete(req.entity().getContentType().toString() + " = "
        + req.entity().getContentType().getClass())
    );

    final CompletionStage<ServerBinding> binding =
      Http.get(system)
        .newServerAt(host, 0)
        .withSettings(serverSettings)
        .bind(route);

    //#application-custom-java
    final ServerBinding serverBinding = binding.toCompletableFuture().get();

    final int port = serverBinding.localAddress().getPort();

    final HttpResponse response = Http.get(system)
      .singleRequest(HttpRequest
        .GET("http://" + host + ":" + port + "/")
        .withEntity(applicationCustom.toContentType(), "~~example~=~value~~"))
      .toCompletableFuture()
      .get();

    assertEquals(StatusCodes.OK, response.status());
    final String body = response.entity().toStrict(1000, system).toCompletableFuture().get()
      .getDataBytes().runFold(emptyByteString(), (a, b) -> a.$plus$plus(b), system)
      .toCompletableFuture().get().utf8String();
    assertEquals("application/custom = class akka.http.scaladsl.model.ContentType$WithFixedCharset", body); // it's the Scala DSL package because it's the only instance of the Java DSL
  }

}
