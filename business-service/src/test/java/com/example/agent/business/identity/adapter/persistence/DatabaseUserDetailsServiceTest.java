package com.example.agent.business.identity.adapter.persistence;

import com.example.agent.business.identity.adapter.persistence.mapper.LocalAccountMapper;
import com.example.agent.business.identity.adapter.persistence.model.LocalAccountRow;
import com.example.agent.business.identity.security.AuthenticatedUser;
import com.example.agent.common.core.context.CallerContext;
import com.example.agent.common.core.context.CallerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 只替换存储 Mapper，验证数据库认证适配器的身份、状态和失败边界。 */
@ExtendWith(MockitoExtension.class)
class DatabaseUserDetailsServiceTest {

    private static final String TENANT = "tenant-1";
    private static final String USER_ID = "internal-user-42";

    @Mock
    private LocalAccountMapper accountMapper;

    private DatabaseUserDetailsService service;

    @BeforeEach
    void setUp() {
        service = new DatabaseUserDetailsService(accountMapper, TENANT);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", " tenant-1", "tenant-1 "})
    void shouldRejectInvalidDeploymentTenant(String tenantId) {
        assertThatThrownBy(() -> new DatabaseUserDetailsService(accountMapper, tenantId))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(accountMapper);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void shouldRejectBlankUsernameBeforeReadingStorage(String username) {
        assertThatThrownBy(() -> service.loadUserByUsername(username))
                .isInstanceOf(UsernameNotFoundException.class);
        verifyNoInteractions(accountMapper);
    }

    @Test
    void shouldRejectUnknownAccountWithoutReadingPermissions() {
        assertThatThrownBy(() -> service.loadUserByUsername("missing"))
                .isInstanceOf(UsernameNotFoundException.class);
        verify(accountMapper).findLoginAccount(TENANT, "missing");
        verify(accountMapper, never()).findPermissionCodes(anyString(), anyString());
    }

    @Test
    void shouldUseInternalIdentityStoredHashAndServerTenantForPermissionLookup() {
        LocalAccountRow stored = account(TENANT, "stored-password-hash", 1, 1);
        when(accountMapper.findLoginAccount(TENANT, "alice")).thenReturn(stored);
        when(accountMapper.findPermissionCodes(TENANT, USER_ID))
                .thenReturn(List.of("chat:agent:invoke", "chat:workflow:invoke"));

        AuthenticatedUser user = (AuthenticatedUser) service.loadUserByUsername("alice");

        assertThat(user.isEnabled()).isTrue();
        assertThat(user.getPassword()).isEqualTo("stored-password-hash");
        assertThat(user.getUsername()).isEqualTo("alice");
        assertThat(user.callerContext()).isEqualTo(new CallerContext(TENANT, CallerType.USER, USER_ID));
        assertThat(user.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("chat:agent:invoke", "chat:workflow:invoke");
        var order = inOrder(accountMapper);
        order.verify(accountMapper).findLoginAccount(TENANT, "alice");
        order.verify(accountMapper).findPermissionCodes(TENANT, USER_ID);

        user.eraseCredentials();
        assertThat(user.getPassword()).isNull();
        assertThat(stored.getPasswordHash()).isEqualTo("stored-password-hash");
    }

    @Test
    void shouldAllowEnabledAccountWithoutBusinessPermissions() {
        when(accountMapper.findLoginAccount(TENANT, "alice"))
                .thenReturn(account(TENANT, "stored-hash", 1, 1));
        when(accountMapper.findPermissionCodes(TENANT, USER_ID)).thenReturn(List.of());

        AuthenticatedUser user = (AuthenticatedUser) service.loadUserByUsername("alice");

        assertThat(user.isEnabled()).isTrue();
        assertThat(user.getAuthorities()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(value = {
            "0,1", "1,0", "0,0", "2,1", "1,2", "null,1", "1,null"
    }, nullValues = "null")
    void shouldFailClosedUnlessUserAndAccountAreBothEnabled(
            Integer userEnabled, Integer accountEnabled) {
        when(accountMapper.findLoginAccount(TENANT, "alice"))
                .thenReturn(account(TENANT, "stored-hash", userEnabled, accountEnabled));

        AuthenticatedUser user = (AuthenticatedUser) service.loadUserByUsername("alice");

        assertThat(user.isEnabled()).isFalse();
        assertThat(user.getAuthorities()).isEmpty();
        verify(accountMapper, never()).findPermissionCodes(anyString(), anyString());
    }

    @Test
    void shouldRejectAnAccountReturnedForAnotherTenant() {
        when(accountMapper.findLoginAccount(TENANT, "alice"))
                .thenReturn(account("other-tenant", "stored-hash", 1, 1));

        assertThatThrownBy(() -> service.loadUserByUsername("alice"))
                .isInstanceOf(InternalAuthenticationServiceException.class);
        verify(accountMapper, never()).findPermissionCodes(anyString(), anyString());
    }

    @Test
    void shouldPropagateAccountStorageFailureWithoutPretendingAccountIsMissing() {
        var failure = new DataAccessResourceFailureException("account store unavailable");
        when(accountMapper.findLoginAccount(TENANT, "alice")).thenThrow(failure);

        assertThatThrownBy(() -> service.loadUserByUsername("alice")).isSameAs(failure);
        verify(accountMapper, never()).findPermissionCodes(anyString(), anyString());
    }

    @Test
    void shouldPropagatePermissionStorageFailureWithoutGrantingPartialPermissions() {
        when(accountMapper.findLoginAccount(TENANT, "alice"))
                .thenReturn(account(TENANT, "stored-hash", 1, 1));
        var failure = new DataAccessResourceFailureException("permission store unavailable");
        when(accountMapper.findPermissionCodes(TENANT, USER_ID)).thenThrow(failure);

        assertThatThrownBy(() -> service.loadUserByUsername("alice")).isSameAs(failure);
    }

    @Test
    void shouldReloadPasswordAndPermissionsWithoutChangingPreviousPrincipal() {
        when(accountMapper.findLoginAccount(TENANT, "alice"))
                .thenReturn(account(TENANT, "first-hash", 1, 1), account(TENANT, "second-hash", 1, 1));
        when(accountMapper.findPermissionCodes(TENANT, USER_ID))
                .thenReturn(List.of("chat:agent:invoke"), List.of("chat:workflow:invoke"));

        AuthenticatedUser previous = (AuthenticatedUser) service.loadUserByUsername("alice");
        previous.eraseCredentials();
        AuthenticatedUser current = (AuthenticatedUser) service.loadUserByUsername("alice");

        assertThat(previous.getPassword()).isNull();
        assertThat(previous.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("chat:agent:invoke");
        assertThat(current.getPassword()).isEqualTo("second-hash");
        assertThat(current.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("chat:workflow:invoke");
        verify(accountMapper, times(2)).findLoginAccount(TENANT, "alice");
        verify(accountMapper, times(2)).findPermissionCodes(TENANT, USER_ID);
    }

    private static LocalAccountRow account(
            String tenantId, String passwordHash, Integer userEnabled, Integer accountEnabled) {
        LocalAccountRow row = new LocalAccountRow();
        row.setTenantId(tenantId);
        row.setUserId(USER_ID);
        row.setUsername("alice");
        row.setPasswordHash(passwordHash);
        row.setUserEnabled(userEnabled);
        row.setAccountEnabled(accountEnabled);
        return row;
    }
}
