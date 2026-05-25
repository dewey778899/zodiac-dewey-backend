package com.zodiac.api.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Swiss Ephemeris 精确天文计算工具
 * 
 * 当前实现：使用基于 Meeus《天文算法》的纯 Java 实现
 * 精度：
 * - 太阳位置：±0.01°
 * - 月亮位置：±0.1°
 * - 上升点：±0.5°（需精确出生时间和经纬度）
 * 
 * 未来可替换为 Swiss Ephemeris JNI 绑定以获得更高精度
 */
@Slf4j
@Component
public class SwissEphemerisCalculator {

    private static final String[] ZODIAC_NAMES = {
            "白羊座", "金牛座", "双子座", "巨蟹座", "狮子座", "处女座",
            "天秤座", "天蝎座", "射手座", "摩羯座", "水瓶座", "双鱼座"
    };

    // 黄道十二宫起始角度
    private static final double[] ZODIAC_START = {
            0, 30, 60, 90, 120, 150, 180, 210, 240, 270, 300, 330
    };

    public SwissEphemerisCalculator() {
        log.info("Swiss Ephemeris 计算器初始化完成（使用 Meeus 天文算法）");
    }

    /**
     * 计算太阳星座（精确到日期边界）
     */
    public String computeSun(String birthDate, String birthTime, String timezone) {
        try {
            double julianDay = toJulianDay(birthDate, birthTime, timezone);
            double sunLongitude = computeSunLongitude(julianDay);
            return longitudeToZodiac(sunLongitude);
        } catch (Exception e) {
            log.warn("精确太阳计算失败，使用回退算法", e);
            return ZodiacCalculator.computeSun(birthDate);
        }
    }

    /**
     * 计算月亮星座（考虑月球轨道偏心率）
     */
    public String computeMoon(String birthDate, String birthTime, String timezone) {
        try {
            double julianDay = toJulianDay(birthDate, birthTime, timezone);
            double moonLongitude = computeMoonLongitude(julianDay);
            return longitudeToZodiac(moonLongitude);
        } catch (Exception e) {
            log.warn("精确月亮计算失败，使用回退算法", e);
            return ZodiacCalculator.computeMoon(birthDate, birthTime);
        }
    }

    /**
     * 计算上升星座（考虑地理纬度、黄赤交角）
     */
    public String computeRising(String birthDate, String birthTime, String timezone, 
                                 double latitude, double longitude) {
        try {
            double julianDay = toJulianDay(birthDate, birthTime, timezone);
            double siderealTime = computeSiderealTime(julianDay, longitude);
            double obliquity = computeObliquity(julianDay);
            double ascendant = computeAscendant(siderealTime, latitude, obliquity);
            return longitudeToZodiac(ascendant);
        } catch (Exception e) {
            log.warn("精确上升点计算失败，使用回退算法", e);
            return ZodiacCalculator.computeRising(birthDate, birthTime);
        }
    }

    /**
     * 计算完整星盘
     */
    public ZodiacCalculator.ZodiacTriplet computeAll(String birthDate, String birthTime, 
                                                      String timezone, double latitude, double longitude) {
        String sun = computeSun(birthDate, birthTime, timezone);
        String moon = computeMoon(birthDate, birthTime, timezone);
        String rising = computeRising(birthDate, birthTime, timezone, latitude, longitude);
        return new ZodiacCalculator.ZodiacTriplet(sun, moon, rising);
    }

    // ==================== 天文计算核心方法 ====================

    /**
     * 计算太阳黄经（基于 Meeus 天文算法）
     * 精度：±0.01°
     */
    private double computeSunLongitude(double julianDay) {
        double T = (julianDay - 2451545.0) / 36525.0; // 儒略世纪数
        
        // 太阳平黄经
        double L0 = 280.46646 + 36000.76983 * T + 0.0003032 * T * T;
        L0 = normalizeAngle(L0);
        
        // 太阳平近点角
        double M = 357.52911 + 35999.05029 * T - 0.0001537 * T * T;
        M = normalizeAngle(M);
        
        // 中心差
        double C = (1.914602 - 0.004817 * T - 0.000014 * T * T) * Math.sin(Math.toRadians(M))
                 + (0.019993 - 0.000101 * T) * Math.sin(Math.toRadians(2 * M))
                 + 0.000289 * Math.sin(Math.toRadians(3 * M));
        
        // 太阳黄经
        double sunLongitude = L0 + C;
        
        // 章动修正（简化）
        double omega = 125.04452 - 1934.136261 * T;
        double nutation = -0.00478 * Math.sin(Math.toRadians(omega));
        sunLongitude += nutation;
        
        return normalizeAngle(sunLongitude);
    }

