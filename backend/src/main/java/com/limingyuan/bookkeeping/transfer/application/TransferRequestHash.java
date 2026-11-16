package com.limingyuan.bookkeeping.transfer.application;

import com.limingyuan.bookkeeping.common.CanonicalEncoding;
import java.io.ByteArrayOutputStream;

/** Canonical business fields only. JSON property order and raw decimal spelling are not part of the hash. */
public final class TransferRequestHash {
    private TransferRequestHash() { }

    public static byte[] sha256(long fromAccountId, long toAccountId, String amount, String fromBusinessTime,
            String toBusinessTime) {
        var canonical = new ByteArrayOutputStream();
        writeBusinessFields(canonical, fromAccountId, toAccountId, amount, fromBusinessTime, toBusinessTime);
        return CanonicalEncoding.sha256(canonical.toByteArray());
    }

    /** New creates. A blank name is the empty string, never the display fallback. */
    public static byte[] sha256V2(long fromAccountId, long toAccountId, String amount, String fromBusinessTime,
            String toBusinessTime, String canonicalName) {
        var canonical = new ByteArrayOutputStream();
        CanonicalEncoding.writeUtf8(canonical, "transfer-create-v2");
        writeBusinessFields(canonical, fromAccountId, toAccountId, amount, fromBusinessTime, toBusinessTime);
        CanonicalEncoding.writeUtf8(canonical, canonicalName == null ? "" : canonicalName);
        return CanonicalEncoding.sha256(canonical.toByteArray());
    }

    private static void writeBusinessFields(ByteArrayOutputStream canonical, long fromAccountId, long toAccountId,
            String amount, String fromBusinessTime, String toBusinessTime) {
        CanonicalEncoding.writeUtf8(canonical, Long.toString(fromAccountId));
        CanonicalEncoding.writeUtf8(canonical, Long.toString(toAccountId));
        CanonicalEncoding.writeUtf8(canonical, amount);
        CanonicalEncoding.writeUtf8(canonical, fromBusinessTime);
        CanonicalEncoding.writeUtf8(canonical, toBusinessTime);
    }
}
