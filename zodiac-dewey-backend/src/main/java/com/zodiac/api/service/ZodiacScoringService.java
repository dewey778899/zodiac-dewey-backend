package com.zodiac.api.service;

import com.zodiac.api.dto.CompatibilityRequest;
import com.zodiac.api.util.ZodiacCalculator;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

import static com.zodiac.api.util.ZodiacCalculator.ELEMENT;

import java.util.Set;

@Service
public class ZodiacScoringService {

    // 元素分类
    private static final Set<String> FIRE = Set.of("白羊座", "狮子座", "射手座");
    private static final Set<String> EARTH = Set.of("金牛座", "处女座", "摩羯座");
    private static final Set<String> AIR = Set.of("双子座", "天秤座", "水瓶座");
    private static final Set<String> WATER = Set.of("巨蟹座", "天蝎座", "双鱼座");

    // 太阳星座相合度矩阵 (12x12)
    // 行=星座A, 列=星座B, 值=基础分加成 (-10 到 +15)
    // 顺序: 白羊, 金牛, 双子, 巨蟹, 狮子, 处女, 天秤, 天蝎, 射手, 摩羯, 水瓶, 双鱼
    private static final int[][] SUN_COMPATIBILITY = {
        // 白羊 金牛 双子 巨蟹 狮子 处女 天秤 天蝎 射手 摩羯 水瓶 双鱼
        {   8,  -2,  10,  -5,  15,  -8,   5,   0,  12,   2,   8,  -3}, // 白羊
        {  -2,   8,  -5,  10,  -3,  12,  -8,   5,   0,  15,   2,   8}, // 金牛
        {  10,  -5,   8,  -2,   5,   0,  12,  -8,   8,  -3,  15,   2}, // 双子
        {  -5,  10,  -2,   8,  -8,   5,   0,  12,  -3,   8,   2,  15}, // 巨蟹
        {  15,  -3,   5,  -8,   8,  -2,  10,   2,  12,   0,   8,  -5}, // 狮子
        {  -8,  12,   0,   5,  -2,   8,  -5,  10,   2,   8,  -3,  15}, // 处女
        {   5,  -8,  12,   0,  10,  -5,   8,  -2,   8,  15,   2,  -3}, // 天秤
        {   0,   5,  -8,  12,   2,  10,  -2,   8,  -5,   8,  15,   0}, // 天蝎
        {  12,   0,   8,  -3,  12,   2,   8,  -5,   8,  -2,  10,  -8}, // 射手
        {   2,  15,  -3,   8,   0,   8,  15,   8,  -2,   8,  -5,  10}, // 摩羯
        {   8,   2,  15,   2,   8,  -3,   2,  15,  10,  -5,   8,  -2}, // 水瓶
        {  -3,   8,   2,  15,  -5,  15,  -3,   0,  -8,  10,  -2,   8}  // 双鱼
    };

    private static final String[] ZODIAC_ORDER = {
        "白羊座", "金牛座", "双子座", "巨蟹座", "狮子座", "处女座",
        "天秤座", "天蝎座", "射手座", "摩羯座", "水瓶座", "双鱼座"
    };

    private static final Map<String, Integer> ZODIAC_INDEX = new HashMap<>();
    static {
        for (int i = 0; i < ZODIAC_ORDER.length; i++) {
            ZODIAC_INDEX.put(ZODIAC_ORDER[i], i);
        }
    }


    /**
     * 计算合盘分数
     * 基础分 65 + 太阳星座相合度 + 元素平衡 + 信息完整度微调
     * 范围: 60-95
     */
    public int calculateScore(CompatibilityRequest request,
                               ZodiacCalculator.ZodiacTriplet triA,
                               ZodiacCalculator.ZodiacTriplet triB) {
        int baseScore = 65;

        // 1. 太阳星座相合度 (核心权重)
        int sunBonus = getSunCompatibility(triA.sun(), triB.sun());
        baseScore += sunBonus;

        // 2. 元素平衡加成
        int elementBonus = getElementBonus(triA.sun(), triB.sun());
        baseScore += elementBonus;

        // 3. 信息完整度微调 (鼓励用户填写详细信息)
        int detailBonus = getDetailBonus(request);
        baseScore += detailBonus;

        // 4. 月亮星座微调 (如果出生时间提供了)
        int moonBonus = getMoonBonus(triA.moon(), triB.moon(), request);
        baseScore += moonBonus;

        // clamp 到 60-95
        return Math.max(60, Math.min(95, baseScore));
    }

