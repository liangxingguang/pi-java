package com.pijava.ai.provider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * models.json parsing: schema mapping, id-from-map-key, defaults, errors.
 */
class ModelsJsonConfigTest {

    @TempDir
    Path tmp;

    private static final String FULL_CONFIG = """
        {
          "providers": {
            "teamorouter": {
              "name": "TeamoRouter",
              "baseUrl": "https://api.teamorouter.cn/v1",
              "apiKey": "sk-inline-test",
              "api": "openai-completions",
              "unknownFutureField": true,
              "models": [
                {
                  "id": "deepseek-v4-flash",
                  "name": "DeepSeek V4 Flash",
                  "reasoning": true,
                  "contextWindow": 1048576,
                  "maxTokens": 393216,
                  "alsoUnknown": {"x": 1}
                }
              ]
            },
            "my-relay": {
              "baseUrl": "https://relay.example.com",
              "api": "anthropic-messages",
              "models": [{"id": "m1", "input": ["text", "image"], "cost": {"input": 1.5, "output": 6}}]
            }
          }
        }
        """;

    @Test
    void missingFileYieldsEmptyConfig() {
        var config = ModelsJsonConfig.load(tmp.resolve("absent.json"));
        assertThat(config.isEmpty()).isTrue();
        assertThat(config.providerIds()).isEmpty();
    }

    @Test
    void parsesProvidersWithIdFromMapKey() {
        var config = write(FULL_CONFIG);
        // Jackson map deserialization does not preserve file order.
        assertThat(config.providerIds()).containsExactlyInAnyOrder("teamorouter", "my-relay");
        var def = config.provider("teamorouter");
        assertThat(def).isNotNull();
        assertThat(def.name()).isEqualTo("TeamoRouter");
        assertThat(def.baseUrl()).isEqualTo("https://api.teamorouter.cn/v1");
        assertThat(def.api()).isEqualTo("openai-completions");
        assertThat(def.apiKey()).isEqualTo("sk-inline-test");
    }

    @Test
    void ignoresUnknownFields() {
        var config = write(FULL_CONFIG);
        assertThat(config.provider("teamorouter")).isNotNull();
        assertThat(config.provider("teamorouter").models()).hasSize(1);
    }

    @Test
    void buildProvidersRoutesProtocolsAndDefaultsDisplayName() {
        var config = write(FULL_CONFIG);
        var providers = config.buildProviders();
        assertThat(providers).hasSize(2);

        var teamo = (ModelsJsonProvider) providers.stream()
            .filter(p -> p.name().equals("teamorouter")).findFirst().orElseThrow();
        assertThat(teamo.displayName()).isEqualTo("TeamoRouter");
        assertThat(teamo.supportedProtocols()).containsExactly(Protocol.OPENAI_COMPLETIONS);
        assertThat(teamo.providerConfig().defaultBaseUrl()).isEqualTo("https://api.teamorouter.cn/v1");

        var relay = (ModelsJsonProvider) providers.stream()
            .filter(p -> p.name().equals("my-relay")).findFirst().orElseThrow();
        assertThat(relay.displayName()).isEqualTo("my-relay");
        assertThat(relay.supportedProtocols()).containsExactly(Protocol.ANTHROPIC_MESSAGES);
    }

    @Test
    void catalogMapsModelDefaults() {
        var config = write(FULL_CONFIG);
        var models = config.catalog().listModels();
        assertThat(models).hasSize(2);

        var deepseek = models.stream()
            .filter(m -> m.id().modelName().equals("deepseek-v4-flash")).findFirst().orElseThrow();
        assertThat(deepseek.id().provider()).isEqualTo("teamorouter");
        assertThat(deepseek.displayName()).isEqualTo("DeepSeek V4 Flash");
        assertThat(deepseek.maxInputTokens()).isEqualTo(1_048_576);
        assertThat(deepseek.maxOutputTokens()).isEqualTo(393_216);
        assertThat(deepseek.capabilities()).contains(ModelCapability.TEXT, ModelCapability.THINKING);

        var m1 = models.stream()
            .filter(m -> m.id().modelName().equals("m1")).findFirst().orElseThrow();
        assertThat(m1.capabilities()).contains(ModelCapability.IMAGE_INPUT);
        assertThat(m1.maxInputTokens()).isEqualTo(128_000);
        assertThat(m1.maxOutputTokens()).isEqualTo(16_384);
        assertThat(m1.pricing().inputPrice()).isEqualTo(1.5);
        assertThat(m1.pricing().outputPrice()).isEqualTo(6.0);
    }

