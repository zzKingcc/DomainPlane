/**
 * 知识库存储后端 SPI 契约：把「检索与写入」对后端（ES / 专用向量库 / 关键词库）的依赖
 * 收敛到一组接口，域层只认契约，适配器在 infrastructure 侧按能力注册。
 *
 * 分层位置（设计第 3 节）：编排层（{@code CompositeRetriever} + 融合策略）→ 通道层
 * （server 的 {@code ChannelProvider}）→ 本 SPI 层 → 适配器层。本包是域层唯一允许
 * 出现的存储抽象，融合与编排一行算法都不依赖它。
 *
 * 三条契约纪律，新后端实现前必读：
 *
 *   • **异常姿态逐条钉死**（{@link KnowledgeStore} 的 javadoc 表）：{@code exists} /
 *     {@code deleteCollection} fail-closed（抛），只读列举 fail-open（空/0）。
 *     写错姿态不会编译失败，只会在生产上把"链路坏了"演成"知识没了/复活了"。
 *   • **分数只承诺序**（{@link KbHit}）：越大越好、同后端同模态内可比；
 *     不承诺 [0,1]、不承诺跨后端可比。阈值是后端自己的配置，不进 SPI 入参。
 *   • **向量化在编排层**（{@link QueryVectorResolver}）：{@link VectorSearcher} 只收
 *     {@code float[]}，embed 失败按"该路失败"降级而不是整轮抛异常。
 *
 * @author zzkingcc
 */
package com.zzkingcc.domainplane.domain.rag.store;
