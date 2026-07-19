package com.zodiac.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zodiac.api.dto.CompatibilityRequest;
import com.zodiac.api.dto.CompatibilityResponse;
import com.zodiac.api.entity.SoulmateReport;
import com.zodiac.api.repository.SoulmateReportRepository;
import com.zodiac.api.util.SwissEphemerisCalculator;
import com.zodiac.api.util.ZodiacCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompatibilityService {

    private final AiChatService aiChatService;
    private final SoulmateReportRepository repository;
    private final ZodiacScoringService scoringService;
    private final ObjectMapper objectMapper;
    private final SwissEphemerisCalculator swissEphemerisCalculator;
    private static final int MIN_CHAPTERS = 6;
    private static final int MIN_ESSENCE = 6;
    private static final int PREMIUM_MIN_CHAPTERS = 8;
    private static final int PREMIUM_MIN_ESSENCE = 8;
    private static final Pattern KEYWORD_COMMA_FIX =
            Pattern.compile("(?<=[\\}\"\\]0-9])\\s*\\n\\s*\"(?=[A-Za-z\\u4e00-\\u9fa5_]+\"\\s*:)");
    private static final Pattern ARRAY_OBJECT_BOUNDARY_FIX =
            Pattern.compile("}\\s*\\n\\s*\"(?=[A-Za-z\\u4e00-\\u9fa5_]+\"\\s*:)");
    private static final Pattern MISSING_ARRAY_COMMA_FIX =
            Pattern.compile("}\\s*\\n\\s*\\{");
    private static final Pattern PROMPT_INJECTION_CLEAN =
            Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]");
    private static final String DEFAULT_MODEL = "deepseek";
    private static final String PROMPT_BASE_PATH = "prompts/";
    private static final String DEEPSEEK_MODEL = "deepseek";
    private static final String CLAUDE_MODEL = "claude";
    private static final String DEEPSEEK_ADDON = "model-deepseek-addon.txt";
    private static final DateTimeFormatter REPORT_UID_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String CLAUDE_ADDON = "model-claude-addon.txt";


    public CompatibilityResponse generateReport(CompatibilityRequest request, String ip, String userAgent) {
        String reportType = normalizeReportType(request.getReportType());
        boolean singleReport = isSingleReport(reportType);
        // 使用 Swiss Ephemeris 精确计算（如果有经纬度），否则使用简化算法
        var triA = computeZodiacTriplet(request.getPersonA());
        var triB = singleReport
                ? triA
                : computeZodiacTriplet(request.getPersonB());

        if (singleReport) {
            log.info("Generating {} report: {}({}/{}/{})",
                    reportType,
                    request.getPersonA().getName(), triA.sun(), triA.moon(), triA.rising());
        } else {
            log.info("Generating compatibility report: {}({}/{}/{}) x {}({}/{}/{})",
                    request.getPersonA().getName(), triA.sun(), triA.moon(), triA.rising(),
                    request.getPersonB().getName(), triB.sun(), triB.moon(), triB.rising());
        }

        String selectedModel = normalizeModelCode(request.getModel());
        boolean isPremium = CLAUDE_MODEL.equals(selectedModel);
        String executionModel = isPremium ? DEEPSEEK_MODEL : selectedModel;
        int score = singleReport
                ? scoringService.calculatePersonalScore(request, triA, reportType)
                : scoringService.calculateScore(request, triA, triB);
        String relType = singleReport
                ? scoringService.inferPersonalType(score, triA.sun(), reportType)
                : scoringService.inferRelationshipType(score, triA.sun(), triB.sun());

        String systemPrompt = buildSystemPrompt(reportType, isPremium, executionModel);
        String deepSeekFallbackSystemPrompt = buildSystemPrompt(reportType, isPremium, DEEPSEEK_MODEL);
        String userPrompt = buildUserPrompt(request, triA, triB, isPremium, score, relType, reportType);
        String raw = aiChatService.generate(systemPrompt, userPrompt, executionModel, deepSeekFallbackSystemPrompt);
        CompatibilityResponse response;
        try {
            response = buildResponseWithScore(raw, request, triA, triB, score, relType, reportType);
        } catch (AiServiceException error) {
            if (error.getReason() != AiServiceException.Reason.INVALID_RESPONSE) {
                throw error;
            }
            log.warn("AI payload invalid after recovery, returning fallback report instead. raw preview: {}", preview(raw), error);
            response = buildFallbackResponse(request, triA, triB, raw, score, relType, reportType);
        }

        // 附加表单信息,方便前端渲染
        response.setPersonA(buildPersonInfo(request.getPersonA()));
        response.setPersonB(singleReport ? null : buildPersonInfo(request.getPersonB()));
        response.setReportType(reportType);

        try {
            SoulmateReport entity = toEntity(request, response, triA, triB, raw, ip, userAgent, reportType);
            repository.saveAndFlush(entity);
        } catch (Exception e) {
            log.warn("Saving report failed but response remains usable: {}", e.getMessage(), e);
        }

        return response;
    }

    /**
     * 根据 reportUid 从数据库查询并重建内容(分享链接用)
     */
    public Optional<CompatibilityResponse> getReportByUid(String uid) {
        return repository.findByReportUid(uid).map(entity -> {
            String reportType = normalizeReportType(entity.getReportType());
            boolean singleReport = isSingleReport(reportType);
            // 用 entity 字段重建 Person
            var personA = new CompatibilityRequest.Person();
            personA.setName(entity.getUserAName());
            personA.setGender(entity.getUserAGender());
            personA.setBirthDate(entity.getUserABirthDate());
            personA.setBirthTime(entity.getUserABirthTime());
            personA.setBirthPlace(entity.getUserABirthPlace());
            personA.setBirthLatitude(entity.getUserABirthLatitude());
            personA.setBirthLongitude(entity.getUserABirthLongitude());
            personA.setBirthTimezone(entity.getUserABirthTimezone());

            CompatibilityRequest.Person personB = null;
            if (!singleReport && entity.getUserBName() != null) {
                personB = new CompatibilityRequest.Person();
                personB.setName(entity.getUserBName());
                personB.setGender(entity.getUserBGender());
                personB.setBirthDate(entity.getUserBBirthDate());
                personB.setBirthTime(entity.getUserBBirthTime());
                personB.setBirthPlace(entity.getUserBBirthPlace());
                personB.setBirthLatitude(entity.getUserBBirthLatitude());
                personB.setBirthLongitude(entity.getUserBBirthLongitude());
                personB.setBirthTimezone(entity.getUserBBirthTimezone());
            }

            var req = new CompatibilityRequest();
            req.setPersonA(personA);
            req.setPersonB(personB);
            req.setModel(entity.getModelCode());
            req.setReportType(reportType);

            var triA = new ZodiacCalculator.ZodiacTriplet(
                    entity.getZodiacA(), entity.getMoonA(), entity.getRisingA());
            var triB = singleReport
                    ? triA
                    : new ZodiacCalculator.ZodiacTriplet(
                            entity.getZodiacB(), entity.getMoonB(), entity.getRisingB());

            int storedScore = entity.getScore() != null
                    ? entity.getScore()
                    : (singleReport
                        ? scoringService.calculatePersonalScore(req, triA, reportType)
                        : scoringService.calculateScore(req, triA, triB));
            String storedRelType = entity.getRelationshipType() != null
                    ? entity.getRelationshipType()
                    : (singleReport
                        ? scoringService.inferPersonalType(storedScore, triA.sun(), reportType)
                        : scoringService.inferRelationshipType(storedScore, triA.sun(), triB.sun()));
            CompatibilityResponse resp;
            try {
                resp = buildResponseWithScore(
                        entity.getFullReport(), req, triA, triB, storedScore, storedRelType, reportType);
            } catch (Exception error) {
                log.warn("Rebuild shared report failed, fallback to stored metadata: uid={}", uid, error);
                resp = buildFallbackResponse(
                        req,
                        triA,
                        triB,
                        entity.getFullReport(),
                        storedScore,
                        storedRelType,
                        reportType
                );
            }
            resp.setPersonA(buildPersonInfo(personA));
            resp.setPersonB(singleReport ? null : buildPersonInfo(personB));
            resp.setReportType(reportType);
            resp.setReportUid(entity.getReportUid());
            return resp;
        });
    }

    private CompatibilityResponse.PersonInfo buildPersonInfo(CompatibilityRequest.Person p) {
        if (p == null) {
            return null;
        }
        return CompatibilityResponse.PersonInfo.builder()
                .name(p.getName())
                .gender(p.getGender())
                .birthDate(p.getBirthDate())
                .birthTime(p.getBirthTime())
                .birthPlace(p.getBirthPlace())
                .build();
    }

    private String buildSystemPrompt(String reportType, boolean isPremium, String modelCode) {
        String themePrompt = loadPrompt(resolveSystemPromptKey(reportType, isPremium));
        String modelAddon = loadPrompt(resolveModelAddonKey(modelCode));
        return themePrompt + System.lineSeparator() + System.lineSeparator() + modelAddon;
    }

    private String resolveSystemPromptKey(String reportType, boolean isPremium) {
        String normalizedReportType = normalizeReportType(reportType);
        String tier = isPremium ? "premium" : "free";
        return normalizedReportType + "-" + tier + "-system.txt";
    }

    private String resolveModelAddonKey(String modelCode) {
        return CLAUDE_MODEL.equalsIgnoreCase(modelCode) ? CLAUDE_ADDON : DEEPSEEK_ADDON;
    }

    private String loadPrompt(String promptKey) {
        String resourcePath = PROMPT_BASE_PATH + promptKey;
        ClassPathResource resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
            log.error("System prompt file not found: {}", resourcePath);
            throw new IllegalStateException("Missing system prompt file: " + resourcePath);
        }
        try {
            String prompt = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8).trim();
            if (prompt.isBlank()) {
                log.error("System prompt file is blank: {}", resourcePath);
                throw new IllegalStateException("Blank system prompt file: " + resourcePath);
            }
            return prompt;
        } catch (IOException e) {
            log.error("Failed to load system prompt file: {}", resourcePath, e);
            throw new IllegalStateException("Failed to load system prompt file: " + resourcePath, e);
        }
    }

    private String buildFreeSystemPrompt() {
        return """
                你是「小登哥」，一位擅长关系沟通与个人状态分析的内容顾问。你的任务不是做神秘化定性，而是根据系统整理出的双方信息、风格标签、评分和阶段标签，生成一份清晰、具体、有人味的双人关系内容。

                【输出要求】
                1. 只输出 JSON，不要代码块，不要解释，不要额外前后缀。
                2. JSON 必须合法，所有字符串必须正确转义，字段之间必须有英文逗号。
                3. 所有 chapter.content 都必须是普通字符串，不要嵌套对象，不要列表标记。
                4. 不要在 JSON 末尾补任何总结或署名说明，署名只允许出现在最后一章正文里。
                5. 总字数控制在 3000-5000 字。

                【分析框架 - 6章】
                {
                  "score": 60-95 的整数（由系统计算，不要自行编造）,
                  "relationshipType": "4到8个字的关系类型（使用系统给定的值）",
                  "tagline": "一句话总结，不超过30字",
                  "chapters": [
                    {"title": "你们各自的相处底色", "emoji": "✨", "content": "分析两个人各自的沟通方式、情绪节奏和安全感来源，解释这些特质在亲密关系中的具体表现，每人不低于300字。" },
                    {"title": "你们在一起的互动感觉", "emoji": "💞", "content": "从两个维度分析：①彼此节奏是否匹配；②情绪表达、回应方式和价值侧重点是否容易形成默契。要描述具体相处场景，不要只列抽象特质。" },
                    {"title": "你们最容易卡住的地方", "emoji": "⚠️", "content": "分析3-4个具体矛盾场景。每个矛盾要有画面感，说明双方为什么会误解，以及误解是怎么一步步放大的。" },
                    {"title": "相处指南", "emoji": "🧭", "content": "提供5-6条具体可操作的相处策略。每条建议要给出适用场景和话术示例，重点落在如何说清楚、如何减少误解、如何让关系更稳。" },
                    {"title": "未来一段时间的关系重点", "emoji": "🔮", "content": "结合当前状态，判断未来三个月更应该优先处理哪些现实议题、沟通议题和边界议题。" },
                    {"title": "综合评估与悄悄话", "emoji": "🌙", "content": "①五维度评分（情感/激情/沟通/承诺/成长），每个维度1-10分并附一句话解读；②列出3个关系优势和3个需要注意的挑战；③以知心朋友的口吻写一段温暖的结尾，署名：—— 小登哥 ✨" }
                  ],
                  "essence": [
                    "6条可收藏的建议，每条15字以内，具体可操作"
                  ]
                }

                【禁止事项】
                - 不推断生死、疾病、灾难等敏感内容
                - 不做财务投资具体建议
                - 不替代心理咨询或医疗建议
                - 不使用绝对化表述（如"一定会""注定"）

                【写作风格】
                - 专业但通俗易懂，每个判断必须有明确依据
                - 有温度、有洞察，像朋友聊天但保持专业度
                - 避免神秘化、空泛化和模板化描述
                - 偶尔使用 emoji 增加亲和力，但不过度
                """;
    }

    private String buildPremiumSystemPrompt() {
        return """
                你是「小登哥」，一位擅长关系沟通、长期相处与个人成长分析的内容顾问。你的任务不是做神秘化定性，而是根据系统整理出的双方信息、风格标签、评分和阶段标签，生成一份更深入、更具体的双人关系扩展内容。

                【输出要求】
                1. 只输出 JSON，不要代码块，不要解释，不要额外前后缀。
                2. JSON 必须合法，所有字符串必须正确转义，字段之间必须有英文逗号。
                3. 所有 chapter.content 都必须是普通字符串，不要嵌套对象，不要列表标记。
                4. 不要在 JSON 末尾补任何总结或署名说明，署名只允许出现在最后一章正文里。
                5. 总字数控制在 5000-8000 字，每章至少 500 字。
                6. 全文必须使用第二人称"你"来叙述，营造一对一咨询的专属感。
                7. 分析时必须先进行逻辑推演（在思考中完成），确保每个判断都有明确依据。

                【分析框架 - 8章】
                {
                  "score": 60-95 的整数（由系统计算，不要自行编造）,
                  "relationshipType": "4到8个字的关系类型（使用系统给定的值）",
                  "tagline": "一句话总结，不超过30字",
                  "chapters": [
                    {"title": "你们各自的相处底色", "emoji": "✨", "content": "深度分析两个人各自的沟通方式、情绪节奏和安全感来源，每人各不低于400字。用第二人称'你'叙述，描述这些特质在真实关系中的表现。" },
                    {"title": "互动模式与吸引来源", "emoji": "💞", "content": "从三个维度分析：①节奏是否匹配；②回应方式是否顺畅；③价值侧重点是否容易形成默契。必须使用具体场景描写，制造'被看穿'的惊喜感。" },
                    {"title": "矛盾的真相：你们最容易出问题的地方", "emoji": "⚠️", "content": "分析3-4个具体矛盾场景。每个矛盾按五段式写：①场景还原；②你的期待；③TA的实际反应；④结果；⑤真相。真相部分要落在沟通差异、情绪节奏和边界感上，不要写成空泛定性。" },
                    {"title": "扩展相处指南", "emoji": "🧭", "content": "提供6-8条具体可操作的相处策略。每条建议必须包含：①适用场景；②你可以这样说（给出 exact 话术示例，带引号）；③为什么有效（明确依据）。" },
                    {"title": "现实议题与长期安排", "emoji": "🔮", "content": "分析这段关系在现实安排、边界感、承诺感和相处节奏上的关键议题，描述它们如何影响关系走向。" },
                    {"title": "成长视角", "emoji": "🌟", "content": "从成长视角解读：这段关系会放大你们各自哪些旧问题、旧习惯和盲区，又可能推动你们在哪些地方变得更成熟。不要写神秘化内容。" },
                    {"title": "时间维度：当下与未来", "emoji": "📅", "content": "分析未来12个月里对关系影响最大的2到3个关键节点，说明哪些阶段适合推进，哪些阶段适合回看和调整。" },
                    {"title": "写给你的悄悄话", "emoji": "🌙", "content": "①五维度综合评分（情感/激情/沟通/承诺/成长），每个维度1-10分并附一句话解读；②3个关系优势与3个需要注意的挑战；③以知心朋友的口吻写一段350字左右的走心总结，让用户觉得'这说的就是我'。自然引导：'这份内容可以转发给TA，或截图保存。' 结尾署名：—— 小登哥 ✨" }
                  ],
                  "essence": [
                    "8条珍藏锦囊，每条15字以内，格式如：'当他专注其他事时，直接说'我需要你抱抱我''"
                  ]
                }

                【禁止事项】
                - 不推断生死、疾病、灾难等敏感内容
                - 不做财务投资具体建议
                - 不替代心理咨询或医疗建议
                - 不使用绝对化表述（如"一定会""注定""永远"）

                【写作风格】
                - 全文使用第二人称"你"，像真人顾问一对一咨询
                - 有温度、有细节、有画面感，使用具体对话和场景
                - 制造'被看穿'的惊喜感，让用户觉得"这说的就是我"
                - 每个判断必须有明确依据，专业但不学究
                - 在关键处埋下情感钩子，让用户想分享或保存
                - 避免模板化，每对组合的描述都要有独特性
                """;
    }

    private String buildSingleSystemPrompt(boolean isPremium, String reportType) {
        String reportName = reportTypeName(reportType);
        String focus = CompatibilityRequest.REPORT_TYPE_CAREER.equals(reportType)
                ? "职业驱动力、适合角色、团队协作方式、未来90天行动安排"
                : "资源安排方式、风险点、副业机会、未来90天生活节奏";
        String chapters = CompatibilityRequest.REPORT_TYPE_CAREER.equals(reportType)
                ? """
                    {"title": "你的核心驱动力", "emoji": "✨", "content": "分析你的个人状态如何影响职业欲望、决策方式与压力反应。"},
                    {"title": "适合你的工作角色", "emoji": "🧭", "content": "结合元素与模式，判断你更适合独立推进、团队协作还是资源整合型岗位。"},
                    {"title": "职场里的优势与盲区", "emoji": "⚠️", "content": "拆解你最容易被看见的优点，以及最容易卡住你的习惯性模式。"},
                    {"title": "未来90天行动建议", "emoji": "📈", "content": "给出短期推进建议：先做什么、暂停什么、每周如何复盘。"},
                    {"title": "升级建议", "emoji": "🔮", "content": "总结最值得投入的成长方向、合作方式和执行策略。"},
                    {"title": "写给你的提醒", "emoji": "🌙", "content": "以一对一咨询口吻写一段有行动感的结语。"}
                  """
                : """
                    {"title": "你的生活基调", "emoji": "✨", "content": "分析你的个人状态如何影响安全感、资源安排和风险反应。"},
                    {"title": "资源最容易从哪里放大", "emoji": "💎", "content": "结合你的信息结构，判断你更适合稳定累积、资源整合还是机会型增长。"},
                    {"title": "最需要提防的风险点", "emoji": "⚠️", "content": "拆解消费习惯、拖延模式和高风险冲动。"},
                    {"title": "接下来90天的生活节奏", "emoji": "📊", "content": "给出短期安排建议：什么时候适合保守、什么时候适合推进收入增长。"},
                    {"title": "副业与放大机会", "emoji": "🔮", "content": "总结最适合你当前阶段的副业思路和资源放大方式。"},
                    {"title": "写给你的提醒", "emoji": "🌙", "content": "以一对一咨询口吻写一段务实又温柔的结语。"}
                  """;
        String premiumNote = isPremium
                ? "5. 总字数控制在 900-1300 字，每章 120-180 字。\n6. 全文使用第二人称“你”，做成专业咨询感。"
                : "5. 总字数控制在 600-900 字。";

        return """
                你是「小登哥」，一位拥有20年经验的个人成长顾问，擅长把用户提供的信息整理成清晰、实用、有人味的个人分析。

                【输出要求】
                1. 只输出 JSON，不要代码块，不要解释，不要额外前后缀。
                2. JSON 必须合法，所有字符串必须正确转义，字段之间必须有英文逗号。
                3. 所有 chapter.content 都必须是普通字符串，不要嵌套对象，不要列表标记。
                4. 不要在 JSON 末尾补任何总结或署名说明，署名只允许出现在最后一章正文里。
                %s

                【内容类型】
                当前要生成的是「%s」。
                重点围绕：%s。

                【分析框架】
                {
                  "score": 60-95 的整数（由系统计算，不要自行编造）,
                  "relationshipType": "4到8个字的阶段标签（使用系统给定的值）",
                  "tagline": "一句话总结，不超过30字",
                  "chapters": [
                %s
                  ],
                  "essence": [
                    "%s"
                  ]
                }

                【禁止事项】
                - 不推断生死、疾病、灾难等敏感内容
                - 不提供具体金融投资买卖建议
                - 不替代法律、医疗或心理咨询
                - 不使用绝对化表述（如“一定会”“注定”）

                【写作风格】
                - 专业但通俗，必须有明确依据
                - 有画面感、有行动建议，但不夸张神化
                - 结论务实，适合直接截图保存或转发
                """.formatted(
                premiumNote,
                reportName,
                focus,
                chapters,
                CompatibilityRequest.REPORT_TYPE_CAREER.equals(reportType)
                        ? "把要紧的事排在高能量时段"
                        : "先稳现金流，再谈放大机会");
    }

    private String buildUserPrompt(CompatibilityRequest req,
                                   ZodiacCalculator.ZodiacTriplet triA,
                                   ZodiacCalculator.ZodiacTriplet triB,
                                   boolean isPremium,
                                   int calculatedScore,
                                   String relationshipType,
                                   String reportType) {
        if (isSingleReport(reportType)) {
            return buildSingleUserPrompt(req, triA, isPremium, calculatedScore, relationshipType, reportType);
        }
        var a = req.getPersonA();
        var b = req.getPersonB();

        StringBuilder sb = new StringBuilder();
        sb.append("请为以下两位用户生成一份双人关系内容。\n\n");
        sb.append("【系统已计算的数据 - 必须使用】\n");
        sb.append("关系分数: ").append(calculatedScore).append("分\n");
        sb.append("关系类型: ").append(relationshipType).append("\n");
        sb.append("请在内容中严格使用以上分数和关系类型，不要自行编造。\n\n");

        appendPersonInfo(sb, "A", a.getName(), a.getGender(), a.getBirthDate(),
                a.getBirthTime(), a.getBirthPlace(), triA, isPremium);
        sb.append("\n");
        appendPersonInfo(sb, "B", b.getName(), b.getGender(), b.getBirthDate(),
                b.getBirthTime(), b.getBirthPlace(), triB, isPremium);
        sb.append("\n");

        // 元素与模式对比摘要
        sb.append("【元素与模式对比】\n");
        sb.append("A的元素: 太阳").append(elem(triA.sun())).append(" / 月亮").append(elem(triA.moon()))
          .append(" / 上升").append(elem(triA.rising())).append("\n");
        sb.append("B的元素: 太阳").append(elem(triB.sun())).append(" / 月亮").append(elem(triB.moon()))
          .append(" / 上升").append(elem(triB.rising())).append("\n");
        sb.append("A的太阳模式: ").append(mode(triA.sun())).append(" / B的太阳模式: ").append(mode(triB.sun())).append("\n\n");

        if (isPremium) {
            sb.append("【扩展版特别要求 - 必须遵守】\n");
            sb.append("1. 全文必须使用第二人称'你'来叙述，营造一对一咨询的专属感\n");
            sb.append("2. '矛盾的真相'章节：每个矛盾必须按'场景还原→你的期待→TA的实际反应→结果→真相'五段式写\n");
            sb.append("3. '扩展相处指南'章节：每条建议必须包含适用场景+你可以这样说（exact话术）+为什么有效（明确依据）\n");
            sb.append("4. '元素模式与互动张力'章节：必须逐一分析双方互动中的关键差异\n");
            sb.append("5. '成长视角与调整方向'章节：聚焦关系里的成长意义，不要写成神秘化叙事\n");
            sb.append("6. '时间维度'章节：标注未来12个月中对关系影响最大的2-3个关键节点\n");
            sb.append("7. essence珍藏锦囊必须是8条，每条15字以内\n");
            sb.append("8. 营造'小登哥一对一为你深入解读'的专属感，让用户觉得'这说的就是我'\n\n");
        }

        sb.append("请用专业但有温度的中文写作，但最终只返回合法 JSON。");
        return sb.toString();
    }

    private String buildSingleUserPrompt(CompatibilityRequest req,
                                         ZodiacCalculator.ZodiacTriplet triA,
                                         boolean isPremium,
                                         int calculatedScore,
                                         String stageLabel,
                                         String reportType) {
        var a = req.getPersonA();
        StringBuilder sb = new StringBuilder();
        sb.append("请为以下用户生成一份").append(reportTypeName(reportType)).append("。\n\n");
        sb.append("【系统已计算的数据 - 必须使用】\n");
        sb.append("内容分数: ").append(calculatedScore).append("分\n");
        sb.append("阶段标签: ").append(stageLabel).append("\n");
        sb.append("请在内容中严格使用以上分数和阶段标签，不要自行编造。\n\n");

        appendPersonInfo(sb, "A", a.getName(), a.getGender(), a.getBirthDate(),
                a.getBirthTime(), a.getBirthPlace(), triA, isPremium);
        sb.append("\n");
        sb.append("【主题重点】\n");
        if (CompatibilityRequest.REPORT_TYPE_CAREER.equals(reportType)) {
            sb.append("请重点分析：职业驱动力、适合角色、团队协作方式、未来90天行动安排。\n");
        } else {
            sb.append("请重点分析：资源安排方式、消费习惯、副业机会、未来90天生活节奏。\n");
        }
        if (isPremium) {
            sb.append("【扩展版要求】\n");
            sb.append("1. 使用第二人称'你'做一对一咨询感输出。\n");
            sb.append("2. 每章都要给出具象场景和可执行建议。\n");
            sb.append("3. essence 至少 8 条，每条15字以内。\n\n");
        }
        sb.append("请用专业但有温度的中文写作，但最终只返回合法 JSON。");
        return sb.toString();
    }

    private void appendPersonInfo(StringBuilder sb, String label, String name, String gender,
                                  String birthDate, String birthTime, String birthPlace,
                                  ZodiacCalculator.ZodiacTriplet tri, boolean isPremium) {
        sb.append("【用户").append(label).append(" / ").append("A".equals(label) ? "内容主角" : "TA").append("】\n");
        sb.append("姓名: ").append(sanitizeForPrompt(name)).append("\n");
        sb.append("性别: ").append("male".equals(gender) ? "男" : "女").append("\n");
        sb.append("生日: ").append(sanitizeForPrompt(birthDate)).append("\n");
        if (birthTime != null && !birthTime.isBlank()) {
            sb.append("出生时间: ").append(sanitizeForPrompt(birthTime)).append("\n");
        }
        if (birthPlace != null && !birthPlace.isBlank()) {
            sb.append("出生地: ").append(sanitizeForPrompt(birthPlace)).append("\n");
        }
        sb.append("太阳: ").append(tri.sun()).append(" (").append(elem(tri.sun())).append("元素, ")
          .append(mode(tri.sun())).append("模式)\n");
        sb.append("月亮: ").append(tri.moon()).append(" (").append(elem(tri.moon())).append("元素)\n");
        sb.append("上升: ").append(tri.rising()).append(" (").append(elem(tri.rising())).append("元素)\n");
        if (isPremium) {
            sb.append("补充维度A: ").append(tri.sun()).append("（用于补充偏好表达）\n");
            sb.append("补充维度B: ").append(tri.moon()).append("（用于补充行动风格）\n");
        }
    }

    private String elem(String zodiac) {
        return ZodiacCalculator.ELEMENT.getOrDefault(zodiac, "?");
    }

    private String mode(String zodiac) {
        return ZodiacCalculator.MODE.getOrDefault(zodiac, "?");
    }

    // 保留 4 参数签名供测试使用
    CompatibilityResponse parseResponse(String raw,
                                        CompatibilityRequest request,
                                        ZodiacCalculator.ZodiacTriplet triA,
                                        ZodiacCalculator.ZodiacTriplet triB) {
        String reportType = normalizeReportType(request.getReportType());
        boolean singleReport = isSingleReport(reportType);
        int score = singleReport
                ? scoringService.calculatePersonalScore(request, triA, reportType)
                : scoringService.calculateScore(request, triA, triB);
        String relType = singleReport
                ? scoringService.inferPersonalType(score, triA.sun(), reportType)
                : scoringService.inferRelationshipType(score, triA.sun(), triB.sun());
        return buildResponseWithScore(raw, request, triA, triB, score, relType, reportType);
    }

    private CompatibilityResponse buildResponseWithScore(String raw,
                                                         CompatibilityRequest request,
                                                         ZodiacCalculator.ZodiacTriplet triA,
                                                         ZodiacCalculator.ZodiacTriplet triB,
                                                         int score,
                                                         String relType,
                                                         String reportType) {
        String normalized = sanitizeRawJson(raw);
        try {
            JsonNode root = tryParseJson(normalized);
            return buildResponseFromJson(root, request, triA, triB, score, relType, reportType);
        } catch (Exception parseError) {
            log.warn("Primary JSON parse failed, attempting fallback extraction. raw preview: {}",
                    preview(raw), parseError);
            try {
                JsonNode recovered = tryParseJson(repairCommonJsonIssues(normalized));
                return buildResponseFromJson(recovered, request, triA, triB, score, relType, reportType);
            } catch (Exception recoveryError) {
                log.error("AI response recovery failed, switching to fallback report. raw preview: {}",
                        preview(raw), recoveryError);
                throw new AiServiceException(
                        AiServiceException.Reason.INVALID_RESPONSE,
                        "大模型返回内容格式异常，无法生成内容。请稍后重试。"
                );
            }
        }
    }

    private CompatibilityResponse buildResponseFromJson(JsonNode root,
                                                        CompatibilityRequest request,
                                                        ZodiacCalculator.ZodiacTriplet triA,
                                                        ZodiacCalculator.ZodiacTriplet triB,
                                                        int score,
                                                        String relType,
                                                        String reportType) {
        List<CompatibilityResponse.Chapter> chapters = extractChapters(root.path("chapters"));
        List<String> essence = extractEssence(root.path("essence"));

        if (chapters.isEmpty()) {
            throw new IllegalArgumentException("AI response did not contain usable chapters.");
        }

        boolean isPremium = "claude".equalsIgnoreCase(request.getModel());
        chapters = ensureChapterDefaults(chapters, request, triA, triB, isPremium, reportType);
        essence = ensureEssenceDefaults(essence, request, triA, triB, isPremium, reportType);

        String relationshipType = textOrDefault(root.path("relationshipType"), relType);
        String tagline = textOrDefault(root.path("tagline"), defaultTagline(request, reportType));

        return CompatibilityResponse.builder()
                .score(score)
                .relationshipType(relationshipType)
                .tagline(tagline)
                .reportType(reportType)
                .chapters(chapters)
                .essence(essence)
                .reportUid(generateReportUid(request.getPersonA().getName()))
                .zodiacA(toZodiacInfo(triA))
                .zodiacB(isSingleReport(reportType) ? null : toZodiacInfo(triB))
                .build();
    }

    private JsonNode tryParseJson(String content) throws Exception {
        return objectMapper.readTree(content);
    }

    private String sanitizeRawJson(String raw) {
        if (raw == null) {
            return "{}";
        }

        String cleaned = raw.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        cleaned = cleaned.trim();

        int first = cleaned.indexOf('{');
        int last = cleaned.lastIndexOf('}');
        if (first >= 0 && last > first) {
            cleaned = cleaned.substring(first, last + 1);
        }
        return cleaned.trim();
    }

    private String repairCommonJsonIssues(String content) {
        String fixed = KEYWORD_COMMA_FIX.matcher(content).replaceAll(",\n\"");
        fixed = ARRAY_OBJECT_BOUNDARY_FIX.matcher(fixed).replaceAll("},\n\"");
        fixed = MISSING_ARRAY_COMMA_FIX.matcher(fixed).replaceAll("},\n{");
        if (fixed.length() > content.length() * 2) {
            fixed = content;
        }
        fixed = fixed
                .replace("“", "\"")
                .replace("”", "\"")
                .replace("‘", "'")
                .replace("’", "'")
                .replace("：", ":");
        return fixed;
    }

    private List<CompatibilityResponse.Chapter> extractChapters(JsonNode chaptersNode) {
        List<CompatibilityResponse.Chapter> chapters = new ArrayList<>();
        if (chaptersNode.isArray()) {
            for (Iterator<JsonNode> it = chaptersNode.elements(); it.hasNext(); ) {
                JsonNode c = it.next();
                String title = textOrDefault(c.path("title"), "");
                String content = textOrDefault(c.path("content"), "");
                if (title.isBlank() && content.isBlank()) {
                    continue;
                }
                chapters.add(CompatibilityResponse.Chapter.builder()
                        .title(title.isBlank() ? "内容章节" : title)
                        .emoji(textOrDefault(c.path("emoji"), "✨"))
                        .content(content)
                        .build());
            }
        }
        return chapters;
    }

    private List<String> extractEssence(JsonNode essenceNode) {
        List<String> essence = new ArrayList<>();
        if (essenceNode.isArray()) {
            for (Iterator<JsonNode> it = essenceNode.elements(); it.hasNext(); ) {
                String item = textOrDefault(it.next(), "");
                if (!item.isBlank()) {
                    essence.add(item);
                }
            }
        }
        return essence;
    }

    private List<CompatibilityResponse.Chapter> ensureChapterDefaults(List<CompatibilityResponse.Chapter> chapters,
                                                                      CompatibilityRequest request,
                                                                      ZodiacCalculator.ZodiacTriplet triA,
                                                                      ZodiacCalculator.ZodiacTriplet triB,
                                                                      boolean isPremium,
                                                                      String reportType) {
        List<CompatibilityResponse.Chapter> result = new ArrayList<>(chapters);
        List<CompatibilityResponse.Chapter> fallback = fallbackChapters(request, triA, triB, isPremium, reportType);
        int minChapters = isPremium ? PREMIUM_MIN_CHAPTERS : MIN_CHAPTERS;

        for (int i = 0; i < result.size(); i++) {
            CompatibilityResponse.Chapter current = result.get(i);
            CompatibilityResponse.Chapter base = fallback.get(Math.min(i, fallback.size() - 1));
            result.set(i, CompatibilityResponse.Chapter.builder()
                    .title(current.getTitle() == null || current.getTitle().isBlank() ? base.getTitle() : current.getTitle())
                    .emoji(current.getEmoji() == null || current.getEmoji().isBlank() ? base.getEmoji() : current.getEmoji())
                    .content(current.getContent() == null || current.getContent().isBlank() ? base.getContent() : current.getContent())
                    .build());
        }

        int idx = result.size();
        while (result.size() < minChapters && idx < fallback.size()) {
            result.add(fallback.get(idx));
            idx++;
        }

        return result;
    }

    private List<String> ensureEssenceDefaults(List<String> essence,
                                               CompatibilityRequest request,
                                               ZodiacCalculator.ZodiacTriplet triA,
                                               ZodiacCalculator.ZodiacTriplet triB,
                                               boolean isPremium,
                                               String reportType) {
        List<String> result = new ArrayList<>(essence);
        List<String> fallback = fallbackEssence(request, triA, triB, isPremium, reportType);
        int minEssence = isPremium ? PREMIUM_MIN_ESSENCE : MIN_ESSENCE;
        int idx = 0;
        while (result.size() < minEssence && idx < fallback.size()) {
            result.add(fallback.get(idx));
            idx++;
        }
        return result;
    }

    private CompatibilityResponse buildFallbackResponse(CompatibilityRequest request,
                                                        ZodiacCalculator.ZodiacTriplet triA,
                                                        ZodiacCalculator.ZodiacTriplet triB,
                                                        String raw,
                                                        int score,
                                                        String relType,
                                                        String reportType) {
        boolean isPremium = "claude".equalsIgnoreCase(request.getModel());
        return CompatibilityResponse.builder()
                .score(score)
                .relationshipType(relType)
                .tagline(defaultTagline(request, reportType))
                .reportType(reportType)
                .chapters(fallbackChapters(request, triA, triB, isPremium, reportType))
                .essence(fallbackEssence(request, triA, triB, isPremium, reportType))
                .reportUid(null)
                .zodiacA(toZodiacInfo(triA))
                .zodiacB(isSingleReport(reportType) ? null : toZodiacInfo(triB))
                .build();
    }

    private List<CompatibilityResponse.Chapter> fallbackChapters(CompatibilityRequest request,
                                                                 ZodiacCalculator.ZodiacTriplet triA,
                                                                 ZodiacCalculator.ZodiacTriplet triB,
                                                                 boolean isPremium,
                                                                 String reportType) {
        if (isSingleReport(reportType)) {
            return fallbackSingleChapters(request, triA, isPremium, reportType);
        }
        String nameA = request.getPersonA().getName();
        String nameB = request.getPersonB().getName();

        List<CompatibilityResponse.Chapter> chapters = new ArrayList<>();
        chapters.add(chapter(
            scoringService.generateChapterTitle(0, triA.sun(), triB.sun(), isPremium), "✨",
            nameA + "在关系里更重视稳定和投入，也更容易把很多担心藏进细节里。"
                    + nameB + "这边更在意被看见的感觉，也更需要明确的回应来建立安全感。你们不是没有默契，而是默契常常被表达方式拖慢。"));
        chapters.add(chapter(
            scoringService.generateChapterTitle(1, triA.sun(), triB.sun(), isPremium), "💞",
            nameA + "容易被" + nameB + "身上更鲜明、更直接的情绪吸引，" + nameB + "也会被" + nameA + "带来的稳定感安抚。好的时候，这段关系很容易形成一个人点火、一个人续航的组合。问题在于，一旦其中一方退回自己的舒适区，另一方就会误读成冷淡或不在乎。"));
        chapters.add(chapter(
            scoringService.generateChapterTitle(2, triA.sun(), triB.sun(), isPremium), "⚠️",
            "你们最大的摩擦往往不是爱得不够，而是节奏不一致。一个人希望马上回应，另一个人习惯先消化再表达；一个人想确认关系，另一个人先去处理现实细节。矛盾累积后，就会从具体事情升级成“你是不是根本不懂我”。这类关系最怕把情绪拖成沉默。"));
        chapters.add(chapter(
            scoringService.generateChapterTitle(3, triA.sun(), triB.sun(), isPremium), "🧭",
            "先约定一个固定的沟通动作，比方说遇到分歧时先说明情绪、再说诉求、最后给出具体请求。对" + nameA + "来说，少一点闷着做事、多一点把想法说出来；对" + nameB + "来说，少一点试探式表达、多一点直接说明自己要什么。你们需要的不是更激烈，而是更清楚。"));
        chapters.add(chapter(
            scoringService.generateChapterTitle(4, triA.sun(), triB.sun(), isPremium), "🔮",
            "接下来三个月，这段关系适合处理现实安排、边界感和期待值。只要把容易误解的事情说清楚，关系会稳得更快；如果继续靠猜，前期的小别扭很容易放大。建议把重要话题放到情绪平稳的时候谈，不要在最上头的时候决定关系走向。"));
        chapters.add(chapter(
            scoringService.generateChapterTitle(5, triA.sun(), triB.sun(), isPremium), "🌙",
            nameA + "，你们之间不是没有缘分，而是这段缘分更考验耐心和表达。真正重要的，不是谁更会爱，而是谁愿意在误解出现时往前走一步。你把心事说出来，TA才有机会真正靠近你。\n\n—— 小登哥 ✨"));

        if (isPremium) {
            chapters.add(chapter(
                scoringService.generateChapterTitle(6, triA.sun(), triB.sun(), true), "🌟",
                "从长期相处的视角来看，" + nameA + "和" + nameB + "的相遇承载着明显的成长意义。你们之间容易出现一种'很熟悉'的感觉，但真正重要的不是这种熟悉本身，而是它会把彼此原本回避的问题带到台面上。\n\n这段关系最重要的功课可能不是表面上的靠近，而是通过彼此看见自己、修正自己。只要愿意面对旧问题、建立新边界，这段关系就有机会从反复消耗走向更稳的合作与陪伴。"));
            chapters.add(chapter(
                scoringService.generateChapterTitle(7, triA.sun(), triB.sun(), true), "📅",
                "从接下来一段时间看，这段关系会经历几个重要节点：有的阶段适合做出明确约定，有的阶段更适合回头梳理问题。对你们来说，最关键的不是抓某个所谓的完美时机，而是把重要话题放在双方都能冷静说清楚的时候。\n\n记住：节奏只是参考，不是判决。真正决定关系走向的，还是你们愿不愿意认真面对彼此。\n\n—— 小登哥 ✨"));
        }

        return chapters;
    }

    private List<String> fallbackEssence(CompatibilityRequest request,
                                         ZodiacCalculator.ZodiacTriplet triA,
                                         ZodiacCalculator.ZodiacTriplet triB,
                                         boolean isPremium,
                                         String reportType) {
        if (isSingleReport(reportType)) {
            return fallbackSingleEssence(request, isPremium, reportType);
        }
        String nameA = request.getPersonA().getName();
        String nameB = request.getPersonB().getName();
        List<String> essence = new ArrayList<>();
        essence.add("别把“我以为你懂”当成沟通。");
        essence.add(nameA + "先讲感受，" + nameB + "再讲需求，效率会高很多。");
        essence.add("稳定感不是沉默，是让对方知道你还在。");
        essence.add("情绪上来的时候先暂停，别急着判关系输赢。");
        essence.add("你们适合把模糊的问题说具体。");
        essence.add("真正拉开差距的，是愿不愿意认真回应彼此。");

        if (isPremium) {
            essence.add("【短期】每周安排一次'无手机约会'，专注陪伴对方。");
            essence.add("【短期】吵架后 24 小时内必须有一次非指责性沟通。");
            essence.add("【短期】学会用对方的'爱的语言'表达关心。");
            essence.add("【中期】每季度一起做一件新鲜事，保持关系的新鲜感。");
            essence.add("【中期】建立共同的财务或生活目标，增强关系的稳定性。");
            essence.add("【长期】定期回顾这份内容，看看哪些建议已经实现了。");
            essence.add("【长期】重要决定放在双方都平稳的时候推进。");
            essence.add("【长期】培养一个共同爱好，成为你们的'关系锚点'。");
        }

        return essence;
    }

    private List<CompatibilityResponse.Chapter> fallbackSingleChapters(CompatibilityRequest request,
                                                                       ZodiacCalculator.ZodiacTriplet triA,
                                                                       boolean isPremium,
                                                                       String reportType) {
        String name = request.getPersonA().getName();
        List<CompatibilityResponse.Chapter> chapters = new ArrayList<>();
        if (CompatibilityRequest.REPORT_TYPE_CAREER.equals(reportType)) {
            chapters.add(chapter("你的核心驱动力", "✨",
                    name + "在工作上有自己的启动节奏，也有比较明显的压力反应模式。你不是没有潜力，而是需要在对的节奏里发力，才能把优势变成稳定结果。"));
            chapters.add(chapter("适合你的工作角色", "🧭",
                    "你更容易在需要清晰判断、持续推进或资源整合的岗位里被看见。真正适合你的，不一定是最热闹的赛道，而是既能让你保持掌控感，又能持续积累信用和成果的位置。"));
            chapters.add(chapter("职场里的优势与盲区", "⚠️",
                    "你的优势在于一旦进入状态就很有连续性，但盲区也常常来自这里：太想一次做到位，就容易把决定拖到过晚；太想自己扛住，就容易错过协作窗口。与其逼自己全能，不如把判断、节奏和借力拆开来看。"));
            chapters.add(chapter("未来90天行动建议", "📈",
                    "未来三个月更适合把重心放在两件事上：一是把最关键的目标排到高能量时段，二是把每周的复盘固定下来。只要节奏稳定下来，你的推进感会比想象中更快。"));
            chapters.add(chapter("升级建议", "🔮",
                    "你最值得投入的方向，不是盲目加量，而是找到真正能放大你判断力和执行力的场域。选项目时看三件事：是否有成长空间、是否能形成可复用成果、是否能让你持续被看见。"));
            chapters.add(chapter("写给你的提醒", "🌙",
                    "你的职业状态不是一条直线，它更像是先校准、再发力、再放大的过程。稳住自己的节奏，你会比着急证明自己的时候更强。\n\n—— 小登哥 ✨"));
        } else {
            chapters.add(chapter("你的生活基调", "✨",
                    name + "对收入、掌控感和资源安排有自己稳定的一套判断方式。你当前更值得关注的，不是追逐短期刺激，而是如何把资源留住并逐步放大。"));
            chapters.add(chapter("资源最容易从哪里放大", "💎",
                    "你更适合通过稳定能力、长期信用或资源整合来放大结果。真正适合你的方式，不一定最刺激，但往往更可持续，也更容易累积成下一阶段的安全感。"));
            chapters.add(chapter("最需要提防的风险点", "⚠️",
                    "你需要特别留意两类习惯：一种是情绪上来时的即时决策，另一种是明明知道该收口，却因为拖延把小口子放成大问题。建立边界，不是压抑自己，而是让每一次投入都更清楚。"));
            chapters.add(chapter("接下来90天的生活节奏", "📊",
                    "接下来三个月适合先稳住基础安排，再看增量机会。把固定支出、可调整支出和潜在增长项拆开，你会更清楚哪些地方值得投入，哪些决定应该再观察一下。"));
            chapters.add(chapter("扩展机会", "🔮",
                    "如果你要做副业或额外尝试，优先考虑那些能复用你现有能力、口碑或资源的方向。比起一时冲动的新赛道，更值得你押注的是能持续滚大的熟练项。"));
            chapters.add(chapter("写给你的提醒", "🌙",
                    "你的优势不是靠追热点堆出来的，而是靠持续的判断力和节奏感慢慢拉开差距。先把底盘稳住，机会来的时候你会更敢接。\n\n—— 小登哥 ✨"));
        }

        if (isPremium) {
            chapters.add(chapter("高阶机会窗口", "📅",
                    CompatibilityRequest.REPORT_TYPE_CAREER.equals(reportType)
                            ? "扩展内容建议你把未来12个月拆成三个阶段：一段用来冲曝光，一段用来稳结果，一段用来做关键转向。不要每个月都追求同一种推进方式，节奏比蛮力更重要。"
                            : "扩展内容建议你把未来12个月拆成三个阶段：一段用来守现金流，一段用来试增量，一段用来放大有效渠道。不是每个机会都值得接，最重要的是筛选。"));
            chapters.add(chapter("写给你的悄悄话", "🌟",
                    CompatibilityRequest.REPORT_TYPE_CAREER.equals(reportType)
                            ? "你真正的职业优势，来自清楚自己该在什么地方出手、在什么地方留白。看见自己的节奏，比盯着别人的速度更有用。\n\n—— 小登哥 ✨"
                            : "你真正的优势，来自会判断、会守、也敢在对的时候放大。先把底层习惯养好，后面的安排会更从容。\n\n—— 小登哥 ✨"));
        }
        return chapters;
    }

    private List<String> fallbackSingleEssence(CompatibilityRequest request,
                                               boolean isPremium,
                                               String reportType) {
        List<String> essence = new ArrayList<>();
        if (CompatibilityRequest.REPORT_TYPE_CAREER.equals(reportType)) {
            essence.add("先定主目标，再分执行节奏。");
            essence.add("高能量时段做最难的事。");
            essence.add("复盘比临时加班更值钱。");
            essence.add("别把犹豫包装成谨慎。");
            essence.add("会借力，推进才会更快。");
            essence.add("稳定输出比偶尔爆发更重要。");
        } else {
            essence.add("先稳现金流，再谈放大。");
            essence.add("花钱前先分必要和冲动。");
            essence.add("副业优先选熟练项。");
            essence.add("预算比情绪更可靠。");
            essence.add("漏财常常从小口子开始。");
            essence.add("把可复用资源盘清楚。");
        }

        if (isPremium) {
            essence.add(CompatibilityRequest.REPORT_TYPE_CAREER.equals(reportType)
                    ? "关键沟通尽量放在顺势窗口。"
                    : "大额决策先隔一天再确认。");
            essence.add(CompatibilityRequest.REPORT_TYPE_CAREER.equals(reportType)
                    ? "给自己留一段纯思考时间。"
                    : "账户分层会让安全感更强。");
        }

        return essence;
    }

    private CompatibilityResponse.Chapter chapter(String title, String emoji, String content) {
        return CompatibilityResponse.Chapter.builder()
                .title(title)
                .emoji(emoji)
                .content(content)
                .build();
    }

    private String textOrDefault(JsonNode node, String fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String preview(String raw) {
        if (raw == null || raw.isBlank()) {
            return "<empty>";
        }
        return raw.substring(0, Math.min(raw.length(), 500));
    }

    private CompatibilityResponse.ZodiacInfo toZodiacInfo(ZodiacCalculator.ZodiacTriplet t) {
        return CompatibilityResponse.ZodiacInfo.builder()
                .sun(t.sun())
                .moon(t.moon())
                .rising(t.rising())
                .build();
    }

    private String generateReportUid(String userName) {
        String initial = extractReportInitial(userName);
        LocalDateTime now = LocalDateTime.now();
        String timestamp = now.format(REPORT_UID_TIMESTAMP);
        String prefix = initial + timestamp;
        long existingCount = repository.countByReportUidStartingWith(initial + now.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE));
        int sequence = (int) existingCount + 1;
        int attempts = 0;
        String uid;
        do {
            uid = prefix + padSequence(sequence + attempts);
            attempts++;
        } while (repository.findByReportUid(uid).isPresent() && attempts < 1000);
        return uid;
    }

    private String extractReportInitial(String userName) {
        String normalized = buildReportNamePinyin(userName);
        if (normalized.isBlank() || "Report".equals(normalized)) {
            return "X";
        }
        char first = normalized.charAt(0);
        if (first >= 'a' && first <= 'z') {
            return String.valueOf(Character.toUpperCase(first));
        }
        if ((first >= 'A' && first <= 'Z') || (first >= '0' && first <= '9')) {
            return String.valueOf(first);
        }
        return "X";
    }

    private String padSequence(int sequence) {
        int normalized = Math.max(sequence, 1);
        return String.format("%03d", normalized);
    }

    private String buildReportNamePinyin(String userName) {
        String normalized = userName == null ? "" : userName.trim();
        if (normalized.isBlank()) {
            return "Report";
        }
        StringBuilder builder = new StringBuilder();
        for (char ch : normalized.toCharArray()) {
            if (Character.isWhitespace(ch) || ch == '-' || ch == '_') {
                continue;
            }
            if (isAsciiAlphaNumeric(ch)) {
                builder.append(normalizeAsciiChar(ch));
                continue;
            }
            if (isChineseCharacter(ch)) {
                builder.append(toCapitalizedPinyin(ch));
            }
        }
        String value = builder.toString().replaceAll("[^A-Za-z0-9]", "");
        if (value.isBlank()) {
            return "Report";
        }
        return value.length() > 32 ? value.substring(0, 32) : value;
    }

    private boolean isAsciiAlphaNumeric(char ch) {
        return (ch >= '0' && ch <= '9')
                || (ch >= 'A' && ch <= 'Z')
                || (ch >= 'a' && ch <= 'z');
    }

    private String normalizeAsciiChar(char ch) {
        if (ch >= 'A' && ch <= 'Z') {
            return String.valueOf(ch);
        }
        if (ch >= 'a' && ch <= 'z') {
            return String.valueOf(Character.toUpperCase(ch));
        }
        return String.valueOf(ch);
    }

    private boolean isChineseCharacter(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }

    private String toCapitalizedPinyin(char ch) {
        return "X";
    }


    private SoulmateReport toEntity(CompatibilityRequest req, CompatibilityResponse resp,
                                    ZodiacCalculator.ZodiacTriplet triA,
                                    ZodiacCalculator.ZodiacTriplet triB,
                                    String rawJson, String ip, String userAgent,
                                    String reportType) {
        boolean singleReport = isSingleReport(reportType);
        SoulmateReport e = new SoulmateReport();
        e.setReportUid(truncate(resp.getReportUid(), 50));

        var a = req.getPersonA();
        e.setUserAName(truncate(a.getName(), 50));
        e.setUserAGender(truncate(a.getGender(), 10));
        e.setUserABirthDate(truncate(a.getBirthDate(), 20));
        e.setUserABirthTime(truncate(a.getBirthTime(), 10));
        e.setUserABirthPlace(truncate(a.getBirthPlace(), 50));
        e.setUserABirthLatitude(a.getBirthLatitude());
        e.setUserABirthLongitude(a.getBirthLongitude());
        e.setUserABirthTimezone(truncate(a.getBirthTimezone(), 50));
        e.setZodiacA(truncate(triA.sun(), 20));
        e.setMoonA(truncate(triA.moon(), 20));
        e.setRisingA(truncate(triA.rising(), 20));

        if (!singleReport && req.getPersonB() != null) {
            var b = req.getPersonB();
            e.setUserBName(truncate(b.getName(), 50));
            e.setUserBGender(truncate(b.getGender(), 10));
            e.setUserBBirthDate(truncate(b.getBirthDate(), 20));
            e.setUserBBirthTime(truncate(b.getBirthTime(), 10));
            e.setUserBBirthPlace(truncate(b.getBirthPlace(), 50));
            e.setUserBBirthLatitude(b.getBirthLatitude());
            e.setUserBBirthLongitude(b.getBirthLongitude());
            e.setUserBBirthTimezone(truncate(b.getBirthTimezone(), 50));
            e.setZodiacB(truncate(triB.sun(), 20));
            e.setMoonB(truncate(triB.moon(), 20));
            e.setRisingB(truncate(triB.rising(), 20));
        }

        e.setScore(resp.getScore());
        e.setModelCode(truncate(normalizeModelCode(req.getModel()), 20));
        e.setReportType(truncate(reportType, 20));
        e.setRelationshipType(truncate(resp.getRelationshipType(), 50));
        e.setTagline(truncate(resp.getTagline(), 500));
        e.setFullReport(rawJson);

        e.setIpAddress(truncate(ip, 50));
        e.setUserAgent(truncate(userAgent, 500));
        e.setSharedCount(0);
        return e;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String normalizeReportType(String reportType) {
        if (CompatibilityRequest.REPORT_TYPE_CAREER.equalsIgnoreCase(reportType)) {
            return CompatibilityRequest.REPORT_TYPE_CAREER;
        }
        if (CompatibilityRequest.REPORT_TYPE_WEALTH.equalsIgnoreCase(reportType)) {
            return CompatibilityRequest.REPORT_TYPE_WEALTH;
        }
        return CompatibilityRequest.REPORT_TYPE_LOVE;
    }

    private boolean isSingleReport(String reportType) {
        return CompatibilityRequest.REPORT_TYPE_CAREER.equals(reportType)
                || CompatibilityRequest.REPORT_TYPE_WEALTH.equals(reportType);
    }

    /**
     * 计算三元组（太阳/月亮/上升）
     * 优先使用 Swiss Ephemeris 精确计算（需要经纬度），否则使用简化算法
     */
    private ZodiacCalculator.ZodiacTriplet computeZodiacTriplet(CompatibilityRequest.Person person) {
        if (person == null) {
            return new ZodiacCalculator.ZodiacTriplet("未知", "未知", "未知");
        }
        
        // 如果有经纬度信息，使用 Swiss Ephemeris 精确计算
        if (hasValidCoordinates(person)) {
            try {
                double lat = person.getBirthLatitude();
                double lon = person.getBirthLongitude();
                String timezone = person.getBirthTimezone();
                if (timezone == null || timezone.isBlank()) {
                    timezone = "Asia/Shanghai";
                }
                
                String sun = swissEphemerisCalculator.computeSun(person.getBirthDate(), person.getBirthTime(), timezone);
                String moon = swissEphemerisCalculator.computeMoon(person.getBirthDate(), person.getBirthTime(), timezone);
                String rising = swissEphemerisCalculator.computeRising(
                        person.getBirthDate(), person.getBirthTime(), timezone, lat, lon);
                
                log.info("Swiss Ephemeris 精确计算: {} -> 太阳:{}, 月亮:{}, 上升:{}", 
                        person.getName(), sun, moon, rising);
                return new ZodiacCalculator.ZodiacTriplet(sun, moon, rising);
            } catch (Exception e) {
                log.warn("Swiss Ephemeris 计算失败，使用回退算法: {}", e.getMessage());
            }
        }
        
        // 使用简化算法
        return ZodiacCalculator.computeAll(person.getBirthDate(), person.getBirthTime());
    }
    
    private boolean hasValidCoordinates(CompatibilityRequest.Person person) {
        if (person == null) return false;
        Double lat = person.getBirthLatitude();
        Double lon = person.getBirthLongitude();
        return lat != null && lon != null 
                && lat >= -90 && lat <= 90 
                && lon >= -180 && lon <= 180;
    }

    private String reportTypeName(String reportType) {
        return switch (normalizeReportType(reportType)) {
            case CompatibilityRequest.REPORT_TYPE_CAREER -> "职业状态内容";
            case CompatibilityRequest.REPORT_TYPE_WEALTH -> "生活节奏内容";
            default -> "双人关系内容";
        };
    }

    private String defaultTagline(CompatibilityRequest request, String reportType) {
        if (CompatibilityRequest.REPORT_TYPE_CAREER.equals(reportType)) {
            return request.getPersonA().getName() + "需要把节奏调顺，机会就会比想象中更快靠近。";
        }
        if (CompatibilityRequest.REPORT_TYPE_WEALTH.equals(reportType)) {
            return request.getPersonA().getName() + "当前更适合先稳住日常安排，再逐步放大真正有效的机会。";
        }
        String otherName = request.getPersonB() != null ? request.getPersonB().getName() : "TA";
        return request.getPersonA().getName() + "和" + otherName + "之间有吸引，也需要耐心磨合。";
    }

    private String normalizeModelCode(String modelCode) {
        return CLAUDE_MODEL.equalsIgnoreCase(modelCode) ? CLAUDE_MODEL : DEFAULT_MODEL;
    }

    private String sanitizeForPrompt(String input) {
        if (input == null) return "";
        String cleaned = PROMPT_INJECTION_CLEAN.matcher(input).replaceAll("");
        if (cleaned.length() > 100) cleaned = cleaned.substring(0, 100);
        return cleaned.trim();
    }
}
