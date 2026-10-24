package com.pijava.tui.component.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.pijava.coding.agent.pkg.PackageManager;
import com.pijava.coding.agent.pkg.PathMetadata;
import com.pijava.coding.agent.pkg.PathMetadata.Origin;
import com.pijava.coding.agent.pkg.ResolvedPaths;
import com.pijava.coding.agent.pkg.ResolvedResource;
import com.pijava.coding.agent.pkg.ResourceType;
import com.pijava.coding.agent.pkg.SourceScope;
import com.pijava.coding.agent.pkg.SourceResolver;

/**
 * pi config TUI 的列表模型（批 19 T6，docs/30 §3）——{@code buildGroups}
 * （config-selector.ts:103-186）＋flat/filter/selection（:287-375）逐字对位。
 *
 * <p>分组规则：Group（来源分组）→ Subgroup（四类固定序
 * extensions→skills→prompts→themes，标签 :35-40）→ Item；排序＝package 先于
 * top-level、user 先于 project、同源按 source 字典序（:164-174）。</p>
 */
public final class ConfigSelectorModel {

    /** pi {@code RESOURCE_TYPE_LABELS}（:35-40）。 */
    private static final Map<ResourceType, String> TYPE_LABELS = Map.of(
        ResourceType.EXTENSIONS, "Extensions",
        ResourceType.SKILLS, "Skills",
        ResourceType.PROMPTS, "Prompts",
        ResourceType.THEMES, "Themes");

    private static final List<ResourceType> TYPE_ORDER = ResourceType.ALL;

    /** pi {@code ResourceItem}（:42-50）。 */
    public record ResourceItem(String path, boolean enabled, PathMetadata metadata,
            ResourceType resourceType, String displayName, String groupKey) {
        public ResourceItem withEnabled(boolean newEnabled) {
            return new ResourceItem(path, newEnabled, metadata, resourceType, displayName,
                groupKey);
        }

        /** pi {@code getResourceItemKey}（:850-852）。 */
        public String itemKey() {
            return resourceType.id() + ":" + PackageManager.canonicalizePath(path);
        }
    }

    /** pi {@code ResourceSubgroup}（:52-56）。 */
    public record ResourceSubgroup(ResourceType type, String label, List<ResourceItem> items) {}

    /** pi {@code ResourceGroup}（:58-65）。 */
    public record ResourceGroup(String key, String label, SourceScope scope, Origin origin,
            String source, List<ResourceSubgroup> subgroups) {}

    /** pi {@code FlatEntry}（:188-191）——三类行 ADT。 */
    public sealed interface FlatEntry {
        record GroupRow(ResourceGroup group) implements FlatEntry {}
        record SubgroupRow(ResourceSubgroup subgroup, ResourceGroup group)
            implements FlatEntry {}
        record ItemRow(ResourceItem item) implements FlatEntry {}
    }

    // ── buildGroups（:103-186）───────────────────────────────────

    /** pi {@code buildGroups} 逐字（java LinkedHashMap 保插入序 ≙ pi Map）。 */
    public static List<ResourceGroup> buildGroups(ResolvedPaths resolved, Path agentDir) {
        var groupMap = new LinkedHashMap<String, ResourceGroup>();
        addToGroup(groupMap, resolved.extensions(), ResourceType.EXTENSIONS, agentDir);
        addToGroup(groupMap, resolved.skills(), ResourceType.SKILLS, agentDir);
        addToGroup(groupMap, resolved.prompts(), ResourceType.PROMPTS, agentDir);
        addToGroup(groupMap, resolved.themes(), ResourceType.THEMES, agentDir);

        var groups = new ArrayList<>(groupMap.values());
        // pi :164-174：package 先于 top-level；user 先于 project；source 字典序。
        groups.sort(Comparator
            .comparingInt((ResourceGroup g) -> g.origin() == Origin.PACKAGE ? 0 : 1)
            .thenComparingInt(g -> g.scope() == SourceScope.USER ? 0 : 1)
            .thenComparing(ResourceGroup::source));
        for (var group : groups) {
            group.subgroups().sort(Comparator.comparingInt(sg -> TYPE_ORDER.indexOf(sg.type())));
            for (var subgroup : group.subgroups()) {
                subgroup.items().sort(Comparator.comparing(ResourceItem::displayName));
            }
        }
        return groups;
    }

