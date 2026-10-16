package com.limingyuan.bookkeeping.transaction.application;

import com.limingyuan.bookkeeping.common.CanonicalEncoding;
import java.io.ByteArrayOutputStream;
import java.util.List;

/** Canonical business fields only. JSON property order and raw decimal spelling are not part of the hash. */
public final class TransactionRequestHash {
    private TransactionRequestHash() { }

    public static byte[] sha256(long accountId, String type, String amount, Long categoryId, List<Long> tagIds,
            String businessTime, String name, String remark) {
        var canonical = new ByteArrayOutputStream();
        CanonicalEncoding.writeUtf8(canonical, Long.toString(accountId));
        CanonicalEncoding.writeUtf8(canonical, type);
        CanonicalEncoding.writeUtf8(canonical, amount);
        CanonicalEncoding.writeUtf8(canonical, categoryId == null ? "" : Long.toString(categoryId));
        CanonicalEncoding.writeInt(canonical, tagIds.size());
        for (Long tagId : tagIds) CanonicalEncoding.writeUtf8(canonical, Long.toString(tagId));
        CanonicalEncoding.writeUtf8(canonical, businessTime);
        CanonicalEncoding.writeUtf8(canonical, name);
        CanonicalEncoding.writeUtf8(canonical, remark);
        return CanonicalEncoding.sha256(canonical.toByteArray());
    }
}
