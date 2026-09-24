package com.limingyuan.bookkeeping.transaction.application;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** Canonical business fields only. JSON property order and raw decimal spelling are not part of the hash. */
public final class TransactionRequestHash {
    private TransactionRequestHash() { }

    public static byte[] sha256(long accountId, String type, String amount, long categoryId, List<Long> tagIds,
            String businessTime, String name, String remark) {
        var canonical = new ByteArrayOutputStream();
        writeUtf8(canonical, Long.toString(accountId));
        writeUtf8(canonical, type);
        writeUtf8(canonical, amount);
        writeUtf8(canonical, Long.toString(categoryId));
        writeInt(canonical, tagIds.size());
        for (Long tagId : tagIds) writeUtf8(canonical, Long.toString(tagId));
        writeUtf8(canonical, businessTime);
        writeUtf8(canonical, name);
        writeUtf8(canonical, remark);
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    /** 4-byte big-endian UTF-8 byte length, then the bytes. Field text cannot shift a later field. */
    private static void writeUtf8(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeInt(out, bytes.length);
        try {
            out.write(bytes);
        } catch (IOException ex) {
            throw new IllegalStateException("canonical buffer write failed", ex);
        }
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }
}
