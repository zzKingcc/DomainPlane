package com.zzkingcc.domainplane.api.conversation;

import java.util.List;

/**
 * 装配清单的存放（F04-a：每轮召回可观测）。
 *
 * <p>与 {@link ConversationArchive} 的分工同上：归档是<b>真相</b>，这里是<b>派生账本</b>。
 * 派生意味着两件事：写失败只告警不抛；存储可以裁剪（每会话只留最近若干条、整体带 TTL），
 * 裁剪不构成"丢历史"——被裁的是"某轮装了什么"的账，不是对话内容。</p>
 *
 * <p><b>键形如</b> {@code domainplane:conv:asm:{域}|{session}}（List，LPUSH + LTRIM）。
 * 与倒排索引同族前缀 {@code domainplane:conv:}，删域级联按同一套扫描清理。</p>
 *
 * @author zzkingcc
 */
public interface AssemblyReportStore {

    /** 每会话保留的清单条数（超出即裁最老的；账本不是档案） */
    int KEEP_PER_SESSION = 50;

    /**
     * 记一条装配清单。失败由实现方按"只告警 + 不抛"处理。
     *
     * @param sessionKey 会话键（域|sessionId）
     * @param report     本轮清单
     */
    void save(String sessionKey, ContextAssemblyReport report);

    /**
     * 最近的装配清单，<b>新的在前</b>。
     *
     * @param sessionKey 会话键
     * @param limit      条数上限（实现侧再按 {@link #KEEP_PER_SESSION} 收一次）
     * @return 可能为空，但不会为 {@code null}
     */
    List<ContextAssemblyReport> recent(String sessionKey, int limit);

    /** 删除某会话的全部清单（删会话级联用） */
    int deleteBySession(String sessionKey);

    /** 删除某域下的全部清单键（删域级联用） */
    int deleteByDomain(String domain);
}
