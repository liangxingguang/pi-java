package com.pijava.agent.skill;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Registry for named skills.
 */
public class SkillManager {
    private final ConcurrentMap<String, Skill> skills = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PromptTemplate> templates = new ConcurrentHashMap<>();

    /** Register a skill. */
    public void register(Skill skill) {
        skills.put(skill.name(), skill);
    }

    /** Look up a skill by name. */
    public Skill get(String name) {
        var skill = skills.get(name);
        if (skill == null) throw new UnknownSkillException(name);
        return skill;
    }

    /** List all registered skills. */
    public Collection<Skill> all() {
        return List.copyOf(skills.values());
    }

    /** Register a named prompt template. */
    public void registerTemplate(PromptTemplate template) {
        templates.put(template.name(), template);
    }

    /** Look up a prompt template by name. */
    public PromptTemplate template(String name) {
        var template = templates.get(name);
        if (template == null) throw new UnknownTemplateException(name);
        return template;
    }

    /** Exception thrown when a skill is not found. */
    public static final class UnknownSkillException extends RuntimeException {
        public UnknownSkillException(String name) {
            super("Unknown skill: " + name);
        }
    }

    /** Exception thrown when a prompt template is not found. */
    public static final class UnknownTemplateException extends RuntimeException {
        public UnknownTemplateException(String name) {
            super("Unknown template: " + name);
        }
    }
}
