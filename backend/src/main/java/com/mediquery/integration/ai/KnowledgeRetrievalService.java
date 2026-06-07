package com.mediquery.integration.ai;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.mediquery.module.illness.entity.Illness;
import com.mediquery.module.illness.service.IllnessService;
import com.mediquery.module.medicine.entity.Medicine;
import com.mediquery.module.medicine.service.MedicineService;

import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 知识检索服务：根据用户问题从疾病/药品库中检索相关条目，
 * 格式化为 LLM 可直接理解的上下文字符串。
 */
@Service
public class KnowledgeRetrievalService {

    private final IllnessService illnessService;
    private final MedicineService medicineService;

    public KnowledgeRetrievalService(IllnessService illnessService, MedicineService medicineService) {
        this.illnessService = illnessService;
        this.medicineService = medicineService;
    }

    /**
     * 根据用户输入检索相关知识，格式化为上下文文本。
     * 返回空字符串表示未找到匹配。
     */
    public String retrieve(String userMessage) {
        if (!StringUtils.hasText(userMessage)) return "";

        var context = new StringBuilder();

        // 检索疾病（top 3）
        var illnesses = illnessService.search(userMessage, null, 1, 3).getRecords();
        if (!illnesses.isEmpty()) {
            context.append("【参考知识库 · 疾病】\n");
            for (var i : illnesses) {
                context.append(formatIllness(i));
            }
        }

        // 检索药品（top 3）
        var medicines = medicineService.search(userMessage, null, 1, 3).getRecords();
        if (!medicines.isEmpty()) {
            context.append("【参考知识库 · 药品】\n");
            for (var m : medicines) {
                context.append(formatMedicine(m));
            }
        }

        return context.toString();
    }

    private String formatIllness(Illness i) {
        var sb = new StringBuilder();
        sb.append("## 疾病：").append(i.getName()).append("\n");
        if (StringUtils.hasText(i.getCause()))
            sb.append("- 病因：").append(i.getCause()).append("\n");
        if (StringUtils.hasText(i.getSymptom()))
            sb.append("- 症状：").append(i.getSymptom()).append("\n");
        if (StringUtils.hasText(i.getSpecialSymptom()))
            sb.append("- 特殊说明：").append(i.getSpecialSymptom()).append("\n");
        // 关联药品
        var medicines = i.getMedicines();
        if (medicines != null && !medicines.isEmpty()) {
            var names = medicines.stream().map(Medicine::getName).collect(Collectors.joining("、"));
            sb.append("- 关联药品：").append(names).append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    private String formatMedicine(Medicine m) {
        var sb = new StringBuilder();
        sb.append("## 药品：").append(m.getName()).append("\n");
        if (StringUtils.hasText(m.getEffect()))
            sb.append("- 功效：").append(m.getEffect()).append("\n");
        if (StringUtils.hasText(m.getUsageDosage()))
            sb.append("- 用法用量：").append(m.getUsageDosage()).append("\n");
        if (StringUtils.hasText(m.getContraindication()))
            sb.append("- 禁忌：").append(m.getContraindication()).append("\n");
        if (StringUtils.hasText(m.getInteraction()))
            sb.append("- 药物相互作用：").append(m.getInteraction()).append("\n");
        sb.append("\n");
        return sb.toString();
    }
}
