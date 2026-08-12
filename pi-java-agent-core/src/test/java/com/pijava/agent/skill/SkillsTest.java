package com.pijava.agent.skill;

import java.util.Map;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillsTest {

    private static Skill skill(String name) {
        return new Skill() {
            @Override public String name() { return name; }
            @Override public String label() { return name; }
            @Override public String description() { return name + " description"; }
            @Override public String systemPrompt() { return "Use " + name; }
        };
    }

    @Test
    void registerAndGetSkill() {
        var manager = new SkillManager();
        manager.register(skill("tdd"));
        assertThat(manager.get("tdd").name()).isEqualTo("tdd");
    }

    @Test
    void allReturnsRegisteredSkills() {
        var manager = new SkillManager();
        manager.register(skill("a"));
        manager.register(skill("b"));
        assertThat(manager.all()).hasSize(2);
    }

    @Test
    void unknownSkillThrows() {
        var manager = new SkillManager();
        assertThatThrownBy(() -> manager.get("missing"))
                .isInstanceOf(SkillManager.UnknownSkillException.class);
    }

    @Test
    void registerAndRenderTemplate() {
        var manager = new SkillManager();
        manager.registerTemplate(new PromptTemplate() {
            @Override public String name() { return "greet"; }
            @Override public String render(Map<String, Object> vars) {
                return "Hello " + vars.get("name");
            }
        });
        assertThat(manager.template("greet").render(Map.of("name", "World")))
                .isEqualTo("Hello World");
    }

    @Test
    void unknownTemplateThrows() {
        var manager = new SkillManager();
        assertThatThrownBy(() -> manager.template("missing"))
                .isInstanceOf(SkillManager.UnknownTemplateException.class);
    }
}
