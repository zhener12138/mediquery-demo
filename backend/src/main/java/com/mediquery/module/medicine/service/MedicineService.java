package com.mediquery.module.medicine.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mediquery.module.medicine.entity.Medicine;
import com.mediquery.module.medicine.mapper.MedicineMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 药品知识库的读侧，只服务 RAG 召回。
 *
 * <p>这里曾经是「药品百科」的完整 CRUD：分页搜索、详情缓存、增删改。
 * 药品浏览页和管理后台删掉之后，那些方法在整个仓库里零调用 ——
 * 唯一还活着的入口是 {@link #searchByKeywords}，它被 {@code LikeRetrieval}
 * （H2 测试路径）调用；生产走全文索引 {@code FulltextRetrieval}，
 * 直接查 Mapper，不经过这里。
 *
 * <p>顺带说明为什么 {@code java.math.BigDecimal} 还在 Redis 白名单里：
 * {@code Medicine.price} 是 BigDecimal，缓存序列化那条规则曾经为它加过
 * {@code java.math.} —— 缓存方法删了，但白名单是通用的防御配置，
 * 而且 {@code RedisConfigTest} 钉着它，没必要为了「现在没人缓存药品」
 * 去拆一个正确的安全配置。
 */
@Service
public class MedicineService {

    private final MedicineMapper medicineMapper;

    public MedicineService(MedicineMapper medicineMapper) {
        this.medicineMapper = medicineMapper;
    }

    /**
     * 多关键词批量检索：一次 SQL 完成所有关键词的 OR 匹配，供 RAG 使用。
     *
     * @param keywords 关键词列表（调用方已去重）
     * @param limit    最多返回条数
     */
    public List<Medicine> searchByKeywords(List<String> keywords, int limit) {
        if (keywords == null || keywords.isEmpty()) return List.of();
        var query = new LambdaQueryWrapper<Medicine>().eq(Medicine::getStatus, 1);
        query.and(w -> {
            for (int i = 0; i < keywords.size(); i++) {
                var kw = keywords.get(i);
                if (i > 0) w.or();
                w.like(Medicine::getName, kw).or().like(Medicine::getKeyword, kw).or().like(Medicine::getEffect, kw);
            }
        });
        // 带 LIMIT 的召回同样要 tie-breaker：否则每次查询返回的最后几条可能不同，
        // RAG 的检索结果不可复现，评估数字也就没意义了。
        query.orderByDesc(Medicine::getCreateTime).orderByDesc(Medicine::getId);
        query.last("LIMIT " + limit);
        return medicineMapper.selectList(query);
    }
}
