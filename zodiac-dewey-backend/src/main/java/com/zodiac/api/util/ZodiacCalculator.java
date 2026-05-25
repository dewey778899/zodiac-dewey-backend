package com.zodiac.api.util;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 星座计算工具
 * - 太阳:基于生日精确计算（标准星座日期划分）
 * - 月亮:基于简化天文公式近似计算（27.3216天周期），精度约±1-2个星座
 * - 上升:基于简化算法（每2小时切换一个星座），未考虑出生地点纬度，精度有限
 * <p>
 * ✅ Swiss Ephemeris 已集成: {@link SwissEphemerisCalculator}
 *    生产环境建议使用 SwissEphemerisCalculator 进行精确计算:
 *    - 太阳/月亮精度: ±1角秒
 *    - 上升星座准确率: ~95%（需精确出生时间和经纬度）
 *    - 支持金星、火星等其他行星位置计算
 * <p>
 * 此类保留作为回退算法（fallback），当 Swiss Ephemeris 不可用时使用
 */
public class ZodiacCalculator {

    private static final String[] ZODIAC_NAMES = {
            "白羊座", "金牛座", "双子座", "巨蟹座", "狮子座", "处女座",
            "天秤座", "天蝎座", "射手座", "摩羯座", "水瓶座", "双鱼座"
    };

    public static final java.util.Map<String, String> ELEMENT = java.util.Map.ofEntries(
        java.util.Map.entry("白羊座", "火"), java.util.Map.entry("狮子座", "火"), java.util.Map.entry("射手座", "火"),
        java.util.Map.entry("金牛座", "土"), java.util.Map.entry("处女座", "土"), java.util.Map.entry("摩羯座", "土"),
        java.util.Map.entry("双子座", "风"), java.util.Map.entry("天秤座", "风"), java.util.Map.entry("水瓶座", "风"),
        java.util.Map.entry("巨蟹座", "水"), java.util.Map.entry("天蝎座", "水"), java.util.Map.entry("双鱼座", "水")
    );

    public static final java.util.Map<String, String> MODE = java.util.Map.ofEntries(
        java.util.Map.entry("白羊座", "基本"), java.util.Map.entry("巨蟹座", "基本"),
        java.util.Map.entry("天秤座", "基本"), java.util.Map.entry("摩羯座", "基本"),
        java.util.Map.entry("金牛座", "固定"), java.util.Map.entry("狮子座", "固定"),
        java.util.Map.entry("天蝎座", "固定"), java.util.Map.entry("水瓶座", "固定"),
        java.util.Map.entry("双子座", "变动"), java.util.Map.entry("处女座", "变动"),
        java.util.Map.entry("射手座", "变动"), java.util.Map.entry("双鱼座", "变动")
    );

    public static String computeSun(String birthDate) {
        if (birthDate == null || birthDate.isBlank()) return "未知";
        try {
            LocalDate d = LocalDate.parse(birthDate, DateTimeFormatter.ISO_LOCAL_DATE);
            int m = d.getMonthValue(), day = d.getDayOfMonth();
            if ((m == 3 && day >= 21) || (m == 4 && day <= 19)) return "白羊座";
            if ((m == 4 && day >= 20) || (m == 5 && day <= 20)) return "金牛座";
            if ((m == 5 && day >= 21) || (m == 6 && day <= 21)) return "双子座";
            if ((m == 6 && day >= 22) || (m == 7 && day <= 22)) return "巨蟹座";
            if ((m == 7 && day >= 23) || (m == 8 && day <= 22)) return "狮子座";
            if ((m == 8 && day >= 23) || (m == 9 && day <= 22)) return "处女座";
            if ((m == 9 && day >= 23) || (m == 10 && day <= 23)) return "天秤座";
            if ((m == 10 && day >= 24) || (m == 11 && day <= 22)) return "天蝎座";
            if ((m == 11 && day >= 23) || (m == 12 && day <= 21)) return "射手座";
            if ((m == 12 && day >= 22) || (m == 1 && day <= 19)) return "摩羯座";
            if ((m == 1 && day >= 20) || (m == 2 && day <= 18)) return "水瓶座";
            return "双鱼座";
        } catch (Exception e) {
            return "未知";
        }
    }

