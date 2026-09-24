package com.limingyuan.bookkeeping.common;

import com.limingyuan.bookkeeping.common.api.ApiFailure;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class MoneyTest {
    @ParameterizedTest
    @CsvSource({"100,100.00", "0,0.00", "-12.30,-12.30", "0.10,0.10", "+1.2,1.20",
            "99999999999999999.99,99999999999999999.99", "-99999999999999999.99,-99999999999999999.99",
            "0000000000000000000.01,0.01"})
    void parsesExactlyAndFormatsTwoPlaces(String input, String expected) {
        assertThat(Money.format(Money.parse(input))).isEqualTo(expected);
    }
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "1.234", "1.000", "1e2", "NaN", "100000000000000000", "1,000", "--1"})
    void rejectsInvalidAmountsWithoutRounding(String input) {
        assertThatThrownBy(() -> Money.parse(input)).isInstanceOf(ApiFailure.class);
    }
}
