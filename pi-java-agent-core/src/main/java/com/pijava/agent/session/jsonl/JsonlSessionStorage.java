package com.pijava.agent.session.jsonl;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.NewRecord;
import com.pijava.agent.session.BranchBounds;
import com.pijava.agent.session.EntryQuery;
import com.pijava.agent.session.LanePointer;
import com.pijava.agent.session.LogItem;
import com.pijava.agent.session.LogOptions;
import com.pijava.agent.session.RecordQuery;
import com.pijava.agent.session.SessionError;
import com.pijava.agent.session.SessionErrorCode;
import com.pijava.agent.session.ForkOptions;
import com.pijava.agent.session.SessionMutation;
import com.pijava.agent.session.SessionState;
import com.pijava.agent.session.SessionStats;
import com.pijava.agent.session.SessionStorage;

/**
 * JSONL v4 session storage (aligned with pi {@code JsonlSessionStorage}).
 * Writes go through a serialized tail chain (Java: {@code synchronized} on a
 * write lock): append to file first, then apply to the in-memory
 * {@link SessionState}. Load performs torn-tail repair for crash recovery.
 */
public final class JsonlSessionStorage implements SessionStorage<JsonlSessionMetadata> {

    private final JsonlSessionRepoFileSystem fs;
    private final JsonlSessionMetadata metadata;
    private final SessionState state = new SessionState();
    private final Object writeLock = new Object();
    /** 创建时的头（惰性落盘时重写）；load/fork 后不再使用。 */
    private final JsonlV4Header header;
    /** 文件是否已存在（create 后为 false，首次对话或 load/fork 后为 true）。 */
    private boolean flushed;
    /** 未落盘的已编码行（含尾换行）——首次对话时随头一次性写出。 */
    private final StringBuilder pending = new StringBuilder();

    private JsonlSessionStorage(JsonlSessionRepoFileSystem fs, JsonlV4Header header,
                                JsonlSessionMetadata metadata) {
        this.fs = fs;
        this.header = header;
        this.metadata = metadata;
    }

    /**
     * Create a storage backed by a fresh file.
     *
     * <p>批 33（#19）：与 pi {@code _persist} 一致 —— 建会话时**不落盘**，
     * 出现第一条 user/assistant 消息时才一次性写出 header ＋ 全部内存条目
     * （pi {@code session-manager.ts:1172-1189}）。{@code modifiedAtMs} 在没有
     * 文件时用头的时间占位。</p>
     */
    public static JsonlSessionStorage create(JsonlSessionRepoFileSystem fs, Path path,
                                             JsonlV4Header header) {
        var storage = new JsonlSessionStorage(fs, header,
            metadataFromHeader(header, path, header.createdAtMs()));
        storage.flushed = false;
        return storage;
    }

    /** Load a storage from an existing file, repairing a torn tail. */
    public static JsonlSessionStorage load(JsonlSessionRepoFileSystem fs, Path path) {
        String content = fs.readTextFile(path);
        // 批 33（#17）：pi v1/v2 在严格解析前迁移并重写，重读迁移结果再走严格路径。
        if (LegacyPiVersionMigrator.needsMigration(content)) {
            LegacyPiVersionMigrator.migrate(fs, path, content);
            content = fs.readTextFile(path);
        }
        String[] physicalLines = content.split("\n", -1);
        if (physicalLines.length > 0 && physicalLines[physicalLines.length - 1].isEmpty()) {
            physicalLines = java.util.Arrays.copyOf(physicalLines, physicalLines.length - 1);
        }
        if (physicalLines.length == 0 || physicalLines[0].isEmpty()) {
            throw new JsonlSessionError(path, 1, "is missing a header");
        }
        var headerResult = JsonlCodec.parseHeader(physicalLines[0]);
        if (!headerResult.ok()) {
            throw new JsonlSessionError(path, 1, headerResult.error());
        }
        var storage = new JsonlSessionStorage(fs, headerResult.value(),
            metadataFromHeader(headerResult.value(), path, fs.fileInfoMtimeMs(path)));
        storage.flushed = true;

        for (int index = 1; index < physicalLines.length; index++) {
            String line = physicalLines[index];
            var mutationResult = JsonlCodec.parseMutation(line, index);
            if (!mutationResult.ok()) {
                boolean isTornTail = index == physicalLines.length - 1
                    && "syntax".equals(mutationResult.error().kind());
                if (isTornTail) {
                    // Drop the unacknowledged partial append by atomically publishing the valid prefix.
                    StringBuilder prefix = new StringBuilder();
                    for (int i = 0; i < index; i++) {
                        prefix.append(physicalLines[i]).append("\n");
                    }
                    String tempPath = path + ".tmp";
                    fs.writeFile(Path.of(tempPath), prefix.toString());
                    fs.renameFile(Path.of(tempPath), path);
                    return storage;
                }
                throw new JsonlSessionError(path, index + 1, mutationResult.error());
            }
            try {
                storage.applyMutation(mutationResult.value());
            } catch (SessionError e) {
                if (e.code() == SessionErrorCode.INVALID_ENTRY) {
                    throw new JsonlSessionError(path, index + 1, e);
                }
                throw e;
            }
        }

        if (!content.endsWith("\n")) {
            fs.appendFile(path, "\n");
        }
        if (storage.metadata.legacyFormat()) {
            storage.migrateLegacyToPiV3(path);
        }
        return storage;
    }

