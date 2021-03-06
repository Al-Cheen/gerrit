// Copyright (C) 2008 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.account;

import com.google.common.base.Splitter;
import com.google.gerrit.reviewdb.client.Account;
import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/** An SSH key approved for use by an {@link Account}. */
public final class AccountSshKey {
  public static class Id implements Serializable {
    private static final long serialVersionUID = 2L;

    private Account.Id accountId;
    private int seq;

    public Id(Account.Id a, int s) {
      accountId = a;
      seq = s;
    }

    public Account.Id getParentKey() {
      return accountId;
    }

    public int get() {
      return seq;
    }

    public boolean isValid() {
      return seq > 0;
    }

    @Override
    public int hashCode() {
      return Objects.hash(accountId, seq);
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof Id)) {
        return false;
      }
      Id otherId = (Id) obj;
      return Objects.equals(accountId, otherId.accountId) && Objects.equals(seq, otherId.seq);
    }
  }

  private AccountSshKey.Id id;
  private String sshPublicKey;
  private boolean valid;

  public AccountSshKey(AccountSshKey.Id i, String pub) {
    id = i;
    sshPublicKey = pub.replace("\n", "").replace("\r", "");
    valid = id.isValid();
  }

  public Account.Id getAccount() {
    return id.accountId;
  }

  public AccountSshKey.Id getKey() {
    return id;
  }

  public String getSshPublicKey() {
    return sshPublicKey;
  }

  private String getPublicKeyPart(int index, String defaultValue) {
    String s = getSshPublicKey();
    if (s != null && s.length() > 0) {
      List<String> parts = Splitter.on(' ').splitToList(s);
      if (parts.size() > index) {
        return parts.get(index);
      }
    }
    return defaultValue;
  }

  public String getAlgorithm() {
    return getPublicKeyPart(0, "none");
  }

  public String getEncodedKey() {
    return getPublicKeyPart(1, null);
  }

  public String getComment() {
    return getPublicKeyPart(2, "");
  }

  public boolean isValid() {
    return valid && id.isValid();
  }

  public void setInvalid() {
    valid = false;
  }

  @Override
  public boolean equals(Object o) {
    if (o instanceof AccountSshKey) {
      AccountSshKey other = (AccountSshKey) o;
      return Objects.equals(id, other.id)
          && Objects.equals(sshPublicKey, other.sshPublicKey)
          && Objects.equals(valid, other.valid);
    }
    return false;
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, sshPublicKey, valid);
  }
}
