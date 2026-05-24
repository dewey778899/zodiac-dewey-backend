package com.zodiac.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class CompatibilityRequest {

    public static final String REPORT_TYPE_LOVE = "love";
    public static final String REPORT_TYPE_CAREER = "career";
    public static final String REPORT_TYPE_WEALTH = "wealth";

    @Valid
    @NotNull(message = "请提供你的信息")
    private Person personA;

    @Valid
    private Person personB;

    /** 选择的模型: "deepseek" 或 "claude"(深度解析 / Opus 4.7), 默认 "deepseek" */
    @Pattern(regexp = "^(deepseek|claude)$", message = "不支持的模型类型")
    private String model;

    /** 报告主题: love / career / wealth, 默认 love */
    private String reportType;

    @Data
    public static class Person {
        @NotBlank(message = "名字不能为空")
        private String name;

        @NotBlank(message = "性别不能为空")
        private String gender;

        @NotBlank(message = "生日不能为空")
        @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "生日格式必须为 YYYY-MM-DD")
        private String birthDate;

        private String birthTime;
        private String birthPlace;
    }
}
