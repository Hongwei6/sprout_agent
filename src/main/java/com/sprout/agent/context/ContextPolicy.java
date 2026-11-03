package com.sprout.agent.context;

import java.util.HashSet;
import java.util.Set;

/**
 * 上下文压缩策略。
 *
 * @param tokenThreshold  auto_compact 的 token 估算阈值，超过则用 LLM 摘要替换全部旧消息
 * @param keepRecentTools micro_compact 保留最近几次工具调用的完整内容（ToolResponse 与 ToolCall 参数一致）
 * @param maxToolLength   工具内容超过该字符数才替换为占位符；0 表示只压缩 ToolResponse、不压缩参数
 * @param protectedTools  额外受保护的工具名；read_skill 内置保护（其返回是技能执行指南，压缩后 Agent 将失去说明书）
 */
public record ContextPolicy(int tokenThreshold, int keepRecentTools, int maxToolLength, Set<String> protectedTools) {

    private static final Set<String> BUILTIN_PROTECTED_TOOLS = Set.of("read_skill");

    public ContextPolicy {
        if (tokenThreshold <= 0) throw new IllegalArgumentException("tokenThreshold must be positive");
        if (keepRecentTools < 0) throw new IllegalArgumentException("keepRecentTools must not be negative");
        if (maxToolLength < 0) throw new IllegalArgumentException("maxToolLength must not be negative");
        Set<String> merged = new HashSet<>(BUILTIN_PROTECTED_TOOLS);
        if (protectedTools != null) merged.addAll(protectedTools);
        protectedTools = Set.copyOf(merged);
    }

    public boolean isProtected(String toolName) {
        return toolName != null && protectedTools.contains(toolName);
    }
}
