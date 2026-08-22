package com.pijava.web;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.pijava.web.WebProtocol.CommitInfo;
import com.pijava.web.WebProtocol.StatusEntry;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.Constants;

/**
 * Git 状态 / diff / 历史（代码调试，Phase 7 Stage B），基于 JGit。
 * 非 git 仓库统一抛 {@link IllegalArgumentException} → 前端回 {@code error}。
 */
final class GitService {

    /** status 结果。 */
    record Status(String cwd, List<StatusEntry> entries) {
    }

    /** diff 结果（unified diff 文本）。 */
    record Diff(String file, boolean staged, String text) {
    }

    /** 历史结果。 */
    record History(String file, List<CommitInfo> commits) {
    }

    /**
     * 工作区 status（对齐 git porcelain XY）：索引侧 A/M/D，工作区侧 M/D/U。
     * JGit 语义：{@code getAdded/getRemoved} 索引新增/删除；{@code getChanged}
     * 索引已改（staged）；{@code getModified} 工作区已改（tracked unstaged）；
     * {@code getMissing} 工作区删除；{@code getUntracked} 未跟踪。
     */
    Status status(Path cwd) {
        try (var git = Git.open(cwd.toFile())) {
            var st = git.status().call();
            var entries = new ArrayList<StatusEntry>();
            for (var path : st.getAdded()) {
                entries.add(new StatusEntry(path, "A", ""));
            }
            for (var path : st.getRemoved()) {
                entries.add(new StatusEntry(path, "D", ""));
            }
            for (var path : st.getChanged()) {
                entries.add(new StatusEntry(path, "M", ""));
            }
            for (var path : st.getModified()) {
                entries.add(new StatusEntry(path, "", "M"));
            }
            for (var path : st.getMissing()) {
                entries.add(new StatusEntry(path, "", "D"));
            }
            for (var path : st.getUntracked()) {
                entries.add(new StatusEntry(path, "", "U"));
            }
            for (var path : st.getConflicting()) {
                entries.add(new StatusEntry(path, "U", "U"));
            }
            return new Status(cwd.toString(), entries);
        } catch (Exception e) {
            throw new IllegalArgumentException("Not a git repository: " + cwd);
        }
    }

    /** diff：{@code staged=true} 索引 vs HEAD；否则 HEAD vs 工作区（含未跟踪内容）。 */
    Diff diff(Path cwd, String file, boolean staged) {
        try (var git = Git.open(cwd.toFile())) {
            var output = new java.io.ByteArrayOutputStream();
            var formatter = new DiffFormatter(output);
            formatter.setRepository(git.getRepository());
            formatter.setDetectRenames(false);

            var command = git.diff();
            if (staged) {
                command.setCached(true);
            } else {
                // HEAD vs 工作区：避免「工作区 blob 不在对象库」的 MissingObject
                var reader = git.getRepository().newObjectReader();
                try (var revWalk = new org.eclipse.jgit.revwalk.RevWalk(reader)) {
                    var head = git.getRepository().resolve(Constants.HEAD);
                    var commit = revWalk.parseCommit(head);
                    var oldTree = new org.eclipse.jgit.treewalk.CanonicalTreeParser();
                    oldTree.reset(reader, commit.getTree());
                    var newTree = new org.eclipse.jgit.treewalk.FileTreeIterator(git.getRepository());
                    command.setOldTree(oldTree).setNewTree(newTree);
                } finally {
                    reader.close();
                }
            }
            var entries = command.call();
            if (!staged) {
                insertWorktreeBlobs(git, entries);
            }
            for (var entry : entries) {
                if (file == null || file.isBlank()
                        || entry.getNewPath().equals(file) || entry.getOldPath().equals(file)) {
                    formatter.format(entry);
                }
            }
            formatter.flush();
            return new Diff(file, staged, output.toString());
        } catch (Exception e) {
            throw new IllegalArgumentException("git diff failed: " + e.getMessage());
        }
    }

    /**
     * 工作区 diff 的新侧 blob 通常不在对象库（未提交/未暂存），
     * {@code DiffFormatter.format} 打开时会抛 {@code MissingObject}。
     * 预先把工作区文件内容插入对象库，让 {@code newId} 可解析。
     */
    private static void insertWorktreeBlobs(Git git, List<org.eclipse.jgit.diff.DiffEntry> entries)
            throws java.io.IOException {
        try (var inserter = git.getRepository().newObjectInserter()) {
            for (var entry : entries) {
                var workTree = git.getRepository().getWorkTree().toPath();
                var newPath = workTree.resolve(entry.getNewPath());
                if (java.nio.file.Files.isRegularFile(newPath)) {
                    inserter.insert(Constants.OBJ_BLOB,
                        java.nio.file.Files.readAllBytes(newPath));
                }
            }
            inserter.flush();
        }
    }

    /** 提交历史（{@code limit} 默认 50；指定 file 则只含该文件）。 */
    History history(Path cwd, String file, Integer limit) {
        int max = limit == null || limit <= 0 ? 50 : limit;
        try (var git = Git.open(cwd.toFile())) {
            var log = git.log();
            if (file != null && !file.isBlank()) {
                log.addPath(file);
            }
            var commits = new ArrayList<CommitInfo>();
            for (var rev : log.setMaxCount(max).call()) {
                commits.add(new CommitInfo(
                    rev.getId().name().substring(0, 8),
                    rev.getShortMessage(),
                    rev.getAuthorIdent().getName(),
                    rev.getAuthorIdent().getWhen().toInstant().toString()));
            }
            return new History(file, commits);
        } catch (Exception e) {
            throw new IllegalArgumentException("git history failed: " + e.getMessage());
        }
    }
}
