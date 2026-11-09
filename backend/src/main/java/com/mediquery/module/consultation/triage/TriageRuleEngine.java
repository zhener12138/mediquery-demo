package com.mediquery.module.consultation.triage;

import com.mediquery.common.text.NegationMatcher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 危险信号与科室指向的确定性预筛。
 *
 * <h3>为什么这一段必须是规则，不能交给大模型</h3>
 *
 * <p>医疗场景下有两类错误，代价完全不对等：
 * <ul>
 *   <li><b>漏报</b>：患者说「胸口像被压住一样疼，还冒冷汗」，模型没意识到这是心梗前兆，
 *       顺着聊了两轮饮食建议。这个代价是人的健康。</li>
 *   <li><b>误报</b>：患者说「有点头疼」，被弹了一个「立即拨打 120」的红框。
 *       代价是用户被吓到，然后以后再也不信任何提示 —— 这叫告警脱敏，
 *       它的长期危害甚至更大。</li>
 * </ul>
 *
 * <p>模型对第一类错误没有保证：同一个输入跑两次，可能一次抓到一次漏掉。
 * 所以这里用规则兜住「必须抓到」的那部分 —— 命中就是命中，不受采样温度影响。
 * 规则只做**召回**（宁可多抓几个可疑的），把**精确性**留给后面的模型措辞。
 *
 * <h3>为什么关键词表要写得这么啰嗦</h3>
 *
 * <p>中文症状描述的变体极多：「喘不上气」「透不过气」「呼吸费劲」说的是一件事。
 * 只写一两个词必然漏。多写几个词的代价只是多几行常量，
 * 而漏掉一个词的代价是这条规则在该场景下等于不存在。
 */
@Component
public class TriageRuleEngine {

    /**
     * 高危信号：命中任意一条，即时判定 severity=2。
     *
     * <p>选词标准是「看到这几个字就必须立刻就医，不需要等进一步确认」。
     * 有争议的词（比如单纯的「发烧」）一律不进来 —— 那个交给 {@link #MODERATE}。
     */
    private static final Map<String, List<String>> HIGH_RISK = Map.ofEntries(
            Map.entry("剧烈胸痛", List.of("胸痛", "胸疼", "胸口痛", "胸口疼", "心口痛", "心口疼",
                    "心前区", "胸口像", "压榨", "胸闷疼")),
            Map.entry("呼吸困难", List.of("呼吸困难", "喘不上气", "喘不过气", "透不过气", "呼吸费劲",
                    "憋气", "窒息", "喘得厉害")),
            Map.entry("意识改变", List.of("昏迷", "意识不清", "意识模糊", "叫不醒", "晕倒", "昏过去",
                    "抽搐", "抽风", "惊厥", "说胡话")),
            Map.entry("大出血", List.of("大出血", "大量出血", "呕血", "吐血", "吐了血", "一口血",
                    "咳血", "咳出血", "痰中带血", "便血", "血便", "流血不止", "止不住血")),
            Map.entry("剧烈腹痛", List.of("剧烈腹痛", "肚子剧烈", "腹痛难忍", "肚子疼得", "疼得直不起腰")),
            Map.entry("高热不退", List.of("高烧不退", "持续高烧", "烧到40", "烧到39度5", "40度")),
            Map.entry("剧烈头痛", List.of("剧烈头痛", "头痛欲裂", "炸裂般")),
            Map.entry("严重过敏", List.of("喉头水肿", "舌头肿", "嘴唇肿", "全身起疹", "过敏休克")),
            Map.entry("自伤风险", List.of("不想活", "自杀", "轻生", "活不下去")),
            Map.entry("剧烈呕吐", List.of("喷射状呕吐", "吐个不停", "喝水都吐")),
            Map.entry("孕产急症", List.of("胎动减少", "见红", "破水", "孕晚期出血")),
            Map.entry("外伤急症", List.of("骨折", "骨头露", "伤口很深", "砸伤", "车祸", "坠落")),
            Map.entry("儿童急症", List.of("孩子抽搐", "宝宝抽搐", "婴儿高烧", "孩子叫不醒"))
    );

    /**
     * 中度信号：命中后建议近期就医，但不弹急救提示。
     *
     * <p>和高危分开维护，因为它们的处置完全不同：
     * 高危要让用户停下手上的事立刻走，中度只是提醒「别拖」。
     * 混在一张表里，加词的人就分不清该给哪种措辞。
     */
    private static final Map<String, List<String>> MODERATE = Map.ofEntries(
            Map.entry("持续发热", List.of("发烧", "发热", "低烧", "高烧", "体温")),
            Map.entry("持续疼痛", List.of("疼了", "痛了", "好几天", "一直疼", "反复")),
            Map.entry("消化道症状", List.of("腹泻", "拉肚子", "呕吐", "恶心", "反酸", "胃胀")),
            Map.entry("呼吸道症状", List.of("咳嗽", "咳痰", "喉咙痛", "嗓子疼", "鼻塞", "流鼻涕")),
            Map.entry("皮肤症状", List.of("起疹", "疹子", "瘙痒", "红肿", "皮疹", "水泡", "很痒", "发痒")),
            Map.entry("排尿异常", List.of("尿频", "尿急", "尿痛", "血尿")),
            Map.entry("头晕乏力", List.of("头晕", "眩晕", "乏力", "没力气", "心悸"))
    );

