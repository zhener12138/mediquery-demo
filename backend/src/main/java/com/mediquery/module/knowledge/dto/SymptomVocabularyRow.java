package com.mediquery.module.knowledge.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 症状词表的一行：{@code symptom} 与 {@code symptom_alias} 左连接后的扁平结果。
 *
 * <p>为什么把两张表拍平成一张：归一化的时候不关心「这个词是标准名还是口语说法」，
 * 只关心「输入里出现了什么词、它指向哪个症状、有多可信」。
 * 标准名以 weight=1.00 出现在同一张表里，用同一个匹配循环处理，
 * 省掉一条「先匹标准名、再匹别名」的分支 —— 那种分支最后一定会不一致。
 */
@Data
public class SymptomVocabularyRow {
    private Integer symptomId;
    private String symptomName;
    private String bodyPart;
    private String category;
    /** 实际参与匹配的文本：标准名本身，或某条别名 */
    private String matchText;
    /** 该说法指向这个症状的可信度 0.00-1.00 */
    private BigDecimal weight;
}