    /** Atomically publish a forked copy at {@code path}. */
    public JsonlSessionStorage fork(Path path, JsonlV4Header header, ForkOptions options) {
        var mutations = state.createForkMutations(options);
        String tempPath = path + ".tmp";
        try {
            // B274（docs/24 W4b）：pi :1868-1877 用单个 fd 写头＋全部条目；Java 的
            // fs 边界没有 fd 概念，等价做法是把整份内容一次 writeFile 后再 rename
            // （避免逐条 open/close，失败原子性不变：要么 tmp 不存在、要么完整）。
            var content = new StringBuilder(JsonlCodec.encodeHeader(header));
            for (var mutation : mutations) {
                content.append(JsonlCodec.encodeMutation(mutation));
            }
            fs.writeFile(Path.of(tempPath), content.toString());
            fs.renameFile(Path.of(tempPath), path);
        } catch (RuntimeException e) {
            fs.remove(Path.of(tempPath), true);
            throw e;
        }
        return load(fs, path);
    }

    /**
     * Wait for all queued writes. Java's serialized tail chain means every
     * write has completed when this method is called; nothing is queued.
     */
    @Override
    public void drain() {
        // No-op: the synchronized write path is the serial chain.
    }

    /** The backing JSONL file path (used by the export fast path). */
    public Path path() {
        return metadata.path();
    }

    @Override
    public JsonlSessionMetadata getMetadata() {
        return metadata;
    }

    @Override
    public List<LanePointer> getLanes() {
        return state.getLanes();
    }

    @Override
    public void createLane(String lane, String at) {
        synchronized (writeLock) {
            state.validateNewLane(lane);
            state.validateTarget(at);
            var mutation = new SessionMutation.Lane(state.nextSequence(),
                state.lastEntryId(), Instant.now(), lane, at);
            appendMutation(mutation);
            applyMutation(mutation);
        }
    }

    @Override
    public void moveLane(String lane, String to) {
        synchronized (writeLock) {
            state.requireLane(lane);
            state.validateTarget(to);
            var mutation = new SessionMutation.Lane(state.nextSequence(),
                state.lastEntryId(), Instant.now(), lane, to);
            appendMutation(mutation);
            applyMutation(mutation);
        }
    }

    @Override
    public <T extends Entry> T appendEntry(ProvisionedEntry<T> entry, String lane) {
        synchronized (writeLock) {
            String parentId = state.requireLane(lane);
            state.validateUnusedId(entry.entry().id());
            // The generic cast is safe: committed() preserves the runtime subtype of the provisioned entry.
            @SuppressWarnings("unchecked")
            T committed = (T) entry.entry().committed(state.nextSequence(), parentId,
                java.time.Instant.ofEpochMilli(System.currentTimeMillis()));
            com.pijava.agent.session.SessionJson.assertSerializable(committed);
            var mutation = new SessionMutation.Entry(lane, committed);
            appendMutation(mutation);
            applyMutation(mutation);
            flushIfConversationStarted();
            return committed;
        }
    }

