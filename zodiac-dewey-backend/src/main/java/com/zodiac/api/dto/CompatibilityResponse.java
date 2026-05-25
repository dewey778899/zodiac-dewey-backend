package com.zodiac.api.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CompatibilityResponse {
    private Integer score;
    private String relationshipType;
    private String tagline;
    private String reportType;
    private List<Chapter> chapters;
    private List<String> essence;
    private String reportUid;
    private ZodiacInfo zodiacA;
    private ZodiacInfo zodiacB;

    @Data
    @Builder
    public static class Chapter {
        private String title;
        private String emoji;
        private String content;
    }

    @Data
    @Builder
    public static class ZodiacInfo {
        private String sun;
        private String moon;
        private String rising;
    }

    @Data
    @Builder
    public static class PersonInfo {
        private String name;
        private String gender;
        private String birthDate;
        private String birthTime;
        private String birthPlace;
    }

    /** 分享链接查询时,附带表单信息方便前端渲染 */
    private PersonInfo personA;
    private PersonInfo personB;
}
