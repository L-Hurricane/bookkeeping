package com.limingyuan.bookkeeping.analysis.infrastructure.mock;

import com.limingyuan.bookkeeping.analysis.application.AnalysisGenerationAdmission;
import com.limingyuan.bookkeeping.common.api.ApiFailure;
import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import com.limingyuan.bookkeeping.identity.application.UserDirectory;
import java.util.Set;

/** Rejects a new mock generation before a permit, quota reservation, or snapshot exists. */
final class MockUserAdmission implements AnalysisGenerationAdmission {
    private final Set<String> allowed;
    private final UserDirectory users;
    private final TrustedUserContext context;

    MockUserAdmission(Set<String> allowed, UserDirectory users, TrustedUserContext context) {
        this.allowed = Set.copyOf(allowed);
        this.users = users;
        this.context = context;
    }

    @Override
    public void admit() {
        String username = users.username(context.userId()).orElse("");
        if (!allowed.contains(username)) {
            throw new ApiFailure(403, "OPERATION_FORBIDDEN", "当前本地模拟不接受此用户");
        }
    }
}
