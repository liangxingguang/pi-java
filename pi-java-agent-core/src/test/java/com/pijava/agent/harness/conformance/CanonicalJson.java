package com.pijava.agent.harness.conformance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * pi 侧 {@code canonical()} 的等价实现（{@code docs/23c §2.3}）：对象键递归按字典序排列、
 * 数组保持原序。
 *
 * <p>键序不承载语义，若任其参与比较，两侧的 Map 实现差异会伪装成帧差异。因此归一化阶段
 * 就把它抹平，差分器只需要做逐行字符串比较。</p>
 */
final class CanonicalJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CanonicalJson() {}

    /** 把 Map/List/标量组成的结构渲染成键序稳定的 JSON 单行。 */
    static String render(Object value) {
        try {
            return MAPPER.writeValueAsString(canonical(value));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot render frame", e);
        }
    }

    /** 递归把对象换成 {@link TreeMap}，让 Jackson 按键序遍历写出。 */
    static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            for (var entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), canonical(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof List<?> list) {
            var out = new ArrayList<Object>(list.size());
            for (var item : list) {
                out.add(canonical(item));
            }
            return out;
        }
        return value;
    }

    /** 便捷构造：{@code obj("type", "agent_start")}，键值成对给出。 */
    static Map<String, Object> obj(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }
}