    // ------------------------------------------------- B8-3：models.json 的 compat 块

    /**
     * <b>B8-3</b>：{@code compat.allowEmptySignature} 从 models.json 读进
     * {@link com.pijava.ai.catalog.ModelInfo#compat()}。
     *
     * <p>与 B8-1/B8-2（{@code AnthropicThinkingReplayTest}）分工：那两条钉**投送与落线**
     * （compat 从 {@code StreamRequest} 到唯一行为点），这条钉**入口**（文件 → 目录元数据）。
     * 两者互不依赖 —— 这条不经过 {@code StreamRequest}，所以即使投送链断了也照样绿。</p>
     */
    @Test
    void readsAllowEmptySignatureFromCompatBlock() {
        var config = write("""
            {"providers": {"relay": {
              "baseUrl": "https://relay.example.com",
              "api": "anthropic-messages",
              "models": [{"id": "m1", "compat": {"allowEmptySignature": true}}]
            }}}
            """);

        var model = config.catalog().find(ModelId.of("relay", "m1")).orElseThrow();

        assertThat(model.compat().allowEmptySignature()).isTrue();
    }

    /**
     * <b>B8-3</b>：没有 {@code compat} 块 ⇒ 全 false（{@link ModelCompat#NONE}）。
     *
     * <p>pi `anthropic-messages.ts:193` 的 `?? false` 把「缺席」与「false」归一 ⇒ **二态**，
     * 不是三态（§8.34.4 决策 3）。这条与上一条合起来把二态钉成事实。</p>
     */
    @Test
    void absentCompatBlockMeansNoFlags() {
        var config = write("""
            {"providers": {"relay": {
              "baseUrl": "https://relay.example.com",
              "api": "anthropic-messages",
              "models": [{"id": "m1"}]
            }}}
            """);

        var model = config.catalog().find(ModelId.of("relay", "m1")).orElseThrow();

        assertThat(model.compat()).isEqualTo(ModelCompat.NONE);
    }

    /**
     * 包 A-10：顶层思考预算的两个键从 models.json 读进 {@code compat}。
     *
     * <p>⚠️ 这两个字段的**探测面只产出 {@code null}/{@code false}**
     * （pi {@code detectCompat:1662-1663}，注释：{@code not set on the generated catalog}）
     * ⇒ 内置目录一个都不标，**models.json 是唯一可达入口** ⇒ 这条不是「顺带」，是那
     * 两个字段在生产上的**唯一生产者**。</p>
     */
    @Test
    void readsTheThinkingTokenBudgetKeysFromCompatBlock() {
        var config = write("""
            {"providers": {"relay": {
              "baseUrl": "https://relay.example.com",
              "api": "openai-completions",
              "models": [{"id": "m1", "compat": {
                "thinkingTokenBudgetField": "thinking_budget_tokens",
                "supportsThinkingTokenBudget": true
              }}]
            }}}
            """);

        var compat = config.catalog().find(ModelId.of("relay", "m1")).orElseThrow().compat();

        assertThat(compat.thinkingTokenBudgetField())
            .isEqualTo(com.pijava.ai.catalog.ThinkingTokenBudgetField.THINKING_BUDGET_TOKENS);
        assertThat(compat.supportsThinkingTokenBudget()).isTrue();
    }