    @Override
    public <T extends LaneRecord> T appendRecord(NewRecord<T> record) {
        synchronized (writeLock) {
            state.requireLane(record.record().lane());
            state.validateUnusedId(record.record().id());
            var currentOpen = state.findOpenOperations(record.record().lane(), 1);
            if (record.record() instanceof LaneRecord.OperationStarted
                    && !currentOpen.isEmpty()) {
                throw new SessionError(SessionErrorCode.STORAGE,
                    "Lane " + record.record().lane()
                        + " already has an open operation " + currentOpen.getFirst().id());
            }
            // The generic cast is safe: committed() preserves the runtime subtype of the provisioned record.
            @SuppressWarnings("unchecked")
            T committed = (T) record.record().committed(state.nextSequence(),
                java.time.Instant.ofEpochMilli(System.currentTimeMillis()));
            com.pijava.agent.session.SessionJson.assertSerializable(committed);
            var mutation = new SessionMutation.Record(state.lastEntryId(), committed);
            appendMutation(mutation);
            applyMutation(mutation);
            return committed;
        }
    }

    @Override
    public Entry getEntry(String id) {
        return state.getEntry(id);
    }

    @Override
    public List<Entry> findEntries(EntryQuery query) {
        return state.findEntries(query);
    }

    @Override
    public List<Entry> findEntriesOnBranch(EntryQuery query, BranchBounds bounds, String start) {
        return state.findEntriesOnBranch(query, bounds, start);
    }

    @Override
    public List<LaneRecord> findRecords(RecordQuery query) {
        return state.findRecords(query);
    }

    @Override
    public List<LaneRecord.OperationStarted> findOpenOperations(String lane, int limit) {
        return state.findOpenOperations(lane, limit);
    }

    @Override
    public List<LogItem> getLog(LogOptions options) {
        return state.getLog(options);
    }

    @Override
    public String getName() {
        return state.getName();
    }

    @Override
    public void setName(String name) {
        synchronized (writeLock) {
            var mutation = new SessionMutation.FactName(state.nextSequence(),
                state.lastEntryId(), Instant.now(), name);
            appendMutation(mutation);
            applyMutation(mutation);
        }
    }

    @Override
    public String getLabel(String id) {
        return state.getLabel(id);
    }

    @Override
    public Instant getLabelTimestamp(String id) {
        return state.getLabelTimestamp(id);
    }

    @Override
    public void setLabel(String id, String label) {
        synchronized (writeLock) {
            state.validateTarget(id);
            var mutation = new SessionMutation.FactLabel(state.nextSequence(),
                state.lastEntryId(), Instant.now(), id, label);
            appendMutation(mutation);
            applyMutation(mutation);
        }
    }

    @Override
    public SessionStats getStats() {
        return state.getStats();
    }

    /** JSONL storage is stateless between opens; nothing to release. */
    @Override
    public void close() {
        // No-op for the JSONL backend.
    }

    // ── Internals ──────────────────────────────────────────

    private void appendMutation(SessionMutation mutation) {
        String line = JsonlCodec.encodeMutation(mutation);
        if (!flushed) {
            pending.append(line);
            return;
        }
        fs.appendFile(metadata.path(), line);
    }

    /**
     * 批 33（#19）：首次出现 user/assistant 消息时一次性写出 header ＋ 全部
     * 缓冲行（pi {@code _persist} 的 {@code flushed=false} 臂，{@code session-manager.ts:1175-1189}）。
     * temp＋rename 保证文件要么不存在、要么是完整前缀。
     */
    private void flushIfConversationStarted() {
        if (flushed || !hasConversation()) {
            return;
        }
        Path target = metadata.path();
        String tempPath = target + ".tmp";
        fs.writeFile(Path.of(tempPath), JsonlCodec.encodeHeader(header) + pending);
        fs.renameFile(Path.of(tempPath), target);
        pending.setLength(0);
        flushed = true;
    }

