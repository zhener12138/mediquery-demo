package com.mediquery.module.illness.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mediquery.module.illness.entity.IllnessMedicineRel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface IllnessMedicineRelMapper extends BaseMapper<IllnessMedicineRel> {

    @Select("SELECT m.* FROM medicine m INNER JOIN illness_medicine_rel imr ON m.id = imr.medicine_id WHERE imr.illness_id = #{illnessId} AND m.deleted = 0")
    List<com.mediquery.module.medicine.entity.Medicine> findMedicinesByIllnessId(@Param("illnessId") Integer illnessId);
}
