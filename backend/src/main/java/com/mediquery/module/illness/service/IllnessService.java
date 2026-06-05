package com.mediquery.module.illness.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mediquery.common.dto.PageResult;
import com.mediquery.module.illness.entity.Illness;
import com.mediquery.module.illness.entity.IllnessCategory;
import com.mediquery.module.illness.mapper.IllnessCategoryMapper;
import com.mediquery.module.illness.mapper.IllnessMapper;
import com.mediquery.module.illness.mapper.IllnessMedicineRelMapper;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

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
        query.orderByDesc(Illness::getViewCount).orderByDesc(Illness::getCreateTime);
        var result = illnessMapper.selectPage(new Page<>(page, size), query);
        for (var illness : result.getRecords()) {
            if (illness.getCategoryId() != null) {
                var category = categoryMapper.selectById(illness.getCategoryId());
                if (category != null) illness.setCategoryName(category.getName());
            }
        }
        return PageResult.of(result.getTotal(), page, size, result.getRecords());
    }

    // 获取详情：Redis缓存 → 浏览量+1 → 关联分类名 → 关联药品列表
    @Cacheable(value = "illness", key = "#id")
    public Illness getById(Integer id) {
        var illness = illnessMapper.selectById(id);
        if (illness == null || illness.getStatus() == 0 || illness.getDeleted() == 1) return null;
        illness.setViewCount(illness.getViewCount() == null ? 1 : illness.getViewCount() + 1);
        illnessMapper.updateById(illness);
        if (illness.getCategoryId() != null) {
            var category = categoryMapper.selectById(illness.getCategoryId());
            if (category != null) illness.setCategoryName(category.getName());
        }
        var medicines = relMapper.findMedicinesByIllnessId(id);
        illness.setMedicines(medicines);
        return illness;
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
