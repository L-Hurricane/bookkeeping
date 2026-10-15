package com.limingyuan.bookkeeping.analysis.infrastructure.mock;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockAnalysisPolicyTest {
    private static final String SECRET = "policy-test-key-value";

    @Test void localProfileWithoutAKeyIsSafe() {
        MockAnalysisPolicy.requireSafe(new String[] {"local"}, new String[0], " ", "mock_accept_a, mock_accept_b,", "success");
        assertThat(MockAnalysisPolicy.usernames("mock_accept_a, mock_accept_b,")).containsExactly("mock_accept_a", "mock_accept_b");
    }

    @Test void defaultLocalProfileIsEnoughWhenNothingIsActive() {
        MockAnalysisPolicy.requireSafe(new String[0], new String[] {"local"}, "", "mock_accept_a", null);
    }

    @Test void configuredKeyRefusesStartupWithoutRevealingIt() {
        assertThatThrownBy(() -> MockAnalysisPolicy.requireSafe(new String[] {"local"}, new String[0], SECRET, "mock_accept_a", "success"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("monthly analysis mock refuses to start while a DashScope API key is configured")
                .hasMessageNotContaining(SECRET);
    }

    @Test void nonLocalProfileRefusesStartup() {
        assertThatThrownBy(() -> MockAnalysisPolicy.requireSafe(new String[] {"test"}, new String[] {"local"}, "", "mock_accept_a", "success"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("monthly analysis mock requires the local profile");
    }

    @Test void productionProfileRefusesStartup() {
        assertThatThrownBy(() -> MockAnalysisPolicy.requireSafe(new String[] {"local", "production"}, new String[0], "", "mock_accept_a", "success"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("monthly analysis mock refuses a production profile");
        assertThatThrownBy(() -> MockAnalysisPolicy.requireSafe(new String[] {"prod"}, new String[0], "", "mock_accept_a", "success"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("monthly analysis mock refuses a production profile");
    }

    @Test void missingWhitelistOrUnknownModeRefusesStartup() {
        assertThatThrownBy(() -> MockAnalysisPolicy.requireSafe(new String[] {"local"}, new String[0], "", " , ", "success"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("monthly analysis mock requires bookkeeping.analysis.mock.allowed-usernames");
        assertThatThrownBy(() -> MockAnalysisPolicy.requireSafe(new String[] {"local"}, new String[0], "", "mock_accept_a", "replay"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("monthly analysis mock mode must be success or failure");
    }
}
