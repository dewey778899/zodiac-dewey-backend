package com.zodiac.api.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AdminReportPageResponse {

    private long totalElements;
    private int totalPages;
    private int page;
    private int size;
    private List<ReportItem> items;

    @Data
    @Builder
    public static class ReportItem {
        private String reportUid;
        private String userAName;
        private String userBName;
        private String modelCode;
        private Integer score;
        private String relationshipType;
        private String createdAt;
        private String wechatId;
    }
}
