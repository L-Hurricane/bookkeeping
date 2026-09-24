package com.limingyuan.bookkeeping.adjustment.application;

import com.limingyuan.bookkeeping.common.CanonicalEncoding;
import java.io.ByteArrayOutputStream;

/** Canonical target balance, business time, and remark. Not the raw HTTP body. */
public final class AdjustmentRequestHash {
    private AdjustmentRequestHash() { }

    public static byte[] sha256(long accountId, String targetBalance, String businessTime, String remark) {
        var canonical = new ByteArrayOutputStream();
        CanonicalEncoding.writeUtf8(canonical, Long.toString(accountId));
        CanonicalEncoding.writeUtf8(canonical, targetBalance);
        CanonicalEncoding.writeUtf8(canonical, businessTime);
        CanonicalEncoding.writeUtf8(canonical, remark);
        return CanonicalEncoding.sha256(canonical.toByteArray());
    }
}