    public int calculatePersonalScore(CompatibilityRequest request,
                                      ZodiacCalculator.ZodiacTriplet triA,
                                      String reportType) {
        int baseScore = switch (reportType) {
            case CompatibilityRequest.REPORT_TYPE_CAREER -> 72;
            case CompatibilityRequest.REPORT_TYPE_WEALTH -> 70;
            default -> 71;
        };

        baseScore += getSingleDetailBonus(request.getPersonA());
        baseScore += getElementDriveBonus(triA.sun(), reportType);
        baseScore += getModeDriveBonus(triA.sun(), reportType);

        return Math.max(60, Math.min(95, baseScore));
    }

    public String inferPersonalType(int score, String sun, String reportType) {
        String element = ELEMENT.getOrDefault(sun, "火");
        return switch (reportType) {
            case CompatibilityRequest.REPORT_TYPE_CAREER -> inferCareerType(score, element);
            case CompatibilityRequest.REPORT_TYPE_WEALTH -> inferWealthType(score, element);
            default -> inferCareerType(score, element);
        };
    }

    /**
     * 根据分数推断关系类型
     */
    public String inferRelationshipType(int score, String sunA, String sunB) {
        if (sunA.equals(sunB)) {
            return score >= 85 ? "同频共振型" : "同频拉扯型";
        }

        String elemA = ELEMENT.getOrDefault(sunA, "火");
        String elemB = ELEMENT.getOrDefault(sunB, "火");

        if (elemA.equals(elemB)) {
            if (score >= 88) return "灵魂共鸣型";
            if (score >= 80) return "同元素默契型";
            return "同元素磨合型";
        }

        // 相生关系
        if (isComplementary(elemA, elemB)) {
            if (score >= 88) return "天作之合型";
            if (score >= 80) return "互补滋养型";
            return "互补成长型";
        }

        if (score >= 85) return "热烈吸引型";
        if (score >= 78) return "互补磨合型";
        if (score >= 70) return "细水长流型";
        return "修行伴侣型";
    }

    /**
     * 根据星座组合生成个性化章节标题
     */
    public String generateChapterTitle(int chapterIndex, String sunA, String sunB, boolean isPremium) {
        String key = sunA + "×" + sunB;

        return switch (chapterIndex) {
            case 0 -> switch (key) {
                case "白羊座×狮子座", "狮子座×白羊座" -> "火与火的碰撞：两个王者如何共存";
                case "巨蟹座×双鱼座", "双鱼座×巨蟹座" -> "深海双生：两个敏感灵魂的共鸣";
                case "天蝎座×巨蟹座", "巨蟹座×天蝎座" -> "水象深情：当敏感遇见占有欲";
                case "双子座×水瓶座", "水瓶座×双子座" -> "风象自由：灵魂伴侣的智力游戏";
                case "金牛座×处女座", "处女座×金牛座" -> "土象踏实：细水长流的稳定之爱";
                case "天秤座×狮子座", "狮子座×天秤座" -> "光芒与优雅：舞台中央的完美搭档";
                default -> "你们的星座基因";
            };
            case 1 -> isPremium
                ? switch (key) {
                    case "白羊座×狮子座", "狮子座×白羊座" -> "烈焰共鸣：激情背后的能量博弈";
                    case "天蝎座×双鱼座", "双鱼座×天蝎座" -> "灵魂共振：当水象深情遇见水象直觉";
                    default -> "元素模式与相位磁场";
                  }
                : switch (key) {
                    case "白羊座×狮子座", "狮子座×白羊座" -> "激情燃烧：谁先低头谁是输家";
                    case "天蝎座×双鱼座", "双鱼座×天蝎座" -> "灵魂交融：一眼万年的宿命感";
                    default -> "你们在一起的化学反应";
                  };
            case 2 -> isPremium ? "矛盾的真相：你们最容易出问题的地方" : "你们最容易出问题的地方";
            case 3 -> isPremium ? "深度相处指南" : "相处指南";
            case 4 -> isPremium ? "宫位叠加与组合盘" : "宫位与运势预演";
            case 5 -> isPremium ? "业力与演化视角" : "综合评估与悄悄话";
            case 6 -> isPremium ? "时间维度：当下与未来" : "合盘总结";
            case 7 -> isPremium ? "写给你的悄悄话" : "珍藏锦囊";
            default -> "合盘章节";
        };
    }

    private int getSunCompatibility(String sunA, String sunB) {
        int idxA = indexOfZodiac(sunA);
        int idxB = indexOfZodiac(sunB);
        if (idxA < 0 || idxB < 0) return 0;
        return SUN_COMPATIBILITY[idxA][idxB];
    }

    private int getElementBonus(String sunA, String sunB) {
        String elemA = ELEMENT.getOrDefault(sunA, "火");
        String elemB = ELEMENT.getOrDefault(sunB, "火");

        if (elemA.equals(elemB)) {
            // 同元素：默契度高，但容易同质化
            return 3;
        }

        // 相生：火->土->风->水->火
        if (isComplementary(elemA, elemB)) {
            return 5;
        }

        // 相克：火-水，土-风
        return -2;
    }

