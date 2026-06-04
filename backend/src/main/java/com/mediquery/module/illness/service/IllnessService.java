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

    public List<IllnessCategory> getAllCategories() {
        return categoryMapper.selectList(new LambdaQueryWrapper<IllnessCategory>().orderByAsc(IllnessCategory::getSortOrder));
    }

    public Illness save(Illness illness) {
        illness.setViewCount(0);
        illness.setStatus(1);
        illnessMapper.insert(illness);
        return illness;
    }

    public Illness update(Illness illness) {
        illnessMapper.updateById(illness);
        return illness;
    }

    public void delete(Integer id) {
        illnessMapper.deleteById(id);
    }

    public long countToday() {
        return illnessMapper.selectCount(new LambdaQueryWrapper<Illness>().apply("DATE(create_time) = CURDATE()"));
    }
}
