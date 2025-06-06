/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import akka.http.javadsl.model.Uri;

/**
 *  Model for the `Referer` header.
 *  Specification: http://tools.ietf.org/html/rfc7231#section-5.5.2
 */
public abstract class Referer extends akka.http.scaladsl.model.HttpHeader {
    public abstract Uri getUri();

    public static Referer create(Uri uri) {
        return new akka.http.scaladsl.model.headers.Referer(uri.asScala());
    }
}
