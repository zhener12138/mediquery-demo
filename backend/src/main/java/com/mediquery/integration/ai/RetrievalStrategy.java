package com.mediquery.integration.ai;

import com.mediquery.module.illness.entity.Illness;
import com.mediquery.module.medicine.entity.Medicine;

import java.util.List;

/**
 * RAG 召回的底层实现。两套实现的分歧只在一条 SQL：
 * MySQL 用 {@code MATCH ... AGAINST} 走全文索引，H2（CI 与本地测试）用 OR LIKE。
 *
 * <p><b>为什么必须抽接口，而不是只写 FULLTEXT 版本。</b>
 * H2 没有 {@code MATCH} 函数（实测报 {@code Function "MATCH" not found}），
 * 而 CI 跑的就是 H2。若只有 FULLTEXT 实现，CI 里所有涉及检索的测试都会语法报错，
 * 结果是「要么删掉 CI 里的检索测试，要么让 CI 跑不了」。
 *
 * <p><b>这个抽象不能让 CI 证明生产代码是对的。</b>CI 里跑的是 {@link LikeRetrieval}，
 * 生产跑的是 {@link FulltextRetrieval} —— 两条 SQL 不同。
 * 所以：
 * <ul>
 *   <li>两个实现<b>共享</b>的东西（匹配语义、排序、截断）由
 *       {@link MatchExpression} 的单元测试与断言级对照覆盖；</li>
 *   <li>FULLTEXT 那条 SQL 本身只能靠真 MySQL 验证，已在
 *       {@code docs/在线问诊机器人-开发设计文档.md} 记录 EXPLAIN 与耗时对照。</li>
 * </ul>
 * 这条限制必须写在代码里，否则下一个接手的人会以为 CI 绿了就等于全文检索是对的。
 */
public interface RetrievalStrategy {

    List<Illness> recallIllnesses(List<String> keywords, int limit);

    List<Medicine> recallMedicines(List<String> keywords, int limit);

    /** 供日志与测试断言用，便于在日志里看出到底走了哪条路 */
    String name();
}