    /**
     * 包 A-10 第 6 步：{@code supportsMaxOutputTokens} 从 models.json 读进 {@code compat}，
     * 且**原样保留三态**（「没写」必须与「写了 {@code true}」可分 —— 前者会吃车道缺省）。
     *
     * <p>⚠️ 本键的探测面同样是常量（pi {@code types.ts:774} 的 {@code Default: true}，
     * 没有任何 detect 分支）⇒ {@code models.json} 是**唯一**能把它关掉的入口。</p>
     */
    @Test
    void readsTheMaxOutputTokensKeyFromCompatBlockAndKeepsItThreeState() {
        var config = write("""
            {"providers": {"relay": {
              "baseUrl": "https://relay.example.com",
              "api": "openai-responses",
              "models": [
                {"id": "off", "compat": {"supportsMaxOutputTokens": false}},
                {"id": "on", "compat": {"supportsMaxOutputTokens": true}},
                {"id": "silent", "compat": {"supportsStrictMode": true}}
              ]
            }}}
            """);

        var catalog = config.catalog();
        assertThat(catalog.find(ModelId.of("relay", "off")).orElseThrow()
            .compat().supportsMaxOutputTokens()).isFalse();
        assertThat(catalog.find(ModelId.of("relay", "on")).orElseThrow()
            .compat().supportsMaxOutputTokens()).isTrue();
        assertThat(catalog.find(ModelId.of("relay", "silent")).orElseThrow()
            .compat().supportsMaxOutputTokens())
            .as("没写 ⇒ 保持 null（由解析层补车道缺省）").isNull();
    }

    /**
     * 包 A-10：{@code thinkingTokenBudgetField} 的未知取值也是**响亮**的
     * （理由与 {@code maxTokensField} 同：字段名会静默换掉，没有其它症状）。
     */
    @Test
    void anUnknownThinkingTokenBudgetFieldIsALoudError() {
        var config = write("""
            {"providers": {"relay": {
              "baseUrl": "https://relay.example.com",
              "api": "openai-completions",
              "models": [{"id": "typo", "compat": {"thinkingTokenBudgetField": "thinking_budgt"}}]
            }}}
            """);

        assertThatThrownBy(() -> config.catalog())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("relay")
            .hasMessageContaining("typo")
            .hasMessageContaining("thinkingTokenBudgetField")
            .hasMessageContaining("thinking_budgt");
    }

    /**
     * <b>B8-3</b>：{@code compat} 块里**未知**的键被忽略（pi-java 只做被消费的那些标志）。
     *
     * <p>⚠️ 这是与 pi 的一处**刻意不同**：pi 的五个 compat 接口共 52 个字段（{@code types.ts:674}
     * 起），而 {@link com.pijava.ai.catalog.ModelCompat} 只携带本仓真正消费的十八个
     * —— 其余按 {@code docs/53 §4.4} 的归属表留给各自的包。忽略未知键使「pi 新加的 compat
     * 标志」不会把文件打崩 —— 代价是新标志会**静默失效**，故此处显式钉住该行为，
     * 免得日后误以为是解析 bug。</p>
     *
     * <p>⚠️ 但**已知键的取值**写错是**响亮**的：见 {@code anUnknownMaxTokensFieldIsALoudError}
     * —— 那条钉的是「字段名会静默换掉」而这里钉的是「新键静默失效」，两者代价不同。</p>
     */
    @Test
    void ignoresUnknownKeysInsideCompatBlock() {
        var config = write("""
            {"providers": {"relay": {
              "baseUrl": "https://relay.example.com",
              "api": "anthropic-messages",
              "models": [{"id": "m1", "compat": {
                "allowEmptySignature": true,
                "someFutureFlag": 1
              }}]
            }}}
            """);

        var model = config.catalog().find(ModelId.of("relay", "m1")).orElseThrow();

        assertThat(model.compat()).isEqualTo(ModelCompat.of(true));
    }

