package com.limingyuan.bookkeeping.transaction.application;

import com.limingyuan.bookkeeping.common.CanonicalEncoding;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;

/** Canonical business fields only. JSON property order and raw decimal spelling are not part of the hash. */
public final class TransactionRequestHash {
    private TransactionRequestHash() { }

    public static byte[] sha256(long accountId, String type, String amount, Long categoryId, List<Long> tagIds,
            String businessTime, String name, String remark) {
        var canonical = new ByteArrayOutputStream();
        writeBusinessFields(canonical, accountId, type, amount, categoryId, tagIds, businessTime, name, remark);
        return CanonicalEncoding.sha256(canonical.toByteArray());
    }

    /** New creates. A blank counterparty is the empty string. */
    public static byte[] sha256V2(long accountId, String type, String amount, Long categoryId, List<Long> tagIds,
            String businessTime, String name, String remark, String counterparty) {
        var canonical = new ByteArrayOutputStream();
        CanonicalEncoding.writeUtf8(canonical, "transaction-create-v2");
        writeBusinessFields(canonical, accountId, type, amount, categoryId, tagIds, businessTime, name, remark);
        CanonicalEncoding.writeUtf8(canonical, counterparty == null ? "" : counterparty);
        return CanonicalEncoding.sha256(canonical.toByteArray());
    }

    /** H2 wins. An empty counterparty may still replay a stored H1 without rewriting it. */
    public static boolean matchesStored(byte[] stored, byte[] legacy, byte[] current, boolean counterpartyEmpty) {
        if (stored == null) return false;
        if (Arrays.equals(stored, current)) return true;
        return counterpartyEmpty && Arrays.equals(stored, legacy);
    }

    private static void writeBusinessFields(ByteArrayOutputStream canonical, long accountId, String type, String amount,
            Long categoryId, List<Long> tagIds, String businessTime, String name, String remark) {
        CanonicalEncoding.writeUtf8(canonical, Long.toString(accountId));
        CanonicalEncoding.writeUtf8(canonical, type);
        CanonicalEncoding.writeUtf8(canonical, amount);
        CanonicalEncoding.writeUtf8(canonical, categoryId == null ? "" : Long.toString(categoryId));
        CanonicalEncoding.writeInt(canonical, tagIds.size());
        for (Long tagId : tagIds) CanonicalEncoding.writeUtf8(canonical, Long.toString(tagId));
        CanonicalEncoding.writeUtf8(canonical, businessTime);
        CanonicalEncoding.writeUtf8(canonical, name);
        CanonicalEncoding.writeUtf8(canonical, remark);
    }
}
