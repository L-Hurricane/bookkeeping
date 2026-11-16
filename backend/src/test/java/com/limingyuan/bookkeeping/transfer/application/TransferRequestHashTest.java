package com.limingyuan.bookkeeping.transfer.application;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TransferRequestHashTest {
    @Test void sameCanonicalFieldsHashTheSameAndAnyFieldChangeDoesNot() {
        byte[] first = TransferRequestHash.sha256(11, 22, "10.00", "2026-01-31T23:00:00+08:00", "2026-02-01T00:00:00+08:00");
        byte[] again = TransferRequestHash.sha256(11, 22, "10.00", "2026-01-31T23:00:00+08:00", "2026-02-01T00:00:00+08:00");
        assertThat(first).hasSize(32);
        assertThat(Arrays.equals(first, again)).isTrue();
        assertThat(Arrays.equals(first, TransferRequestHash.sha256(11, 23, "10.00", "2026-01-31T23:00:00+08:00", "2026-02-01T00:00:00+08:00"))).isFalse();
        assertThat(Arrays.equals(first, TransferRequestHash.sha256(11, 22, "10.01", "2026-01-31T23:00:00+08:00", "2026-02-01T00:00:00+08:00"))).isFalse();
        assertThat(Arrays.equals(first, TransferRequestHash.sha256(11, 22, "10.00", "2026-02-01T00:00:00+08:00", "2026-01-31T23:00:00+08:00"))).isFalse();
    }

    @Test void versionTwoBindsTheCanonicalNameAndStaysDistinctFromVersionOne() {
        byte[] named = TransferRequestHash.sha256V2(11, 22, "10.00", "2026-01-31T23:00:00+08:00", "2026-02-01T00:00:00+08:00", "旅行");
        byte[] again = TransferRequestHash.sha256V2(11, 22, "10.00", "2026-01-31T23:00:00+08:00", "2026-02-01T00:00:00+08:00", "旅行");
        byte[] blank = TransferRequestHash.sha256V2(11, 22, "10.00", "2026-01-31T23:00:00+08:00", "2026-02-01T00:00:00+08:00", "");
        byte[] historical = TransferRequestHash.sha256(11, 22, "10.00", "2026-01-31T23:00:00+08:00", "2026-02-01T00:00:00+08:00");
        assertThat(Arrays.equals(named, again)).isTrue();
        assertThat(Arrays.equals(named, blank)).isFalse();
        assertThat(Arrays.equals(blank, historical)).isFalse();
        assertThat(Arrays.equals(named, TransferRequestHash.sha256V2(11, 22, "10.00", "2026-01-31T23:00:00+08:00", "2026-02-01T00:00:00+08:00", "转账"))).isFalse();
    }
}
