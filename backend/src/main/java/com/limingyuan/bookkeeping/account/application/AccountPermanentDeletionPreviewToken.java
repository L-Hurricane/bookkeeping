package com.limingyuan.bookkeeping.account.application;

import com.limingyuan.bookkeeping.common.CanonicalEncoding;
import com.limingyuan.bookkeeping.common.Money;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Opaque preview token derived from the deletion basis at preview time. */
public final class AccountPermanentDeletionPreviewToken {
    private static final int VERSION = 2;

    private AccountPermanentDeletionPreviewToken() { }

    public record TransferBasis(long id, long fromAccountId, long toAccountId, BigDecimal amount) { }

    public record Basis(
            long accountId,
            List<Long> transactionIds,
            List<Long> adjustmentIds,
            List<Long> analysisSnapshotIds,
            List<Long> analysisGenerationIds,
            List<TransferBasis> transfers,
            Map<Long, BigDecimal> counterpartBalanceBefore) { }

    public static String issue(Basis basis) {
        return HexFormat.of().formatHex(CanonicalEncoding.sha256(encode(basis)));
    }

    public static boolean matches(Basis basis, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return issue(basis).equalsIgnoreCase(token.trim());
    }

    private static byte[] encode(Basis basis) {
        var out = new ByteArrayOutputStream();
        CanonicalEncoding.writeInt(out, VERSION);
        writeLong(out, basis.accountId());
        CanonicalEncoding.writeUtf8(out, "ARCHIVED");
        writeLongs(out, basis.transactionIds());
        writeLongs(out, basis.adjustmentIds());
        writeLongs(out, basis.analysisSnapshotIds());
        writeLongs(out, basis.analysisGenerationIds());
        CanonicalEncoding.writeInt(out, basis.transfers().size());
        for (var transfer : basis.transfers()) {
            writeLong(out, transfer.id());
            writeLong(out, transfer.fromAccountId());
            writeLong(out, transfer.toAccountId());
            CanonicalEncoding.writeUtf8(out, Money.format(transfer.amount()));
        }
        var counterpartIds = basis.counterpartBalanceBefore().keySet().stream().sorted().toList();
        CanonicalEncoding.writeInt(out, counterpartIds.size());
        for (long accountId : counterpartIds) {
            writeLong(out, accountId);
            CanonicalEncoding.writeUtf8(out, Money.format(basis.counterpartBalanceBefore().get(accountId)));
        }
        return out.toByteArray();
    }

    private static void writeLongs(ByteArrayOutputStream out, List<Long> values) {
        CanonicalEncoding.writeInt(out, values.size());
        for (long value : values) {
            writeLong(out, value);
        }
    }

    private static void writeLong(ByteArrayOutputStream out, long value) {
        CanonicalEncoding.writeInt(out, (int) (value >>> 32));
        CanonicalEncoding.writeInt(out, (int) value);
    }
}
