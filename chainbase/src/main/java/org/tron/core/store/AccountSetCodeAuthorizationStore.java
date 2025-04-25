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

package org.tron.core.store;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.tron.core.capsule.AccountSetCodeAuthorizationCapsule;
import org.tron.core.db.TronStoreWithRevoking;

@Slf4j(topic = "DB")
@Component
public class AccountSetCodeAuthorizationStore
    extends TronStoreWithRevoking<AccountSetCodeAuthorizationCapsule> {

  @Autowired
  private AccountSetCodeAuthorizationStore(@Value("account-set-code-authorization") String dbName) {
    super(dbName);
  }

  @Override
  public AccountSetCodeAuthorizationCapsule get(byte[] key) {
    return getUnchecked(key);
  }
}
