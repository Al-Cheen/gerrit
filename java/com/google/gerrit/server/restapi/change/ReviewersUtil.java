// Copyright (C) 2016 The Android Open Source Project
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

package com.google.gerrit.server.restapi.change;

import static java.util.stream.Collectors.toList;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.gerrit.common.data.GroupReference;
import com.google.gerrit.extensions.common.GroupBaseInfo;
import com.google.gerrit.extensions.common.SuggestedReviewerInfo;
import com.google.gerrit.extensions.restapi.Url;
import com.google.gerrit.index.IndexConfig;
import com.google.gerrit.index.QueryOptions;
import com.google.gerrit.index.query.FieldBundle;
import com.google.gerrit.index.query.Predicate;
import com.google.gerrit.index.query.QueryParseException;
import com.google.gerrit.metrics.Description;
import com.google.gerrit.metrics.Description.Units;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.metrics.Timer0;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.account.AccountControl;
import com.google.gerrit.server.account.AccountDirectory.FillOptions;
import com.google.gerrit.server.account.AccountLoader;
import com.google.gerrit.server.account.GroupBackend;
import com.google.gerrit.server.account.GroupMembers;
import com.google.gerrit.server.index.account.AccountField;
import com.google.gerrit.server.index.account.AccountIndexCollection;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.permissions.GlobalPermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.query.account.AccountPredicates;
import com.google.gerrit.server.query.account.AccountQueryBuilder;
import com.google.gwtorm.server.OrmException;
import com.google.gwtorm.server.ResultSet;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReviewersUtil {
  @Singleton
  private static class Metrics {
    final Timer0 queryAccountsLatency;
    final Timer0 recommendAccountsLatency;
    final Timer0 loadAccountsLatency;
    final Timer0 queryGroupsLatency;
    final Timer0 filterVisibility;

    @Inject
    Metrics(MetricMaker metricMaker) {
      queryAccountsLatency =
          metricMaker.newTimer(
              "reviewer_suggestion/query_accounts",
              new Description("Latency for querying accounts for reviewer suggestion")
                  .setCumulative()
                  .setUnit(Units.MILLISECONDS));
      recommendAccountsLatency =
          metricMaker.newTimer(
              "reviewer_suggestion/recommend_accounts",
              new Description("Latency for recommending accounts for reviewer suggestion")
                  .setCumulative()
                  .setUnit(Units.MILLISECONDS));
      loadAccountsLatency =
          metricMaker.newTimer(
              "reviewer_suggestion/load_accounts",
              new Description("Latency for loading accounts for reviewer suggestion")
                  .setCumulative()
                  .setUnit(Units.MILLISECONDS));
      queryGroupsLatency =
          metricMaker.newTimer(
              "reviewer_suggestion/query_groups",
              new Description("Latency for querying groups for reviewer suggestion")
                  .setCumulative()
                  .setUnit(Units.MILLISECONDS));
      filterVisibility =
          metricMaker.newTimer(
              "reviewer_suggestion/filter_visibility",
              new Description("Latency for removing users that can't see the change")
                  .setCumulative()
                  .setUnit(Units.MILLISECONDS));
    }
  }

  private static final Logger log = LoggerFactory.getLogger(ReviewersUtil.class);

  // Generate a candidate list at 2x the size of what the user wants to see to
  // give the ranking algorithm a good set of candidates it can work with
  private static final int CANDIDATE_LIST_MULTIPLIER = 2;

  private final AccountLoader.Factory accountLoaderFactory;
  private final AccountQueryBuilder accountQueryBuilder;
  private final GroupBackend groupBackend;
  private final GroupMembers groupMembers;
  private final ReviewerRecommender reviewerRecommender;
  private final Metrics metrics;
  private final AccountIndexCollection accountIndexes;
  private final IndexConfig indexConfig;
  private final AccountControl.Factory accountControlFactory;
  private final Provider<CurrentUser> self;
  private final PermissionBackend permissionBackend;

  @Inject
  ReviewersUtil(
      AccountLoader.Factory accountLoaderFactory,
      AccountQueryBuilder accountQueryBuilder,
      GroupBackend groupBackend,
      GroupMembers groupMembers,
      ReviewerRecommender reviewerRecommender,
      Metrics metrics,
      AccountIndexCollection accountIndexes,
      IndexConfig indexConfig,
      AccountControl.Factory accountControlFactory,
      Provider<CurrentUser> self,
      PermissionBackend permissionBackend) {
    this.accountLoaderFactory = accountLoaderFactory;
    this.accountQueryBuilder = accountQueryBuilder;
    this.groupBackend = groupBackend;
    this.groupMembers = groupMembers;
    this.reviewerRecommender = reviewerRecommender;
    this.metrics = metrics;
    this.accountIndexes = accountIndexes;
    this.indexConfig = indexConfig;
    this.accountControlFactory = accountControlFactory;
    this.self = self;
    this.permissionBackend = permissionBackend;
  }

  public interface VisibilityControl {
    boolean isVisibleTo(Account.Id account) throws OrmException;
  }

  public List<SuggestedReviewerInfo> suggestReviewers(
      ChangeNotes changeNotes,
      SuggestReviewers suggestReviewers,
      ProjectState projectState,
      VisibilityControl visibilityControl,
      boolean excludeGroups)
      throws IOException, OrmException, ConfigInvalidException, PermissionBackendException {
    CurrentUser currentUser = self.get();
    log.debug(
        "Suggesting reviewers for change {} to user {}.",
        changeNotes.getChangeId().get(),
        currentUser.getLoggableName());
    String query = suggestReviewers.getQuery();
    log.debug("Query: {}", query);
    int limit = suggestReviewers.getLimit();

    if (!suggestReviewers.getSuggestAccounts()) {
      log.debug("Reviewer suggestion is disabled.");
      return Collections.emptyList();
    }

    List<Account.Id> candidateList = new ArrayList<>();
    if (!Strings.isNullOrEmpty(query)) {
      candidateList = suggestAccounts(suggestReviewers);
      log.debug("Candidate list: {}", candidateList);
    }

    List<Account.Id> sortedRecommendations =
        recommendAccounts(changeNotes, suggestReviewers, projectState, candidateList);
    log.debug("Sorted recommendations: {}", sortedRecommendations);

    // Filter accounts by visibility and enforce limit
    List<Account.Id> filteredRecommendations = new ArrayList<>();
    try (Timer0.Context ctx = metrics.filterVisibility.start()) {
      for (Account.Id reviewer : sortedRecommendations) {
        if (filteredRecommendations.size() >= limit) {
          break;
        }
        // Check if change is visible to reviewer and if the current user can see reviewer
        if (visibilityControl.isVisibleTo(reviewer)
            && accountControlFactory.get().canSee(reviewer)) {
          filteredRecommendations.add(reviewer);
        }
      }
    }
    log.debug("Filtered recommendations: {}", filteredRecommendations);

    List<SuggestedReviewerInfo> suggestedReviewers = loadAccounts(filteredRecommendations);
    if (!excludeGroups && suggestedReviewers.size() < limit && !Strings.isNullOrEmpty(query)) {
      // Add groups at the end as individual accounts are usually more
      // important.
      suggestedReviewers.addAll(
          suggestAccountGroups(
              suggestReviewers,
              projectState,
              visibilityControl,
              limit - suggestedReviewers.size()));
    }

    if (suggestedReviewers.size() > limit) {
      suggestedReviewers = suggestedReviewers.subList(0, limit);
      log.debug("Limited suggested reviewers to {} accounts.", limit);
    }
    log.debug(
        "Suggested reviewers: {}",
        suggestedReviewers
            .stream()
            .map(
                r -> {
                  if (r.account != null) {
                    return "a/" + r.account._accountId;
                  } else if (r.group != null) {
                    return "g/" + r.group.id;
                  } else {
                    return "";
                  }
                })
            .collect(toList()));
    return suggestedReviewers;
  }

  private List<Account.Id> suggestAccounts(SuggestReviewers suggestReviewers) throws OrmException {
    try (Timer0.Context ctx = metrics.queryAccountsLatency.start()) {
      try {
        // For performance reasons we don't use AccountQueryProvider as it would always load the
        // complete account from the cache (or worse, from NoteDb) even though we only need the ID
        // which we can directly get from the returned results.
        ResultSet<FieldBundle> result =
            accountIndexes
                .getSearchIndex()
                .getSource(
                    Predicate.and(
                        AccountPredicates.isActive(),
                        accountQueryBuilder.defaultQuery(suggestReviewers.getQuery())),
                    QueryOptions.create(
                        indexConfig,
                        0,
                        suggestReviewers.getLimit() * CANDIDATE_LIST_MULTIPLIER,
                        ImmutableSet.of(AccountField.ID.getName())))
                .readRaw();
        return result
            .toList()
            .stream()
            .map(f -> new Account.Id(f.getValue(AccountField.ID).intValue()))
            .collect(toList());
      } catch (QueryParseException e) {
        return ImmutableList.of();
      }
    }
  }

  private List<Account.Id> recommendAccounts(
      ChangeNotes changeNotes,
      SuggestReviewers suggestReviewers,
      ProjectState projectState,
      List<Account.Id> candidateList)
      throws OrmException, IOException, ConfigInvalidException {
    try (Timer0.Context ctx = metrics.recommendAccountsLatency.start()) {
      return reviewerRecommender.suggestReviewers(
          changeNotes, suggestReviewers, projectState, candidateList);
    }
  }

  private List<SuggestedReviewerInfo> loadAccounts(List<Account.Id> accountIds)
      throws OrmException, PermissionBackendException {
    Set<FillOptions> fillOptions =
        permissionBackend.user(self).test(GlobalPermission.MODIFY_ACCOUNT)
            ? EnumSet.of(FillOptions.SECONDARY_EMAILS)
            : EnumSet.noneOf(FillOptions.class);
    fillOptions.addAll(AccountLoader.DETAILED_OPTIONS);
    AccountLoader accountLoader = accountLoaderFactory.create(fillOptions);

    try (Timer0.Context ctx = metrics.loadAccountsLatency.start()) {
      List<SuggestedReviewerInfo> reviewer =
          accountIds
              .stream()
              .map(accountLoader::get)
              .filter(Objects::nonNull)
              .map(
                  a -> {
                    SuggestedReviewerInfo info = new SuggestedReviewerInfo();
                    info.account = a;
                    info.count = 1;
                    return info;
                  })
              .collect(toList());
      accountLoader.fill();
      return reviewer;
    }
  }

  private List<SuggestedReviewerInfo> suggestAccountGroups(
      SuggestReviewers suggestReviewers,
      ProjectState projectState,
      VisibilityControl visibilityControl,
      int limit)
      throws OrmException, IOException {
    try (Timer0.Context ctx = metrics.queryGroupsLatency.start()) {
      List<SuggestedReviewerInfo> groups = new ArrayList<>();
      for (GroupReference g : suggestAccountGroups(suggestReviewers, projectState)) {
        GroupAsReviewer result =
            suggestGroupAsReviewer(
                suggestReviewers, projectState.getProject(), g, visibilityControl);
        if (result.allowed || result.allowedWithConfirmation) {
          GroupBaseInfo info = new GroupBaseInfo();
          info.id = Url.encode(g.getUUID().get());
          info.name = g.getName();
          SuggestedReviewerInfo suggestedReviewerInfo = new SuggestedReviewerInfo();
          suggestedReviewerInfo.group = info;
          suggestedReviewerInfo.count = result.size;
          if (result.allowedWithConfirmation) {
            suggestedReviewerInfo.confirm = true;
          }
          groups.add(suggestedReviewerInfo);
          if (groups.size() >= limit) {
            break;
          }
        }
      }
      return groups;
    }
  }

  private List<GroupReference> suggestAccountGroups(
      SuggestReviewers suggestReviewers, ProjectState projectState) {
    return Lists.newArrayList(
        Iterables.limit(
            groupBackend.suggest(suggestReviewers.getQuery(), projectState),
            suggestReviewers.getLimit()));
  }

  private static class GroupAsReviewer {
    boolean allowed;
    boolean allowedWithConfirmation;
    int size;
  }

  private GroupAsReviewer suggestGroupAsReviewer(
      SuggestReviewers suggestReviewers,
      Project project,
      GroupReference group,
      VisibilityControl visibilityControl)
      throws OrmException, IOException {
    GroupAsReviewer result = new GroupAsReviewer();
    int maxAllowed = suggestReviewers.getMaxAllowed();
    int maxAllowedWithoutConfirmation = suggestReviewers.getMaxAllowedWithoutConfirmation();

    if (!PostReviewers.isLegalReviewerGroup(group.getUUID())) {
      return result;
    }

    try {
      Set<Account> members = groupMembers.listAccounts(group.getUUID(), project.getNameKey());

      if (members.isEmpty()) {
        return result;
      }

      result.size = members.size();
      if (maxAllowed > 0 && result.size > maxAllowed) {
        return result;
      }

      boolean needsConfirmation = result.size > maxAllowedWithoutConfirmation;

      // require that at least one member in the group can see the change
      for (Account account : members) {
        if (visibilityControl.isVisibleTo(account.getId())) {
          if (needsConfirmation) {
            result.allowedWithConfirmation = true;
          } else {
            result.allowed = true;
          }
          return result;
        }
      }
    } catch (NoSuchProjectException e) {
      return result;
    }

    return result;
  }
}
