/*
 * Copyright (C) 2017-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.caching.scaladsl

import java.util.Optional
import java.util.concurrent.{ CompletableFuture, CompletionStage }

import akka.annotation.{ ApiMayChange, DoNotInherit }
import akka.japi.{ Creator, Procedure }

import scala.collection.immutable
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.jdk.CollectionConverters._
import scala.jdk.FutureConverters._

/**
 * API MAY CHANGE
 *
 * General interface implemented by all akka-http cache implementations.
 */
@ApiMayChange
@DoNotInherit
abstract class Cache[K, V] extends akka.http.caching.javadsl.Cache[K, V] {
  cache =>

  /**
   * Returns either the cached Future for the given key or evaluates the given value generating
   * function producing a `Future[V]`.
   */
  def apply(key: K, genValue: () => Future[V]): Future[V]

  /**
   * Returns either the cached Future for the key or evaluates the given function which
   * should lead to eventual completion of the promise.
   */
  def apply(key: K, f: Promise[V] => Unit): Future[V] =
    apply(key, () => { val p = Promise[V](); f(p); p.future })

  /**
   * Returns either the cached Future for the given key, or applies the given value loading
   * function on the key, producing a `Future[V]`.
   */
  def getOrLoad(key: K, loadValue: K => Future[V]): Future[V]

  /**
   * Returns either the cached Future for the given key or the given value as a Future
   */
  def get(key: K, block: () => V): Future[V] =
    cache.apply(key, () => Future.successful(block()))

  /**
   * Retrieves the future instance that is currently in the cache for the given key.
   * Returns None if the key has no corresponding cache entry.
   */
  def get(key: K): Option[Future[V]]
  override def getOptional(key: K): Optional[CompletionStage[V]] =
    Optional.ofNullable(get(key).map(f => f.asJava).orNull)

  /**
   * Cache the given future if not cached previously.
   * Or replace the old cached value on successful completion of given future.
   * In case the given future fails, the previously cached value for that key (if any) will remain unchanged.
   */
  def put(key: K, mayBeValue: Future[V])(implicit ex: ExecutionContext): Future[V]

  /**
   * Removes the cache item for the given key.
   */
  override def remove(key: K): Unit

  /**
   * Clears the cache by removing all entries.
   */
  override def clear(): Unit

  /**
   * Returns the set of keys in the cache, in no particular order
   * Should return in roughly constant time.
   * Note that this number might not reflect the exact keys of active, unexpired
   * cache entries, since expired entries are only evicted upon next access
   * (or by being thrown out by a capacity constraint).
   */
  def keys: immutable.Set[K]
  override def getKeys: java.util.Set[K] = keys.asJava

  final override def getFuture(key: K, genValue: Creator[CompletionStage[V]]): CompletionStage[V] =
    apply(key, () => genValue.create().asScala).asJava

  final override def getOrFulfil(key: K, f: Procedure[CompletableFuture[V]]): CompletionStage[V] =
    apply(key, promise => {
      val completableFuture = new CompletableFuture[V]
      f(completableFuture)
      promise.completeWith(completableFuture.asScala)
    }).asJava

  /**
   * Returns either the cached CompletionStage for the given key or the given value as a CompletionStage
   */
  override def getOrCreateStrict(key: K, block: Creator[V]): CompletionStage[V] =
    get(key, () => block.create()).asJava

  /**
   * Returns the upper bound for the number of currently cached entries.
   * Note that this number might not reflect the exact number of active, unexpired
   * cache entries, since expired entries are only evicted upon next access
   * (or by being thrown out by a capacity constraint).
   */
  def size(): Int
}
