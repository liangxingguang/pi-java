package com.pijava.ai.protocol;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.Message;
import com.pijava.ai.stream.StreamEvent;

/**
 * Google 车道的<b>出站请求体</b>夹具（包 B84）：起 {@link RecordingHttpServer}、跑一条流、
 * 把录到的 {@code contents} 解析出来。
 *
 * <p>抽出来是因为 B84 有三处要看出站形状（工具结果路由／助手重放／谓词），各写一份内嵌
 * 副本会变成三份漂移源 —— 与 {@code RecordingHttpServer} 本身的抽取理由相同。</p>
 *
 * <p>桩回 400 让车道尽快收场，体已录到；序列化在 {@code com.google.genai} 内部，
 * 没有可反射的出参构造器 ⇒ 这是**最强**的观察面。</p>
 */
final class GoogleWireBody {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GoogleWireBody() {
    }

    /** 真出站请求体的 {@code contents} 数组。 */
    static List<JsonNode> contents(ModelInfo model, List<Message> messages) throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new GoogleGenerativeAiApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()));
            var request = new StreamRequest(model, null, messages, List.of(), -1, -1, Map.of());
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                var events = new ArrayList<StreamEvent>();
                while (iter.hasNext() && events.size() < 100) {
                    events.add(iter.next());
                }
            } catch (Exception ignored) {
                // 桩回 400，请求体已录到
            }
            var body = server.body();
            var root = MAPPER.readTree(body);
            if (!root.has("contents")) {
                throw new AssertionError("请求体里没有 contents（线格：" + body + "）");
            }
            var contents = new ArrayList<JsonNode>();
            root.path("contents").forEach(contents::add);
            return contents;
        }
    }

    /** 第 {@code index} 条 content 的 {@code parts} 数组。 */
    static List<JsonNode> parts(List<JsonNode> contents, int index) {
        var parts = new ArrayList<JsonNode>();
        contents.get(index).path("parts").forEach(parts::add);
        return parts;
    }
}
