/*
 * Copyright 2014 Ben Manes. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.benmanes.caffeine.cache;

import static com.github.benmanes.caffeine.cache.testing.HasRemovalNotifications.hasRemovalNotifications;
import static com.github.benmanes.caffeine.cache.testing.HasStats.hasEvictionCount;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.Awaits;
import com.github.benmanes.caffeine.cache.BoundedLocalCache.DrainStatus;
import com.github.benmanes.caffeine.cache.Policy.Eviction;
import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExecutor;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Compute;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.MaximumSize;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.locks.NonReentrantLock;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * The test cases for the implementation details of {@link BoundedLocalCache}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class BoundedLocalCacheTest {
  final Executor executor = Executors.newCachedThreadPool(
      new ThreadFactoryBuilder().setDaemon(true).build());

  static BoundedLocalCache<Integer, Integer> asBoundedLocalCache(Cache<Integer, Integer> cache) {
    return (BoundedLocalCache<Integer, Integer>) cache.asMap();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      maximumSize = MaximumSize.UNREACHABLE, weigher = CacheWeigher.MAX_VALUE)
  public void putWeighted_noOverflow() {
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .weigher(CacheWeigher.MAX_VALUE)
        .maximumWeight(Long.MAX_VALUE)
        .build();
    BoundedLocalCache<Integer, Integer> map = asBoundedLocalCache(cache);

    cache.put(1, 1);
    map.replacement.lazySetWeightedSize(BoundedLocalCache.MAXIMUM_CAPACITY);
    cache.put(2, 2);

    assertThat(map.size(), is(1));
    assertThat(map.weightedSize(), is(BoundedLocalCache.MAXIMUM_CAPACITY));
  }

  @Test(dataProvider = "caches", expectedExceptions = RejectedExecutionException.class)
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.FULL, maximumSize = MaximumSize.FULL,
      executor = CacheExecutor.REJECTING, removalListener = Listener.CONSUMING)
  public void evict_rejected(Cache<Integer, Integer> cache, CacheContext context) {
    cache.put(context.absentKey(), context.absentValue());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, maximumSize = MaximumSize.ONE)
  public void evict_alreadyRemoved(Cache<Integer, Integer> cache, CacheContext context) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    Entry<Integer, Integer> oldEntry = Iterables.get(context.absent().entrySet(), 0);
    Entry<Integer, Integer> newEntry = Iterables.get(context.absent().entrySet(), 1);

    localCache.put(oldEntry.getKey(), oldEntry.getValue());
    localCache.replacement.evictionLock().lock();
    try {
      Object keyRef = localCache.nodeFactory.newLookupKey(oldEntry.getKey());
      Node<Integer, Integer> node = localCache.data.get(keyRef);
      checkStatus(localCache, node, Status.ALIVE);
      new Thread(() -> {
        localCache.put(newEntry.getKey(), newEntry.getValue());
        assertThat(localCache.remove(oldEntry.getKey()), is(oldEntry.getValue()));
      }).start();
      Awaits.await().until(() -> localCache.containsKey(oldEntry.getKey()), is(false));
      checkStatus(localCache, node, Status.RETIRED);
      localCache.drainBuffers();

      checkStatus(localCache, node, Status.DEAD);
      assertThat(localCache.containsKey(newEntry.getKey()), is(true));
      assertThat(cache, hasRemovalNotifications(context, 1, RemovalCause.EXPLICIT));
    } finally {
      localCache.replacement.evictionLock().unlock();
    }
  }

  enum Status { ALIVE, RETIRED, DEAD }

  static void checkStatus(BoundedLocalCache<Integer, Integer> localCache,
      Node<Integer, Integer> node, Status expected) {
    assertThat(node.isAlive(), is(expected == Status.ALIVE));
    assertThat(node.isRetired(), is(expected == Status.RETIRED));
    assertThat(node.isDead(), is(expected == Status.DEAD));

    if (node.isDead()) {
      node.makeRetired();
      assertThat(node.isRetired(), is(false));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, maximumSize = MaximumSize.TEN, weigher = CacheWeigher.DEFAULT)
  public void evict_lru(Cache<Integer, Integer> cache, CacheContext context) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    for (int i = 0; i < 10; i++) {
      cache.put(i, -i);
    }

    checkContainsInOrder(localCache, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9);

    // re-order
    checkReorder(localCache, asList(0, 1, 2), 3, 4, 5, 6, 7, 8, 9, 0, 1, 2);

    // evict 3, 4, 5
    checkEvict(localCache, asList(10, 11, 12), 6, 7, 8, 9, 0, 1, 2, 10, 11, 12);

    // re-order
    checkReorder(localCache, asList(6, 7, 8), 9, 0, 1, 2, 10, 11, 12, 6, 7, 8);

    // evict 9, 0, 1
    checkEvict(localCache, asList(13, 14, 15), 2, 10, 11, 12, 6, 7, 8, 13, 14, 15);

    assertThat(context, hasEvictionCount(6));
  }

  private void checkReorder(BoundedLocalCache<Integer, Integer> localCache,
      List<Integer> keys, Integer... expect) {
    keys.forEach(localCache::get);
    checkContainsInOrder(localCache, expect);
  }

  private void checkEvict(BoundedLocalCache<Integer, Integer> localCache,
      List<Integer> keys, Integer... expect) {
    keys.forEach(i -> localCache.put(i, i));
    checkContainsInOrder(localCache, expect);
  }

  private void checkContainsInOrder(BoundedLocalCache<Integer, Integer> localCache,
      Integer... expect) {
    localCache.drainBuffers();
    List<Integer> evictionList = Lists.newArrayList();
    localCache.replacement.getAccessOrderDeque().forEach(
        node -> evictionList.add(node.getKey()));
    assertThat(localCache.size(), is(equalTo(expect.length)));
    assertThat(localCache.keySet(), containsInAnyOrder(expect));
    assertThat(evictionList, is(equalTo(asList(expect))));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.FULL, maximumSize = MaximumSize.FULL)
  public void updateRecency_onGet(Cache<Integer, Integer> cache) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    Node<Integer, Integer> first = localCache.replacement.getAccessOrderDeque().peek();
    updateRecency(localCache, () -> localCache.get(first.getKey()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.FULL, maximumSize = MaximumSize.FULL)
  public void updateRecency_onGetQuietly(Cache<Integer, Integer> cache) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    int index = BoundedLocalCache.readBufferIndex();
    AtomicLong drainCounter = localCache.replacement.readBufferDrainAtWriteCount()[index];

    Node<Integer, Integer> first = localCache.replacement.getAccessOrderDeque().peek();
    Node<Integer, Integer> last = localCache.replacement.getAccessOrderDeque().peekLast();
    long drained = drainCounter.get();
    localCache.drainBuffers();

    assertThat(localCache.replacement.getAccessOrderDeque().peekFirst(), is(first));
    assertThat(localCache.replacement.getAccessOrderDeque().peekLast(), is(last));
    assertThat(drainCounter.get(), is(drained));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.FULL, maximumSize = MaximumSize.FULL)
  public void updateRecency_onPutIfAbsent(Cache<Integer, Integer> cache) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    Node<Integer, Integer> first = localCache.replacement.getAccessOrderDeque().peek();
    updateRecency(localCache, () -> localCache.putIfAbsent(first.getKey(), first.getKey()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.FULL, maximumSize = MaximumSize.FULL)
  public void updateRecency_onPut(Cache<Integer, Integer> cache) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    Node<Integer, Integer> first = localCache.replacement.getAccessOrderDeque().peek();
    updateRecency(localCache, () -> localCache.put(first.getKey(), first.getKey()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.FULL, maximumSize = MaximumSize.FULL)
  public void updateRecency_onReplace(Cache<Integer, Integer> cache) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    Node<Integer, Integer> first = localCache.replacement.getAccessOrderDeque().peek();
    updateRecency(localCache, () -> localCache.replace(first.getKey(), first.getKey()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.FULL, maximumSize = MaximumSize.FULL)
  public void updateRecency_onReplaceConditionally(
      Cache<Integer, Integer> cache, CacheContext context) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    Node<Integer, Integer> first = localCache.replacement.getAccessOrderDeque().peek();
    Integer key = first.getKey();
    Integer value = context.original().get(key);

    updateRecency(localCache, () -> localCache.replace(first.getKey(), value, value));
  }

  private void updateRecency(BoundedLocalCache<Integer, Integer> cache, Runnable operation) {
    Node<Integer, Integer> first = cache.replacement.getAccessOrderDeque().peek();

    operation.run();
    cache.drainBuffers();

    assertThat(cache.replacement.getAccessOrderDeque().peekFirst(), is(not(first)));
    assertThat(cache.replacement.getAccessOrderDeque().peekLast(), is(first));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, maximumSize = MaximumSize.FULL)
  public void exceedsMaximumBufferSize_onRead(Cache<Integer, Integer> cache) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    Node<Integer, Integer> dummy = localCache.nodeFactory.newNode(null, null, null, 1, 0);

    int index = BoundedLocalCache.readBufferIndex();
    AtomicLong drainCounter = localCache.replacement.readBufferDrainAtWriteCount()[index];
    localCache.replacement.readBufferWriteCount()[index].set(
        BoundedLocalCache.READ_BUFFER_THRESHOLD - 1);

    localCache.afterRead(dummy, true);
    assertThat(drainCounter.get(), is(0L));

    localCache.afterRead(dummy, true);
    assertThat(drainCounter.get(), is(BoundedLocalCache.READ_BUFFER_THRESHOLD + 1L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, maximumSize = MaximumSize.FULL)
  public void exceedsMaximumBufferSize_onWrite(Cache<Integer, Integer> cache) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    Node<Integer, Integer> dummy = localCache.nodeFactory.newNode(null, null, null, 1, 0);

    boolean[] ran = new boolean[1];
    localCache.afterWrite(dummy, () -> ran[0] = true);
    assertThat(ran[0], is(true));

    assertThat(localCache.replacement.writeBuffer(), hasSize(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.FULL, maximumSize = MaximumSize.FULL)
  public void drain_onRead(Cache<Integer, Integer> cache, CacheContext context) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);

    int index = BoundedLocalCache.readBufferIndex();
    AtomicReference<Node<Integer, Integer>>[] buffer = localCache.replacement.readBuffers()[index];
    AtomicLong writeCounter = localCache.replacement.readBufferWriteCount()[index];

    for (int i = 0; i < BoundedLocalCache.READ_BUFFER_THRESHOLD; i++) {
      localCache.get(context.firstKey());
    }

    int pending = 0;
    for (AtomicReference<?> slot : buffer) {
      if (slot.get() != null) {
        pending++;
      }
    }
    assertThat(pending, is(equalTo(BoundedLocalCache.READ_BUFFER_THRESHOLD)));
    assertThat((int) writeCounter.get(), is(equalTo(pending)));

    localCache.get(context.firstKey());
    assertThat(localCache.replacement.readBufferReadCount()[index], is(equalTo(writeCounter.get())));
    for (int i = 0; i < localCache.replacement.readBuffers().length; i++) {
      assertThat(localCache.replacement.readBuffers()[index][i].get(), is(nullValue()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, maximumSize = MaximumSize.FULL)
  public void drain_onWrite(Cache<Integer, Integer> cache) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    cache.put(1, 1);
    assertThat(localCache.replacement.writeBuffer(), hasSize(0));
    assertThat(localCache.replacement.getAccessOrderDeque(), hasSize(1));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, maximumSize = MaximumSize.FULL)
  public void drain_nonblocking(Cache<Integer, Integer> cache) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    AtomicBoolean done = new AtomicBoolean();
    Thread thread = new Thread(() -> {
      localCache.replacement.lazySetDrainStatus(DrainStatus.REQUIRED);
      localCache.tryToDrainBuffers();
      done.set(true);
    });
    localCache.replacement.evictionLock().lock();
    try {
      thread.start();
      Awaits.await().untilTrue(done);
    } finally {
      localCache.replacement.evictionLock().unlock();
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, maximumSize = MaximumSize.FULL)
  public void drain_blocksClear(Cache<Integer, Integer> cache) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    checkDrainBlocks(localCache, () -> localCache.clear());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, maximumSize = MaximumSize.FULL)
  public void drain_blocksOrderedMap(Cache<Integer, Integer> cache,
      CacheContext context, Eviction<Integer, Integer> eviction) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    checkDrainBlocks(localCache, () -> eviction.coldest(((int) context.maximumSize())));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, maximumSize = MaximumSize.FULL)
  public void drain_blocksCapacity(Cache<Integer, Integer> cache, CacheContext context) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    checkDrainBlocks(localCache, () -> localCache.setCapacity(0));
  }

  void checkDrainBlocks(BoundedLocalCache<Integer, Integer> localCache, Runnable task) {
    NonReentrantLock lock = localCache.replacement.evictionLock();
    AtomicBoolean done = new AtomicBoolean();
    lock.lock();
    try {
      executor.execute(() -> {
        localCache.replacement.lazySetDrainStatus(DrainStatus.REQUIRED);
        task.run();
        done.set(true);
      });
      Awaits.await().until(() -> lock.hasQueuedThreads());
    } finally {
      lock.unlock();
    }
    Awaits.await().untilTrue(done);
  }
}
