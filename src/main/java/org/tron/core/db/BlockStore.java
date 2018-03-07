/*
 * java-tron is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * java-tron is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.tron.core.db;

import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tron.core.Sha256Hash;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.exception.ValidateException;

public class BlockStore extends TronDatabase {

  public static final Logger logger = LoggerFactory.getLogger("BlockStore");
  //private LevelDbDataSourceImpl blockDbDataSource;
  //private LevelDbDataSourceImpl unSpendCache;

  //private LevelDbDataSourceImpl numHashCache;

  //private KhaosDatabase khaosDb;

  private BlockCapsule head;

  private BlockStore(String dbName) {
    super(dbName);
  }

  private static BlockStore instance;

  /**
   * create fun.
   */
  public static BlockStore create(String dbName) {
    if (instance == null) {
      synchronized (AccountStore.class) {
        if (instance == null) {
          instance = new BlockStore(dbName);
        }
      }
    }
    return instance;
  }


  /**
   * to do.
   */
  public Sha256Hash getHeadBlockId() {
    return head == null ? Sha256Hash.ZERO_HASH : head.getBlockId();
  }

  /**
   * Get the head block's number.
   */
  public long getHeadBlockNum() {
    return head == null ? 0 : head.getNum();
  }


  public DateTime getHeadBlockTime() {
    return head == null ? getGenesisTime() : new DateTime(head.getTimeStamp());
  }

  public long currentASlot() {
    return getHeadBlockNum(); // assume no missed slot
  }

  // genesis_time
  public DateTime getGenesisTime() {
    return DateTime.parse("20180101", DateTimeFormat.forPattern("yyyyMMdd"));
  }


  /**
   * push transaction into db.
   */
  public boolean pushTransactions(TransactionCapsule trx) throws ValidateException {
    logger.info("push transaction");
    if (!trx.validateSignature()) {
      throw new ValidateException("trans sig validate failed");
    }
    dbSource.putData(trx.getTransactionId().getBytes(), trx.getData());
    return true;
  }


  public void getUnspend(byte[] key) {
  }

  /**
   * resetDb the database.
   */
  public void reset() {
    dbSource.resetDb();
  }

  @Override
  public void close() {
    dbSource.closeDB();
  }

  @Override
  void add() {

  }

  @Override
  void del() {

  }

  @Override
  void fetch() {

  }
}