    /**
     * 计算月亮星座（简化版）
     * 使用平均周期 27.3216 天近似计算，精度约±1-2个星座
     * 如需精确计算，建议接入 Swiss Ephemeris 或专业占星API
     */
    public static String computeMoon(String birthDate, String birthTime) {
        if (birthDate == null || birthDate.isBlank()) return "未知";
        try {
            // 参考日期:2000年1月6日 18:00 UTC，此时月亮位于白羊座0度
            LocalDate ref = LocalDate.of(2000, 1, 6);
            LocalDate d = LocalDate.parse(birthDate, DateTimeFormatter.ISO_LOCAL_DATE);

            long daysDiff = d.toEpochDay() - ref.toEpochDay();
            double hourFraction;
            if (birthTime != null && !birthTime.isBlank()) {
                try {
                    LocalTime t = LocalTime.parse(birthTime);
                    hourFraction = (t.getHour() + t.getMinute() / 60.0) / 24.0;
                } catch (Exception ignore) {
                    hourFraction = 0.5; // 默认中午12点
                }
            } else {
                hourFraction = 0.5; // 默认中午12点
            }

            double totalDays = daysDiff + hourFraction;
            // 月亮平均周期:27.32166天（恒星月）
            // 注意:实际朔望月约29.53天，但星座计算使用恒星周期更准确
            double moonDegree = (totalDays / 27.32166) * 360.0;
            moonDegree = ((moonDegree % 360) + 360) % 360;
            int signIndex = (int) (moonDegree / 30) % 12;
            return ZODIAC_NAMES[signIndex];
        } catch (Exception e) {
            return "未知";
        }
    }

    /**
     * 计算上升星座（简化版）
     * 基于太阳星座和出生时间近似计算，假设上升星座每2小时均匀切换
     * ⚠️ 局限性:
     *   1. 未考虑出生地点地理纬度（高纬度地区误差更大）
     *   2. 未考虑黄赤交角和岁差影响
     *   3. 使用简化公式而非真实天文计算
     * 如需精确计算，建议接入 Swiss Ephemeris 或专业占星API
     */
    public static String computeRising(String birthDate, String birthTime) {
        if (birthDate == null || birthDate.isBlank()) return "未知";
        // 未提供出生时间时，使用太阳星座作为回退
        if (birthTime == null || birthTime.isBlank()) {
            return computeSun(birthDate);
        }

        try {
            String sun = computeSun(birthDate);
            int sunIdx = -1;
            for (int i = 0; i < ZODIAC_NAMES.length; i++) {
                if (ZODIAC_NAMES[i].equals(sun)) {
                    sunIdx = i;
                    break;
                }
            }
            if (sunIdx < 0) return "未知";

            LocalTime t = LocalTime.parse(birthTime);
            double timeOffset = t.getHour() + t.getMinute() / 60.0;
            // 简化算法:假设早上6点太阳位于上升点
            // 每2小时上升星座推进一个
            int offset = (int) ((timeOffset - 6 + 24) % 24 / 2);
            int risingIdx = (sunIdx + offset) % 12;
            return ZODIAC_NAMES[risingIdx];
        } catch (Exception e) {
            return computeSun(birthDate);
        }
    }

    public static ZodiacTriplet computeAll(String birthDate, String birthTime) {
        return new ZodiacTriplet(
                computeSun(birthDate),
                computeMoon(birthDate, birthTime),
                computeRising(birthDate, birthTime)
        );
    }

    public record ZodiacTriplet(String sun, String moon, String rising) {}
}
