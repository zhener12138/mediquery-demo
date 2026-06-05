package com.mediquery.module.medicine.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mediquery.common.dto.PageResult;
import com.mediquery.module.medicine.entity.Medicine;
import com.mediquery.module.medicine.mapper.MedicineMapper;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

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
        query.orderByDesc(Medicine::getCreateTime);
        var result = medicineMapper.selectPage(new Page<>(page, size), query);
        return PageResult.of(result.getTotal(), page, size, result.getRecords());
    }

    // 获取详情（Redis缓存）
    @Cacheable(value = "medicine", key = "#id")
    public Medicine getById(Integer id) {
        return medicineMapper.selectById(id);
    }

    // 新增药品（默认状态=上架）
    public Medicine save(Medicine medicine) {
        medicine.setStatus(1);
        medicineMapper.insert(medicine);
        return medicine;
    }

    // 更新药品信息
    public Medicine update(Medicine medicine) {
        medicineMapper.updateById(medicine);
        return medicine;
    }

    // 逻辑删除
    public void delete(Integer id) {
        medicineMapper.deleteById(id);
    }
}
