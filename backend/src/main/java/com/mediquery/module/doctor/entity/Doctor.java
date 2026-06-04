package com.mediquery.module.doctor.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("sys_doctor")
public class Doctor {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String name;
    private String title;
    private String department;
    private String hospital;
    private String avatarUrl;
    private String specialty;
    private String description;
    private String phone;
    private Integer status;
    private Integer sortOrder;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
