package com.limingyuan.bookkeeping.common;

import com.limingyuan.bookkeeping.common.api.ApiFailure;
import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Money {
    private Money() { }
    public static BigDecimal parse(String value) {
        if (value == null || !value.matches("[+-]?[0-9]+(\\.[0-9]{1,2})?")) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "金额必须为最多两位小数的十进制字符串");
        }
        BigDecimal amount = new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY);
        if (amount.precision() > 19) throw new ApiFailure(400, "VALIDATION_FAILED", "金额超出数据库精确存储范围");
        return amount;
    }
    public static String format(BigDecimal value) { return value.setScale(2, RoundingMode.UNNECESSARY).toPlainString(); }
}
