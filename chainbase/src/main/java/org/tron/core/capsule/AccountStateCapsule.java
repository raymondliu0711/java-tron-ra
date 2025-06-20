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

package org.tron.core.capsule;

import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.tron.core.exception.BadItemException;
import org.tron.protos.Protocol.AccountState;

@Slf4j(topic = "capsule")
public class AccountStateCapsule implements ProtoCapsule<AccountState> {

  private AccountState accountState;

  public AccountStateCapsule(AccountState accountState) {
    this.accountState = accountState;
  }

  public AccountStateCapsule(byte[] data) throws BadItemException {
    try {
      this.accountState = AccountState.parseFrom(data);
    } catch (InvalidProtocolBufferException e) {
      throw new BadItemException("AccountState proto data parse exception");
    }
  }

  public AccountStateCapsule() {
    this.accountState = AccountState.newBuilder().build();
  }

  public void addNonce() {
    this.accountState = this.accountState.toBuilder().setNonce(getNonce() + 1).build();
  }

  public long getNonce() {
    return this.accountState.getNonce();
  }

  @Override
  public byte[] getData() {
    return this.accountState.toByteArray();
  }

  @Override
  public AccountState getInstance() {
    return this.accountState;
  }
}
