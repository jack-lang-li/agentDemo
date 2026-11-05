package com.example.agent.common.core.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** 不完整的身份必须在公共契约入口被拒绝，不自动回退到默认客户或用户。 */
class CallerContextTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void shouldRejectMissingTenant(String tenantId) {
        assertThrows(IllegalArgumentException.class,
                () -> new CallerContext(tenantId, CallerType.USER, "user-1"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void shouldRejectMissingCallerId(String callerId) {
        assertThrows(IllegalArgumentException.class,
                () -> new CallerContext("tenant-1", CallerType.USER, callerId));
    }

    @Test
    void shouldRejectMissingCallerType() {
        assertThrows(IllegalArgumentException.class,
                () -> new CallerContext("tenant-1", null, "user-1"));
    }
}