    /**
     * 科室指向词表。
     *
     * <p>这不是医学分诊，是「帮用户少走一次挂号弯路」的启发式匹配。
     * 表里的词都取自 {@code illness_category} 的科室名，避免推荐出不存在的科室。
     */
    private static final Map<String, List<String>> DEPARTMENT_HINTS = Map.ofEntries(
            Map.entry("急诊科", List.of("突然", "剧烈", "大出血", "昏迷", "抽搐", "呼吸困难", "车祸")),
            Map.entry("内科", List.of("发烧", "发热", "咳嗽", "乏力", "头晕", "感冒", "头痛")),
            Map.entry("消化内科", List.of("胃", "腹", "恶心", "呕吐", "腹泻", "便秘", "反酸")),
            Map.entry("呼吸内科", List.of("咳", "痰", "喘", "鼻塞", "呼吸", "嗓子")),
            Map.entry("心内科", List.of("胸", "心", "心悸", "血压", "胸闷")),
            Map.entry("皮肤科", List.of("疹", "痒", "斑", "痘", "红肿", "脱皮", "湿疹")),
            Map.entry("妇产科", List.of("月经", "经期", "白带", "孕", "怀孕", "胎")),
            Map.entry("儿科", List.of("孩子", "宝宝", "婴儿", "小孩", "儿童")),
            Map.entry("骨科", List.of("关节", "腰", "颈椎", "扭伤", "骨折", "膝盖", "腿疼")),
            Map.entry("耳鼻喉科", List.of("耳朵", "耳鸣", "鼻窦", "扁桃体", "嗓子疼")),
            Map.entry("口腔科", List.of("牙", "牙龈", "口腔", "溃疡", "口臭")),
            Map.entry("眼科", List.of("眼睛", "视物", "看不清", "眼红", "眼痛")),
            Map.entry("神经内科", List.of("头痛", "失眠", "麻木", "手抖", "偏头痛")),
            Map.entry("泌尿外科", List.of("尿", "肾", "结石", "前列腺"))
    );

    /**
     * 单条消息的预筛入口。
     *
     * <p>实现上刻意不做分词：直接用 {@code contains} 扫全部词。
     * 一百多个词扫一遍是微秒级，比引入分词器省掉一整类「分词把词切坏了」的故障。
     *
     * @param message 用户这一轮说的话，可以为 null
     */
    public TriageResult triage(String message) {
        if (message == null || message.isBlank()) {
            return TriageResult.none();
        }
        // 统一去空白，避免「胸 痛」这种输入绕过匹配
        String text = message.replaceAll("\\s+", "");

        var highFlags = matchFlags(text, HIGH_RISK);
        var departments = matchDepartments(text);

        if (!highFlags.isEmpty()) {
            return new TriageResult(
                    2,
                    highFlags,
                    departments.isEmpty() ? List.of("急诊科") : departments,
                    "你描述的情况可能存在需要立即处理的危险信号，请马上前往最近的医院急诊科，"
                            + "或让身边的人拨打 120。不要自行服药观察。");
        }

        var moderateFlags = matchFlags(text, MODERATE);
        if (!moderateFlags.isEmpty()) {
            return new TriageResult(
                    1,
                    List.of(),
                    departments,
                    "这些症状建议近期到医院看一下，如果加重请及时就诊。");
        }

        return new TriageResult(0, List.of(), departments, "");
    }

    /** 把命中的类别名去重收集。同一个类别命中多个词也只报一次。 */
    private List<String> matchFlags(String text, Map<String, List<String>> table) {
        var hits = new ArrayList<String>();
        for (var entry : table.entrySet()) {
            for (String kw : entry.getValue()) {
                if (NegationMatcher.containsUnnegated(text, kw)) {
                    hits.add(entry.getKey());
                    break;
                }
            }
        }
        return hits;
    }

    /**
     * 匹配科室，最多返回 2 个。
     *
     * <p>按命中词数量排序而不是按表顺序：一句话里出现三个「胃」相关词，
     * 显然比只出现一个「头」更该指向消化内科。
     */
    private List<String> matchDepartments(String text) {
        var scored = new ArrayList<DeptScore>();
        for (var entry : DEPARTMENT_HINTS.entrySet()) {
            int hit = 0;
            for (String kw : entry.getValue()) {
                if (NegationMatcher.containsUnnegated(text, kw)) {
                    hit++;
                }
            }
            if (hit > 0) {
                scored.add(new DeptScore(entry.getKey(), hit));
            }
        }
        scored.sort((a, b) -> Integer.compare(b.hits, a.hits));
        var out = new ArrayList<String>();
        for (var d : scored) {
            if (out.size() >= 2) break;
            out.add(d.name);
        }
        return out;
    }

    private record DeptScore(String name, int hits) {
    }
}
