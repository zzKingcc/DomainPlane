package com.zzkingcc.domainplane.server.mcp;

/**
 * MCP 传输类型。
 *
 * <ul>
 *   <li>{@code STREAMABLE_HTTP} —— 2025-03-26 规范：单一端点，POST JSON-RPC，
 *       响应可能是 JSON 或 SSE 流，会话通过 {@code Mcp-Session-Id} 头维系。</li>
 *   <li>{@code LEGACY_SSE} —— 2024-11-05 规范：先 GET /sse 建立流、服务端回一个动态
 *       POST endpoint 事件，再 POST 到那个动态地址；无 session 头，会话编码在动态 endpoint 里。</li>
 * </ul>
 *
 * 两者均在 {@code McpRpcClient} 实装；STREAMABLE_HTTP 为 v1 主力（mock 与联调均覆盖），
 * LEGACY_SSE 已实现但尚未对真实旧 server 联调，选用前需自行验证。
 *
 * @author zzkingcc
 */
public enum McpTransport {
    STREAMABLE_HTTP,
    LEGACY_SSE
}
