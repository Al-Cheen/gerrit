// Copyright (C) 2009 The Android Open Source Project
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

package com.google.gerrit.server.mail.send;

import com.google.gerrit.common.errors.EmailException;
import com.google.gerrit.extensions.api.changes.RecipientType;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.account.ProjectWatches.NotifyType;
import com.google.gerrit.server.mail.send.ProjectWatch.Watchers;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.RefPermission;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.util.stream.StreamSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Notify interested parties of a brand new change. */
public class CreateChangeSender extends NewChangeSender {
  private static final Logger log = LoggerFactory.getLogger(CreateChangeSender.class);

  public interface Factory {
    CreateChangeSender create(Project.NameKey project, Change.Id id);
  }

  private final IdentifiedUser.GenericFactory identifiedUserFactory;
  private final PermissionBackend permissionBackend;

  @Inject
  public CreateChangeSender(
      EmailArguments ea,
      IdentifiedUser.GenericFactory identifiedUserFactory,
      PermissionBackend permissionBackend,
      @Assisted Project.NameKey project,
      @Assisted Change.Id id)
      throws OrmException {
    super(ea, newChangeData(ea, project, id));
    this.identifiedUserFactory = identifiedUserFactory;
    this.permissionBackend = permissionBackend;
  }

  @Override
  protected void init() throws EmailException {
    super.init();

    try {
      // Upgrade watching owners from CC and BCC to TO.
      Watchers matching =
          getWatchers(NotifyType.NEW_CHANGES, !change.isWorkInProgress() && !change.isPrivate());
      // TODO(hiesel): Remove special handling for owners
      StreamSupport.stream(matching.all().accounts.spliterator(), false)
          .filter(acc -> isOwnerOfProjectOrBranch(acc))
          .forEach(acc -> add(RecipientType.TO, acc));
      // Add everyone else. Owners added above will not be duplicated.
      add(RecipientType.TO, matching.to);
      add(RecipientType.CC, matching.cc);
      add(RecipientType.BCC, matching.bcc);
    } catch (OrmException err) {
      // Just don't CC everyone. Better to send a partial message to those
      // we already have queued up then to fail deliver entirely to people
      // who have a lower interest in the change.
      log.warn("Cannot notify watchers for new change", err);
    }

    includeWatchers(NotifyType.NEW_PATCHSETS, !change.isWorkInProgress() && !change.isPrivate());
  }

  private boolean isOwnerOfProjectOrBranch(Account.Id userId) {
    return permissionBackend
        .user(identifiedUserFactory.create(userId))
        .ref(change.getDest())
        .testOrFalse(RefPermission.WRITE_CONFIG);
  }
}
