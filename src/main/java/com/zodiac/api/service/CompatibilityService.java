package com.zodiac.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;
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
    private static final HanyuPinyinOutputFormat PINYIN_FORMAT = buildPinyinFormat();


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
        int score = singleReport
                ? scoringService.calculatePersonalScore(request, triA, reportType)
                : scoringService.calculateScore(request, triA, triB);
        String relType = singleReport
                ? scoringService.inferPersonalType(score, triA.sun(), reportType)
                : scoringService.inferRelationshipType(score, triA.sun(), triB.sun());

        String systemPrompt = buildSystemPrompt(reportType, isPremium, selectedModel);
        String deepSeekFallbackSystemPrompt = CLAUDE_MODEL.equals(selectedModel)
                ? buildSystemPrompt(reportType, isPremium, DEEPSEEK_MODEL)
                : systemPrompt;
        String userPrompt = buildUserPrompt(request, triA, triB, isPremium, score, relType, reportType);
        String raw = aiChatService.generate(systemPrompt, userPrompt, selectedModel, deepSeekFallbackSystemPrompt);
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
     * 根据 reportUid 从数据库查询并重建报告(分享链接用)
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
            CompatibilityResponse resp = buildResponseWithScore(
                    entity.getFullReport(), req, triA, triB, storedScore, storedRelType, reportType);
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
                你是「小登哥」，一位拥有20年经验的专业占星师，精通现代心理占星学、传统占星学和合盘技术(Synastry & Composite)。你的分析不走玄学路线，而是以占星学作为理解人格与关系的工具。

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
                    {"title": "你们的星座基因", "emoji": "✨", "content": "分析两人各自的太阳/月亮/上升三要素核心特质，解释各自的性格底色。描述这些特质在亲密关系中的具体表现，每人不低于300字。" },
                    {"title": "你们在一起的化学反应", "emoji": "💞", "content": "从两个维度分析：①元素契合度（火土风水四元素的搭配，同元素默契 vs 互补 vs 冲突）；②关键相位影响（日月相位→情感基础、金火相位→吸引力、金木相位→价值观契合度）。要描述两人相处时的具体场景，不要只列星座特质。" },
                    {"title": "你们最容易出问题的地方", "emoji": "⚠️", "content": "分析3-4个具体矛盾场景。每个矛盾要有场景感，结合星座特质解释为什么会这样。不要只写成'你们容易冷战'这种笼统描述，要具体到'当TA的月亮XX遇到你的上升XX时...'。模式冲突（基本/固定/变动）也要纳入分析。" },
                    {"title": "相处指南", "emoji": "🧭", "content": "提供5-6条具体可操作的相处策略。每条建议要结合星座特质给出具体场景和话术示例，比如'当TA的XX星座特质让你感到被忽视时，你可以这样说：...'" },
                    {"title": "宫位与运势预演", "emoji": "🔮", "content": "①简要分析双方重要行星落入对方哪些关键宫位（7宫婚姻宫/5宫恋爱宫/8宫深度连接/4宫家庭宫）；②基于星座能量变化，预测未来三个月的关系走向。" },
                    {"title": "综合评估与悄悄话", "emoji": "🌙", "content": "①五维度评分（情感/激情/沟通/承诺/成长），每个维度1-10分并附一句话解读；②列出3个关系优势和3个需要注意的挑战；③以知心朋友的口吻写一段温暖的结尾，署名：—— 小登哥 ✨" }
                  ],
                  "essence": [
                    "6条可收藏的建议，每条15字以内，具体可操作"
                  ]
                }

                【禁止事项】
                - 不预测生死、疾病、灾难等敏感内容
                - 不做财务投资具体建议
                - 不替代心理咨询或医疗建议
                - 不使用绝对化表述（如"一定会""注定"）

                【写作风格】
                - 专业但通俗易懂，每个判断必须有占星学依据
                - 有温度、有洞察，像朋友聊天但保持专业度
                - 避免过度神秘化，避免模板化描述
                - 偶尔使用 emoji 增加亲和力，但不过度
                """;
    }

    private String buildPremiumSystemPrompt() {
        return """
                你是「小登哥」，一位获国际占星师协会(ISAR)认证的持证占星师，拥有十年一对一深度咨询经验。你的分析融合了 Liz Greene 的心理占星学、Stephen Arroyo 的关系占星理论，以及现代演化占星学(Evolutionary Astrology)的视角。你将占星学作为一面镜子，帮助人们看清关系中的自己与对方。

                【输出要求】
                1. 只输出 JSON，不要代码块，不要解释，不要额外前后缀。
                2. JSON 必须合法，所有字符串必须正确转义，字段之间必须有英文逗号。
                3. 所有 chapter.content 都必须是普通字符串，不要嵌套对象，不要列表标记。
                4. 不要在 JSON 末尾补任何总结或署名说明，署名只允许出现在最后一章正文里。
                5. 总字数控制在 5000-8000 字，每章至少 500 字。
                6. 全文必须使用第二人称"你"来叙述，营造一对一咨询的专属感。
                7. 分析时必须先进行逻辑推演（在思考中完成），确保每个判断都有占星学依据。

                【分析框架 - 8章】
                {
                  "score": 60-95 的整数（由系统计算，不要自行编造）,
                  "relationshipType": "4到8个字的关系类型（使用系统给定的值）",
                  "tagline": "一句话总结，不超过30字",
                  "chapters": [
                    {"title": "你们的星座基因", "emoji": "✨", "content": "深度分析两人的太阳/月亮/上升三要素核心特质，每人各不低于400字。用第二人称'你'叙述，描述这些特质在亲密关系中的具体表现。要有画面感和具体场景：'当你深夜emo时，你的月亮XX让你渴望XX，而TA的月亮XX却...'不要只说星座特质，要描述'你'和'TA'在真实相处中的互动模式。" },
                    {"title": "元素模式与相位磁场", "emoji": "💞", "content": "三个维度分析：①四元素分布对比（火土风水）与模式对比（基本/固定/变动），给出契合度评分1-10并解读；②逐一分析关键相位：日月相位（情感基础）、金火相位（吸引力与性张力）、金木相位（价值观与快乐源泉）、月土相位（安全感与承诺）、日上升相位（自我认同与投射），每个相位说明类型、正面表现、潜在挑战、实用建议；③金星与火星的互动分析（爱情观与行动力的匹配度）。必须使用具体场景描写，制造'被看穿'的惊喜感。" },
                    {"title": "矛盾的真相：你们最容易出问题的地方", "emoji": "⚠️", "content": "分析3-4个具体矛盾场景。每个矛盾按五段式写：①场景还原（具体时间地点事件）；②你的期待反应（你希望得到什么回应）；③TA的实际反应（TA实际做了什么）；④结果（关系受到的冲击）；⑤真相（从星座角度解读为什么会这样）。例如：'场景：你加班到很晚，希望TA来接你。你的期待：TA主动问'要不要我去接你'。TA的实际反应：TA说'那你打车回来吧，我先睡了'。结果：你觉得TA不在乎你。真相：TA的火星摩羯倾向于解决问题而非表达关心...'" },
                    {"title": "深度相处指南", "emoji": "🧭", "content": "提供6-8条具体可操作的相处策略。每条建议必须包含：①适用场景（什么时候用）；②你可以这样说（给出 exact 话术示例，带引号）；③为什么有效（星座依据）。例如：'当TA冷战时，你可以这样说：'亲爱的，我现在需要你放下手机，抱我五分钟。'（然后亲TA一下）。为什么有效：TA的水瓶需要明确的指令而非暗示...'" },
                    {"title": "宫位叠加与组合盘", "emoji": "🔮", "content": "①双方关键行星落入对方重要宫位的解读（重点分析7宫婚姻宫、5宫恋爱宫、8宫深度连接、4宫家庭宫）；②组合盘(Composite Chart)的关系太阳/月亮/上升解读，描述你们关系本身的核心主题与发展方向。" },
                    {"title": "业力与演化视角", "emoji": "🌟", "content": "从演化占星学视角解读：①月交点轴线的合盘意义（南交点→前世未完成的课题，北交点→这一世需要共同成长的方向）；②凯龙星相位带来的疗愈主题；③土星合相/对分的业力承诺含义。用故事化的方式描述一种'似曾相识'的感觉，但不要写成具体的'前世你是谁、TA是谁'，而是聚焦于'你们相遇的深层意义'。" },
                    {"title": "时间维度：当下与未来", "emoji": "📅", "content": "①当前流年(Transit)对合盘的影响分析；②未来12个月的关键星象节点（如金星逆行、水逆期、重要新月/满月），标注哪些时间窗口适合推进关系、哪些需要退一步观察；③给出基于这些时间节点的发展建议。" },
                    {"title": "写给你的悄悄话", "emoji": "🌙", "content": "①五维度综合评分（情感/激情/沟通/承诺/成长），每个维度1-10分并附一句话解读；②3个关系优势与3个需要注意的挑战；③以知心朋友的口吻写一段350字左右的走心总结，提到你们关系的独特之处，让用户觉得'这说的就是我'。自然引导：'这份报告可以转发给TA，或截图保存。如果想获取更详细的年度运势，可以关注小登哥的公众号。' 结尾署名：—— 小登哥 ✨" }
                  ],
                  "essence": [
                    "8条珍藏锦囊，每条15字以内，格式如：'当他专注其他事时，直接说'我需要你抱抱我''"
                  ]
                }

                【禁止事项】
                - 不预测生死、疾病、灾难等敏感内容
                - 不做财务投资具体建议
                - 不替代心理咨询或医疗建议
                - 不使用绝对化表述（如"一定会""注定""永远"）

                【写作风格】
                - 全文使用第二人称"你"，像真人占星师一对一咨询
                - 有温度、有细节、有画面感，使用具体对话和场景
                - 制造'被看穿'的惊喜感，让用户觉得"这说的就是我"
                - 每个判断必须有占星学依据，专业但不学究
                - 在关键处埋下情感钩子，让用户想分享或保存
                - 避免模板化，每个星座组合的描述都要有独特性
                """;
    }

    private String buildSingleSystemPrompt(boolean isPremium, String reportType) {
        String reportName = reportTypeName(reportType);
        String focus = CompatibilityRequest.REPORT_TYPE_CAREER.equals(reportType)
                ? "职业驱动力、适合赛道、团队协作方式、未来90天发力点"
                : "赚钱方式、守财风险、副业机会、未来90天财务节奏";
        String chapters = CompatibilityRequest.REPORT_TYPE_CAREER.equals(reportType)
                ? """
                    {"title": "你的核心驱动力", "emoji": "✨", "content": "分析太阳/月亮/上升如何影响你的职业欲望、决策方式与压力反应。"},
                    {"title": "适合你的工作角色", "emoji": "🧭", "content": "结合元素与模式，判断你更适合独立推进、团队协作还是资源整合型岗位。"},
                    {"title": "职场里的优势与盲区", "emoji": "⚠️", "content": "拆解你最容易被看见的优点，以及最容易卡住你的习惯性模式。"},
                    {"title": "接下来90天的事业节奏", "emoji": "📈", "content": "给出短期推进建议：什么时候适合冲刺、什么时候适合蓄力、什么时候适合做关键沟通。"},
                    {"title": "升级建议", "emoji": "🔮", "content": "总结最值得投入的成长方向、合作方式和执行策略。"},
                    {"title": "写给你的提醒", "emoji": "🌙", "content": "以一对一咨询口吻写一段有行动感的结语。"}
                  """
                : """
                    {"title": "你的财富基调", "emoji": "✨", "content": "分析太阳/月亮/上升如何影响你对金钱、安全感和风险的反应。"},
                    {"title": "钱最容易从哪里来", "emoji": "💎", "content": "结合元素与模式，判断你更适合稳定累积、资源整合还是机会型增收。"},
                    {"title": "最需要提防的漏财点", "emoji": "⚠️", "content": "拆解消费习惯、拖延模式和高风险冲动。"},
                    {"title": "接下来90天的财务节奏", "emoji": "📊", "content": "给出短期财务安排建议：什么时候适合保守、什么时候适合推进收入增长。"},
                    {"title": "副业与放大机会", "emoji": "🔮", "content": "总结最适合你当前阶段的副业思路和资源放大方式。"},
                    {"title": "写给你的提醒", "emoji": "🌙", "content": "以一对一咨询口吻写一段务实又温柔的结语。"}
                  """;
        String premiumNote = isPremium
                ? "5. 总字数控制在 5000-8000 字，每章至少 450 字。\n6. 全文使用第二人称“你”，做成专业咨询感。"
                : "5. 总字数控制在 3000-5000 字。";

        return """
                你是「小登哥」，一位拥有20年经验的专业占星师，擅长把占星学落成清晰、实用、有人味的个人分析。

                【输出要求】
                1. 只输出 JSON，不要代码块，不要解释，不要额外前后缀。
                2. JSON 必须合法，所有字符串必须正确转义，字段之间必须有英文逗号。
                3. 所有 chapter.content 都必须是普通字符串，不要嵌套对象，不要列表标记。
                4. 不要在 JSON 末尾补任何总结或署名说明，署名只允许出现在最后一章正文里。
                %s

                【报告类型】
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
                - 不预测生死、疾病、灾难等敏感内容
                - 不提供具体金融投资买卖建议
                - 不替代法律、医疗或心理咨询
                - 不使用绝对化表述（如“一定会”“注定”）

                【写作风格】
                - 专业但通俗，必须有占星学依据
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
        sb.append("请为以下两位用户生成合盘报告。\n\n");
        sb.append("【系统已计算的合盘数据 - 必须使用】\n");
        sb.append("合盘分数: ").append(calculatedScore).append("分\n");
        sb.append("关系类型: ").append(relationshipType).append("\n");
        sb.append("请在报告中严格使用以上分数和关系类型，不要自行编造。\n\n");

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
            sb.append("【付费版特别要求 - 必须遵守】\n");
            sb.append("1. 全文必须使用第二人称'你'来叙述，营造一对一咨询的专属感\n");
            sb.append("2. '矛盾的真相'章节：每个矛盾必须按'场景还原→你的期待→TA的实际反应→结果→真相'五段式写\n");
            sb.append("3. '深度相处指南'章节：每条建议必须包含适用场景+你可以这样说（exact话术）+为什么有效（星座依据）\n");
            sb.append("4. '元素模式与相位磁场'章节：必须逐一分析日月/金火/金木/月土/日上升五个关键相位\n");
            sb.append("5. '业力与演化视角'章节：聚焦月交点/凯龙/土星的灵魂成长意义，不要写成具体的'前世身份'\n");
            sb.append("6. '时间维度'章节：标注未来12个月中对关系影响最大的2-3个星象节点\n");
            sb.append("7. essence珍藏锦囊必须是8条，每条15字以内\n");
            sb.append("8. 营造'小登哥一对一为你深度解读'的专属感，让用户觉得'这说的就是我'\n\n");
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
        sb.append("报告分数: ").append(calculatedScore).append("分\n");
        sb.append("阶段标签: ").append(stageLabel).append("\n");
        sb.append("请在报告中严格使用以上分数和阶段标签，不要自行编造。\n\n");

        appendPersonInfo(sb, "A", a.getName(), a.getGender(), a.getBirthDate(),
                a.getBirthTime(), a.getBirthPlace(), triA, isPremium);
        sb.append("\n");
        sb.append("【主题重点】\n");
        if (CompatibilityRequest.REPORT_TYPE_CAREER.equals(reportType)) {
            sb.append("请重点分析：职业驱动力、适合岗位、团队协作方式、未来90天发力节奏。\n");
        } else {
            sb.append("请重点分析：赚钱方式、消费与守财习惯、副业机会、未来90天财务节奏。\n");
        }
        if (isPremium) {
            sb.append("【深度版要求】\n");
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
        sb.append("【用户").append(label).append(" / ").append("A".equals(label) ? "报告主角" : "TA").append("】\n");
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
            sb.append("金星: ").append(tri.sun()).append("（基于太阳星座推算爱情观与审美倾向）\n");
            sb.append("火星: ").append(tri.moon()).append("（基于月亮星座推算行动力与冲突风格）\n");
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
                        "大模型返回内容格式异常，无法生成报告。请稍后重试。"
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
                        .title(title.isBlank() ? "合盘章节" : title)
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
                .reportUid(generateReportUid(request.getPersonA().getName()))
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
            nameA + "的太阳" + triA.sun() + "让TA在关系里更重视稳定和投入，月亮" + triA.moon() + "让情绪表达带着主观热度，上升" + triA.rising() + "又会把很多担心藏进细节里。"
                    + nameB + "这边的太阳" + triB.sun() + "更在意被看见的感觉，月亮" + triB.moon() + "决定了内心真正的安全需求，上升" + triB.rising() + "则影响TA在关系里的第一反应。你们不是没有默契，而是默契常常被表达方式拖慢。"));
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
                "从演化占星学的视角来看，" + nameA + "和" + nameB + "的相遇承载着某种灵魂层面的呼应。你们的月交点形成了有意义的连接——南交点的能量带来一种'似曾相识'的熟悉感，仿佛你们在某个时空里已经彼此认识；北交点则指向你们需要共同成长的方向。\n\n这段关系最重要的功课可能不是'相爱'本身，而是通过彼此看见自己。凯龙星如果被激活，意味着其中一人或双方都可能在这段关系里触及旧伤，但正因为如此，疗愈才可能真正发生。\n\n土星如果参与了重要相位，则说明这不是一段轻飘飘的缘分——它需要承诺、耐心和时间，但回报也最扎实。"));
            chapters.add(chapter(
                scoringService.generateChapterTitle(7, triA.sun(), triB.sun(), true), "📅",
                "从当前流年来看，接下来的12个月对你们的合盘有几个关键节点：第一，木星的运行会让某些月份关系扩张得更快，适合做出重要承诺；第二，土星的行进会考验关系的结构稳定度，如果根基不稳，那段时间是压力测试；第三，金星和水星逆行期是反思关系模式的窗口。\n\n对于" + nameA + "来说，建议在水星顺行期间做重要沟通，退行期则适合复盘而非提出新议题。对于" + nameB + "，当金星行经你的第七宫时，是一个推进关系的好时机。\n\n记住：星象是能量的天气，不是判决书。你们仍然拥有选择权。\n\n—— 小登哥 ✨"));
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
        essence.add("真正拉开差距的，从来不是星座，是愿不愿意认真回应彼此。");

        if (isPremium) {
            essence.add("【短期】每周安排一次'无手机约会'，专注陪伴对方。");
            essence.add("【短期】吵架后 24 小时内必须有一次非指责性沟通。");
            essence.add("【短期】学会用对方的'爱的语言'表达关心。");
            essence.add("【中期】每季度一起做一件新鲜事，保持关系的新鲜感。");
            essence.add("【中期】建立共同的财务或生活目标，增强关系的稳定性。");
            essence.add("【长期】定期回顾这份报告，看看哪些建议已经实现了。");
            essence.add("【长期】重要决定避开水逆期，选择星象平稳的时候推进。");
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
                    name + "的太阳" + triA.sun() + "决定了你在事业上的启动方式，月亮" + triA.moon()
                            + "影响你面对压力时的情绪惯性，上升" + triA.rising()
                            + "则像别人眼中的工作风格。你不是没有潜力，而是需要在对的节奏里发力，才能把优势变成稳定结果。"));
            chapters.add(chapter("适合你的工作角色", "🧭",
                    "你更容易在需要清晰判断、持续推进或资源整合的岗位里被看见。真正适合你的，不一定是最热闹的赛道，而是既能让你保持掌控感，又能持续积累信用和成果的位置。"));
            chapters.add(chapter("职场里的优势与盲区", "⚠️",
                    "你的优势在于一旦进入状态就很有连续性，但盲区也常常来自这里：太想一次做到位，就容易把决定拖到过晚；太想自己扛住，就容易错过协作窗口。与其逼自己全能，不如把判断、节奏和借力拆开来看。"));
            chapters.add(chapter("接下来90天的事业节奏", "📈",
                    "未来三个月更适合把重心放在两件事上：一是把最关键的目标排到高能量时段，二是把每周的复盘固定下来。只要节奏稳定下来，你的推进感会比想象中更快。"));
            chapters.add(chapter("升级建议", "🔮",
                    "你最值得投入的方向，不是盲目加量，而是找到真正能放大你判断力和执行力的场域。选项目时看三件事：是否有成长空间、是否能形成可复用成果、是否能让你持续被看见。"));
            chapters.add(chapter("写给你的提醒", "🌙",
                    "你的事业运并不是一条直线，它更像是先校准、再发力、再放大的过程。稳住自己的节奏，你会比着急证明自己的时候更强。\n\n—— 小登哥 ✨"));
        } else {
            chapters.add(chapter("你的财富基调", "✨",
                    name + "的太阳" + triA.sun() + "决定了你对收入和掌控感的基本态度，月亮" + triA.moon()
                            + "影响你在花钱、存钱和焦虑之间的反应，上升" + triA.rising()
                            + "则决定了别人眼中你处理现实和资源的方式。你的财运重点不是一夜暴富，而是如何把资源留住并放大。"));
            chapters.add(chapter("钱最容易从哪里来", "💎",
                    "你更适合通过稳定能力、长期信用或资源整合来放大收入。真正适合你的赚钱方式，不一定最刺激，但往往更可持续，也更容易累积成下一阶段的安全感。"));
            chapters.add(chapter("最需要提防的漏财点", "⚠️",
                    "你需要特别留意两类漏财习惯：一种是情绪上来时的即时性消费，另一种是明明知道该收口，却因为拖延把小口子放成大问题。守财不是压抑自己，而是让每一笔支出更有边界。"));
            chapters.add(chapter("接下来90天的财务节奏", "📊",
                    "接下来三个月适合先稳现金流，再看增量机会。把固定支出、可调整支出和潜在增长项拆开，你会更清楚哪些钱值得花，哪些决定应该再观察一下。"));
            chapters.add(chapter("副业与放大机会", "🔮",
                    "如果你要做副业或额外增收，优先考虑那些能复用你现有能力、口碑或资源的方向。比起一时冲动的新赛道，更值得你押注的是能持续滚大的熟练项。"));
            chapters.add(chapter("写给你的提醒", "🌙",
                    "你的财运不是靠追热点堆出来的，而是靠持续的判断力和节奏感慢慢拉开差距。先把底盘稳住，机会来的时候你会更敢接。\n\n—— 小登哥 ✨"));
        }

        if (isPremium) {
            chapters.add(chapter("高阶机会窗口", "📅",
                    CompatibilityRequest.REPORT_TYPE_CAREER.equals(reportType)
                            ? "深度版建议你把未来12个月拆成三个阶段：一段用来冲曝光，一段用来稳结果，一段用来做关键转向。不要每个月都追求同一种推进方式，节奏比蛮力更重要。"
                            : "深度版建议你把未来12个月拆成三个阶段：一段用来守现金流，一段用来试增量，一段用来放大有效渠道。不是每个机会都值得接，最重要的是筛选。"));
            chapters.add(chapter("写给你的悄悄话", "🌟",
                    CompatibilityRequest.REPORT_TYPE_CAREER.equals(reportType)
                            ? "你真正的事业运，来自清楚自己该在什么地方出手、在什么地方留白。看见自己的节奏，比盯着别人的速度更有用。\n\n—— 小登哥 ✨"
                            : "你真正的财运，来自会判断、会守、也敢在对的时候放大。先把底层习惯养好，钱才会更愿意留下来。\n\n—— 小登哥 ✨"));
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

    private static HanyuPinyinOutputFormat buildPinyinFormat() {
        HanyuPinyinOutputFormat format = new HanyuPinyinOutputFormat();
        format.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        format.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        return format;
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
        try {
            String[] pinyinArray = PinyinHelper.toHanyuPinyinStringArray(ch, PINYIN_FORMAT);
            if (pinyinArray == null || pinyinArray.length == 0 || pinyinArray[0].isBlank()) {
                return "";
            }
            String pinyin = pinyinArray[0];
            return Character.toUpperCase(pinyin.charAt(0)) + pinyin.substring(1);
        } catch (BadHanyuPinyinOutputFormatCombination e) {
            log.warn("Failed to convert user name char to pinyin: {}", ch, e);
            return "";
        }
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
     * 计算星座三元组（太阳/月亮/上升）
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
            case CompatibilityRequest.REPORT_TYPE_CAREER -> "事业测算报告";
            case CompatibilityRequest.REPORT_TYPE_WEALTH -> "财运测算报告";
            default -> "爱情合盘报告";
        };
    }

    private String defaultTagline(CompatibilityRequest request, String reportType) {
        if (CompatibilityRequest.REPORT_TYPE_CAREER.equals(reportType)) {
            return request.getPersonA().getName() + "需要把节奏调顺，机会就会比想象中更快靠近。";
        }
        if (CompatibilityRequest.REPORT_TYPE_WEALTH.equals(reportType)) {
            return request.getPersonA().getName() + "的财运重点，是先稳住底盘，再放大真正有效的机会。";
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