    /**
     * <b>B19</b>：{@code compat.requiresReasoningContentOnAssistantMessages} 从 models.json 读进
     * {@link com.pijava.ai.catalog.ModelCompat}，且**三态**原样保留。
     *
     * <p>与 {@code OpenAICompletionsReasoningReplayTest.explicitCompatFalseDisablesTheEmptyFill}
     * 分工：那条钉**行为**（显式 false 关掉回填），这条钉**入口**（文件里写的 false 真的变成
     * {@code FALSE} 而不是被吞成「没写」）。两者都会红，但红的含义不同 —— 入口若把
     * {@code null} 与 {@code false} 归一，行为侧是怎么也测不出来的。</p>
     */
    @Test
    void readsRequiresReasoningContentFromCompatBlock() {
        var config = write("""
            {"providers": {"relay": {
              "baseUrl": "https://relay.example.com",
              "api": "openai-completions",
              "models": [
                {"id": "m1", "compat": {"requiresReasoningContentOnAssistantMessages": false}},
                {"id": "m2"}
              ]
            }}}
            """);

        var explicit = config.catalog().find(ModelId.of("relay", "m1")).orElseThrow();
        assertThat(explicit.compat().requiresReasoningContentOnAssistantMessages()).isFalse();
        // ⚠️ 不能写成 isEqualTo(false) 的地方：null 与 false **不是一回事** ——
        // null = 按 provider/baseUrl 自动判（three-state，见 ModelCompat 的 javadoc）。
        assertThat(explicit.compat().requiresReasoningContentOnAssistantMessages()).isNotNull();

        var absent = config.catalog().find(ModelId.of("relay", "m2")).orElseThrow();
        assertThat(absent.compat().requiresReasoningContentOnAssistantMessages()).isNull();
    }

    /**
     * <b>B20</b>：{@code compat.supportsFinishReason} 从 models.json 读进
     * {@link com.pijava.ai.catalog.ModelCompat}，且**缺席即 {@code true}**。
     *
     * <p>⚠️ 这条钉的是**方向**。三个 compat 标志的缺席语义各不相同（见 {@code ModelCompat} 的
     * javadoc）：{@code allowEmptySignature} 缺席 ≙ {@code false}、
     * {@code requiresReasoningContentOnAssistantMessages} 缺席 ≙ 探测（{@code null}）、
     * 本标志缺席 ≙ <b>{@code true}</b>。落地时最容易犯的错是「顺手归一成 {@code ?? false}」——
     * 那会让**每一条**没写 compat 的 models.json 模型静默退回「缺 finish_reason 也算成功」的
     * 容忍版，而且**行为侧测不出来**（容忍版只是不报错，不会红）。</p>
     *
     * <p>与 {@code OpenAICompletionsApi} 的行为夹具分工：那条钉「显式 {@code false} 真的放宽了
     * 检查」，这条钉「入口没把缺席读成 false」。</p>
     */
    @Test
    void readsSupportsFinishReasonFromCompatBlock() {
        var config = write("""
            {"providers": {"relay": {
              "baseUrl": "https://relay.example.com",
              "api": "openai-completions",
              "models": [
                {"id": "relaxed", "compat": {"supportsFinishReason": false}},
                {"id": "strict", "compat": {"supportsFinishReason": true}},
                {"id": "keyAbsent", "compat": {"allowEmptySignature": true}},
                {"id": "blockAbsent"}
              ]
            }}}
            """);

        assertThat(config.catalog().find(ModelId.of("relay", "relaxed")).orElseThrow()
            .compat().supportsFinishReason()).isFalse();
        assertThat(config.catalog().find(ModelId.of("relay", "strict")).orElseThrow()
            .compat().supportsFinishReason()).isTrue();
        // ⚠️ 下面两条走的**不是**同一条路，必须分开钉：
        //   keyAbsent   ⇒ compat 块在、键不在 ⇒ compatOf 的 `== null ||` 分支
        //   blockAbsent ⇒ 连块都没有 ⇒ compatOf(null) ⇒ ModelCompat.NONE 的第三位
        // 只写一条的话，另一条路径上的「顺手归一成 ?? false」就测不出来。
        assertThat(config.catalog().find(ModelId.of("relay", "keyAbsent")).orElseThrow()
            .compat().supportsFinishReason()).isTrue();
        assertThat(config.catalog().find(ModelId.of("relay", "blockAbsent")).orElseThrow()
            .compat().supportsFinishReason()).isTrue();
        assertThat(ModelCompat.NONE.supportsFinishReason()).isTrue();
    }

