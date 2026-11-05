package com.mediquery.module.doctor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mediquery.common.dto.PageResult;
import com.mediquery.module.doctor.entity.Doctor;
import com.mediquery.module.doctor.mapper.DoctorMapper;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 医生信息服务：按科室/关键词搜索、sortOrder排序、详情缓存
 */
@Service
public class DoctorService {

    private final DoctorMapper doctorMapper;

    public DoctorService(DoctorMapper doctorMapper) {
        this.doctorMapper = doctorMapper;
    }

    // 多条件分页搜索：状态过滤 → 科室筛选 → 关键词模糊匹配 → sortOrder升序
    public PageResult<Doctor> search(String keyword, String department, int page, int size) {
        var query = new LambdaQueryWrapper<Doctor>().eq(Doctor::getStatus, 1);
        if (StringUtils.hasText(department)) query.eq(Doctor::getDepartment, department);
        if (StringUtils.hasText(keyword))
            query.and(w -> w.like(Doctor::getName, keyword).or().like(Doctor::getSpecialty, keyword).or().like(Doctor::getHospital, keyword));
        query.orderByAsc(Doctor::getSortOrder).orderByDesc(Doctor::getCreateTime);
        var result = doctorMapper.selectPage(new Page<>(page, size), query);
        return PageResult.of(result.getTotal(), page, size, result.getRecords());
    }

    // 获取详情（Redis缓存）
    @Cacheable(value = "doctor", key = "#id", unless = "#result == null")
    public Doctor getById(Integer id) { return doctorMapper.selectById(id); }

    // 新增医生（默认在岗）
    public Doctor save(Doctor doctor) { doctor.setStatus(1); doctorMapper.insert(doctor); return doctor; }
    // 更新医生信息
    public Doctor update(Doctor doctor) { doctorMapper.updateById(doctor); return doctor; }
    // 删除医生
    public void delete(Integer id) { doctorMapper.deleteById(id); }
}
