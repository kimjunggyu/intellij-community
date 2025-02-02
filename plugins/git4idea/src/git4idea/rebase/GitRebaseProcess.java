// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package git4idea.rebase;

import com.google.common.annotations.VisibleForTesting;
import com.intellij.diff.DiffEditorTitleCustomizer;
import com.intellij.dvcs.DvcsUtil;
import com.intellij.dvcs.repo.Repository;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationListener;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.AccessToken;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.VcsNotifier;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import com.intellij.openapi.vcs.changes.committed.CommittedChangesTreeBrowser;
import com.intellij.openapi.vcs.changes.ui.ChangeListViewerDialog;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.vcs.merge.MergeDialogCustomizer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.labels.LinkLabel;
import com.intellij.util.ExceptionUtil;
import com.intellij.util.ThreeState;
import com.intellij.util.containers.MultiMap;
import com.intellij.util.ui.components.BorderLayoutPanel;
import com.intellij.vcs.log.Hash;
import com.intellij.vcs.log.TimedVcsCommit;
import com.intellij.vcs.log.VcsCommitMetadata;
import com.intellij.vcs.log.impl.HashImpl;
import com.intellij.vcs.log.impl.VcsCommitMetadataImpl;
import com.intellij.vcs.log.ui.details.MultipleCommitInfoDialog;
import com.intellij.vcs.log.util.VcsLogUtil;
import git4idea.DialogManager;
import git4idea.GitProtectedBranchesKt;
import git4idea.GitRevisionNumber;
import git4idea.branch.GitRebaseParams;
import git4idea.changes.GitChangeUtils;
import git4idea.changes.GitCommittedChangeList;
import git4idea.commands.*;
import git4idea.history.GitCommitRequirements;
import git4idea.history.GitHistoryUtils;
import git4idea.merge.GitConflictResolver;
import git4idea.merge.GitDefaultMergeDialogCustomizerKt;
import git4idea.merge.GitMergeProvider;
import git4idea.rebase.GitSuccessfulRebase.SuccessType;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import git4idea.stash.GitChangesSaver;
import git4idea.util.GitFreezingProcess;
import git4idea.util.GitUntrackedFilesHelper;
import org.jetbrains.annotations.CalledInBackground;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;
import java.util.List;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.intellij.dvcs.DvcsUtil.getShortRepositoryName;
import static com.intellij.openapi.ui.Messages.getWarningIcon;
import static com.intellij.openapi.util.text.StringUtil.capitalize;
import static com.intellij.openapi.vcs.VcsNotifier.IMPORTANT_ERROR_NOTIFICATION;
import static com.intellij.util.ObjectUtils.*;
import static com.intellij.util.containers.ContainerUtil.*;
import static git4idea.GitUtil.*;
import static git4idea.history.GitLogUtil.readFullDetails;
import static git4idea.history.GitLogUtil.readFullDetailsForHashes;
import static java.util.Arrays.asList;
import static java.util.Collections.singleton;

public class GitRebaseProcess {

  private static final Logger LOG = Logger.getInstance(GitRebaseProcess.class);

  private final NotificationAction ABORT_ACTION = NotificationAction.create("Abort", (event, notification) -> {
    abort();
    notification.expire();
  });

  private final NotificationAction CONTINUE_ACTION = NotificationAction.create("Continue", (event, notification) -> {
    retry(GitRebaseUtils.CONTINUE_PROGRESS_TITLE);
    notification.expire();
  });

  private final NotificationAction RETRY_ACTION = NotificationAction.create("Retry", (event, notification) -> {
    retry("Retry Rebase Process...");
    notification.expire();
  });

  private final NotificationAction VIEW_STASH_ACTION;

  @NotNull private final Project myProject;
  @NotNull private final Git myGit;
  @NotNull private final ChangeListManager myChangeListManager;
  @NotNull private final VcsNotifier myNotifier;
  @NotNull private final GitRepositoryManager myRepositoryManager;

  @NotNull private final GitRebaseSpec myRebaseSpec;
  @Nullable private final GitRebaseResumeMode myCustomMode;
  @NotNull private final GitChangesSaver mySaver;
  @NotNull private final ProgressManager myProgressManager;
  @NotNull private final VcsDirtyScopeManager myDirtyScopeManager;

  public GitRebaseProcess(@NotNull Project project, @NotNull GitRebaseSpec rebaseSpec, @Nullable GitRebaseResumeMode customMode) {
    myProject = project;
    myRebaseSpec = rebaseSpec;
    myCustomMode = customMode;
    mySaver = rebaseSpec.getSaver();

    myGit = Git.getInstance();
    myChangeListManager = ChangeListManager.getInstance(myProject);
    myNotifier = VcsNotifier.getInstance(myProject);
    myRepositoryManager = getRepositoryManager(myProject);
    myProgressManager = ProgressManager.getInstance();
    myDirtyScopeManager = VcsDirtyScopeManager.getInstance(myProject);

    VIEW_STASH_ACTION = NotificationAction.createSimple("View " + capitalize(mySaver.getSaverName()) + "...",
                                                        () -> mySaver.showSavedChanges());
  }

  public void rebase() {
    if (checkForRebasingPublishedCommits()) {
      new GitFreezingProcess(myProject, "rebase", this::doRebase).execute();
    }
  }

