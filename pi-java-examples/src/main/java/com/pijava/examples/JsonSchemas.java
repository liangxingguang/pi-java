package com.pijava.examples;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 批 28（docs/40 T3，裁决②＋F3 取证）：TypeBox {@code Type.Object} 的手写 schema map
 * 构造器——键序与 TypeBox 1.3.27 产出一一致（object: {@code type/required/properties}；
 * string: {@code type/description}；array: {@code type/items/description}），
 * 无 {@code additionalProperties}/{@code $id}（{@code ~kind} 非枚举不进 JSON）。
 */
final class JsonSchemas {

    private JsonSchemas() {}

    /** 保序键值对 ⇒ LinkedHashMap（键序即 JSON 输出序）。 */
    static Map<String, Object> obj(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("odd key/value count");
        }
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }
}
