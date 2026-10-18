package com.zzkingcc.domainplane.server.mcp;

import com.zzkingcc.domainplane.api.annotation.Tool;

/**
 * 连接级审批策略：与外部 server 自己声明的 annotations 共同决定工具是否需要人工批准。
 *
 * 默认 {@link #ALL}：annotations 是外部输入，平台不该替不可信的 server 背书其
 * "readOnlyHint"。默认可信度为零、逐项放权是更安全的方向。
 *
 * @author zzkingcc
 */
public enum McpApprovalPolicy {

    /** 所有 MCP 工具都需人工批准（推荐默认） */
    ALL,

    /** 只有副作用为 DESTRUCTIVE（或未声明 annotations）的工具需批准 */
    DESTRUCTIVE_ONLY,

    /** READ 免批准，其余需批准（适合内网可信 server） */
    READ_ONLY_FREE,

    /** 全部免批准（仅建议用于内网高度可信 server） */
    NONE;

    /** 结合工具副作用，判定该工具是否需要人工批准 */
    public boolean requiresApproval(Tool.Effect sideEffect) {
        return switch (this) {
            case ALL -> true;
            case DESTRUCTIVE_ONLY -> sideEffect == Tool.Effect.DESTRUCTIVE;
            case READ_ONLY_FREE -> sideEffect != Tool.Effect.READ;
            case NONE -> false;
        };
    }
}