  /**
   * Given a GitRebaseSpec this method either starts, or continues the ongoing rebase in multiple repositories.
   * <ul>
   * <li>It does nothing with "already successfully rebased repositories" (the ones which have {@link GitRebaseStatus} == SUCCESSFUL,
   * and just remembers them to use in the resulting notification.</li>
   * <li>If there is a repository with rebase in progress, it calls `git rebase --continue` (or `--skip`).
   * It is assumed that there is only one such repository.</li>
   * <li>For all remaining repositories rebase on which didn't start yet, it calls {@code git rebase <original parameters>}</li>
   * </ul>
   */
  private void doRebase() {
    LOG.info("Started rebase");
    LOG.debug("Started rebase with the following spec: " + myRebaseSpec);

    Map<GitRepository, GitRebaseStatus> statuses = new LinkedHashMap<>(myRebaseSpec.getStatuses());
    List<GitRepository> repositoriesToRebase = myRepositoryManager.sortByDependency(myRebaseSpec.getIncompleteRepositories());
    try (AccessToken ignore = DvcsUtil.workingTreeChangeStarted(myProject, "Rebase")) {
      if (!saveDirtyRootsInitially(repositoriesToRebase)) return;

      GitRepository latestRepository = null;
      for (GitRepository repository : repositoriesToRebase) {
        GitRebaseResumeMode customMode = null;
        if (repository == myRebaseSpec.getOngoingRebase()) {
          customMode = myCustomMode == null ? GitRebaseResumeMode.CONTINUE : myCustomMode;
        }

        Collection<Change> changes = collectFutureChanges(repository);

        GitRebaseStatus rebaseStatus = rebaseSingleRoot(repository, customMode, getSuccessfulRepositories(statuses));
        repository.update(); // make the repo state info actual ASAP
        if (customMode == GitRebaseResumeMode.CONTINUE) {
          myDirtyScopeManager.dirDirtyRecursively(repository.getRoot());
        }

        latestRepository = repository;
        statuses.put(repository, rebaseStatus);
        if (shouldBeRefreshed(rebaseStatus)) {
          refreshVfs(repository.getRoot(), changes);
        }
        if (rebaseStatus.getType() != GitRebaseStatus.Type.SUCCESS) {
          break;
        }
      }

      GitRebaseStatus.Type latestStatus = statuses.get(latestRepository).getType();
      if (latestStatus == GitRebaseStatus.Type.SUCCESS || latestStatus == GitRebaseStatus.Type.NOT_STARTED) {
        LOG.debug("Rebase completed successfully.");
        mySaver.load();
      }
      if (latestStatus == GitRebaseStatus.Type.SUCCESS) {
        notifySuccess(getSuccessfulRepositories(statuses), getSkippedCommits(statuses));
      }

      saveUpdatedSpec(statuses);
    }
    catch (ProcessCanceledException pce) {
      throw pce;
    }
    catch(Throwable e) {
      myRepositoryManager.setOngoingRebaseSpec(null);
      ExceptionUtil.rethrowUnchecked(e);
    }
  }

  @Nullable
  private Collection<Change> collectFutureChanges(@NotNull GitRepository repository) {
    GitRebaseParams params = myRebaseSpec.getParams();
    if (params == null) return null;

    Collection<Change> changes = new ArrayList<>();
    String branch = params.getBranch();
    if (branch != null) {
      Collection<Change> changesFromCheckout = GitChangeUtils.getDiff(repository, HEAD, branch, false);
      if (changesFromCheckout == null) return null;
      changes.addAll(changesFromCheckout);
    }

    String rev1 = coalesce(params.getNewBase(), branch, HEAD);
    String rev2 = params.getUpstream();
    Collection<Change> changesFromRebase = GitChangeUtils.getDiff(repository, rev1, rev2, false);
    if (changesFromRebase == null) return null;

    changes.addAll(changesFromRebase);
    return changes;
  }

  private void saveUpdatedSpec(@NotNull Map<GitRepository, GitRebaseStatus> statuses) {
    if (myRebaseSpec.shouldBeSaved()) {
      GitRebaseSpec newRebaseInfo = myRebaseSpec.cloneWithNewStatuses(statuses);
      myRepositoryManager.setOngoingRebaseSpec(newRebaseInfo);
    }
    else {
      myRepositoryManager.setOngoingRebaseSpec(null);
    }
  }

