package com.skillforge.core.skill;

import com.skillforge.core.model.SkillDefinition;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tool 注册中心，管理内置 Tool 实例和基于 zip 包的 SkillDefinition。
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
     * 注销 Java Tool。
     */
    public void unregisterTool(String name) {
        tools.remove(name);
    }

    /**
     * 获取 Java Tool。
     */
    public Optional<Tool> getTool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * 获取所有已注册的 Java Tool。
     */
    public Collection<Tool> getAllTools() {
        return Collections.unmodifiableCollection(tools.values());
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
