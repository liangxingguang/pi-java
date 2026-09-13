package com.pijava.agent.harness;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 车道的容器与生命周期（{@link AgentHarness} 的多车道那一半）。
 *
 * <p>拆出来是为了让 {@code AgentHarness} 保持在 500 行以内（{@code CLAUDE.md}）——
 * 它本身是 pi {@code Agent} 的宿主外观，方法多而薄。这里只放「有哪些车道、怎么建、
 * 怎么找」；车道的**状态**仍然是 {@link LaneState}，运行由
 * {@link PiLaneEngine} 驱动。</p>
 */
final class LaneRegistry {

    private final ConcurrentMap<String, LaneState> lanes = new ConcurrentHashMap<>();
    private final String defaultLaneName;

    LaneRegistry(String defaultLaneName) {
        this.defaultLaneName = defaultLaneName;
        var defaultLane = new LaneState();
        defaultLane.laneName = defaultLaneName;
        lanes.put(defaultLaneName, defaultLane);
    }

    /** 全部车道状态（可变视图；调用方不得增删键）。 */
    ConcurrentMap<String, LaneState> lanes() {
        return lanes;
    }

    String defaultLaneName() {
        return defaultLaneName;
    }

    LaneState get(String laneName) {
        return lanes.get(laneName);
    }

    /** 查找车道，缺席即抛。 */
    LaneState require(String laneName) {
        return HarnessUtils.requireLane(lanes, laneName);
    }

    /** 默认车道的句柄。 */
    LaneHandle handle(AgentHarness harness) {
        return new LaneHandle(defaultLaneName, harness);
    }

    /**
     * 建一条新车道。车道级覆盖（工具集、系统提示）随 {@link LaneConfig} 一起设置；
     * 重名即抛。
     */
    LaneHandle create(AgentHarness harness, LaneConfig config) {
        if (lanes.containsKey(config.name())) {
            throw new LaneExistsException(config.name());
        }
        var lane = new LaneState();
        lane.laneName = config.name();
        lane.parentLeafId = config.parentLeafId();
        lane.activeTools = config.activeTools() != null
            ? Set.copyOf(config.activeTools()) : null;
        lane.systemPrompt = config.systemPrompt();
        lanes.put(config.name(), lane);
        return new LaneHandle(config.name(), harness);
    }

    /** 全部车道的句柄。 */
    List<LaneHandle> handles(AgentHarness harness) {
        return lanes.keySet().stream()
            .map(name -> new LaneHandle(name, harness))
            .toList();
    }

    /**
     * 把 entry 从一条车道搬到另一条，源车道清空（分支/搬迁用）。
     *
     * <p>只搬 entry 日志；两侧的运行态（{@code activeRun}、队列）不动 —— 两个车道的
     * 运行期状态本就互不相干。</p>
     */
    void move(String source, String target) {
        var src = require(source);
        var tgt = require(target);
        tgt.transcript.addAll(src.transcript);
        src.transcript.clear();
        // 两侧日志都变 ⇒ 工作副本各自重建（docs/31 §4.2 的「日志整体替换」点）。
        HarnessUtils.rebuildLaneMessages(tgt);
        HarnessUtils.rebuildLaneMessages(src);
    }
}
