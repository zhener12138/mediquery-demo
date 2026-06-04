package com.mediquery.module.illness.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("illness_medicine_rel")
public class IllnessMedicineRel {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private Integer illnessId;
    private Integer medicineId;
}