    // ------------------------------------------------- 包 A7：compat 块扩到十四个键

    /**
     * <b>A7</b>：包 A7 新开的八个三态键从 models.json 原样读进
     * {@link com.pijava.ai.catalog.ModelCompat}。
     *
     * <p>⚠️ 这条钉的是**入口没把它们归一掉**：五个 mid-convo/tool 标志之外，
     * {@code supportsStore}／{@code supportsDeveloperRole}／{@code supportsStrictMode}
     * 的缺席都要能区分「用户没写」（{@code null} ⇒ 目录值与探测值活下来）与
     * 「用户写了 false」（压掉它们）。今天的 {@code models[]} 路径上两者行为相同
     * （整条替换，{@code docs/53 §3 F7} 的 path C），但 A-16 补上逐字段合并时立刻需要
     * （{@code docs/53 §9 R6}）。</p>
     */
    @Test
    void readsTheEightThreeStateCompatKeysFromCompatBlock() {
        var config = write("""
            {"providers": {"relay": {
              "baseUrl": "https://relay.example.com",
              "api": "openai-completions",
              "models": [
                {"id": "written", "compat": {
                  "supportsMidConvoSystemMessages": true,
                  "supportsMidConvoToolAdditions": false,
                  "supportsMidConvoToolChanges": true,
                  "supportsAdditionalTools": true,
                  "supportsToolSearch": false,
                  "supportsStore": false,
                  "supportsDeveloperRole": true,
                  "supportsStrictMode": true
                }},
                {"id": "absent"}
              ]
            }}}
            """);

        var written = config.catalog().find(ModelId.of("relay", "written")).orElseThrow();
        assertThat(written.compat().supportsMidConvoSystemMessages()).isTrue();
        assertThat(written.compat().supportsMidConvoToolAdditions()).isFalse();
        assertThat(written.compat().supportsMidConvoToolChanges()).isTrue();
        assertThat(written.compat().supportsAdditionalTools()).isTrue();
        assertThat(written.compat().supportsToolSearch()).isFalse();
        assertThat(written.compat().supportsStore()).isFalse();
        assertThat(written.compat().supportsDeveloperRole()).isTrue();
        assertThat(written.compat().supportsStrictMode()).isTrue();

        var absent = config.catalog().find(ModelId.of("relay", "absent")).orElseThrow();
        assertThat(absent.compat().supportsMidConvoSystemMessages()).isNull();
        assertThat(absent.compat().supportsMidConvoToolAdditions()).isNull();
        assertThat(absent.compat().supportsMidConvoToolChanges()).isNull();
        assertThat(absent.compat().supportsAdditionalTools()).isNull();
        assertThat(absent.compat().supportsToolSearch()).isNull();
        assertThat(absent.compat().supportsStore()).isNull();
        assertThat(absent.compat().supportsDeveloperRole()).isNull();
        assertThat(absent.compat().supportsStrictMode()).isNull();
    }

