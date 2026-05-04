package com.skillforge.core.skill.view;

import com.skillforge.core.model.SkillDefinition;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Per-session skill 授权视图。Plan r2 §5。
 * <p>
 * <b>边界（B-3）</b>：view 只管 skill 包（system skill + user skill = SkillDefinition 实例），
 * 不管内置 Java Tool（Bash / Read / Memory / SubAgent 等）。内置 Tool 永远全部可用，受
 * {@code LoopContext.excludedSkillNames} / {@code allowedToolNames} 的 depth-aware 限制，
 * 与 view 正交。
 * <p>
 * <b>调用方协议</b>：调用方必须先判断 name 是 Tool 还是 Skill，再决定查 view 或查 SkillRegistry：
 * <pre>{@code
 *   if (skillRegistry.getTool(name).isPresent()) {
 *       // 内置 Tool 直接放行（depth-aware 仍生效）
 *   } else if (view.isAllowed(name)) {
 *       // skill 包路径
 *   } else {
 *       // NOT_ALLOWED
 *   }
 * }</pre>
 * <p>
 * 不可变值对象：构造后内部 map / set 都拷贝并以 unmodifiable 视图暴露。
 */
public final class SessionSkillView {

    /** Empty view — no skills allowed. */
    public static final SessionSkillView EMPTY = new SessionSkillView(
            Collections.emptyMap(), Collections.emptySet(), Collections.emptySet());

    /** Skill 包级 name → def，仅包含本 session 授权可见的 skills。 */
    private final Map<String, SkillDefinition> allowedSkills;
    /** 该 agent 启用的 system skill 名集合（用于诊断 / 测试断言）。 */
    private final Set<String> systemEnabledNames;
    /** agent.skillIds 解析结果（user-bound skill names），诊断用。 */
    private final Set<String> userBoundSkillNames;

    public SessionSkillView(Map<String, SkillDefinition> allowedSkills,
                            Set<String> systemEnabledNames,
                            Set<String> userBoundSkillNames) {
        Objects.requireNonNull(allowedSkills, "allowedSkills");
        // Defensive copy to keep view immutable from caller mutation.
        this.allowedSkills = Collections.unmodifiableMap(new LinkedHashMap<>(allowedSkills));
        this.systemEnabledNames = systemEnabledNames != null
                ? Set.copyOf(systemEnabledNames)
                : Collections.emptySet();
        this.userBoundSkillNames = userBoundSkillNames != null
                ? Set.copyOf(userBoundSkillNames)
                : Collections.emptySet();
    }

    /** @return SkillDefinition by name; empty if not allowed or not a skill-package name. */
    public Optional<SkillDefinition> resolve(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(allowedSkills.get(name));
    }

    /** @return all skill-package definitions visible to this session. */
    public Collection<SkillDefinition> all() {
        return allowedSkills.values();
    }

    /**
     * @return {@code true} iff {@code name} maps to an allowed skill package.
     * <p><b>语义</b>：仅对 skill 包 name 有意义；对内置 Tool name（如 "Bash"）一律返回 false。
     * 调用方应先用 {@code skillRegistry.getTool(name)} 判断是否内置 Tool。
     */
    public boolean isAllowed(String name) {
        return name != null && allowedSkills.containsKey(name);
    }

    /** Diagnostics: names of system skills enabled for this view. */
    public Set<String> systemEnabledNames() {
        return systemEnabledNames;
    }

    /** Diagnostics: names of user-bound (agent.skillIds) skills resolved for this view. */
    public Set<String> userBoundSkillNames() {
        return userBoundSkillNames;
    }
}
