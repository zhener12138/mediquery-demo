package com.mediquery.module.knowledge.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 归一化产物：一句话里认出的症状。
 *
 * <p>{@code matchedText} 保留「用户原话里是哪个说法命中的」，
 * 比如用户说「睡不着」，这里就是「睡不着」而不是「失眠」。
 * 日志和调试都靠它 —— 归一化出问题的时候，你需要知道是哪句话被判成了什么。
 */
@Data
public class NormalizedSymptom {
    private Integer symptomId;
    private String symptomName;
    private String bodyPart;
    private String category;
    /** 用户原话里命中的那个说法 */
    private String matchedText;
    /** 归一强度 0.00-1.00 */
    private BigDecimal weight;

    public NormalizedSymptom() {
    }

    public NormalizedSymptom(Integer symptomId, String symptomName, String bodyPart,
                             String category, String matchedText, BigDecimal weight) {
        this.symptomId = symptomId;
        this.symptomName = symptomName;
        this.bodyPart = bodyPart;
        this.category = category;
        this.matchedText = matchedText;
        this.weight = weight;
    }
}