    /**
     * 计算月亮黄经（基于 Meeus 天文算法简化版）
     * 精度：±0.1°
     */
    private double computeMoonLongitude(double julianDay) {
        double T = (julianDay - 2451545.0) / 36525.0;
        
        // 月亮平黄经
        double L = 218.3164477 + 481267.88123421 * T - 0.0015786 * T * T;
        L = normalizeAngle(L);
        
        // 月亮平近点角
        double M = 134.9633964 + 477198.8675055 * T + 0.0087414 * T * T;
        M = normalizeAngle(M);
        
        // 太阳平近点角
        double Ms = 357.5291092 + 35999.0502909 * T - 0.0001536 * T * T;
        Ms = normalizeAngle(Ms);
        
        // 月日平角距
        double D = 297.8501921 + 445267.1114034 * T - 0.0018819 * T * T;
        D = normalizeAngle(D);
        
        // 升交点平黄经
        double omega = 125.04452 - 1934.136261 * T + 0.0020708 * T * T;
        omega = normalizeAngle(omega);
        
        // 主要摄动项
        double deltaL = 6.289 * Math.sin(Math.toRadians(M))
                      + 1.274 * Math.sin(Math.toRadians(2 * D - M))
                      + 0.658 * Math.sin(Math.toRadians(2 * D))
                      + 0.214 * Math.sin(Math.toRadians(2 * M))
                      + 0.186 * Math.sin(Math.toRadians(Ms))
                      - 0.114 * Math.sin(Math.toRadians(2 * Ms))
                      + 0.059 * Math.sin(Math.toRadians(2 * D - 2 * Ms))
                      + 0.057 * Math.sin(Math.toRadians(2 * D - M - Ms))
                      + 0.053 * Math.sin(Math.toRadians(2 * D + M))
                      + 0.046 * Math.sin(Math.toRadians(2 * D - Ms))
                      - 0.041 * Math.sin(Math.toRadians(M - Ms))
                      - 0.035 * Math.sin(Math.toRadians(D))
                      - 0.031 * Math.sin(Math.toRadians(M + Ms))
                      + 0.015 * Math.sin(Math.toRadians(2 * D - 2 * M))
                      + 0.011 * Math.sin(Math.toRadians(M - 4 * D));
        
        // 纬度项修正
        double latitudeCorrection = -0.173 * Math.sin(Math.toRadians(omega))
                                   - 0.055 * Math.sin(Math.toRadians(M - omega))
                                   - 0.046 * Math.sin(Math.toRadians(M + omega));
        
        return normalizeAngle(L + deltaL + latitudeCorrection);
    }

    /**
     * 计算恒星时
     */
    private double computeSiderealTime(double julianDay, double longitude) {
        double T = (julianDay - 2451545.0) / 36525.0;
        
        // 格林尼治平恒星时
        double gmst = 280.46061837 + 360.98564736629 * (julianDay - 2451545.0)
                    + 0.000387933 * T * T - T * T * T / 38710000.0;
        
        // 加上经度（东经为正）
        double lmst = gmst + longitude;
        
        return normalizeAngle(lmst);
    }

    /**
     * 计算黄赤交角
     */
    private double computeObliquity(double julianDay) {
        double T = (julianDay - 2451545.0) / 36525.0;
        
        // 平黄赤交角
        double epsilon0 = 23.0 + 26.0 / 60.0 + 21.448 / 3600.0
                        - 46.8150 / 3600.0 * T
                        - 0.00059 / 3600.0 * T * T
                        + 0.001813 / 3600.0 * T * T * T;
        
        // 章动修正（简化）
        double omega = 125.04452 - 1934.136261 * T;
        double deltaEpsilon = 0.00256 * Math.cos(Math.toRadians(omega));
        
        return epsilon0 + deltaEpsilon;
    }

    /**
     * 计算上升点
     */
    private double computeAscendant(double siderealTime, double latitude, double obliquity) {
        double lstRad = Math.toRadians(siderealTime);
        double latRad = Math.toRadians(latitude);
        double oblRad = Math.toRadians(obliquity);
        
        double y = -Math.cos(lstRad);
        double x = Math.sin(oblRad) * Math.tan(latRad) + Math.cos(oblRad) * Math.sin(lstRad);
        
        double ascendant = Math.toDegrees(Math.atan2(y, x));
        
        return normalizeAngle(ascendant);
    }

    /**
     * 将日期时间转换为儒略日
     */
    private double toJulianDay(String birthDate, String birthTime, String timezone) {
        LocalDate date = LocalDate.parse(birthDate);
        LocalTime time = birthTime != null && !birthTime.isBlank() 
                ? LocalTime.parse(birthTime) 
                : LocalTime.NOON;
        
        ZoneId zone = timezone != null && !timezone.isBlank() 
                ? ZoneId.of(timezone) 
                : ZoneId.of("Asia/Shanghai");
        
        ZonedDateTime zdt = ZonedDateTime.of(date, time, zone);
        
        // 转换为 UTC
        ZonedDateTime utc = zdt.withZoneSameInstant(ZoneId.of("UTC"));
        
        int year = utc.getYear();
        int month = utc.getMonthValue();
        int day = utc.getDayOfMonth();
        double hour = utc.getHour() + utc.getMinute() / 60.0 + utc.getSecond() / 3600.0;
        
        // 计算儒略日
        if (month <= 2) {
            year -= 1;
            month += 12;
        }
        
        int A = year / 100;
        int B = 2 - A + A / 4;
        
        double julianDay = Math.floor(365.25 * (year + 4716)) 
                         + Math.floor(30.6001 * (month + 1)) 
                         + day + hour / 24.0 + B - 1524.5;
        
        return julianDay;
    }

    /**
     * 黄经转星座
     */
    private String longitudeToZodiac(double longitude) {
        longitude = normalizeAngle(longitude);
        int index = (int) (longitude / 30) % 12;
        return ZODIAC_NAMES[index];
    }

    /**
     * 角度归一化到 0-360°
     */
    private double normalizeAngle(double angle) {
        angle = angle % 360.0;
        if (angle < 0) {
            angle += 360.0;
        }
        return angle;
    }

    /**
     * 关闭资源
     */
    public void close() {
        log.info("Swiss Ephemeris 计算器已关闭");
    }
}
