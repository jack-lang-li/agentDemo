package com.example.agent.business.security.authorization;

import com.example.agent.common.core.context.CallerContext;
import com.example.agent.common.core.context.CallerType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 快照必须绑定明确身份、复制权限并采用精确匹配。 */
class CallerAuthorizationTest {

    private final CallerContext caller = new CallerContext("tenant-1", CallerType.USER, "user-1");

    @Test
    void shouldRejectMissingCaller() {
        assertThatThrownBy(() -> new CallerAuthorization(null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("caller 不能为空");
    }

    @Test
    void shouldRejectMissingPermissionCollection() {
        assertThatThrownBy(() -> new CallerAuthorization(caller, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("permissionCodes 不能为 null");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", " order:query", "order:query ", "\torder:query"})
    void shouldRejectInvalidPermissionCodes(String code) {
        Set<String> permissions = new HashSet<>();
        permissions.add(code);
        assertThatThrownBy(() -> new CallerAuthorization(caller, permissions))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("permissionCode 不能为空或包含首尾空白");
    }

    @Test
    void shouldCopyPermissionsAndPreventLaterMutation() {
        Set<String> source = new HashSet<>(Set.of("order:query"));
        CallerAuthorization snapshot = new CallerAuthorization(caller, source);
        source.clear();
        source.add("logistics:query");

        assertThat(snapshot.caller()).isSameAs(caller);
        assertThat(snapshot.permissionCodes()).containsExactly("order:query");
        assertThatThrownBy(() -> snapshot.permissionCodes().add("logistics:query"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldAllowEmptyPermissionsWithoutGrantingBusinessCapabilities() {
        CallerAuthorization snapshot = new CallerAuthorization(caller, Set.of());
        assertThat(snapshot.hasPermission("order:query")).isFalse();
        assertThat(snapshot.hasPermission(null)).isFalse();
        assertThatThrownBy(() -> snapshot.requirePermission("order:query"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void shouldMatchOnlyTheExactGrantedPermission() {
        CallerAuthorization snapshot = new CallerAuthorization(caller, Set.of("order:query"));
        assertThatCode(() -> snapshot.requirePermission("order:query")).doesNotThrowAnyException();
        for (String other : Set.of("ORDER:QUERY", "ROLE_order:query", "order:*", "*", "logistics:query")) {
            assertThat(snapshot.hasPermission(other)).as(other).isFalse();
        }
    }
}
