package io.camunda.zeebe.client.api;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;

/** The result of send(): join() waits for it. Same shape as the Zeebe client's ZeebeFuture. */
public interface ZeebeFuture<T> extends Future<T>, CompletionStage<T> {
    T join();
}