  @NotNull
  private GitRebaseStatus rebaseSingleRoot(@NotNull GitRepository repository,
                                           @Nullable GitRebaseResumeMode customMode,
                                           @NotNull Map<GitRepository, GitSuccessfulRebase> alreadyRebased) {
    VirtualFile root = repository.getRoot();
    String repoName = getShortRepositoryName(repository);
    LOG.info("Rebasing root " + repoName + ", mode: " + notNull(customMode, "standard"));

    Collection<GitRebaseUtils.CommitInfo> skippedCommits = new ArrayList<>();
    MultiMap<GitRepository, GitRebaseUtils.CommitInfo> allSkippedCommits = getSkippedCommits(alreadyRebased);
    boolean retryWhenDirty = false;

    while (true) {
      GitRebaseProblemDetector rebaseDetector = new GitRebaseProblemDetector();
      GitUntrackedFilesOverwrittenByOperationDetector untrackedDetector = new GitUntrackedFilesOverwrittenByOperationDetector(root);
      GitRebaseProgressListener progressListener = new GitRebaseProgressListener();
      GitRebaseCommandResult rebaseCommandResult = callRebase(repository, customMode, rebaseDetector, untrackedDetector, progressListener);
      GitCommandResult result = rebaseCommandResult.getCommandResult();

      boolean somethingRebased = customMode != null || progressListener.currentCommit > 1;

      if (rebaseCommandResult.wasCancelledInCommitList()) {
        return GitRebaseStatus.notStarted();
      }
      else if (rebaseCommandResult.wasCancelledInCommitMessage()) {
        showStoppedForEditingMessage();
        return new GitRebaseStatus(GitRebaseStatus.Type.SUSPENDED, skippedCommits);
      }
      else if (result.success()) {
        if (rebaseDetector.hasStoppedForEditing()) {
          showStoppedForEditingMessage();
          return new GitRebaseStatus(GitRebaseStatus.Type.SUSPENDED, skippedCommits);
        }
        LOG.debug("Successfully rebased " + repoName);
        return GitSuccessfulRebase.parseFromOutput(result.getOutput(), skippedCommits);
      }
      else if (rebaseDetector.isDirtyTree() && customMode == null && !retryWhenDirty) {
        // if the initial dirty tree check doesn't find all local changes, we are still ready to stash-on-demand,
        // but only once per repository (if the error happens again, that means that the previous stash attempt failed for some reason),
        // and not in the case of --continue (where all local changes are expected to be committed) or --skip.
        LOG.debug("Dirty tree detected in " + repoName);
        String saveError = saveLocalChanges(singleton(repository.getRoot()));
        if (saveError == null) {
          retryWhenDirty = true; // try same repository again
        }
        else {
          LOG.warn("Couldn't " + mySaver.getOperationName() + " root " + repository.getRoot() + ": " + saveError);
          showFatalError(saveError, repository, somethingRebased, alreadyRebased.keySet(), allSkippedCommits);
          GitRebaseStatus.Type type = somethingRebased ? GitRebaseStatus.Type.SUSPENDED : GitRebaseStatus.Type.ERROR;
          return new GitRebaseStatus(type, skippedCommits);
        }
      }
      else if (untrackedDetector.wasMessageDetected()) {
        LOG.info("Untracked files detected in " + repoName);
        showUntrackedFilesError(untrackedDetector.getRelativeFilePaths(), repository, somethingRebased, alreadyRebased.keySet(),
                                allSkippedCommits);
        GitRebaseStatus.Type type = somethingRebased ? GitRebaseStatus.Type.SUSPENDED : GitRebaseStatus.Type.ERROR;
        return new GitRebaseStatus(type, skippedCommits);
      }
      else if (rebaseDetector.isNoChangeError()) {
        LOG.info("'No changes' situation detected in " + repoName);
        GitRebaseUtils.CommitInfo currentRebaseCommit = GitRebaseUtils.getCurrentRebaseCommit(myProject, root);
        if (currentRebaseCommit != null) skippedCommits.add(currentRebaseCommit);
        customMode = GitRebaseResumeMode.SKIP;
      }
      else if (rebaseDetector.isMergeConflict()) {
        LOG.info("Merge conflict in " + repoName);
        ResolveConflictResult resolveResult = showConflictResolver(repository, false);
        if (resolveResult == ResolveConflictResult.ALL_RESOLVED) {
          customMode = GitRebaseResumeMode.CONTINUE;
        }
        else if (resolveResult == ResolveConflictResult.NOTHING_TO_MERGE) {
          // the output is the same for the cases:
          // (1) "unresolved conflicts"
          // (2) "manual editing of a file not followed by `git add`
          // => we check if there are any unresolved conflicts, and if not, then it is the case #2 which we are not handling
          LOG.info("Unmerged changes while rebasing root " + repoName + ": " + result.getErrorOutputAsJoinedString());
          showFatalError(result.getErrorOutputAsHtmlString(), repository, somethingRebased, alreadyRebased.keySet(), allSkippedCommits);
          GitRebaseStatus.Type type = somethingRebased ? GitRebaseStatus.Type.SUSPENDED : GitRebaseStatus.Type.ERROR;
          return new GitRebaseStatus(type, skippedCommits);
        }
        else {
          notifyNotAllConflictsResolved(repository, allSkippedCommits);
          return new GitRebaseStatus(GitRebaseStatus.Type.SUSPENDED, skippedCommits);
        }
      }
      else {
        LOG.info("Error rebasing root " + repoName + ": " + result.getErrorOutputAsJoinedString());
        showFatalError(result.getErrorOutputAsHtmlString(), repository, somethingRebased, alreadyRebased.keySet(), allSkippedCommits);
        GitRebaseStatus.Type type = somethingRebased ? GitRebaseStatus.Type.SUSPENDED : GitRebaseStatus.Type.ERROR;
        return new GitRebaseStatus(type, skippedCommits);
      }
    }
  }

  @NotNull
  private GitRebaseCommandResult callRebase(@NotNull GitRepository repository,
                                            @Nullable GitRebaseResumeMode mode,
                                            @NotNull GitLineHandlerListener... listeners) {
    if (mode == null) {
      GitRebaseParams params = assertNotNull(myRebaseSpec.getParams());
      return myGit.rebase(repository, params, listeners);
    }
    else if (mode == GitRebaseResumeMode.SKIP) {
      return myGit.rebaseSkip(repository, listeners);
    }
    else {
      LOG.assertTrue(mode == GitRebaseResumeMode.CONTINUE, "Unexpected rebase mode: " + mode);
      return myGit.rebaseContinue(repository, listeners);
    }
  }

