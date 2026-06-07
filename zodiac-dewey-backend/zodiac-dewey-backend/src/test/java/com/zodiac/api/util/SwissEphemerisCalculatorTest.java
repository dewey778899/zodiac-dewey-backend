package com.zodiac.api.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Swiss Ephemeris 计算器测试
 */
public class SwissEphemerisCalculatorTest {

    private final SwissEphemerisCalculator calculator = new SwissEphemerisCalculator();

    @Test
    public void testComputeSun() {
        // 1990-05-20 应该是金牛座/双子座边界
        String sun = calculator.computeSun("1990-05-20", "12:00", "Asia/Shanghai");
        System.out.println("1990-05-20 太阳星座: " + sun);
        assertNotNull(sun);
        assertTrue(sun.equals("金牛座") || sun.equals("双子座"));
    }

    @Test
    public void testComputeMoon() {
        // 测试月亮星座计算
        String moon = calculator.computeMoon("1990-05-20", "12:00", "Asia/Shanghai");
        System.out.println("1990-05-20 月亮星座: " + moon);
        assertNotNull(moon);
    }

    @Test
    public void testComputeRising() {
        // 北京 1990-05-20 12:00
        String rising = calculator.computeRising("1990-05-20", "12:00", "Asia/Shanghai", 39.9042, 116.4074);
        System.out.println("北京 1990-05-20 12:00 上升星座: " + rising);
        assertNotNull(rising);
    }

    @Test
    public void testComputeAll() {
        // 测试完整计算
        ZodiacCalculator.ZodiacTriplet triplet = calculator.computeAll(
                "1990-05-20", "12:00", "Asia/Shanghai", 39.9042, 116.4074);
        
        System.out.println("太阳: " + triplet.sun());
        System.out.println("月亮: " + triplet.moon());
        System.out.println("上升: " + triplet.rising());
        
        assertNotNull(triplet.sun());
        assertNotNull(triplet.moon());
        assertNotNull(triplet.rising());
    }

    @Test
    public void testCompareWithSimplified() {
        // 对比精确算法和简化算法
        String date = "1990-05-20";
        String time = "14:30";
        
        // 简化算法
        ZodiacCalculator.ZodiacTriplet simplified = ZodiacCalculator.computeAll(date, time);
        
        // 精确算法
        ZodiacCalculator.ZodiacTriplet precise = calculator.computeAll(date, time, "Asia/Shanghai", 39.9042, 116.4074);
        
        System.out.println("=== 算法对比 ===");
        System.out.println("简化算法 - 太阳: " + simplified.sun() + ", 月亮: " + simplified.moon() + ", 上升: " + simplified.rising());
        System.out.println("精确算法 - 太阳: " + precise.sun() + ", 月亮: " + precise.moon() + ", 上升: " + precise.rising());
        
        // 太阳星座应该一致（100%准确）
        assertEquals(simplified.sun(), precise.sun(), "太阳星座应该一致");
    }
}
