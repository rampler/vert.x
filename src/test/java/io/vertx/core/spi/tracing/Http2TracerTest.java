/*
 * Copyright (c) 2011-2019 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package io.vertx.core.spi.tracing;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Ignore;
import org.junit.Test;

import io.vertx.core.http.Http2TestBase;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.tracing.TracingPolicy;
import io.vertx.test.faketracer.FakeTracer;

public class Http2TracerTest extends HttpTracerTestBase {

  private static final String SPAN_KIND_SERVER = "server";
  private static final String SPAN_KIND_CLIENT = "client";
  private static final String SPAN_KIND_KEY = "span_kind";

  @Override
  protected HttpServerOptions createBaseServerOptions() {
    return Http2TestBase.createHttp2ServerOptions(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST);
  }

  @Override
  protected HttpClientOptions createBaseClientOptions() {
    return Http2TestBase.createHttp2ClientOptions();
  }

  @Test
  public void testTracingWorksAfterUpgrading() throws Exception {
    client.close();
    FakeTracer fakeTracer = new FakeTracer();
    setTracer(fakeTracer);

    client = vertx.createHttpClient(new HttpClientOptions()
      // setting policy always, if not the span with kind client is not sent
      .setTracingPolicy(TracingPolicy.ALWAYS)
      .setProtocolVersion(HttpVersion.HTTP_2));
    // Server always return "Ok"
    server = vertx.createHttpServer();
    server.requestHandler(req -> {
      req.response().end("Ok");
    });
    startServer(testAddress);
    CountDownLatch finished = new CountDownLatch(1);
    waitFor(1);
    client.request(requestOptions).onSuccess(request -> {
      request.connection().closeHandler((v) -> {
        finished.countDown();
      });
      request.send().onSuccess(response -> {
        complete();
      });
    });
    await();
    finished.await(5, TimeUnit.SECONDS);
    // There should be 2 spans: 1 of kind server and 1 of kind client.
    assertEquals(2, fakeTracer.getFinishedSpans().size());
    assertTrue("Span with kind server was not found!",
      fakeTracer.getFinishedSpans().stream().anyMatch(s -> SPAN_KIND_SERVER.equals(s.getTags().get(SPAN_KIND_KEY))));
    assertTrue("Span with kind client was not found!",
      fakeTracer.getFinishedSpans().stream().anyMatch(s -> SPAN_KIND_CLIENT.equals(s.getTags().get(SPAN_KIND_KEY))));
  }
}