    private static void addToGroup(Map<String, ResourceGroup> groupMap,
            List<ResolvedResource> resources, ResourceType resourceType, Path agentDir) {
        for (var res : resources) {
            var metadata = res.metadata();
            var groupKey = metadata.origin().id() + ":" + metadata.scope().id() + ":"
                + metadata.source() + ":" + (metadata.baseDir() == null ? "" : metadata.baseDir());
            var group = groupMap.computeIfAbsent(groupKey, key -> new ResourceGroup(key,
                getGroupLabel(metadata, agentDir), metadata.scope(), metadata.origin(),
                metadata.source(), new ArrayList<>()));

            ResourceSubgroup subgroup = null;
            for (var sg : group.subgroups()) {
                if (sg.type() == resourceType) {
                    subgroup = sg;
                    break;
                }
            }
            if (subgroup == null) {
                subgroup = new ResourceSubgroup(resourceType, TYPE_LABELS.get(resourceType),
                    new ArrayList<>());
                group.subgroups().add(subgroup);
            }

            // 显示名规则（pi :135-146）。
            var fileName = basename(res.path());
            var parentFolder = basename(dirname(res.path()));
            final String displayName;
            if ("builtin".equals(metadata.source())) {
                displayName = res.path().substring(PackageManager.BUILTIN_PATH_PREFIX.length());
            } else if (resourceType == ResourceType.EXTENSIONS && !"extensions".equals(parentFolder)) {
                displayName = parentFolder + "/" + fileName;
            } else if (resourceType == ResourceType.SKILLS && "SKILL.md".equals(fileName)) {
                displayName = parentFolder;
            } else {
                displayName = fileName;
            }
            subgroup.items().add(new ResourceItem(res.path(), res.enabled(), metadata,
                resourceType, displayName, groupKey));
        }
    }

    /** pi {@code getGroupLabel}（:84-101）逐字。 */
    static String getGroupLabel(PathMetadata metadata, Path agentDir) {
        if (metadata.origin() == Origin.PACKAGE) {
            return metadata.source() + " (" + metadata.scope().id() + ")";
        }
        if ("builtin".equals(metadata.source())) {
            return metadata.scope() == SourceScope.USER ? "Built-in" : "Built-in (project override)";
        }
        if ("auto".equals(metadata.source())) {
            if (metadata.baseDir() != null) {
                return metadata.scope() == SourceScope.USER
                    ? "User (" + formatBaseDir(metadata.baseDir()) + ")"
                    : "Project (" + formatBaseDir(metadata.baseDir()) + ")";
            }
            return metadata.scope() == SourceScope.USER
                ? "User (" + formatBaseDir(agentDir.toString()) + ")"
                : "Project (" + SourceResolver.CONFIG_DIR_NAME + "/)";
        }
        return metadata.scope() == SourceScope.USER ? "User settings" : "Project settings";
    }

    /** pi {@code formatBaseDir}（:67-82）：home ⇒ {@code ~} 前缀，分隔符归一，
     *  尾斜杠补齐。 */
    static String formatBaseDir(String baseDir) {
        var homeDir = Path.of(System.getProperty("user.home")).toString();
        final String displayPath;
        if (baseDir.equals(homeDir)) {
            displayPath = "~";
        } else if (baseDir.startsWith(homeDir)) {
            displayPath = "~" + baseDir.substring(homeDir.length()).replace('\\', '/');
        } else {
            displayPath = baseDir.replace('\\', '/');
        }
        return displayPath.endsWith("/") ? displayPath : displayPath + "/";
    }

    // ── 列表状态（:287-380）──────────────────────────────────────

