package com.skillforge.core.skill;

import com.skillforge.core.model.SkillDefinition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skill 注册中心，管理内置 Skill 实例和基于 zip 包的 SkillDefinition。
 */
public class SkillRegistry {

    private final ConcurrentHashMap<String, Skill> skills = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SkillDefinition> skillDefinitions = new ConcurrentHashMap<>();

    /**
     * 注册内置 Skill。
     */
    public void register(Skill skill) {
        skills.put(skill.getName(), skill);
    }

    /**
     * 注销内置 Skill。
     */
    public void unregister(String name) {
        skills.remove(name);
    }

    /**
     * 获取内置 Skill。
     */
    public Optional<Skill> getSkill(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    /**
     * 获取所有已注册的内置 Skill。
     */
    public Collection<Skill> getAllSkills() {
        return Collections.unmodifiableCollection(skills.values());
    }

    /**
     * 注册基于 zip 包的 SkillDefinition。
     */
    public void registerSkillDefinition(SkillDefinition definition) {
        skillDefinitions.put(definition.getName(), definition);
    }

    /**
     * 注销 SkillDefinition。
     */
    public void unregisterSkillDefinition(String name) {
        skillDefinitions.remove(name);
    }

    /**
     * 获取 SkillDefinition。
     */
    public Optional<SkillDefinition> getSkillDefinition(String name) {
        return Optional.ofNullable(skillDefinitions.get(name));
    }

    /**
     * 获取所有已注册的 SkillDefinition。
     */
    public Collection<SkillDefinition> getAllSkillDefinitions() {
        return Collections.unmodifiableCollection(skillDefinitions.values());
    }
}
