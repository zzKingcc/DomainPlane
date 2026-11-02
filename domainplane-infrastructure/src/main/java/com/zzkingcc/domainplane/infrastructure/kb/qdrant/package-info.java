/**
 * 知识库存储后端的 Qdrant 适配器：把 {@code domain.rag.store} 的四个 SPI 落到 Qdrant。
 *
 * <p>这是 P1 的第二个后端实现（ES 是 P0 的唯一实现）。它存在的意义不是
 * "多一个能用的库"，而是<b>证明抽象真的成立</b>：换后端不需要动编排层、
 * 融合层、写入链路的任何一行代码，只加一个适配器 + 一行
 * {@code domainplane.knowledge.backends.<id>.type: qdrant}。</p>
 *
 * <p>包内纪律（与 ES 适配器同）：</p>
 *
 *   <ul>
 *     <li><b>Qdrant 方言不外泄</b>——payload 键名、距离度量名、scroll 游标语义
 *         全关在本包内，SPI 入参里看不到；</li>
 *     <li><b>分数方向统一</b>——euclid / manhattan 是距离（越小越近），
 *         出 SPI 前统一换算成同向相似度，否则混合后端融合时这一路会倒着排
 *         而且不报错；</li>
 *     <li><b>异常全部接住转 {@code StoreAccessException}</b>——不接会绕过
 *         ErrorCode 分类，调用方给不出可行动提示；</li>
 *     <li><b>失败姿态逐条对齐 SPI 契约</b>——{@code exists} / {@code deleteCollection}
 *         fail-closed，{@code listCollections} / {@code count} / {@code documents} fail-open。</li>
 *   </ul>
 *
 * <p><b>刻意不引第三方 SDK</b>：本机 maven 仓库离线且没有向量库客户端，
 * 引入新依赖要么走不通、要么把部署方的依赖树搅浑。Qdrant 的 REST 面足够薄，
 * JDK 自带的 {@code HttpClient} 直接打就够。</p>
 *
 * @author zzkingcc
 */
package com.zzkingcc.domainplane.infrastructure.kb.qdrant;
