package com.zodiac.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zodiac.api.dto.CompatibilityRequest;
import com.zodiac.api.dto.CompatibilityResponse;
import com.zodiac.api.repository.SoulmateReportRepository;
import com.zodiac.api.util.SwissEphemerisCalculator;
import com.zodiac.api.util.ZodiacCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompatibilityServiceTest {

    private final SoulmateReportRepository repository = mock(SoulmateReportRepository.class);
    private final CompatibilityService service =
            new CompatibilityService(
                    mock(AiChatService.class),
                    repository,
                    new ZodiacScoringService(),
                    new ObjectMapper(),
                    new SwissEphemerisCalculator()
            );

    @BeforeEach
    void setUp() {
        when(repository.findByReportUid(anyString())).thenReturn(Optional.empty());
        when(repository.countByReportUidStartingWith(anyString())).thenReturn(0L);
    }

    @Test
    void parseResponse_recoversMissingCommaJson() {
        CompatibilityRequest request = sampleRequest();
        ZodiacCalculator.ZodiacTriplet triA = new ZodiacCalculator.ZodiacTriplet("金牛座", "射手座", "处女座");
        ZodiacCalculator.ZodiacTriplet triB = new ZodiacCalculator.ZodiacTriplet("狮子座", "双鱼座", "处女座");
        String raw = """
                {
                  "score": 78,
                  "relationshipType": "火土平衡型",
                  "tagline": "你给她安定，她给你光芒。"
                  "chapters": [
                    {"title": "你们的星座基因", "emoji": "✨", "content": "第一章"},
                    {"title": "你们在一起的化学反应", "emoji": "💞", "content": "第二章"}
                  ],
                  "essence": ["建议1", "建议2"]
                }
                """;

        CompatibilityResponse response = service.parseResponse(raw, request, triA, triB);

        assertEquals("火土平衡型", response.getRelationshipType());
        assertEquals(new ZodiacScoringService().calculateScore(request, triA, triB), response.getScore());
        assertTrue(response.getChapters().size() >= 6);
        assertTrue(response.getEssence().size() >= 6);
        assertEquals("第一章", response.getChapters().get(0).getContent());
    }

    @Test
    void parseResponse_throwsWhenJsonIsUnusable() {
        CompatibilityRequest request = sampleRequest();
        ZodiacCalculator.ZodiacTriplet triA = new ZodiacCalculator.ZodiacTriplet("金牛座", "射手座", "处女座");
        ZodiacCalculator.ZodiacTriplet triB = new ZodiacCalculator.ZodiacTriplet("狮子座", "双鱼座", "处女座");

        assertThrows(AiServiceException.class, () -> service.parseResponse("这不是 JSON", request, triA, triB));
    }

    @Test
    void generateReportUid_usesInitialTimestampAndThreeDigitSequence() throws Exception {
        when(repository.countByReportUidStartingWith(anyString())).thenReturn(0L);

        Method method = CompatibilityService.class.getDeclaredMethod("generateReportUid", String.class);
        method.setAccessible(true);
        String uid = (String) method.invoke(service, "Alice");

        assertTrue(uid.matches("^A\\d{14}001$"));
    }

    @Test
    void generateReportUid_usesChinesePinyinInitialAndDailyIncrement() throws Exception {
        when(repository.countByReportUidStartingWith(anyString())).thenReturn(6L);

        Method method = CompatibilityService.class.getDeclaredMethod("generateReportUid", String.class);
        method.setAccessible(true);
        String uid = (String) method.invoke(service, "王小美");

        assertTrue(uid.matches("^W\\d{14}007$"));
    }

    private CompatibilityRequest sampleRequest() {
        CompatibilityRequest.Person a = new CompatibilityRequest.Person();
        a.setName("ZhangSan");
        a.setGender("male");
        a.setBirthDate("1990-05-05");
        a.setBirthTime("14:30");
        a.setBirthPlace("北京");

        CompatibilityRequest.Person b = new CompatibilityRequest.Person();
        b.setName("LiSi");
        b.setGender("female");
        b.setBirthDate("1992-08-08");
        b.setBirthTime("09:15");
        b.setBirthPlace("上海");

        CompatibilityRequest request = new CompatibilityRequest();
        request.setPersonA(a);
        request.setPersonB(b);
        request.setModel("deepseek");
        return request;
    }
}
