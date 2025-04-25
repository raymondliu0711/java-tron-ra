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
import org.tron.protos.Protocol.AccountSetCodeAuthorization;

@Slf4j(topic = "capsule")
public class AccountSetCodeAuthorizationCapsule
    implements ProtoCapsule<AccountSetCodeAuthorization> {

  private AccountSetCodeAuthorization accountSetCodeAuthorization;

  public AccountSetCodeAuthorizationCapsule(
      AccountSetCodeAuthorization accountSetCodeAuthorization) {
    this.accountSetCodeAuthorization = accountSetCodeAuthorization;
  }

  public AccountSetCodeAuthorizationCapsule(byte[] data) throws BadItemException {
    try {
      this.accountSetCodeAuthorization = AccountSetCodeAuthorization.parseFrom(data);
    } catch (InvalidProtocolBufferException e) {
      throw new BadItemException("AccountSetCodeAuthorization proto data parse exception");
    }
  }

  public AccountSetCodeAuthorizationCapsule() {
    this.accountSetCodeAuthorization = AccountSetCodeAuthorization.newBuilder().build();
  }

  public void addNonce() {
    this.accountSetCodeAuthorization =
        this.accountSetCodeAuthorization.toBuilder().setNonce(getNonce() + 1).build();
  }

  public long getNonce() {
    return this.accountSetCodeAuthorization.getNonce();
  }

  @Override
  public byte[] getData() {
    return this.accountSetCodeAuthorization.toByteArray();
  }

  @Override
  public AccountSetCodeAuthorization getInstance() {
    return this.accountSetCodeAuthorization;
  }
}
