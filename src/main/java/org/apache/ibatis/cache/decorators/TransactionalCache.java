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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;

/**
 * The 2nd level cache transactional buffer.
 * <p>
 * This class holds all cache entries that are to be added to the 2nd level cache during a Session.
 * Entries are sent to the cache when commit is called or discarded if the Session is rolled back.
 * Blocking cache support has been added. Therefore any get() that returns a cache miss
 * will be followed by a put() so any lock associated with the key can be released.
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class TransactionalCache implements Cache { // 对二级缓存的静态代理，作用是（可提交读）：如果事务提交，对二级缓存的操作才会生效；如果事务回滚或者不提交，则不对二级缓存产生影响。

  private static final Log log = LogFactory.getLog(TransactionalCache.class);

  private final Cache delegate;  // 二级缓存对象
  private boolean clearOnCommit; // 提交事务之前，是否清空二级缓存的标识。一般在 insert/update/delete 等要求 flushCache 的操作都会要求清空缓存，但是这里不会立即清空，只是设置标志位，等到事务提交的时候再清空缓存
  private final Map<Object, Object> entriesToAddOnCommit; // 未提交缓存，用于暂存未提交的新元素。在事务提交时，再将该集合所有元素存入二级缓存
  private final Set<Object> entriesMissedInCache;         // 未命中缓存，用于防止缓存击穿

  public TransactionalCache(Cache delegate) {
    this.delegate = delegate;
    this.clearOnCommit = false;
    this.entriesToAddOnCommit = new HashMap<>();
    this.entriesMissedInCache = new HashSet<>();
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
  public Object getObject(Object key) {
    // issue #116
    Object object = delegate.getObject(key);
    if (object == null) {
      entriesMissedInCache.add(key); // 从二级缓存查询不到，则加入未命中缓存，防止缓存击穿
    }
    // issue #146
    if (clearOnCommit) { // 这里为 true 说明当前事务中调用过 TransactionalCache#clear，已声明了对二级缓存进行清空，因此二级缓存中的数据是无效的了
      return null;       // 这里返回 null，强制从数据库查询，见 CachingExecutor#query
    } else {
      return object;
    }
  }

  @Override
  public void putObject(Object key, Object object) {
    entriesToAddOnCommit.put(key, object); // 从一级缓存或数据库中查到的数据，不直接存入【二级缓存】，而是暂存在【未提交缓存】中
  }

  @Override
  public Object removeObject(Object key) {
    return null;
  }

  @Override
  public void clear() {
    clearOnCommit = true; // 设定提交时清空二级缓存
    entriesToAddOnCommit.clear(); // 清空未提交缓存
  }

  public void commit() {
    if (clearOnCommit) { // flushCache 操作会设置 clearOnCommit 为 true，说明需要在提交事务之前，清空二级缓存
      delegate.clear();
    }
    flushPendingEntries(); // 将【未提交缓存】中的数据写入【二级缓存】
    reset();
  }

  public void rollback() {
    unlockMissedEntries();
    reset();
  }

  private void reset() { // 重置 TransactionalCache 为初始状态，便于下一次事务操作
    clearOnCommit = false;
    entriesToAddOnCommit.clear();
    entriesMissedInCache.clear();
  }

  private void flushPendingEntries() {
    for (Map.Entry<Object, Object> entry : entriesToAddOnCommit.entrySet()) { // 将暂存在【未提交缓存】中的元素，全部写入【二级缓存】
      delegate.putObject(entry.getKey(), entry.getValue());
    }
    for (Object entry : entriesMissedInCache) {
      if (!entriesToAddOnCommit.containsKey(entry)) { // 对于数据库、一级缓存、未提交缓存，都没有查询到的，同样存入二级缓存。防止缓存击穿
        delegate.putObject(entry, null);
      }
    }
  }

  private void unlockMissedEntries() { // 在【二级缓存】中，清除【未命中缓存】中的元素
    for (Object entry : entriesMissedInCache) {
      try {
        delegate.removeObject(entry);
      } catch (Exception e) {
        log.warn("Unexpected exception while notifying a rollback to the cache adapter. "
            + "Consider upgrading your cache adapter to the latest version. Cause: " + e);
      }
    }
  }

}
