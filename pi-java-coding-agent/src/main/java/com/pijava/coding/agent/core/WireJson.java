package com.pijava.coding.agent.core;

import java.io.IOException;
import java.time.Instant;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;

/**
 * 线格式共用的 Jackson 片段：给**写得出消息**的 mapper 补 {@code Instant} 处理。
 *
 * <p><b>为什么存在</b>：pi 的 {@code timestamp} 是 Unix 毫秒数
 * （{@code ai/src/types.ts} 的三个 message interface 都声明 {@code timestamp: number}），
 * 而 Java 侧 {@code AbstractChatApi} 给每条消息挂的是 {@link Instant}。凡是**拿裸
 * {@code new ObjectMapper()} 序列化消息**的出口都会直接抛
 * {@code InvalidDefinitionException: Java 8 date/time type Instant not supported}
 * —— 而且是**静默**的：调用方只看到 {@code success:false}，或干脆收不到那一帧
 * （事件线由 {@code SessionEventHub} 逐 listener catch 掉）。</p>
 *
 * <p>这同一个成因已经咬过两次、咬在两个线面上：包⑩ 的**事件线**
 * （{@code JsonEventMapper}，{@code docs/37}）与包⑪ 的**命令线／导出线**
 * （{@code JsonlWriter}／{@code RpcDispatcher}，台账 B52，{@code docs/38}）。
 * 两处的补法相同：注册本模块。<b>以后新加一个会序列化消息的出口，先问一句：
 * 那个 mapper 注册了吗？</b></p>
 *
 * <p>为什么不引 jsr310 依赖：落盘层（{@code SessionJson:200-203}）本来就是**手写**
 * serializer 把 Instant 写成毫秒整数，这里照同一手法，不引入新依赖。</p>
 */
public final class WireJson {

    private WireJson() {}

    /** 注册 {@code Instant → Unix 毫秒整数} 的模块。 */
    public static SimpleModule instantAsEpochMillis() {
        return new SimpleModule("pi-wire-instant")
            .addSerializer(Instant.class, new EpochMilliSerializer());
    }

    /** {@code Instant} → Unix 毫秒整数（pi 的 {@code timestamp: number}）。 */
    public static final class EpochMilliSerializer extends JsonSerializer<Instant> {
        @Override
        public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            gen.writeNumber(value.toEpochMilli());
        }
    }
}