  @VisibleForTesting
  @NotNull
  protected Collection<GitRepository> getDirtyRoots(@NotNull Collection<GitRepository> repositories) {
    return findRootsWithLocalChanges(repositories);
  }

  private static boolean shouldBeRefreshed(@NotNull GitRebaseStatus rebaseStatus) {
    return rebaseStatus.getType() != GitRebaseStatus.Type.SUCCESS ||
           ((GitSuccessfulRebase)rebaseStatus).getSuccessType() != SuccessType.UP_TO_DATE;
  }

  private boolean saveDirtyRootsInitially(@NotNull List<? extends GitRepository> repositories) {
    Collection<GitRepository> repositoriesToSave = filter(repositories, repository -> {
      return !repository.equals(myRebaseSpec.getOngoingRebase()); // no need to save anything when --continue/--skip is to be called
    });
    if (repositoriesToSave.isEmpty()) return true;
    Collection<VirtualFile> rootsToSave = getRootsFromRepositories(getDirtyRoots(repositoriesToSave));
    String error = saveLocalChanges(rootsToSave);
    if (error != null) {
      myNotifier.notifyError("Rebase Not Started", error);
      return false;
    }
    return true;
  }

  @Nullable
  private String saveLocalChanges(@NotNull Collection<? extends VirtualFile> rootsToSave) {
    try {
      mySaver.saveLocalChanges(rootsToSave);
      return null;
    }
    catch (VcsException e) {
      LOG.warn(e);
      return "Couldn't " + mySaver.getSaverName() + " local uncommitted changes:<br/>" + e.getMessage();
    }
  }

  private Collection<GitRepository> findRootsWithLocalChanges(@NotNull Collection<GitRepository> repositories) {
    return filter(repositories, repository -> myChangeListManager.haveChangesUnder(repository.getRoot()) != ThreeState.NO);
  }

  protected void notifySuccess(@NotNull Map<GitRepository, GitSuccessfulRebase> successful,
                             @NotNull MultiMap<GitRepository, GitRebaseUtils.CommitInfo> skippedCommits) {
    String rebasedBranch = getCommonCurrentBranchNameIfAllTheSame(myRebaseSpec.getAllRepositories());
    List<SuccessType> successTypes = map(successful.values(), GitSuccessfulRebase::getSuccessType);
    SuccessType commonType = getItemIfAllTheSame(successTypes, SuccessType.REBASED);
    GitRebaseParams params = myRebaseSpec.getParams();
    String baseBranch = params == null ? null : notNull(params.getNewBase(), params.getUpstream());
    if ("HEAD".equals(baseBranch)) {
      baseBranch = getItemIfAllTheSame(myRebaseSpec.getInitialBranchNames().values(), baseBranch);
    }
    String message = commonType.formatMessage(rebasedBranch, baseBranch, params != null && params.getBranch() != null);
    message += mentionSkippedCommits(skippedCommits);
    myNotifier.notifyMinorInfo("Rebase Successful", message, new RebaseNotificationListener(skippedCommits));
  }

  @Nullable
  private static String getCommonCurrentBranchNameIfAllTheSame(@NotNull Collection<? extends GitRepository> repositories) {
    return getItemIfAllTheSame(map(repositories, Repository::getCurrentBranchName), null);
  }

  @Contract("_, !null -> !null")
  private static <T> T getItemIfAllTheSame(@NotNull Collection<? extends T> collection, @Nullable T defaultItem) {
    return new HashSet<>(collection).size() == 1 ? getFirstItem(collection) : defaultItem;
  }

  private void notifyNotAllConflictsResolved(@NotNull GitRepository conflictingRepository,
                                             MultiMap<GitRepository, GitRebaseUtils.CommitInfo> skippedCommits) {
    String description = GitRebaseUtils.mentionLocalChangesRemainingInStash(mySaver);
    Notification notification = IMPORTANT_ERROR_NOTIFICATION.createNotification("Rebase Stopped Due to Conflicts", description, NotificationType.WARNING, new RebaseNotificationListener(skippedCommits));
    notification.addAction(new ResolveAction(conflictingRepository));
    notification.addAction(CONTINUE_ACTION);
    notification.addAction(ABORT_ACTION);
    if (mySaver.wereChangesSaved()) notification.addAction(VIEW_STASH_ACTION);
    myNotifier.notify(notification);
  }

  @NotNull
  private ResolveConflictResult showConflictResolver(@NotNull GitRepository conflicting, boolean calledFromNotification) {
    GitConflictResolver.Params params = new GitConflictResolver
      .Params(myProject)
      .setMergeDialogCustomizer(createDialogCustomizer(conflicting, myRebaseSpec))
      .setReverse(true);
    RebaseConflictResolver conflictResolver = new RebaseConflictResolver(myProject, conflicting, params, calledFromNotification);
    boolean allResolved = conflictResolver.merge();
    if (conflictResolver.myWasNothingToMerge) return ResolveConflictResult.NOTHING_TO_MERGE;
    if (allResolved) return ResolveConflictResult.ALL_RESOLVED;
    return ResolveConflictResult.UNRESOLVED_REMAIN;
  }

