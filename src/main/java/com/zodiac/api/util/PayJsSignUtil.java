package com.zodiac.api.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * PayJS 签名工具 - ASCII 升序排列参数, MD5 大写
 */
public final class PayJsSignUtil {

    private PayJsSignUtil() {}

    /**
     * 对参数做 PayJS 签名 (MD5 大写)
     * @param params 参数 map (不含 sign)
     * @param key    商户通信密钥
     * @return 32 位大写 MD5
     */
    public static String sign(Map<String, Object> params, String key) {
        // 1. 过滤空值 + 按键名 ASCII 升序
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            String v = String.valueOf(e.getValue());
            if (v != null && !v.isEmpty()) {
                sorted.put(e.getKey(), v);
            }
        }
        // 2. 拼接 key=value&...
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (!sb.isEmpty()) sb.append("&");
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        // 3. 追加 &key=商户密钥
        sb.append("&key=").append(key);
        // 4. MD5 → 大写
        return md5Upper(sb.toString());
    }

    /**
     * 验证 PayJS 回调签名
     * @param params POST 过来的所有参数 (含 sign)
     * @param key    商户通信密钥
     * @return true=验签通过
     */
    public static boolean verifySign(Map<String, String> params, String key) {
        String receivedSign = params.get("sign");
        if (receivedSign == null) return false;

        // 去掉 sign 本身再计算
        Map<String, Object> toSign = new TreeMap<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!"sign".equals(e.getKey()) && e.getValue() != null && !e.getValue().isEmpty()) {
                toSign.put(e.getKey(), e.getValue());
            }
        }
        String computed = signStringMap(toSign, key);
        return computed.equals(receivedSign);
    }

    private static String signStringMap(Map<String, Object> params, String key) {
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            String v = String.valueOf(e.getValue());
            if (v != null && !v.isEmpty()) {
                sorted.put(e.getKey(), v);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (!sb.isEmpty()) sb.append("&");
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        sb.append("&key=").append(key);
        return md5Upper(sb.toString());
    }

    private static String md5Upper(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString().toUpperCase();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }
}
