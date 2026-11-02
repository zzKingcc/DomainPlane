package com.zzkingcc.domainplane.domain.rag.store;

/**
 * 查询向量化：编排层（{@code CompositeRetriever}）在每轮检索开头调用一次，
 * 结果供全部向量通道复用。server 侧注入 {@code embeddingModel::embed}。
 *
 * 为什么上移到编排层：现状每条向量通道各自 embed 一次，域链 5 层就是 5 次相同
 * query 的嵌入调用——模型按次计费、延迟按串行累加，而这 5 次的结果完全相同。
 *
 * 失败语义（设计 4.5.1，一审修订 A2）：本方法抛异常时，编排层把**本轮全部向量
 * 通道标记失败、关键词通道照常执行**；只有全部通道失败才向上抛。不 try-catch
 * 会让一次 embed 失败把整轮检索变成异常——"链路坏了"与"真没命中"再次同形，
 * 调用方会反复换问法重试而不是去查模型配置。
 *
 * @author zzkingcc
 */
@FunctionalInterface
public interface QueryVectorResolver {

    /**
     * @param text 查询文本
     * @return 查询向量；失败抛异常（由编排层按"该路失败"降级）
     */
    float[] embed(String text);
}
