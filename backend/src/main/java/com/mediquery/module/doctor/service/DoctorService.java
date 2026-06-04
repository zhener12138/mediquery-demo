package com.mediquery.module.doctor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mediquery.common.dto.PageResult;
import com.mediquery.module.doctor.entity.Doctor;
import com.mediquery.module.doctor.mapper.DoctorMapper;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class DoctorService {

    private final DoctorMapper doctorMapper;

    public DoctorService(DoctorMapper doctorMapper) {
        this.doctorMapper = doctorMapper;
    }

    public PageResult<Doctor> search(String keyword, String department, int page, int size) {
        var query = new LambdaQueryWrapper<Doctor>().eq(Doctor::getStatus, 1);
        if (StringUtils.hasText(department)) query.eq(Doctor::getDepartment, department);
        if (StringUtils.hasText(keyword))
            query.and(w -> w.like(Doctor::getName, keyword).or().like(Doctor::getSpecialty, keyword).or().like(Doctor::getHospital, keyword));
        query.orderByAsc(Doctor::getSortOrder).orderByDesc(Doctor::getCreateTime);
        var result = doctorMapper.selectPage(new Page<>(page, size), query);
        return PageResult.of(result.getTotal(), page, size, result.getRecords());
    }

    @Cacheable(value = "doctor", key = "#id")
    public Doctor getById(Integer id) { return doctorMapper.selectById(id); }

    public List<Doctor> listAll() {
        return doctorMapper.selectList(new LambdaQueryWrapper<Doctor>().eq(Doctor::getStatus, 1).orderByAsc(Doctor::getSortOrder));
    }

    public Doctor save(Doctor doctor) { doctor.setStatus(1); doctorMapper.insert(doctor); return doctor; }
    public Doctor update(Doctor doctor) { doctorMapper.updateById(doctor); return doctor; }
    public void delete(Integer id) { doctorMapper.deleteById(id); }
}
