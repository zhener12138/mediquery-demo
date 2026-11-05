package com.mediquery.module.illness.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mediquery.common.dto.PageResult;
import com.mediquery.module.illness.entity.Illness;
import com.mediquery.module.illness.entity.IllnessCategory;
import com.mediquery.module.illness.mapper.IllnessCategoryMapper;
import com.mediquery.module.illness.mapper.IllnessMapper;
import com.mediquery.module.illness.mapper.IllnessMedicineRelMapper;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 疾病百科服务：多条件分页搜索(关键词模糊匹配 + 科室筛选)、详情缓存、浏览量自增
 */
@Service
public class IllnessService {

    private final IllnessMapper illnessMapper;
    private final IllnessCategoryMapper categoryMapper;
    private final IllnessMedicineRelMapper relMapper;

    public IllnessService(IllnessMapper illnessMapper, IllnessCategoryMapper categoryMapper,
                          IllnessMedicineRelMapper relMapper) {
        this.illnessMapper = illnessMapper;
        this.categoryMapper = categoryMapper;
        this.relMapper = relMapper;
    }

    // 多条件分页搜索：状态过滤 → 科室筛选 → 关键词模糊匹配 → 热门排序
    public PageResult<Illness> search(String keyword, Integer categoryId, int page, int size) {
        var query = new LambdaQueryWrapper<Illness>()
                .eq(Illness::getStatus, 1);
        if (categoryId != null && categoryId > 0) query.eq(Illness::getCategoryId, categoryId);
        if (StringUtils.hasText(keyword)) {
            query.and(w -> w.like(Illness::getName, keyword).or().like(Illness::getCause, keyword).or().like(Illness::getSymptom, keyword));
        }
        // 排序必须有唯一 tie-breaker（最后的 id）。
        // 否则 viewCount 和 createTime 都相同的记录，MySQL 返回顺序是未定义的 ——
        // 分页时同一行可能在第 1 页和第 2 页各出现一次，也可能一行都不出现。
        // createTime 是 DATETIME（秒级精度），同秒批量插入就会全部相等，这不是极端情况。
        query.orderByDesc(Illness::getViewCount)
                .orderByDesc(Illness::getCreateTime)
                .orderByDesc(Illness::getId);
        var result = illnessMapper.selectPage(new Page<>(page, size), query);
        fillCategoryNames(result.getRecords());
        return PageResult.of(result.getTotal(), page, size, result.getRecords());
    }

    /**
     * 批量回填分类名称，替代逐条 selectById 造成的 N+1 查询。
     * 一页 N 条记录从「1 + N 次 SQL」降为「1 + 1 次 SQL」。
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

    // 获取详情：Redis缓存 → 关联分类名 → 关联药品列表
    // 注意：浏览量自增已独立到 incrViewCount()，不能在 @Cacheable 方法体内做写操作，
    // 否则缓存命中时整段方法被跳过，浏览量永久冻结。
    @Cacheable(value = "illness", key = "#id", unless = "#result == null")
    public Illness getById(Integer id) {
        var illness = illnessMapper.selectById(id);
        if (illness == null || illness.getStatus() == 0 || illness.getDeleted() == 1) return null;
        if (illness.getCategoryId() != null) {
            var category = categoryMapper.selectById(illness.getCategoryId());
            if (category != null) illness.setCategoryName(category.getName());
        }
        illness.setMedicines(relMapper.findMedicinesByIllnessId(id));
        return illness;
    }

    /**
     * 浏览量自增：独立方法，走 SQL 原子自增并清理详情缓存。
     *
     * <p>用 {@code setSql("view_count = view_count + 1")} 而非先读后写，
     * 避免并发下的更新丢失；{@code @CacheEvict} 让详情缓存失效，
     * 下次读取能拿到最新浏览量。
     */
    @CacheEvict(value = "illness", key = "#id")
    public void incrViewCount(Integer id) {
        illnessMapper.update(null, new LambdaUpdateWrapper<Illness>()
                .eq(Illness::getId, id)
                .setSql("view_count = view_count + 1"));
    }

    /**
     * 多关键词批量检索：一次 SQL 完成所有关键词的 OR 匹配，供 RAG 使用。
     *
     * <p>替代「每个关键词各调一次 search」的做法 —— 那种写法对一句 24 个 token 的问题
     * 会产生约 96 次数据库往返（每次含 count + 分页两条 SQL）。
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

    // 获取所有分类（按sortOrder升序）
    public List<IllnessCategory> getAllCategories() {
        return categoryMapper.selectList(new LambdaQueryWrapper<IllnessCategory>().orderByAsc(IllnessCategory::getSortOrder));
    }

    // 新增疾病（初始浏览量=0，状态=已发布）
    public Illness save(Illness illness) {
        illness.setViewCount(0);
        illness.setStatus(1);
        illnessMapper.insert(illness);
        return illness;
    }

    // 更新疾病
    public Illness update(Illness illness) {
        illnessMapper.updateById(illness);
        return illness;
    }

    // 逻辑删除（自动追加 deleted=0 条件）
    public void delete(Integer id) {
        illnessMapper.deleteById(id);
    }

    // 统计今日新增疾病数
    public long countToday() {
        return illnessMapper.selectCount(new LambdaQueryWrapper<Illness>().apply("DATE(create_time) = CURDATE()"));
    }
}