  @Nullable
  private static Hash resolveRef(@NotNull GitRepository repository, @NotNull String ref) {
    GitRevisionNumber resolved = null;
    try {
      resolved = GitRevisionNumber.resolve(repository.getProject(), repository.getRoot(), ref);
    }
    catch (VcsException e) {
      LOG.warn(e);
    }
    return resolved != null ? HashImpl.build(resolved.asString()) : null;
  }

  @NotNull
  private static MergeDialogCustomizer createDialogCustomizer(@NotNull GitRepository repository, @NotNull GitRebaseSpec rebaseSpec) {
    GitRebaseParams rebaseParams = rebaseSpec.getParams();
    if (rebaseParams != null) {
      String currentBranchAtTheStartOfRebase = rebaseSpec.getInitialBranchNames().get(repository);
      String upstream = rebaseParams.getUpstream();
      if (upstream.equals(HEAD)) {
          /* this is to overcome a hack: passing HEAD into `git rebase HEAD branch`
             to avoid passing branch names for different repositories */
        upstream = currentBranchAtTheStartOfRebase;
      }
      String branch = rebaseParams.getBranch();
      if (branch == null) {
        branch = currentBranchAtTheStartOfRebase;
      }

      if (upstream != null && branch != null) {
        Hash head = resolveRef(repository, HEAD);
        Hash rebaseHead = resolveRef(repository, "REBASE_HEAD");
        Hash upstreamRef = resolveRef(repository, upstream);
        Hash mergeBase = null;
        try {
          GitRevisionNumber mergeBaseRev = GitHistoryUtils.getMergeBase(repository.getProject(), repository.getRoot(), upstream, branch);
          mergeBase = mergeBaseRev != null ? HashImpl.build(mergeBaseRev.getRev()) : null;
        }
        catch (VcsException e) {
          LOG.warn(e);
        }
        return new GitRebaseMergeDialogCustomizer(repository, upstream, branch, rebaseHead, head, mergeBase, upstreamRef);
      }
    }
    return new MergeDialogCustomizer();
  }

  private static class GitRebaseMergeDialogCustomizer extends MergeDialogCustomizer {
    @NotNull private final GitRepository myRepository;
    @NotNull private final String myRebasingBranch;
    @NotNull private final String myBasePresentable;
    @Nullable private final String myBaseBranch;
    @Nullable private final Hash myBaseHash;
    @Nullable private final Hash myIngoingCommit;
    @Nullable private final Hash myHead;
    @Nullable private final Hash myMergeBase;
    @Nullable private final String myUpstreamHash;

    private GitRebaseMergeDialogCustomizer(@NotNull GitRepository repository,
                                           @NotNull String upstream,
                                           @NotNull String branch,
                                           @Nullable Hash ingoingCommit,
                                           @Nullable Hash head,
                                           @Nullable Hash mergeBase,
                                           @Nullable Hash upstreamRef) {
      myRepository = repository;
      myRebasingBranch = branch;
      myIngoingCommit = ingoingCommit;
      myHead = head;
      myMergeBase = mergeBase;
      myUpstreamHash = upstreamRef != null ? upstreamRef.asString() : null;
      if (upstream.matches("[a-fA-F0-9]{40}")) {
        myBasePresentable = VcsLogUtil.getShortHash(upstream);
        myBaseBranch = null;
        myBaseHash = HashImpl.build(upstream);
      }
      else {
        myBasePresentable = upstream;
        myBaseBranch = upstream;
        myBaseHash = null;
      }
    }

    @NotNull
    @Override
    public String getMultipleFileMergeDescription(@NotNull Collection<VirtualFile> files) {
      return GitDefaultMergeDialogCustomizerKt.getDescriptionForRebase(myRebasingBranch, myBaseBranch, myBaseHash);
    }

    @NotNull
    @Override
    public String getLeftPanelTitle(@NotNull VirtualFile file) {
      return GitDefaultMergeDialogCustomizerKt.getDefaultLeftPanelTitleForBranch(myRebasingBranch);
    }

    @NotNull
    @Override
    public String getRightPanelTitle(@NotNull VirtualFile file, @Nullable VcsRevisionNumber revisionNumber) {
      GitRevisionNumber gitRevisionNumber = tryCast(revisionNumber, GitRevisionNumber.class);
      Hash hash = gitRevisionNumber != null ? HashImpl.build(gitRevisionNumber.asString()) : myBaseHash;
      return GitDefaultMergeDialogCustomizerKt.getDefaultRightPanelTitleForBranch(myBaseBranch, hash);
    }

    @Nullable
    @Override
    public List<String> getColumnNames() {
      return asList(GitMergeProvider.calcColumnName(false, myRebasingBranch),
                    GitMergeProvider.calcColumnName(true, myBasePresentable));
    }

    @Nullable
    @Override
    public DiffEditorTitleCustomizer getLeftTitleCustomizer(@NotNull FilePath file) {
      if (myIngoingCommit == null) {
        return null;
      }
      Project project = myRepository.getProject();
      String title = String.format("<html>Rebasing %s from <b>%s</b></html>",
                                   myIngoingCommit.toShortString(),
                                   myRebasingBranch);
      return () -> createLabelWithShowLink(title, () -> {
        ChangeListViewerDialog dlg = new ChangeListViewerDialog(project);
        dlg.loadChangesInBackground(() -> {
          GitCommittedChangeList changeList = GitChangeUtils.getRevisionChanges(project,
                                                                                myRepository.getRoot(),
                                                                                myIngoingCommit.asString(),
                                                                                true,
                                                                                false,
                                                                                false);
          return new ChangeListViewerDialog.ChangelistData(changeList, file);
        });
        dlg.setTitle("Rebasing " + myIngoingCommit.toShortString());
        dlg.setModal(true);
        dlg.show();
      });
    }

