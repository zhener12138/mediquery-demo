package com.mediquery.module.medicine.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mediquery.common.dto.PageResult;
import com.mediquery.module.medicine.entity.Medicine;
import com.mediquery.module.medicine.mapper.MedicineMapper;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MedicineService {

    private final MedicineMapper medicineMapper;

    public MedicineService(MedicineMapper medicineMapper) {
        this.medicineMapper = medicineMapper;
    }

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

    @Cacheable(value = "medicine", key = "#id")
    public Medicine getById(Integer id) {
        return medicineMapper.selectById(id);
    }

    public Medicine save(Medicine medicine) {
        medicine.setStatus(1);
        medicineMapper.insert(medicine);
        return medicine;
    }

    public Medicine update(Medicine medicine) {
        medicineMapper.updateById(medicine);
        return medicine;
    }

    public void delete(Integer id) {
        medicineMapper.deleteById(id);
    }
}
