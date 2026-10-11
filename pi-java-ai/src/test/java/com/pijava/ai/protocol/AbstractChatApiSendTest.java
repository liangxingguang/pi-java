package com.pijava.ai.protocol;

import java.util.List;
import java.util.Map;
import java.util.concurrent.SubmissionPublisher;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.http.PiHttpException;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B113（docs/52）：{@code AbstractChatApi.send()} 的 catch 是死结构 ——
 * {@code QueueStreamIterator.hasNext()/next()} 永不抛（中断合成落定事件），车道错误全部落流。
 * 若<b>管道自身</b>在拿到迭代器前抛了（如 streamBlocking 装配失败），异常必须保身份原样传播，
 * 不能被包成无类型的 {@code PiHttpException(0, "Streaming failed")}（上游按类型识别异常）。
 */
class AbstractChatApiSendTest {

    /** 最小替身：streamBlocking 直接抛（模拟管道自身故障）。 */
    private static final class FaultyApi extends AbstractChatApi {
        @Override public String apiName() {
            return "faulty";
        }
        @Override protected void streamInternal(StreamRequest request,
                                                 SubmissionPublisher<StreamEvent> publisher) {
            // 不会被调到（streamBlocking 被覆写）。
        }
        @Override public StreamIterator streamBlocking(StreamRequest request, ApiOptions options) {
            throw new IllegalStateException("pipeline fault");
        }
    }

    @Test
    void sendPropagatesPipelineFaultWithOriginalType() {
        var api = new FaultyApi();
        var request = new StreamRequest((com.pijava.ai.catalog.ModelInfo) null,
            "sys", List.of(), List.of(), -1, -1, Map.of());
        assertThatThrownBy(() -> api.send(request, ApiOptions.defaults()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("pipeline fault")
            .isNotInstanceOf(PiHttpException.class);
    }
}
