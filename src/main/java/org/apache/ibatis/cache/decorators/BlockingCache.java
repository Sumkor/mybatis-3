/**
 *    Copyright 2009-2020 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.cache.decorators;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.CacheException;

/**
 * <p>Simple blocking decorator
 *
 * <p>Simple and inefficient version of EhCache's BlockingCache decorator.
 * It sets a lock over a cache key when the element is not found in cache.
 * This way, other threads will wait until this element is filled instead of hitting the database.
 *
 * <p>By its nature, this implementation can cause deadlock when used incorrecly.
 *
 * @author Eduardo Macarron
 *
 */
public class BlockingCache implements Cache { // 若缓存中找不到对应的 key，是否会一直 blocking，直到有对应的数据进入缓存。（不管是对 key 的写入还是访问，都是互斥的。锁的粒度是 key 级别的）

  private long timeout;
  private final Cache delegate;
  private final ConcurrentHashMap<Object, CountDownLatch> locks; // key=缓存key，value=闭锁，该闭锁可以理解为是对 key 的占位。其他占不到位的线程，只能在闭锁处等待。

  public BlockingCache(Cache delegate) {
    this.delegate = delegate;
    this.locks = new ConcurrentHashMap<>();
  }

  @Override
  public String getId() {
    return delegate.getId();
  }

  @Override
  public int getSize() {
    return delegate.getSize();
  }

  @Override
  public void putObject(Object key, Object value) {
    try {
      delegate.putObject(key, value);
    } finally {
      releaseLock(key);
    }
  }

  @Override
  public Object getObject(Object key) {
    acquireLock(key);                       // 尝试获取锁。等待直到在 locks map 中没有其他线程设置的闭锁
    Object value = delegate.getObject(key); // 来到这里，二级缓存中不一定有值，依此查询：二级缓存 -> 一级缓存 -> 数据库
    if (value != null) {
      releaseLock(key);
    }
    return value;
  }

  @Override
  public Object removeObject(Object key) {
    // despite of its name, this method is called only to release locks
    releaseLock(key);
    return null;
  }

  @Override
  public void clear() {
    delegate.clear();
  }

  private void acquireLock(Object key) { // 获取锁。目的是当 key 在二级缓存中不存在时，进入等待。
    CountDownLatch newLatch = new CountDownLatch(1);
    while (true) {
      CountDownLatch latch = locks.putIfAbsent(key, newLatch); // 不管 key 在二级缓存中存在与否，只要在 map 中不存在，当前线程就会在 map 中设置一个闭锁（之后当前线程会去二级缓存/数据库查询，在此期间，其他线程都会在闭锁处等待）
      if (latch == null) {                                     // ConcurrentHashMap 不允许 key 或 value 为 null。只有在 map 中首次加入 key 时，才会使条件 latch == null 成立
        break;                                                 // 也就是说，由 map#putIfAbsent 保证了互斥性（线程对 key 的访问是串行的！）
      }
      try { // 进入这里，说明在 map 中该 key 已存在对应的 CountDownLatch 对象，需要进行等待
        if (timeout > 0) {
          boolean acquired = latch.await(timeout, TimeUnit.MILLISECONDS);
          if (!acquired) {
            throw new CacheException(
                "Couldn't get a lock in " + timeout + " for the key " + key + " at the cache " + delegate.getId());
          }
        } else {
          latch.await();
        }
      } catch (InterruptedException e) {
        throw new CacheException("Got interrupted while trying to acquire lock for key " + key, e);
      }
    }
  }

  private void releaseLock(Object key) {
    CountDownLatch latch = locks.remove(key); // 在 map 中移除闭锁。对于 acquireLock 操作来说，就是一个释放锁的操作
    if (latch == null) {
      throw new IllegalStateException("Detected an attempt at releasing unacquired lock. This should never happen.");
    }
    latch.countDown(); // 唤醒所有在 acquireLock 中等待锁的线程
  }

  public long getTimeout() {
    return timeout;
  }

  public void setTimeout(long timeout) {
    this.timeout = timeout;
  }
}
