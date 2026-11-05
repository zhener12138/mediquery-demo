package com.mediquery.module.medicine.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mediquery.common.dto.PageResult;
import com.mediquery.module.medicine.entity.Medicine;
import com.mediquery.module.medicine.mapper.MedicineMapper;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 药品百科服务：按类型/关键词分页搜索、详情缓存、CRUD
 */
@Service
public class MedicineService {

    private final MedicineMapper medicineMapper;

    public MedicineService(MedicineMapper medicineMapper) {
        this.medicineMapper = medicineMapper;
    }

    // 多条件分页搜索：状态过滤 → 类型筛选 → 关键词模糊匹配
    public PageResult<Medicine> search(String keyword, Integer type, int page, int size) {
        var query = new LambdaQueryWrapper<Medicine>().eq(Medicine::getStatus, 1);
        if (type != null && type >= 0) query.eq(Medicine::getType, type);
        if (StringUtils.hasText(keyword)) {
            query.and(w -> w.like(Medicine::getName, keyword).or().like(Medicine::getKeyword, keyword).or().like(Medicine::getEffect, keyword));
        }
        // tie-breaker 必须有，理由同 IllnessService.search：createTime 是秒级精度，
        // 同秒插入的行排序未定义，分页会跨页重复或漏行。
        query.orderByDesc(Medicine::getCreateTime).orderByDesc(Medicine::getId);
        var result = medicineMapper.selectPage(new Page<>(page, size), query);
        return PageResult.of(result.getTotal(), page, size, result.getRecords());
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

    /**
     * 获取详情（Redis缓存）。
     *
     * <p>只缓存上架药品：{@code status == 0} 或已删除时返回 null 且不入缓存，
     * 避免下架药品在缓存 TTL 内仍然可读。写操作通过 {@code @CacheEvict} 主动失效。
     */
    @Cacheable(value = "medicine", key = "#id", unless = "#result == null")
    public Medicine getById(Integer id) {
        var medicine = medicineMapper.selectById(id);
        if (medicine == null || medicine.getStatus() == 0) return null;
        return medicine;
    }

    // 新增药品（默认状态=上架）
    @CacheEvict(value = "medicine", allEntries = true)
    public Medicine save(Medicine medicine) {
        medicine.setStatus(1);
        medicineMapper.insert(medicine);
        return medicine;
    }

    // 更新药品信息（含上下架切换，需清理缓存）
    @CacheEvict(value = "medicine", key = "#medicine.id")
    public Medicine update(Medicine medicine) {
        medicineMapper.updateById(medicine);
        return medicine;
    }

    // 逻辑删除
    @CacheEvict(value = "medicine", key = "#id")
    public void delete(Integer id) {
        medicineMapper.deleteById(id);
    }
}
