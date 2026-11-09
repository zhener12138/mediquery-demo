package com.mediquery.module.illness.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mediquery.module.illness.entity.Illness;
import com.mediquery.module.illness.entity.IllnessCategory;
import com.mediquery.module.illness.mapper.IllnessCategoryMapper;
import com.mediquery.module.illness.mapper.IllnessMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 疾病知识库的读侧，只服务 RAG 召回。
 *
 * <p>这里曾经是一整套「疾病百科」的 CRUD：分页搜索、详情缓存、浏览量自增、
 * 分类列表、增删改、今日新增统计。疾病浏览页和管理后台删掉之后，
 * 那些方法在整个仓库里零调用 —— 唯一还活着的入口是本类的
 * {@link #searchByKeywords}，它被 {@code LikeRetrieval}（H2 测试路径）调用。
 *
 * <p>同时删掉的还有 {@code @Cacheable}/{@code @CacheEvict}：它们全挂在
 * 那些零调用的方法上，等于缓存从来没被写过。药品那边同理。
 * Redis 本身还在用（验证码、RAG 上下文），只是不再拿它缓存百科详情。
 *
 * <p>召回本身生产走全文索引（{@code FulltextRetrieval}），这个方法只在
 * H2 环境下兜底 —— H2 不认 {@code MATCH ... AGAINST}。
 */
@Service
public class IllnessService {

    private final IllnessMapper illnessMapper;
    private final IllnessCategoryMapper categoryMapper;

    public IllnessService(IllnessMapper illnessMapper, IllnessCategoryMapper categoryMapper) {
        this.illnessMapper = illnessMapper;
        this.categoryMapper = categoryMapper;
    }

    /**
     * 多关键词批量检索：一次 SQL 完成所有关键词的 OR 匹配，供 RAG 使用。
     *
     * <p>替代「每个关键词各调一次旧的分页搜索」的做法 —— 那种写法对一句 24 个 token
     * 的问题会产生约 96 次数据库往返（每次含 count + 分页两条 SQL）。
     *
     * @param keywords 关键词列表（调用方已去重）
     * @param limit    最多返回条数
     */
    public List<Illness> searchByKeywords(List<String> keywords, int limit) {
        if (keywords == null || keywords.isEmpty()) return List.of();
        var query = new LambdaQueryWrapper<Illness>().eq(Illness::getStatus, 1);
        query.and(w -> {
            for (int i = 0; i < keywords.size(); i++) {
                var kw = keywords.get(i);
                if (i > 0) w.or();
                w.like(Illness::getName, kw).or().like(Illness::getCause, kw).or().like(Illness::getSymptom, kw);
            }
        });
        // 库内先按浏览量粗排，最终排序由调用方按关键词命中情况精排。
        // 带 LIMIT 就必须有 tie-breaker：否则同浏览量的记录谁被选中是不确定的，
        // 同一句问诊每次召回的知识可能不同，RAG 行为不可复现。
        query.orderByDesc(Illness::getViewCount).orderByDesc(Illness::getId);
        query.last("LIMIT " + limit);
        var records = illnessMapper.selectList(query);
        fillCategoryNames(records);
        return records;
    }

    /**
     * 批量回填分类名称，替代逐条 selectById 造成的 N+1 查询。
     * 一页 N 条记录从「1 + N 次 SQL」降为「1 + 1 次 SQL」。
     *
     * <p>回填的科室名会被 {@code KnowledgeRetrievalService#formatIllness}
     * 写进给模型的上下文里（「常见科室」一行）—— 在这之前它虽然被查出来，
     * 但没有任何代码读过，属于白查一次。
     */
    private void fillCategoryNames(List<Illness> records) {
        if (records == null || records.isEmpty()) return;
        var categoryIds = records.stream()
                .map(Illness::getCategoryId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (categoryIds.isEmpty()) return;
        var nameById = categoryMapper.selectBatchIds(categoryIds).stream()
                .collect(Collectors.toMap(IllnessCategory::getId, IllnessCategory::getName));
        for (var illness : records) {
            var name = nameById.get(illness.getCategoryId());
            if (name != null) illness.setCategoryName(name);
        }
    }
}