    private final Map<com.pijava.coding.agent.spi.ConfigSelectorProvider.WriteScope,
            List<ResourceGroup>> groupsByScope;
    private com.pijava.coding.agent.spi.ConfigSelectorProvider.WriteScope writeScope;
    private final Map<String, Boolean> inheritedEnabledByKey;
    private List<FlatEntry> flatItems = List.of();
    private List<FlatEntry> filteredItems = List.of();
    private int selectedIndex;

    public ConfigSelectorModel(List<ResourceGroup> globalGroups, List<ResourceGroup> projectGroups,
            com.pijava.coding.agent.spi.ConfigSelectorProvider.WriteScope writeScope) {
        this.groupsByScope = Map.of(
            com.pijava.coding.agent.spi.ConfigSelectorProvider.WriteScope.GLOBAL, globalGroups,
            com.pijava.coding.agent.spi.ConfigSelectorProvider.WriteScope.PROJECT, projectGroups);
        this.writeScope = writeScope;
        this.inheritedEnabledByKey = buildInheritedEnabledMap(globalGroups);
        buildFlatList();
        this.filteredItems = new ArrayList<>(flatItems);
    }

    /** pi {@code setWriteScope}（:277-281）：重建后按当前查询重过滤。 */
    public void setWriteScope(
            com.pijava.coding.agent.spi.ConfigSelectorProvider.WriteScope scope) {
        this.writeScope = scope;
        buildFlatList();
        filterItems(lastQuery);
    }

    public com.pijava.coding.agent.spi.ConfigSelectorProvider.WriteScope writeScope() {
        return writeScope;
    }

    public List<FlatEntry> filteredItems() {
        return filteredItems;
    }

    public int selectedIndex() {
        return selectedIndex;
    }

    /** pi {@code inheritedEnabledByKey}（:287-297）。 */
    public Map<String, Boolean> inheritedEnabledByKey() {
        return inheritedEnabledByKey;
    }

    private Map<String, Boolean> buildInheritedEnabledMap(List<ResourceGroup> groups) {
        var result = new LinkedHashMap<String, Boolean>();
        for (var group : groups) {
            for (var subgroup : group.subgroups()) {
                for (var item : subgroup.items()) {
                    result.put(item.itemKey(), item.enabled());
                }
            }
        }
        return result;
    }

    private List<ResourceGroup> groups() {
        return groupsByScope.get(writeScope);
    }

    /** pi {@code buildFlatList}（:299-313）：选择落首个 item。 */
    private void buildFlatList() {
        var flat = new ArrayList<FlatEntry>();
        for (var group : groups()) {
            flat.add(new FlatEntry.GroupRow(group));
            for (var subgroup : group.subgroups()) {
                flat.add(new FlatEntry.SubgroupRow(subgroup, group));
                for (var item : subgroup.items()) {
                    flat.add(new FlatEntry.ItemRow(item));
                }
            }
        }
        flatItems = List.copyOf(flat);
        selectedIndex = firstItemIndex(flatItems);
    }

    /** pi {@code findNextItem}（:315-324）：跳标题行。 */
    public void moveSelection(int direction) {
        var idx = selectedIndex + direction;
        while (idx >= 0 && idx < filteredItems.size()) {
            if (filteredItems.get(idx) instanceof FlatEntry.ItemRow) {
                selectedIndex = idx;
                return;
            }
            idx += direction;
        }
    }

    /** pi pageUp（:471-481）：±maxVisible 后吸附最近 item。 */
    public void pageUp(int maxVisible) {
        var target = Math.max(0, selectedIndex - maxVisible);
        while (target < filteredItems.size()
            && !(filteredItems.get(target) instanceof FlatEntry.ItemRow)) {
            target++;
        }
        if (target < filteredItems.size()) {
            selectedIndex = target;
        }
    }

    /** pi pageDown（:482-492）。 */
    public void pageDown(int maxVisible) {
        var target = Math.min(filteredItems.size() - 1, selectedIndex + maxVisible);
        while (target >= 0 && !(filteredItems.get(target) instanceof FlatEntry.ItemRow)) {
            target--;
        }
        if (target >= 0) {
            selectedIndex = target;
        }
    }

