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
package org.apache.ibatis.session;

import java.sql.Connection;

/**
 * @author Clinton Begin
 */
public enum TransactionIsolationLevel { // 事务隔离级别枚举：NONE、READ_UNCOMMITTED、READ_COMMITTED、REPEATABLE_READ 和 SERIALIZABLE
  NONE(Connection.TRANSACTION_NONE),
  READ_COMMITTED(Connection.TRANSACTION_READ_COMMITTED),     // 提交读。只能读取到已经提交的数据。Oracle等多数数据库默认都是该级别（不重复读。因为其他事务提交前后，当前事务读取到的数据可能不一致）
  READ_UNCOMMITTED(Connection.TRANSACTION_READ_UNCOMMITTED), // 未提交读。允许脏读，也就是可能读取到其他会话中未提交事务修改的数据。
  REPEATABLE_READ(Connection.TRANSACTION_REPEATABLE_READ),   // 可重复读。在同一个事务内的查询都是事务开始时刻一致的。MySQL InnoDB默认的事务隔离级别（在SQL标准中，该隔离级别消除了不可重复读，但是还存在幻象读，但是innoDB解决了幻读）
  SERIALIZABLE(Connection.TRANSACTION_SERIALIZABLE),         // 串行读。完全串行化的读，每次读都需要获得表级共享锁，读写相互都会阻塞。
  /**
   * A non-standard isolation level for Microsoft SQL Server.
   * Defined in the SQL Server JDBC driver {@link com.microsoft.sqlserver.jdbc.ISQLServerConnection}
   *
   * @since 3.5.6
   */
  SQL_SERVER_SNAPSHOT(0x1000);

  private final int level;

  TransactionIsolationLevel(int level) {
    this.level = level;
  }

  public int getLevel() {
    return level;
  }
}
