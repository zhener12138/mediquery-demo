package com.mediquery.module.illness.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.mediquery.module.medicine.entity.Medicine;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName("illness")
public class Illness {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private Integer categoryId;
    private String name;
    private String cause;
    private String symptom;
    private String specialSymptom;
    private Integer viewCount;
    private Integer status;
    @TableLogic
    private Integer deleted;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    @TableField(exist = false)
    private String categoryName;
    @TableField(exist = false)
    private List<Medicine> medicines;
}