    /** pi {@code filterItems}（:326-375）：displayName/resourceType/path 子串过滤，
     *  保留含命中项的 group/subgroup 头。 */
    private void filterItems(String query) {
        if (query == null || query.trim().isEmpty()) {
            filteredItems = new ArrayList<>(flatItems);
            selectFirstItem();
            return;
        }
        var lowerQuery = query.toLowerCase();
        Set<ResourceItem> matchingItems = new HashSet<>();
        for (var entry : flatItems) {
            if (entry instanceof FlatEntry.ItemRow row
                && (row.item().displayName().toLowerCase().contains(lowerQuery)
                    || row.item().resourceType().id().toLowerCase().contains(lowerQuery)
                    || row.item().path().toLowerCase().contains(lowerQuery))) {
                matchingItems.add(row.item());
            }
        }
        Set<ResourceSubgroup> matchingSubgroups = new HashSet<>();
        Set<ResourceGroup> matchingGroups = new HashSet<>();
        for (var group : groups()) {
            for (var subgroup : group.subgroups()) {
                for (var item : subgroup.items()) {
                    if (matchingItems.contains(item)) {
                        matchingSubgroups.add(subgroup);
                        matchingGroups.add(group);
                    }
                }
            }
        }
        var filtered = new ArrayList<FlatEntry>();
        for (var entry : flatItems) {
            switch (entry) {
                case FlatEntry.GroupRow row -> {
                    if (matchingGroups.contains(row.group())) {
                        filtered.add(row);
                    }
                }
                case FlatEntry.SubgroupRow row -> {
                    if (matchingSubgroups.contains(row.subgroup())) {
                        filtered.add(row);
                    }
                }
                case FlatEntry.ItemRow row -> {
                    if (matchingItems.contains(row.item())) {
                        filtered.add(row);
                    }
                }
            }
        }
        filteredItems = filtered;
        selectFirstItem();
    }

    /** pi {@code updateItem}（:382-394）：就地更新项的 enabled（组内同步）。 */
    public void updateItem(ResourceItem item, boolean enabled) {
        for (var group : groups()) {
            for (var subgroup : group.subgroups()) {
                for (int i = 0; i < subgroup.items().size(); i++) {
                    var found = subgroup.items().get(i);
                    if (found.path().equals(item.path())
                        && found.resourceType() == item.resourceType()) {
                        subgroup.items().set(i, found.withEnabled(enabled));
                        rebuildFlat();
                        return;
                    }
                }
            }
        }
    }

    /** 就地更新后重建 flat/filtered（保查询）。 */
    private void rebuildFlat() {
        var query = lastQuery;
        buildFlatList();
        filterItems(query);
    }

    private String lastQuery = "";

    /** 记查询供 {@link #updateItem} 后重放（pi 组件的 searchInput.getValue() 对位）。 */
    public void filterWith(String query) {
        lastQuery = query == null ? "" : query;
        filterItems(lastQuery);
    }

    public ResourceItem selectedItem() {
        if (selectedIndex >= 0 && selectedIndex < filteredItems.size()
            && filteredItems.get(selectedIndex) instanceof FlatEntry.ItemRow row) {
            return row.item();
        }
        return null;
    }

    private void selectFirstItem() {
        selectedIndex = firstItemIndex(filteredItems);
    }

    private static int firstItemIndex(List<FlatEntry> entries) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) instanceof FlatEntry.ItemRow) {
                return i;
            }
        }
        return 0;
    }

    /** node {@code path.basename} 对位（纯串，兼容两向分隔符）。 */
    static String basename(String path) {
        var idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return idx < 0 ? path : path.substring(idx + 1);
    }

    /** node {@code path.dirname} 对位。 */
    static String dirname(String path) {
        var idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return idx < 0 ? path : path.substring(0, idx);
    }
}