    private int getDetailBonus(CompatibilityRequest request) {
        int bonus = 0;
        var a = request.getPersonA();
        var b = request.getPersonB();

        // 出生时间提供：+2
        if (a.getBirthTime() != null && !a.getBirthTime().isBlank()) bonus += 1;
        if (b.getBirthTime() != null && !b.getBirthTime().isBlank()) bonus += 1;

        // 出生地提供：+1
        if (a.getBirthPlace() != null && !a.getBirthPlace().isBlank()) bonus += 1;
        if (b.getBirthPlace() != null && !b.getBirthPlace().isBlank()) bonus += 1;

        // 双方姓名都提供：+1
        if (a.getName() != null && !a.getName().isBlank() &&
            b.getName() != null && !b.getName().isBlank()) {
            bonus += 1;
        }

        return bonus;
    }

    private int getSingleDetailBonus(CompatibilityRequest.Person person) {
        if (person == null) return 0;
        int bonus = 0;
        if (person.getBirthTime() != null && !person.getBirthTime().isBlank()) bonus += 2;
        if (person.getBirthPlace() != null && !person.getBirthPlace().isBlank()) bonus += 1;
        if (person.getName() != null && !person.getName().isBlank()) bonus += 1;
        return bonus;
    }

    private int getMoonBonus(String moonA, String moonB, CompatibilityRequest request) {
        // 只有提供了出生时间才计算月亮星座加分
        boolean hasTimeA = request.getPersonA().getBirthTime() != null &&
                          !request.getPersonA().getBirthTime().isBlank();
        boolean hasTimeB = request.getPersonB().getBirthTime() != null &&
                          !request.getPersonB().getBirthTime().isBlank();

        if (!hasTimeA || !hasTimeB) return 0;

        String elemA = ELEMENT.getOrDefault(moonA, "火");
        String elemB = ELEMENT.getOrDefault(moonB, "火");

        if (elemA.equals(elemB)) return 2;
        if (isComplementary(elemA, elemB)) return 3;
        return -1;
    }

    private boolean isComplementary(String elemA, String elemB) {
        // 相生：火生土，土生风，风生水，水生火
        return (elemA.equals("火") && elemB.equals("土")) ||
               (elemA.equals("土") && elemB.equals("风")) ||
               (elemA.equals("风") && elemB.equals("水")) ||
               (elemA.equals("水") && elemB.equals("火")) ||
               (elemA.equals("土") && elemB.equals("火")) ||
               (elemA.equals("风") && elemB.equals("土")) ||
               (elemA.equals("水") && elemB.equals("风")) ||
               (elemA.equals("火") && elemB.equals("水"));
    }

    private int getElementDriveBonus(String sun, String reportType) {
        String element = ELEMENT.getOrDefault(sun, "火");
        if (CompatibilityRequest.REPORT_TYPE_CAREER.equals(reportType)) {
            return switch (element) {
                case "火" -> 5;
                case "土" -> 4;
                case "风" -> 3;
                case "水" -> 2;
                default -> 3;
            };
        }
        return switch (element) {
            case "土" -> 5;
            case "风" -> 4;
            case "火" -> 3;
            case "水" -> 2;
            default -> 3;
        };
    }

    private int getModeDriveBonus(String sun, String reportType) {
        String mode = ZodiacCalculator.MODE.getOrDefault(sun, "固定");
        if (CompatibilityRequest.REPORT_TYPE_CAREER.equals(reportType)) {
            return switch (mode) {
                case "基本" -> 4;
                case "固定" -> 3;
                case "变动" -> 2;
                default -> 2;
            };
        }
        return switch (mode) {
            case "固定" -> 4;
            case "基本" -> 3;
            case "变动" -> 2;
            default -> 2;
        };
    }

    private String inferCareerType(int score, String element) {
        if (score >= 88) return "执行型增长期";
        if (score >= 82) {
            return switch (element) {
                case "土" -> "沉淀型跃迁期";
                case "风" -> "协同型拓展期";
                case "水" -> "洞察型蓄势期";
                default -> "行动型突破期";
            };
        }
        if (score >= 74) return "稳步积累期";
        return "调整蓄力期";
    }

    private String inferWealthType(int score, String element) {
        if (score >= 88) return "资源型放大期";
        if (score >= 82) {
            return switch (element) {
                case "土" -> "稳进型积累期";
                case "风" -> "机会型流动期";
                case "水" -> "洞察型守成期";
                default -> "开拓型增收期";
            };
        }
        if (score >= 74) return "稳守调整期";
        return "收支校准期";
    }

    private int indexOfZodiac(String zodiac) {
        return ZODIAC_INDEX.getOrDefault(zodiac, -1);
    }
}
