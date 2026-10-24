package com.pijava.coding.agent.core;

import java.util.List;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 18 T1（docs/29 §3）：pi {@code PackageSourceSchema}（settings-schema.ts:170-190）
 * union 两形互转＋写回不胀形。
 */
class PackageSourceJsonTest {

    @Test
    void stringFormDeserializesToObjectShape() throws Exception {
        var source = Json.mapper().readValue("\"npm:@org/pkg@^1.2\"", PackageSource.class);

        assertThat(source.source()).isEqualTo("npm:@org/pkg@^1.2");
        assertThat(source.autoload()).isNull();
        assertThat(source.extensions()).isNull();
        assertThat(source.skills()).isNull();
        assertThat(source.prompts()).isNull();
        assertThat(source.themes()).isNull();
        assertThat(source.stringForm()).isTrue();
    }

    @Test
    void objectFormDeserializesAllFields() throws Exception {
        var json = """
            {"source":"git:github.com/u/r","autoload":false,
             "extensions":["!**/*.test.jar"],"skills":["skills/foo"],
             "prompts":["+extra.md"],"themes":[]}
            """;
        var source = Json.mapper().readValue(json, PackageSource.class);

        assertThat(source.source()).isEqualTo("git:github.com/u/r");
        assertThat(source.autoload()).isFalse();
        assertThat(source.extensions()).containsExactly("!**/*.test.jar");
        assertThat(source.skills()).containsExactly("skills/foo");
        assertThat(source.prompts()).containsExactly("+extra.md");
        assertThat(source.themes()).isEmpty();
        assertThat(source.stringForm()).isFalse();
    }

    @Test
    void stringFormWritesBackAsBareString() throws Exception {
        var source = Json.mapper().readValue("\"npm:foo\"", PackageSource.class);

        assertThat(Json.mapper().writeValueAsString(source)).isEqualTo("\"npm:foo\"");
    }

    @Test
    void objectFormWritesBackOnlyNonNullKeys() throws Exception {
        var json = """
            {"source":"npm:foo","autoload":false,"skills":["a.md"]}
            """;
        var source = Json.mapper().readValue(json, PackageSource.class);

        // 不胀形：未配置的 extensions/prompts/themes 不出现在写回 JSON。
        assertThat(Json.mapper().writeValueAsString(source))
            .isEqualTo("{\"source\":\"npm:foo\",\"autoload\":false,\"skills\":[\"a.md\"]}");
    }

    @Test
    void settingsPackagesKeyRoundTripsBothForms() throws Exception {
        var settings = new Settings();
        settings.packages = List.of(
            PackageSource.of("npm:foo@1.0.0"),
            PackageSource.object("git:github.com/u/r", false, null, List.of("s.md"), null, null));

        var json = Json.mapper().writeValueAsString(settings);
        assertThat(json).contains("\"npm:foo@1.0.0\"");
        assertThat(json).contains("\"source\":\"git:github.com/u/r\"");

        var parsed = Json.mapper().readValue(json, Settings.class);
        assertThat(parsed.packages).hasSize(2);
        assertThat(parsed.packages.get(0).stringForm()).isTrue();
        assertThat(parsed.packages.get(0).source()).isEqualTo("npm:foo@1.0.0");
        assertThat(parsed.packages.get(1).stringForm()).isFalse();
        assertThat(parsed.packages.get(1).autoload()).isFalse();
        assertThat(parsed.packages.get(1).skills()).containsExactly("s.md");
    }
}