    /**
     * pi {@code _hasConversation}（{@code session-manager.ts:1166-1170}）：是否存在
     * user/assistant 消息条目。bashExecution 与 system 消息不命中（pi 逐字三条件）。
     */
    private boolean hasConversation() {
        for (var item : state.getLog(LogOptions.none())) {
            if (item instanceof LogItem.EntryItem e
                    && e.entry() instanceof Entry.Message message) {
                String role = message.message().role();
                if ("user".equals(role) || "assistant".equals(role)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void applyMutation(SessionMutation mutation) {
        state.applyMutation(mutation);
    }

    /**
     * 惰性迁移：把本仓**旧头**（{@code kind:"header"}）重写成 **pi v3 线**（{@code type:"session"}）。
     *
     * <p>只需重写头 —— {@link JsonlCodec#encodeMutation} 现在按 pi 的形状出线
     * （{@code docs/12 §6 D4}）。旧行里那些 pi 无对应的族（record/lane/fact）由包 12 的后续步
     * 收编，此处照原样重写。</p>
     */
    private void migrateLegacyToPiV3(Path path) {
        var header = new JsonlV4Header(PiV3Wire.SESSION_KIND, PiV3Wire.SESSION_VERSION,
            metadata.id(), metadata.createdAt().toEpochMilli(), metadata.cwd(),
            metadata.parentSessionId(), metadata.legacyParentSessionPath(),
            metadata.metadata());
        String tempPath = path + ".tmp";
        StringBuilder content = new StringBuilder(JsonlCodec.encodeHeader(header));
        // 旧行里**没有** parentId／timestamp（docs/12 §6 D2 才补的）。迁移时按「挂到最近的条目上」
        // 补齐 —— 否则 pi 从叶回溯仍看不到消息，迁移只换了头等于白换。
        String lastEntryId = null;
        for (var item : state.getLog(LogOptions.none())) {
            var mutation = mutationFromLogItem(item);
            if (mutation instanceof SessionMutation.Entry e) {
                lastEntryId = e.entry().id();
            }
            content.append(JsonlCodec.encodeMutation(withParentIfMissing(mutation, lastEntryId)));
        }
        fs.writeFile(Path.of(tempPath), content.toString());
        fs.renameFile(Path.of(tempPath), path);
    }

    /** 只补**缺**的 parentId（旧行读出来是 {@code null}）；已有的一律不动。 */
    private static SessionMutation withParentIfMissing(SessionMutation mutation, String parentId) {
        return switch (mutation) {
            case SessionMutation.Record r when r.parentId() == null ->
                new SessionMutation.Record(parentId, r.record());
            case SessionMutation.Lane l when l.parentId() == null ->
                new SessionMutation.Lane(l.seq(), parentId, l.timestamp(), l.lane(), l.leafId());
            case SessionMutation.FactName n when n.parentId() == null ->
                new SessionMutation.FactName(n.seq(), parentId, n.timestamp(), n.name());
            case SessionMutation.FactLabel l when l.parentId() == null ->
                new SessionMutation.FactLabel(l.seq(), parentId, l.timestamp(), l.targetId(), l.label());
            default -> mutation;
        };
    }

    static SessionMutation mutationFromLogItem(LogItem item) {
        return switch (item) {
            case LogItem.EntryItem e -> new SessionMutation.Entry(null, e.entry());
            case LogItem.RecordItem r -> new SessionMutation.Record(r.parentId(), r.record());
            case LogItem.LaneItem l -> new SessionMutation.Lane(l.seq(), l.parentId(),
                l.timestamp(), l.lane(), l.leafId());
            case LogItem.NameItem n -> new SessionMutation.FactName(n.seq(), n.parentId(),
                n.timestamp(), n.name());
            case LogItem.LabelItem l -> new SessionMutation.FactLabel(l.seq(), l.parentId(),
                l.timestamp(), l.targetId(), l.label());
        };
    }

    static JsonlSessionMetadata metadataFromHeader(JsonlV4Header header, Path path, long modifiedAtMs) {
        return new JsonlSessionMetadata(
            header.id(),
            Instant.ofEpochMilli(header.createdAtMs()),
            header.parentSessionId(),
            header.cwd(),
            path,
            modifiedAtMs,
            // 判别键（不是 version）—— 本仓旧文件也写 version:3（docs/12 §6 D1）。
            !PiV3Wire.SESSION_KIND.equals(header.kind()),
            header.legacyParentSessionPath(),
            header.metadata());
    }

    /** A JSONL file-level decode failure. */
    static final class JsonlSessionError extends RuntimeException {
        private final Path path;
        private final int line;

        JsonlSessionError(Path path, int line, JsonlCodec.DecodeError error) {
            super("Invalid session file " + path + ":" + line + ": " + error.getMessage(), error);
            this.path = path;
            this.line = line;
        }

        JsonlSessionError(Path path, int line, Throwable cause) {
            super("Invalid session file " + path + ":" + line + ": " + cause.getMessage(), cause);
            this.path = path;
            this.line = line;
        }

        JsonlSessionError(Path path, int line, String message) {
            super("Invalid session file " + path + ":" + line + ": " + message);
            this.path = path;
            this.line = line;
        }

        Path path() {
            return path;
        }

        int line() {
            return line;
        }
    }

}
