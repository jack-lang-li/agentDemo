package com.example.agent.business.identity.security;

import com.example.agent.common.core.context.CallerContext;
import com.example.agent.common.core.context.CallerType;
import com.example.agent.business.security.permission.BusinessPermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.GrantedAuthority;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证身份和权限快照不会被凭证清理、外部集合修改或序列化破坏。 */
class AuthenticatedUserTest {

    @Test
    void shouldPreserveIdentityAfterCredentialErasureAndSerialization() throws Exception {
        AuthenticatedUser user = new AuthenticatedUser(
                "tenant-1", "user-42", "alice", "test-hash",
                List.of(BusinessPermissions.CHAT_AGENT_INVOKE, BusinessPermissions.CHAT_WORKFLOW_INVOKE));
        user.eraseCredentials();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(user);
        }
        AuthenticatedUser restored;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (AuthenticatedUser) input.readObject();
        }

        assertThat(restored.getPassword()).isNull();
        assertThat(restored.getUsername()).isEqualTo("alice");
        assertThat(restored.callerContext())
                .isEqualTo(new CallerContext("tenant-1", CallerType.USER, "user-42"));
        assertThat(restored.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("chat:agent:invoke", "chat:workflow:invoke");
        assertThatThrownBy(() -> restored.getAuthorities().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldKeepPermissionSnapshotImmutableAndIndependentFromSource() {
        List<String> source = new ArrayList<>(List.of(
                BusinessPermissions.CHAT_WORKFLOW_INVOKE,
                BusinessPermissions.CHAT_WORKFLOW_INVOKE));
        AuthenticatedUser user = new AuthenticatedUser(
                "tenant-1", "user-42", "alice", "test-hash", source);

        // 创建主体后修改原集合，不能撤掉已有权限或给会话追加权限。
        source.clear();
        source.add(BusinessPermissions.CHAT_AGENT_INVOKE);

        assertThat(user.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("chat:workflow:invoke");
        assertThatThrownBy(() -> user.getAuthorities().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldAllowIdentityWithoutBusinessPermissions() {
        AuthenticatedUser user = new AuthenticatedUser(
                "tenant-1", "user-42", "alice", "test-hash", List.of());

        assertThat(user.getAuthorities()).isEmpty();
        assertThat(user.callerContext())
                .isEqualTo(new CallerContext("tenant-1", CallerType.USER, "user-42"));
    }

    @Test
    void shouldRejectNullPermissionCollectionExplicitly() {
        assertThatThrownBy(() -> new AuthenticatedUser(
                "tenant-1", "user-42", "alice", "test-hash", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("permissionCodes 不能为 null");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ", "\t", " chat:agent:invoke", "chat:agent:invoke ",
            "\nchat:agent:invoke", "chat:workflow:invoke\u2003"
    })
    void shouldRejectInvalidPermissionCode(String permissionCode) {
        // singletonList 允许 null，确保校验发生在主体构造中。
        assertThatThrownBy(() -> new AuthenticatedUser(
                "tenant-1", "user-42", "alice", "test-hash",
                Collections.singletonList(permissionCode)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
