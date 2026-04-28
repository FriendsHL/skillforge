package com.skillforge.server.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.view.SessionSkillResolver;
import com.skillforge.core.skill.view.SessionSkillView;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.repository.AgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Plan r2 §5 — DefaultSessionSkillResolver。
 * 解析逻辑：
 * <ol>
 *   <li>system skills = SkillRegistry 中所有 {@code def.isSystem() == true}，
 *       减去 agent.disabledSystemSkills 集合；</li>
 *   <li>user skills = 解析 agent.skillIds JSON，逐个查 SkillRegistry，
 *       命中即加入；同名 system skill 优先（已经被 system loader registerSkillDefinition 覆盖到 registry）；</li>
 *   <li>合并去重（system 优先），构造不可变 {@link SessionSkillView}。</li>
 * </ol>
 */
@Component
public class DefaultSessionSkillResolver implements SessionSkillResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultSessionSkillResolver.class);

    private final SkillRegistry skillRegistry;
    private final AgentRepository agentRepository;
    private final ObjectMapper objectMapper;

    public DefaultSessionSkillResolver(SkillRegistry skillRegistry,
                                       AgentRepository agentRepository,
                                       ObjectMapper objectMapper) {
        this.skillRegistry = skillRegistry;
        this.agentRepository = agentRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public SessionSkillView resolveFor(AgentDefinition agentDef) {
        if (agentDef == null) {
            return SessionSkillView.EMPTY;
        }

        // disabled system skills set — best-effort, on JSON parse failure treat as empty.
        Set<String> disabledSystem = loadDisabledSystemSkills(agentDef);

        Map<String, SkillDefinition> allowed = new LinkedHashMap<>();
        Set<String> systemEnabledNames = new LinkedHashSet<>();
        Set<String> userBoundNames = new LinkedHashSet<>();

        // 1) System skills (loaded by SystemSkillLoader; flag set on def)
        for (SkillDefinition def : skillRegistry.getAllSkillDefinitions()) {
            if (def.isSystem()) {
                if (disabledSystem.contains(def.getName())) {
                    continue;
                }
                allowed.put(def.getName(), def);
                systemEnabledNames.add(def.getName());
            }
        }

        // 2) User skills bound via agent.skillIds — names override only if not already present.
        if (agentDef.getSkillIds() != null) {
            for (String name : agentDef.getSkillIds()) {
                if (name == null || name.isBlank()) continue;
                Optional<SkillDefinition> opt = skillRegistry.getSkillDefinition(name);
                if (opt.isEmpty()) {
                    continue;
                }
                SkillDefinition def = opt.get();
                userBoundNames.add(name);
                if (allowed.containsKey(name) && allowed.get(name).isSystem()) {
                    // System with same name already wins (plan §6).
                    log.debug("User skill name '{}' shadowed by system skill — keeping system def", name);
                    continue;
                }
                allowed.put(name, def);
            }
        }

        return new SessionSkillView(allowed, systemEnabledNames, userBoundNames);
    }

    private Set<String> loadDisabledSystemSkills(AgentDefinition agentDef) {
        // AgentDefinition does not yet carry disabled_system_skills; read from entity by id.
        // Best-effort lookup; unknown / missing → empty.
        if (agentDef.getId() == null) return Collections.emptySet();
        try {
            Long id = Long.parseLong(agentDef.getId());
            AgentEntity entity = agentRepository.findById(id).orElse(null);
            if (entity == null) return Collections.emptySet();
            String json = entity.getDisabledSystemSkills();
            if (json == null || json.isBlank() || "[]".equals(json.trim())) {
                return Collections.emptySet();
            }
            List<String> names = objectMapper.readValue(json, new TypeReference<List<String>>() {});
            return names == null ? Collections.emptySet() : Set.copyOf(names);
        } catch (NumberFormatException e) {
            return Collections.emptySet();
        } catch (Exception e) {
            log.warn("Failed to parse disabled_system_skills for agent={}: {}",
                    agentDef.getId(), e.getMessage());
            return Collections.emptySet();
        }
    }
}
