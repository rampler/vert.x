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

package io.vertx.core;

import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.impl.VertxInternal;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import io.vertx.test.core.AsyncTestBase;
import org.junit.Test;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class VertxTest extends AsyncTestBase {

  private final org.openjdk.jmh.runner.Runner RUNNER = new Runner(new OptionsBuilder().shouldDoGC(true).build());

  @Test
  public void testCloseHooksCalled() throws Exception {
    AtomicInteger closedCount = new AtomicInteger();
    Closeable myCloseable1 = completionHandler -> {
      closedCount.incrementAndGet();
      completionHandler.handle(Future.succeededFuture());
    };
    Closeable myCloseable2 = completionHandler -> {
      closedCount.incrementAndGet();
      completionHandler.handle(Future.succeededFuture());
    };
    VertxInternal vertx = (VertxInternal) Vertx.vertx();
    vertx.addCloseHook(myCloseable1);
    vertx.addCloseHook(myCloseable2);
    // Now undeploy
    vertx.close(ar -> {
      assertTrue(ar.succeeded());
      assertEquals(2, closedCount.get());
      testComplete();
    });
    await();
  }

  @Test
  public void testCloseHookFailure1() {
    AtomicInteger closedCount = new AtomicInteger();
    class Hook implements Closeable {
      @Override
      public void close(Promise<Void> completion) {
        if (closedCount.incrementAndGet() == 1) {
          throw new RuntimeException();
        } else {
          completion.handle(Future.succeededFuture());
        }
      }
    }
    VertxInternal vertx = (VertxInternal) Vertx.vertx();
    vertx.addCloseHook(new Hook());
    vertx.addCloseHook(new Hook());
    // Now undeploy
    vertx.close(ar -> {
      assertTrue(ar.succeeded());
      assertEquals(2, closedCount.get());
      testComplete();
    });
    await();
  }

  @Test
  public void testCloseHookFailure2() throws Exception {
    AtomicInteger closedCount = new AtomicInteger();
    class Hook implements Closeable {
      @Override
      public void close(Promise<Void> completion) {
        if (closedCount.incrementAndGet() == 1) {
          completion.handle(Future.succeededFuture());
          throw new RuntimeException();
        } else {
          completion.handle(Future.succeededFuture());
        }
      }
    }
    VertxInternal vertx = (VertxInternal) Vertx.vertx();
    vertx.addCloseHook(new Hook());
    vertx.addCloseHook(new Hook());
    // Now undeploy
    vertx.close(ar -> {
      assertTrue(ar.succeeded());
      assertEquals(2, closedCount.get());
      testComplete();
    });
    await();
  }

  @Test
  public void testCloseFuture() {
    Vertx vertx = Vertx.vertx();
    Future<Void> fut = vertx.close();
    // Check that we can get a callback on the future as thread pools are closed by the operation
    fut.onComplete(onSuccess(v -> {
      testComplete();
    }));
    await();
  }

  @Test
  public void testFinalizeHttpClient() throws Exception {
    Vertx vertx = Vertx.vertx();
    try {
      CountDownLatch latch = new CountDownLatch(1);
      AtomicReference<NetSocket> socketRef = new AtomicReference<>();
      vertx.createNetServer()
        .connectHandler(socketRef::set)
        .listen(8080, "localhost")
        .onComplete(onSuccess(server -> latch.countDown()));
      awaitLatch(latch);
      AtomicBoolean closed = new AtomicBoolean();
      // No keep alive so the connection is not held in the pool ????
      HttpClient client = vertx.createHttpClient(new HttpClientOptions().setKeepAlive(false));
      client.closeFuture().onComplete(ar -> closed.set(true));
      client.get(8080, "localhost", "/", onFailure(err -> {}));
      WeakReference<HttpClient> ref = new WeakReference<>(client);
      client = null;
      assertWaitUntil(() -> socketRef.get() != null);
      for (int i = 0;i < 10;i++) {
        Thread.sleep(10);
        RUNNER.runSystemGC();
        assertFalse(closed.get());
        assertNotNull(ref.get());
      }
      socketRef.get().close();
      long now = System.currentTimeMillis();
      while (true) {
        assertTrue(System.currentTimeMillis() - now < 20_000);
        RUNNER.runSystemGC();
        if (ref.get() == null) {
          assertTrue(closed.get());
          break;
        }
      }
    } finally {
      vertx.close(ar -> {
        testComplete();
      });
    }
    await();
  }

  @Test
  public void testFinalizeNetClient() throws Exception {
    Vertx vertx = Vertx.vertx();
    try {
      CountDownLatch latch = new CountDownLatch(1);
      AtomicReference<NetSocket> socketRef = new AtomicReference<>();
      vertx.createNetServer()
        .connectHandler(socketRef::set)
        .listen(1234, "localhost")
        .onComplete(onSuccess(server -> latch.countDown()));
      awaitLatch(latch);
      AtomicBoolean closed = new AtomicBoolean();
      NetClient client = vertx.createNetClient();
      client.closeFuture().onComplete(ar -> closed.set(true));
      client.connect(1234, "localhost", onSuccess(so -> {}));
      WeakReference<NetClient> ref = new WeakReference<>(client);
      client = null;
      assertWaitUntil(() -> socketRef.get() != null);
      for (int i = 0;i < 10;i++) {
        Thread.sleep(10);
        RUNNER.runSystemGC();
        assertFalse(closed.get());
        assertNotNull(ref.get());
      }
      socketRef.get().close();
      long now = System.currentTimeMillis();
      while (true) {
        assertTrue(System.currentTimeMillis() - now < 20_000);
        RUNNER.runSystemGC();
        if (ref.get() == null) {
          assertTrue(closed.get());
          break;
        }
      }
    } finally {
      vertx.close(ar -> {
        testComplete();
      });
    }
    await();
  }
}