    @Nullable
    @Override
    public DiffEditorTitleCustomizer getRightTitleCustomizer(@NotNull FilePath file) {
      if (myHead == null || myMergeBase == null || myUpstreamHash == null) {
        return null;
      }
      Project project = myRepository.getProject();
      VirtualFile root = myRepository.getRoot();
      String title = getRightTitle(true);
      return () -> createLabelWithShowLink(title, () -> {
        List<VcsCommitMetadata> details = new ArrayList<>();
        Set<VcsCommitMetadata> filteredCommits = new HashSet<>();
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
          () -> {
            try {
              readFullDetails(
                project,
                root,
                (commit) -> {
                  VcsCommitMetadata commitMetadata = new VcsCommitMetadataImpl(
                    commit.getId(), commit.getParents(), commit.getCommitTime(), commit.getRoot(),
                    commit.getSubject(), commit.getAuthor(), commit.getFullMessage(), commit.getCommitter(),
                    commit.getAuthorTime());
                  if (commit.getAffectedPaths().contains(file)) {
                    filteredCommits.add(commitMetadata);
                  }
                  details.add(commitMetadata);
                },
                myMergeBase + ".." + myHead);
            }
            catch (VcsException e) {
              LOG.warn(e);
            }
          },
          "Collecting Commit Details...",
          true,
          project);
        DialogWrapper dlg = new MyMultipleCommitInfoDialog(project, root, details, filteredCommits,
                                                           find(details, (commit) -> commit.getId().asString().equals(myUpstreamHash)));
        dlg.setTitle(getRightTitle(false));
        dlg.show();
      });
    }

    @NotNull
    private String getRightTitle(boolean withBold) {
      String branchPartPrefix = "and commits from";
      String branchPartWithBold = myBaseBranch != null ? String.format("%s <b>%s</b>", branchPartPrefix, myBaseBranch) : "";
      String branchPart = myBaseBranch != null ? String.format("%s %s", branchPartPrefix, myBaseBranch) : "";
      return String.format("Already rebased commits %s", withBold ? branchPartWithBold : branchPart);
    }

    private static JPanel createLabelWithShowLink(@NotNull String text, @NotNull Runnable onClick) {
      return new BorderLayoutPanel()
        .addToCenter(new JBLabel(text).setCopyable(true))
        .addToRight(LinkLabel.create("Show Details", onClick));
    }

    private static class MyMultipleCommitInfoDialog extends MultipleCommitInfoDialog {
      @NotNull private final Project myProject;
      @NotNull private final VirtualFile myRoot;
      @NotNull private final Set<VcsCommitMetadata> myFilteredCommits;
      @NotNull private final Set<VcsCommitMetadata> myHighlightedCommits;
      @NotNull private static final JBColor UPSTREAM_COMMITS_BACKGROUND = new JBColor(new Color(228, 250, 255), new Color(63, 71, 73));

      MyMultipleCommitInfoDialog(@NotNull Project project,
                                 @NotNull VirtualFile root,
                                 @NotNull List<VcsCommitMetadata> commits,
                                 @NotNull Set<VcsCommitMetadata> filteredCommits,
                                 @Nullable VcsCommitMetadata baseCommit) {
        super(project, commits);
        myProject = project;
        myRoot = root;
        myFilteredCommits = filteredCommits;

        myHighlightedCommits = new HashSet<>();
        boolean highlighting = false;
        for (VcsCommitMetadata commit : commits) {
          if (commit.equals(baseCommit)) {
            highlighting = true;
          }
          if (highlighting) {
            myHighlightedCommits.add(commit);
          }
        }
        filterCommitsByConflictingFile();
      }

      @NotNull
      @Override
      public List<Change> loadChanges(@NotNull List<? extends VcsCommitMetadata> commits) throws VcsException {
        List<Change> changes = new ArrayList<>();
        readFullDetailsForHashes(myProject,
                                 myRoot,
                                 map(commits, commit -> commit.getId().asString()),
                                 GitCommitRequirements.DEFAULT,
                                 gitCommit -> changes.addAll(gitCommit.getChanges())
        );
        return CommittedChangesTreeBrowser.zipChanges(changes);
      }

      private void filterCommitsByConflictingFile() {
        setFilter((commit) -> myFilteredCommits.contains(commit));
      }

      @Nullable
      @Override
      protected JPanel createSouthAdditionalPanel() {
        JCheckBox checkbox = new JBCheckBox("Filter by conflicted file", true);
        checkbox.addActionListener((e) -> {
          if (checkbox.isSelected()) {
            filterCommitsByConflictingFile();
          }
          else {
            resetFilter();
          }
        });
        return new BorderLayoutPanel().addToCenter(checkbox);
      }


