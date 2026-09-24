package com.limingyuan.bookkeeping.tag;

import com.limingyuan.bookkeeping.tag.domain.TagNames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class TagNameTest {
    @Test void trimsWithoutChangingCaseOrCompatibilityForm() {
        assertThat(TagNames.required("  外卖  ")).isEqualTo("外卖");
        assertThat(TagNames.required(" Takeout ")).isEqualTo("Takeout");
        assertThat(TagNames.required(" café ")).isEqualTo("café");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "   "})
    void rejectsBlankNames(String raw) {
        assertThatThrownBy(() -> TagNames.required(raw)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsNamesLongerThanTheColumn() {
        assertThat(TagNames.required("a".repeat(TagNames.MAX_LENGTH))).hasSize(TagNames.MAX_LENGTH);
        assertThatThrownBy(() -> TagNames.required("a".repeat(TagNames.MAX_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
