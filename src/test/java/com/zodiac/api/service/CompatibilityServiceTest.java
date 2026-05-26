package com.zodiac.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zodiac.api.dto.CompatibilityRequest;
import com.zodiac.api.dto.CompatibilityResponse;
import com.zodiac.api.repository.SoulmateReportRepository;
import com.zodiac.api.util.SwissEphemerisCalculator;
import com.zodiac.api.util.ZodiacCalculator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CompatibilityServiceTest {

    private final CompatibilityService service =
            new CompatibilityService(
                    mock(AiChatService.class),
                    mock(SoulmateReportRepository.class),
                    new ZodiacScoringService(),
                    new ObjectMapper(),
                    new SwissEphemerisCalculator()
            );

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
    void parseResponse_fallsBackWhenJsonIsUnusable() {
        CompatibilityRequest request = sampleRequest();
        ZodiacCalculator.ZodiacTriplet triA = new ZodiacCalculator.ZodiacTriplet("金牛座", "射手座", "处女座");
        ZodiacCalculator.ZodiacTriplet triB = new ZodiacCalculator.ZodiacTriplet("狮子座", "双鱼座", "处女座");

        CompatibilityResponse response = service.parseResponse("这不是 JSON", request, triA, triB);

        assertNotNull(response);
        assertTrue(response.getScore() >= 60 && response.getScore() <= 95);
        assertEquals(6, response.getChapters().size());
        assertEquals(6, response.getEssence().size());
        assertTrue(response.getTagline().contains("ZhangSan"));
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
