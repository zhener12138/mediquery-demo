package com.mediquery.module.consultation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mediquery.module.consultation.entity.Consultation;
import org.apache.ibatis.annotations.Mapper;

/**
 * 问诊单 Mapper。
 *
 * <p>刻意不写任何自定义 SQL：所有查询都用 {@code LambdaQueryWrapper} 在 Service 层拼，
 * 这样条件改动不需要动 XML/注解，也避免全项目又多一处 raw SQL。
 */
@Mapper
public interface ConsultationMapper extends BaseMapper<Consultation> {
}
