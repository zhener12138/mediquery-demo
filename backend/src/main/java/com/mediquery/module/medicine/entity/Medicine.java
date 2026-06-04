package com.mediquery.module.medicine.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("medicine")
public class Medicine {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String name;
    private String keyword;
    private String effect;
    private String brand;
    private String manufacturer;
    private String interaction;
    private String contraindication;
    private String usageDosage;
    private Integer type;
    private BigDecimal price;
    private String imageUrl;
    private Integer status;
    @TableLogic
    private Integer deleted;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
