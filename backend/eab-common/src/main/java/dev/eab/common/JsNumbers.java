package dev.eab.common;

import java.math.BigDecimal;

/** 按 ECMAScript 的规则把数字转成字符串（整数不带小数点，0.1 + 0.2 仍是 0.30000000000000004），页面和消息与 TS 版一致 */
public final class JsNumbers {

    private JsNumbers() {}

    public static String format(double value) {
        if (Double.isNaN(value)) return "NaN";
        if (Double.isInfinite(value)) return value > 0 ? "Infinity" : "-Infinity";
        if (value == Math.rint(value) && Math.abs(value) < 1e21) return String.valueOf((long) value);
        double abs = Math.abs(value);
        if (abs >= 1e-6 && abs < 1e21) return new BigDecimal(Double.toString(value)).stripTrailingZeros().toPlainString();
        String text = Double.toString(value);
        int e = text.indexOf('E');
        String mantissa = new BigDecimal(text.substring(0, e)).stripTrailingZeros().toPlainString();
        int exponent = Integer.parseInt(text.substring(e + 1));
        return mantissa + "e" + (exponent >= 0 ? "+" : "") + exponent;
    }

    /** 数据库里的 REAL 值是整数时按整数输出，JSON 里写成 800 而不是 800.0 */
    public static Object normalize(Object value) {
        if (value instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e15) return d.longValue();
        if (value instanceof Float f && f == Math.rint(f)) return (long) f.floatValue();
        if (value instanceof Integer i) return i.longValue();
        return value;
    }
}