    /**
     * <b>A-01</b>：两个 cache 门从 models.json 读进 {@link com.pijava.ai.catalog.ModelCompat}。
     *
     * <p>与上面那条同形：缺席必须保持 {@code null}（＝「用户没写」），**不能**在
     * {@code compatOf} 里就塌成 {@code true} —— 缺省由解析层按车道补
     * （{@code forAnthropic} 的 {@code ?? true}），写死在这里会让 A-16 的逐字段合并失去
     * 「有没有写过」这个信息。</p>
     *
     * <p>⚠️ 与 {@code supportsTemperature} 的处理**刻意不同**：那一位是原始
     * {@code boolean}，所以 {@code compatOf} 必须当场给值（{@code ?? true}）；这两位是
     * {@code Boolean}，保持三态。</p>
     */
    @Test
    void readsTheTwoCacheGatesFromCompatBlock() {
        var config = write("""
            {"providers": {"relay": {
              "baseUrl": "https://relay.example.com",
              "api": "anthropic-messages",
              "models": [
                {"id": "written", "compat": {
                  "supportsLongCacheRetention": false,
                  "supportsCacheControlOnTools": false
                }},
                {"id": "absent"}
              ]
            }}}
            """);

        var written = config.catalog().find(ModelId.of("relay", "written")).orElseThrow();
        assertThat(written.compat().supportsLongCacheRetention()).isFalse();
        assertThat(written.compat().supportsCacheControlOnTools()).isFalse();

        var absent = config.catalog().find(ModelId.of("relay", "absent")).orElseThrow();
        assertThat(absent.compat().supportsLongCacheRetention()).isNull();
        assertThat(absent.compat().supportsCacheControlOnTools()).isNull();
    }

    /** <b>A7</b>：{@code compat.supportsTemperature} 缺席 ≙ <b>{@code true}</b>（与 B20 同形的方向钉）。 */
    @Test
    void readsSupportsTemperatureFromCompatBlock() {
        var config = write("""
            {"providers": {"relay": {
              "baseUrl": "https://relay.example.com",
              "api": "anthropic-messages",
              "models": [
                {"id": "suppressed", "compat": {"supportsTemperature": false}},
                {"id": "explicit", "compat": {"supportsTemperature": true}},
                {"id": "keyAbsent", "compat": {"allowEmptySignature": true}},
                {"id": "blockAbsent"}
              ]
            }}}
            """);

        assertThat(config.catalog().find(ModelId.of("relay", "suppressed")).orElseThrow()
            .compat().supportsTemperature()).isFalse();
        assertThat(config.catalog().find(ModelId.of("relay", "explicit")).orElseThrow()
            .compat().supportsTemperature()).isTrue();
        assertThat(config.catalog().find(ModelId.of("relay", "keyAbsent")).orElseThrow()
            .compat().supportsTemperature()).isTrue();
        assertThat(config.catalog().find(ModelId.of("relay", "blockAbsent")).orElseThrow()
            .compat().supportsTemperature()).isTrue();
    }

    /**
     * <b>A7</b>：{@code compat.maxTokensField} 的两个合法取值映射到
     * {@link com.pijava.ai.catalog.MaxTokensField}，缺席留 {@code null}（⇒ 请求期探测）。
     */
    @Test
    void readsMaxTokensFieldFromCompatBlock() {
        var config = write("""
            {"providers": {"relay": {
              "baseUrl": "https://relay.example.com",
              "api": "openai-completions",
              "models": [
                {"id": "plain", "compat": {"maxTokensField": "max_tokens"}},
                {"id": "openai", "compat": {"maxTokensField": "max_completion_tokens"}},
                {"id": "absent"}
              ]
            }}}
            """);

        assertThat(config.catalog().find(ModelId.of("relay", "plain")).orElseThrow()
            .compat().maxTokensField())
            .isEqualTo(com.pijava.ai.catalog.MaxTokensField.MAX_TOKENS);
        assertThat(config.catalog().find(ModelId.of("relay", "openai")).orElseThrow()
            .compat().maxTokensField())
            .isEqualTo(com.pijava.ai.catalog.MaxTokensField.MAX_COMPLETION_TOKENS);
        assertThat(config.catalog().find(ModelId.of("relay", "absent")).orElseThrow()
            .compat().maxTokensField()).isNull();
    }