      @Override
      public void customizeListCellRenderer(@NotNull ColoredListCellRenderer<VcsCommitMetadata> renderer,
                                            @NotNull JList<? extends VcsCommitMetadata> list,
                                            @Nullable VcsCommitMetadata value,
                                            int index,
                                            boolean selected,
                                            boolean hasFocus) {
        super.customizeListCellRenderer(renderer, list, value, index, selected, hasFocus);
        if (!selected) {
          if (myHighlightedCommits.contains(value)) {
            renderer.setBackground(UPSTREAM_COMMITS_BACKGROUND);
          }
          else {
            renderer.setBackground(list.getBackground());
          }
        }
      }
    }
  }

  private void showStoppedForEditingMessage() {
    String description = "";
    Notification notification = IMPORTANT_ERROR_NOTIFICATION.createNotification("Rebase Stopped for Editing", description, NotificationType.INFORMATION, new RebaseNotificationListener(MultiMap.empty()));
    notification.addAction(CONTINUE_ACTION);
    notification.addAction(ABORT_ACTION);
    myNotifier.notify(notification);
  }

  private void showFatalError(@NotNull final String error,
                              @NotNull final GitRepository currentRepository,
                              boolean somethingWasRebased,
                              @NotNull final Collection<GitRepository> successful,
                              @NotNull MultiMap<GitRepository, GitRebaseUtils.CommitInfo> skippedCommits) {
    String repo = myRepositoryManager.moreThanOneRoot() ? getShortRepositoryName(currentRepository) + ": " : "";
    String description = repo + error + "<br/>" +
                         mentionSkippedCommits(skippedCommits) +
                         GitRebaseUtils.mentionLocalChangesRemainingInStash(mySaver);
    String title = myRebaseSpec.getOngoingRebase() == null ? "Rebase Failed" : "Continue Rebase Failed";
    Notification notification = IMPORTANT_ERROR_NOTIFICATION.createNotification(title, description, NotificationType.ERROR, new RebaseNotificationListener(skippedCommits));
    notification.addAction(RETRY_ACTION);
    if (somethingWasRebased || !successful.isEmpty()) notification.addAction(ABORT_ACTION);
    if (mySaver.wereChangesSaved()) notification.addAction(VIEW_STASH_ACTION);
    myNotifier.notify(notification);
  }

  private void showUntrackedFilesError(@NotNull Set<String> untrackedPaths,
                                       @NotNull GitRepository currentRepository,
                                       boolean somethingWasRebased,
                                       @NotNull Collection<GitRepository> successful,
                                       MultiMap<GitRepository, GitRebaseUtils.CommitInfo> skippedCommits) {
    String message = mentionSkippedCommits(skippedCommits) +
                     GitRebaseUtils.mentionLocalChangesRemainingInStash(mySaver);
    List<NotificationAction> actions = new ArrayList<>();
    actions.add(RETRY_ACTION);
    if (somethingWasRebased || !successful.isEmpty()) actions.add(ABORT_ACTION);
    if (mySaver.wereChangesSaved()) actions.add(VIEW_STASH_ACTION);
    GitUntrackedFilesHelper.notifyUntrackedFilesOverwrittenBy(myProject, currentRepository.getRoot(), untrackedPaths,
                                                              "rebase", message, new RebaseNotificationListener(skippedCommits), actions.toArray(
        new NotificationAction[0]));
  }

  @NotNull
  private static String mentionSkippedCommits(@NotNull MultiMap<GitRepository, GitRebaseUtils.CommitInfo> skippedCommits) {
    if (skippedCommits.isEmpty()) return "";
    String message = "<br/>";
    if (skippedCommits.values().size() == 1) {
      message += "The following commit was skipped during rebase:<br/>";
    }
    else {
      message += "The following commits were skipped during rebase:<br/>";
    }
    message += StringUtil.join(skippedCommits.values(), commitInfo -> {
      String commitMessage = StringUtil.shortenPathWithEllipsis(commitInfo.subject, 72, true);
      String hash = commitInfo.revision.asString();
      String shortHash = DvcsUtil.getShortHash(commitInfo.revision.asString());
      return String.format("<a href='%s'>%s</a> %s", hash, shortHash, commitMessage);
    }, "<br/>");
    return message;
  }

  @NotNull
  private static MultiMap<GitRepository, GitRebaseUtils.CommitInfo> getSkippedCommits(@NotNull Map<GitRepository, ? extends GitRebaseStatus> statuses) {
    MultiMap<GitRepository, GitRebaseUtils.CommitInfo> map = MultiMap.create();
    for (GitRepository repository : statuses.keySet()) {
      map.put(repository, statuses.get(repository).getSkippedCommits());
    }
    return map;
  }

  @NotNull
  private static Map<GitRepository, GitSuccessfulRebase> getSuccessfulRepositories(@NotNull Map<GitRepository, GitRebaseStatus> statuses) {
    Map<GitRepository, GitSuccessfulRebase> map = new LinkedHashMap<>();
    for (GitRepository repository : statuses.keySet()) {
      GitRebaseStatus status = statuses.get(repository);
      if (status instanceof GitSuccessfulRebase) map.put(repository, (GitSuccessfulRebase)status);
    }
    return map;
  }

  private boolean checkForRebasingPublishedCommits() {
    if (myCustomMode != null || myRebaseSpec.getOngoingRebase() != null) {
      return true;
    }
    if (myRebaseSpec.getParams() == null) {
      LOG.error("Shouldn't happen. Spec: " + myRebaseSpec);
      return true;
    }

    String upstream = myRebaseSpec.getParams().getUpstream();
    for (GitRepository repository : myRebaseSpec.getAllRepositories()) {
      if (repository.getCurrentBranchName() == null) {
        LOG.error("No current branch in " + repository);
        return true;
      }
      String rebasingBranch = notNull(myRebaseSpec.getParams().getBranch(), repository.getCurrentBranchName());
      if (isRebasingPublishedCommit(repository, upstream, rebasingBranch)) {
        return askIfShouldRebasePublishedCommit();
      }
    }
    return true;
  }

  private boolean isRebasingPublishedCommit(@NotNull GitRepository repository,
                                            @NotNull String baseBranch,
                                            @NotNull String rebasingBranch) {
    try {
      List<? extends TimedVcsCommit> commits = GitHistoryUtils.collectTimedCommits(myProject, repository.getRoot(),
                                                                                   baseBranch + ".." + rebasingBranch);
      return exists(commits, commit -> GitProtectedBranchesKt.isCommitPublished(repository, commit.getId()));
    }
    catch (VcsException e) {
      LOG.error("Couldn't collect commits", e);
      return true;
    }
  }

  private static boolean askIfShouldRebasePublishedCommit() {
    String title = "Rebasing Published Commit";
    String message = "<html>You're trying to rebase some commits already pushed to a protected branch.<br/>" +
                     "Rebasing them would duplicate commits, which is not recommended and most likely unwanted.</html>";
    Ref<Boolean> rebaseAnyway = Ref.create(false);
    ApplicationManager.getApplication().invokeAndWait(() -> {
      int answer = DialogManager.showMessage(message, title, new String[]{"Rebase Anyway", "Cancel"}, 1, 1, getWarningIcon(), null);
      rebaseAnyway.set(answer == 0);
    });
    return rebaseAnyway.get();
  }

  private class RebaseConflictResolver extends GitConflictResolver {
    private final boolean myCalledFromNotification;
    private boolean myWasNothingToMerge;

    RebaseConflictResolver(@NotNull Project project,
                           @NotNull GitRepository repository,
                           @NotNull Params params, boolean calledFromNotification) {
      super(project, singleton(repository.getRoot()), params);
      myCalledFromNotification = calledFromNotification;
    }

    @Override
    protected void notifyUnresolvedRemain() {
      // will be handled in the common notification
    }

    @CalledInBackground
    @Override
    protected boolean proceedAfterAllMerged() {
      if (myCalledFromNotification) {
        retry(GitRebaseUtils.CONTINUE_PROGRESS_TITLE);
      }
      return true;
    }

    @Override
    protected boolean proceedIfNothingToMerge() {
      myWasNothingToMerge = true;
      return true;
    }
  }

  private enum ResolveConflictResult {
    ALL_RESOLVED,
    NOTHING_TO_MERGE,
    UNRESOLVED_REMAIN
  }

  private class RebaseNotificationListener extends NotificationListener.Adapter {
    @NotNull private final MultiMap<GitRepository, GitRebaseUtils.CommitInfo> mySkippedCommits;

    RebaseNotificationListener(@NotNull MultiMap<GitRepository, GitRebaseUtils.CommitInfo> skippedCommits) {
      mySkippedCommits = skippedCommits;
    }

    @Override
    protected void hyperlinkActivated(@NotNull Notification notification, @NotNull final HyperlinkEvent e) {
      handlePossibleCommitLinks(e.getDescription(), mySkippedCommits);
    }
  }

  private class ResolveAction extends NotificationAction {
    @NotNull private final GitRepository myCurrentRepository;

    ResolveAction(@NotNull GitRepository currentRepository) {
      super("Resolve...");
      myCurrentRepository = currentRepository;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification notification) {
      myProgressManager.run(new Task.Backgroundable(myProject, "Collecting Conflicts to Resolve...") {
        @Override
        public void run(@NotNull ProgressIndicator indicator) {
          showConflictResolver(myCurrentRepository, true);
        }
      });
    }
  }

  private void abort() {
    myProgressManager.run(new Task.Backgroundable(myProject, "Aborting Rebase Process...") {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        GitRebaseUtils.abort(myProject, indicator);
      }
    });
  }

  private void retry(@NotNull String processTitle) {
    myProgressManager.run(new Task.Backgroundable(myProject, processTitle, true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        GitRebaseUtils.continueRebase(myProject);
      }
    });
  }

  private void handlePossibleCommitLinks(@NotNull String href, @NotNull MultiMap<GitRepository, GitRebaseUtils.CommitInfo> skippedCommits) {
    GitRepository repository = findRootBySkippedCommit(href, skippedCommits);
    if (repository != null) {
      showSubmittedFiles(myProject, href, repository.getRoot(), true, false);
    }
  }

  @Nullable
  private static GitRepository findRootBySkippedCommit(@NotNull final String hash,
                                                       @NotNull MultiMap<GitRepository, GitRebaseUtils.CommitInfo> skippedCommits) {
    return find(skippedCommits.keySet(),  repository-> exists(skippedCommits.get(repository),  info-> info.revision.asString().equals(hash)));
  }

  private static class GitRebaseProgressListener implements GitLineHandlerListener {
    private static final Pattern PROGRESS = Pattern.compile("^Rebasing \\((\\d+)/(\\d+)\\)$"); // `Rebasing (2/3)` means 2nd commit from 3

    private int currentCommit;

    @Override
    public void onLineAvailable(@NotNull String line, @NotNull Key outputType) {
      Matcher matcher = PROGRESS.matcher(line);
      if (matcher.matches()) {
        currentCommit = Integer.parseInt(matcher.group(1));
      }
    }
  }
}
