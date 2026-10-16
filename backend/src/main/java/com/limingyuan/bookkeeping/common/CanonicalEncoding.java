package com.limingyuan.bookkeeping.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Length-prefixed UTF-8 fields. Field text cannot shift a later field. */
public final class CanonicalEncoding {
    private CanonicalEncoding() { }

    public static void writeUtf8(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeInt(out, bytes.length);
        try {
            out.write(bytes);
        } catch (IOException ex) {
            throw new IllegalStateException("canonical buffer write failed", ex);
        }
    }

    public static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    public static byte[] sha256(byte[] canonical) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