    /**
     * <b>A7</b>：未知的 {@code maxTokensField} 取值是**响亮失败**，不是被忽略。
     *
     * <p>⚠️ 与同文件 {@code ignoresUnknownKeysInsideCompatBlock}（未知<b>键</b>被忽略）
     * 是**不同**的两件事：那一条的代价是「新标志静默失效」，而这里的代价是
     * 「线格上的**字段名**静默换掉」—— 没有任何其它症状。pi 的 zod 联合同样会拒绝未知取值。</p>
     *
     * <p>⚠️ 负向断言钉住**消息里的字段名与取值**：只断言 {@code isInstanceOf} 的话，
     * 缺陷态（把未知取值吞成 {@code null}）抛的也是别的异常/不抛，测不出差别
     * （{@code docs/50 §12.2-3} 的教训）。</p>
     */
    @Test
    void anUnknownMaxTokensFieldIsALoudError() {
        var config = write("""
            {"providers": {"relay": {
              "baseUrl": "https://relay.example.com",
              "api": "openai-completions",
              "models": [{"id": "typo", "compat": {"maxTokensField": "max_token"}}]
            }}}
            """);

        assertThatThrownBy(() -> config.catalog())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("relay")
            .hasMessageContaining("typo")
            .hasMessageContaining("maxTokensField")
            .hasMessageContaining("max_token");
    }

    @Test
    void missingApiThrowsWithProviderId() {
        var config = write("""
            {"providers": {"broken": {"baseUrl": "https://x.example.com",
              "models": [{"id": "m"}]}}}
            """);
        assertThatThrownBy(config::buildProviders)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("broken")
            .hasMessageContaining("api");
    }

    @Test
    void missingBaseUrlThrowsWithProviderId() {
        var config = write("""
            {"providers": {"broken": {"api": "openai-completions",
              "models": [{"id": "m"}]}}}
            """);
        assertThatThrownBy(config::buildProviders)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("broken")
            .hasMessageContaining("baseUrl");
    }

    @Test
    void missingModelIdThrowsWithProviderId() {
        var config = write("""
            {"providers": {"broken": {"api": "openai-completions",
              "baseUrl": "https://x.example.com", "models": [{"name": "m"}]}}}
            """);
        assertThatThrownBy(config::buildProviders)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("broken")
            .hasMessageContaining("id");
    }

    @Test
    void malformedJsonThrowsWithPath() throws IOException {
        var path = tmp.resolve("bad-" + System.nanoTime() + ".json");
        Files.writeString(path, "{not json");
        assertThatThrownBy(() -> ModelsJsonConfig.load(path))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(path.toString());
    }

    // ── 包 H1 步 6：cost 扩键（J10）＋ 半价→UNKNOWN（J11，裁决 F）

    /** 单 provider 单 model 的最小配置，返回那个 model 的目录条目。 */
    private ModelInfo modelWith(String... modelFields) {
        var config = write("{\"providers\":{\"p\":{\"baseUrl\":\"https://x.invalid\","
            + "\"api\":\"openai-completions\",\"models\":[{\"id\":\"m\""
            + (modelFields.length == 0 ? "" : "," + String.join(",", modelFields)) + "}]}}}");
        return config.catalog().listModels().stream()
            .filter(m -> m.id().modelName().equals("m"))
            .findFirst().orElseThrow();
    }

    /**
     * T11（A2 的钉子，裁决 F）：{@code cost} 写了但只给一半 ⇒ 整体按<b>「未知」</b>
     * （-1）处理，而不是「免费」（0）。pi 的 zod 对存在的 cost 强制四费率齐全
     * （{@code model-config.ts:125-130}）⇒ 半价在 pi 是校验失败，静默落 0 是引入偏差。
     */
    @Test
    void halfPriceCostBecomesUnknownNotFree() {
        var m = modelWith("\"cost\": {\"input\": 1.5}");
        assertThat(m.pricing().isKnown()).isFalse();
        assertThat(m.pricing().inputPrice()).isEqualTo(-1);
        assertThat(m.pricing().outputPrice()).isEqualTo(-1);
    }

