package com.mediquery.module.operationlog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mediquery.module.operationlog.entity.OperationLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OperationLogMapper extends BaseMapper<OperationLog> {
}
