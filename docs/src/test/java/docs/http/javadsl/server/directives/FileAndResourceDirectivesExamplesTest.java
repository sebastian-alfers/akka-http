/*
 * Copyright (C) 2016-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl.server.directives;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.PathMatchers;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.server.directives.DirectoryRenderer;
import akka.http.javadsl.testkit.JUnitRouteTest;
import org.junit.Ignore;
import org.junit.Test;
import scala.NotImplementedError;

import static akka.http.javadsl.server.PathMatchers.segment;

//#getFromFile
import static akka.http.javadsl.server.Directives.getFromFile;
import static akka.http.javadsl.server.Directives.path;

//#getFromFile
//#getFromResource
import static akka.http.javadsl.server.Directives.getFromResource;
import static akka.http.javadsl.server.Directives.path;

//#getFromResource
//#listDirectoryContents
import akka.http.javadsl.server.Directives;

import static akka.http.javadsl.server.Directives.listDirectoryContents;
import static akka.http.javadsl.server.Directives.path;

//#listDirectoryContents
//#getFromBrowseableDirectory
import static akka.http.javadsl.server.Directives.getFromBrowseableDirectory;
import static akka.http.javadsl.server.Directives.path;

//#getFromBrowseableDirectory
//#getFromBrowseableDirectories
import static akka.http.javadsl.server.Directives.getFromBrowseableDirectories;
import static akka.http.javadsl.server.Directives.path;

//#getFromBrowseableDirectories
//#getFromDirectory
import static akka.http.javadsl.server.Directives.getFromDirectory;
import static akka.http.javadsl.server.Directives.pathPrefix;

//#getFromDirectory
//#getFromResourceDirectory
import static akka.http.javadsl.server.Directives.getFromResourceDirectory;
import static akka.http.javadsl.server.Directives.pathPrefix;

//#getFromResourceDirectory

public class FileAndResourceDirectivesExamplesTest extends JUnitRouteTest {

  @Ignore("Compile only test")
  @Test
  public void testGetFromFile() {
    //#getFromFile
    final Route route = path(PathMatchers.segment("logs").slash(segment()), name ->
      getFromFile(name + ".log")
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/logs/example"))
      .assertEntity("example file contents");
    //#getFromFile
  }

  @Ignore("Compile only test")
  @Test
  public void testGetFromResource() {
    //#getFromResource
    final Route route = path(PathMatchers.segment("logs").slash(segment()), name ->
      getFromResource(name + ".log")
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/logs/example"))
      .assertEntity("example file contents");
    //#getFromResource
  }

  @Ignore("Compile only test")
  @Test
  public void testListDirectoryContents() {
    //#listDirectoryContents
    final Route route = Directives.concat(
      path("tmp", () -> listDirectoryContents("/tmp")),
      path("custom", () -> {
        // implement your custom renderer here
        final DirectoryRenderer renderer = renderVanityFooter -> {
          throw new NotImplementedError();
        };
        return listDirectoryContents(renderer, "/tmp");
      })
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/logs/example"))
      .assertEntity("example file contents");
    //#listDirectoryContents
  }

  @Ignore("Compile only test")
  @Test
  public void testGetFromBrowseableDirectory() {
    //#getFromBrowseableDirectory
    final Route route = path("tmp", () ->
      getFromBrowseableDirectory("/tmp")
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/tmp"))
      .assertStatusCode(StatusCodes.OK);
    //#getFromBrowseableDirectory
  }

  @Ignore("Compile only test")
  @Test
  public void testGetFromBrowseableDirectories() {
    //#getFromBrowseableDirectories
    final Route route = path("tmp", () ->
      getFromBrowseableDirectories("/main", "/backups")
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/tmp"))
      .assertStatusCode(StatusCodes.OK);
    //#getFromBrowseableDirectories
  }

  @Ignore("Compile only test")
  @Test
  public void testGetFromDirectory() {
    //#getFromDirectory
    final Route route = pathPrefix("tmp", () ->
      getFromDirectory("/tmp")
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/tmp/example"))
      .assertEntity("example file contents");
    //#getFromDirectory
  }

  @Ignore("Compile only test")
  @Test
  public void testGetFromResourceDirectory() {
    //#getFromResourceDirectory
    final Route route = pathPrefix("examples", () ->
      getFromResourceDirectory("/examples")
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/examples/example-1"))
      .assertEntity("example file contents");
    //#getFromResourceDirectory
  }
}