    /**
     * 对照的一条：{@code cost} 整块缺席 ⇒ <b>免费</b>（四费率 0）—— 照抄 pi
     * {@code provider-composer.ts:165} 的 {@code definition.cost ?? {input:0,output:0,
     * cacheRead:0,cacheWrite:0}}。「缺席=刻意免费」「存在但残缺=未知」是 pi 的两个不同语义。
     */
    @Test
    void absentCostMeansFreePerPiDefault() {
        var m = modelWith();
        assertThat(m.pricing().isKnown()).isTrue();
        assertThat(m.pricing().inputPrice()).isZero();
        assertThat(m.pricing().outputPrice()).isZero();
        assertThat(m.pricing().cacheReadPrice()).isZero();
        assertThat(m.pricing().cacheWritePrice()).isZero();
    }

    /**
     * J10：{@code cacheRead}/{@code cacheWrite} 键抵达 {@code PricingInfo} ——
     * 此前 {@code Cost} 只声明两键且 {@code ignoreUnknown=true}，用户写了 cache 价
     * 被<b>静默吞掉</b>。
     */
    @Test
    void cacheRateKeysAreHonored() {
        var m = modelWith("\"cost\": {\"input\": 1, \"output\": 2,"
            + " \"cacheRead\": 0.1, \"cacheWrite\": 1.25}");
        assertThat(m.pricing().inputPrice()).isEqualTo(1);
        assertThat(m.pricing().outputPrice()).isEqualTo(2);
        assertThat(m.pricing().cacheReadPrice()).isEqualTo(0.1);
        assertThat(m.pricing().cacheWritePrice()).isEqualTo(1.25);
        assertThat(m.pricing().isCachePricingKnown()).isTrue();
    }

    /** 裁决 B 在本入口的样子：input/output 齐全、cache 未写 ⇒ -1（未知），不是 0（免费）。 */
    @Test
    void absentCacheRatesAreUnknownNotFree() {
        var m = modelWith("\"cost\": {\"input\": 1, \"output\": 2}");
        assertThat(m.pricing().isKnown()).isTrue();
        assertThat(m.pricing().isCachePricingKnown()).isFalse();
        assertThat(m.pricing().cacheReadPrice()).isEqualTo(-1);
        assertThat(m.pricing().cacheWritePrice()).isEqualTo(-1);
    }

    /** J10：{@code tiers} 抵达 {@code PricingInfo.CostTier}（pi {@code ModelCostTierSchema}）。 */
    @Test
    void costTiersAreParsed() {
        var m = modelWith("\"cost\": {\"input\": 1, \"output\": 2, \"cacheRead\": 0.1,"
            + " \"cacheWrite\": 1.25, \"tiers\": [{\"inputTokensAbove\": 272000,"
            + " \"input\": 2.4, \"output\": 4.8, \"cacheRead\": 0.24, \"cacheWrite\": 3.0}]}");
        assertThat(m.pricing().tiers()).hasSize(1);
        var tier = m.pricing().tiers().get(0);
        assertThat(tier.inputTokensAbove()).isEqualTo(272_000);
        assertThat(tier.inputPrice()).isEqualTo(2.4);
        assertThat(tier.outputPrice()).isEqualTo(4.8);
        assertThat(tier.cacheReadPrice()).isEqualTo(0.24);
        assertThat(tier.cacheWritePrice()).isEqualTo(3.0);
    }

    /** tier 缺任一必填数 ⇒ 与 pi 一样按校验失败拒载（不静默补 0 造出假价）。 */
    @Test
    void incompleteTierIsRejected() {
        assertThatThrownBy(() -> modelWith("\"cost\": {\"input\": 1, \"output\": 2,"
            + " \"cacheRead\": 0.1, \"cacheWrite\": 1.25,"
            + " \"tiers\": [{\"inputTokensAbove\": 272000, \"input\": 2.4}]}"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("tier");
    }

    private ModelsJsonConfig write(String json) {
        var path = tmp.resolve("models-" + System.nanoTime() + ".json");
        try {
            Files.writeString(path, json);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return ModelsJsonConfig.load(path);
    }
}
