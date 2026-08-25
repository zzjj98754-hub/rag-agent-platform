package com.example.demo.agent.tool;

import com.example.demo.security.UserRole;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Tool 权限评估器 —— 基于角色的访问控制。
 *
 * 面试要点：
 * - 模型可能被 prompt injection 诱导调用不该调的工具，权限校验是最后一道防线
 * - 默认拒绝原则：未明确授权的操作一律禁止
 * - 生产环境可扩展为 RBAC / ABAC，对接企业权限中心
 */
@Component
public class ToolPermissionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ToolPermissionEvaluator.class);

    /**
     * 角色 → 权限集合 映射（演示用，生产从 DB/配置中心加载）
     */
    private static final Map<UserRole, Set<String>> ROLE_PERMISSIONS = Map.of(
            UserRole.ADMIN, Set.of("*"),
            UserRole.ANALYST, Set.of("KNOWLEDGE_SEARCH", "CALCULATOR", "MCP_TOOL"),
            UserRole.USER, Set.of("KNOWLEDGE_SEARCH", "CALCULATOR"),
            UserRole.GUEST, Set.of("KNOWLEDGE_SEARCH")
    );

    /**
     * 检查指定角色是否有权使用该工具。
     *
     * @param tool  目标工具
     * @param role  用户角色
     * @return true=允许
     */
    public boolean check(ToolDefinition tool, UserRole role) {
        if (role == null) {
            role = UserRole.GUEST;
        }

        Set<String> required = tool.requiredPermissions();
        if (required.isEmpty()) {
            return true; // 无需权限的工具，所有人可用
        }

        Set<String> granted = ROLE_PERMISSIONS.getOrDefault(role, Set.of());
        if (granted.contains("*")) {
            return true; // admin 角色
        }

        boolean allowed = granted.containsAll(required);
        if (!allowed) {
            log.warn("工具权限拒绝 | tool={} role={} required={} granted={}",
                    tool.name(), role, required, granted);
        }
        return allowed;
    }

}
