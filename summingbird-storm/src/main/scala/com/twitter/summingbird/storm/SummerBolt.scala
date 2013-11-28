/*
 Copyright 2013 Twitter, Inc.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package com.twitter.summingbird.storm

import com.twitter.util.{Await, Future}
import com.twitter.algebird.{Semigroup, SummingQueue}
import com.twitter.storehaus.algebra.MergeableStore

import com.twitter.summingbird.online.Externalizer
import com.twitter.summingbird.batch.{BatchID, Timestamp}
import com.twitter.summingbird.storm.option._
import com.twitter.summingbird.option.CacheSize



/**
  * The SummerBolt takes two related options: CacheSize and MaxWaitingFutures.
  * CacheSize sets the number of key-value pairs that the SinkBolt will accept
  * (and sum into an internal map) before committing out to the online store.
  *
  * To perform this commit, the SinkBolt iterates through the map of aggregated
  * kv pairs and performs a "+" on the store for each pair, sequencing these
  * "+" calls together using the Future monad. If the store has high latency,
  * these calls might take a bit of time to complete.
  *
  * MaxWaitingFutures(count) handles this problem by realizing a future
  * representing the "+" of kv-pair n only when kvpair n + 100 shows up in the bolt,
  * effectively pushing back against latency bumps in the host.
  *
  * The allowed latency before a future is forced is equal to
  * (MaxWaitingFutures * execute latency).
  *
  * @author Oscar Boykin
  * @author Sam Ritchie
  * @author Ashu Singhal
  */


class SummerBolt[Key, Value: Semigroup](
  @transient storeSupplier: () => MergeableStore[(Key,BatchID), Value],
  @transient successHandler: OnlineSuccessHandler,
  @transient exceptionHandler: OnlineExceptionHandler,
  cacheSize: CacheSize,
  metrics: SummerStormMetrics,
  maxWaitingFutures: MaxWaitingFutures,
  maxWaitingTime: MaxFutureWaitTime,
  includeSuccessHandler: IncludeSuccessHandler,
  anchor: AnchorTuples,
  shouldEmit: Boolean) extends
    AsyncBaseBolt[((Key, BatchID), Value), (Key, (Option[Value], Value))](
      metrics.metrics,
      anchor,
      maxWaitingFutures,
      maxWaitingTime,
      shouldEmit) {

  import Constants._

  val storeBox = Externalizer(storeSupplier)
  lazy val store = storeBox.get.apply

  // See MaxWaitingFutures for a todo around removing this.
  lazy val cacheCount = cacheSize.size
  lazy val buffer = SummingQueue[Map[(Key, BatchID), (List[TupleWrapper], Timestamp, Value)]](cacheCount.getOrElse(0))

  val exceptionHandlerBox = Externalizer(exceptionHandler.handlerFn.lift)
  val successHandlerBox = Externalizer(successHandler)

  var successHandlerOpt: Option[OnlineSuccessHandler] = null

  override val decoder = new KeyValueInjection[(Key,BatchID), Value](AGG_KEY, AGG_VALUE)
  override val encoder = new SingleItemInjection[(Key, (Option[Value], Value))](VALUE_FIELD)

  override def init {
    super.init
    successHandlerOpt = if (includeSuccessHandler.get) Some(successHandlerBox.get) else None
  }

  protected override def fail(inputs: List[TupleWrapper], error: Throwable): Unit = {
    super.fail(inputs, error)
    exceptionHandlerBox.get.apply(error)
    ()
  }

  override def apply(tuple: TupleWrapper,
    tsIn: (Timestamp, ((Key, BatchID), Value))):
      Future[Iterable[(List[TupleWrapper], Future[TraversableOnce[(Timestamp, (Key, (Option[Value], Value)))]])]] = {

    val (ts, (kb, v)) = tsIn
    Future.value {
      // See MaxWaitingFutures for a todo around simplifying this.
      buffer(Map(kb -> ((List(tuple.expand(1)), ts, v))))
        .map { kvs =>
          kvs.iterator.map { case ((k, batchID), (tups, stamp, delta)) =>
            (tups,
              store.merge(((k, batchID), delta)).map { before =>
                List((stamp, (k, (before, delta))))
              }
              .onSuccess { _ => successHandlerOpt.get.handlerFn.apply() }
            )
          }
          .toList // force, but order does not matter, so we could optimize this
        }
        .getOrElse(Nil)
    }
  }

  override def cleanup { Await.result(store.close) }
}
