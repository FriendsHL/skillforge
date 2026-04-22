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

    private final ConcurrentHashMap<String, Tool> tools = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SkillDefinition> skillDefinitions = new ConcurrentHashMap<>();

    /**
     * 注册 Java Tool。
     */
    public void registerTool(Tool tool) {
        tools.put(tool.getName(), tool);
    }

    /**
     * 兼容旧 API：注册内置 Skill（实际按 Tool 注册）。
     */
    public void register(Skill skill) {
        registerTool(skill);
    }

    /**
     * 注销 Java Tool。
     */
    public void unregisterTool(String name) {
        tools.remove(name);
    }

    /**
     * 兼容旧 API：注销内置 Skill（实际按 Tool 注销）。
     */
    public void unregister(String name) {
        unregisterTool(name);
    }

    /**
     * 获取 Java Tool。
     */
    public Optional<Tool> getTool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * 兼容旧 API：按 Skill 名称获取（仅返回同时实现 Skill 的 Tool）。
     */
    public Optional<Skill> getSkill(String name) {
        Tool tool = tools.get(name);
        if (tool instanceof Skill skill) {
            return Optional.of(skill);
        }
        return Optional.empty();
    }

    /**
     * 获取所有已注册的 Java Tool。
     */
    public Collection<Tool> getAllTools() {
        return Collections.unmodifiableCollection(tools.values());
    }

    /**
     * 兼容旧 API：获取所有已注册的内置 Skill（仅包含实现了 Skill 的 Tool）。
     */
    public Collection<Skill> getAllSkills() {
        List<Skill> skills = new ArrayList<>();
        for (Tool tool : tools.values()) {
            if (tool instanceof Skill skill) {
                skills.add(skill);
            }
        }
        return Collections.unmodifiableCollection(skills);
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
