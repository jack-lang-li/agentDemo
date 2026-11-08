package com.example.agent.business.identity.security;

import com.example.agent.business.security.authorization.CallerAuthorization;
import com.example.agent.common.core.context.CallerContext;
import com.example.agent.common.core.context.CallerType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 校验认证边界，测试之间清理 ThreadLocal，避免身份污染。 */
class SecurityContextCurrentCallerProviderTest {

    private final SecurityContextCurrentCallerProvider provider = new SecurityContextCurrentCallerProvider();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldRejectMissingAuthentication() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(provider::requireCurrentCaller)
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(provider::requireCurrentAuthorization)
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void shouldRejectAnonymousEvenThoughAnonymousTokenIsAuthenticated() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "test-key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        assertThatThrownBy(provider::requireCurrentCaller)
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(provider::requireCurrentAuthorization)
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void shouldRejectAuthenticationBeforePasswordVerification() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.unauthenticated("alice", "unverified"));
        assertThatThrownBy(provider::requireCurrentCaller)
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(provider::requireCurrentAuthorization)
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void shouldNotTreatAnArbitraryAuthenticatedPrincipalAsInternalIdentity() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of()));
        assertThatThrownBy(provider::requireCurrentCaller)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("不支持的认证主体类型");
        assertThatThrownBy(provider::requireCurrentAuthorization)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("不支持的认证主体类型");
    }

    @Test
    void shouldUseInternalIdentityInsteadOfLoginName() {
        AuthenticatedUser principal = new AuthenticatedUser(
                "tenant-1", "internal-user-42", "alice", "test-hash", List.of(), true);
        principal.eraseCredentials();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));

        assertThat(provider.requireCurrentCaller())
                .isEqualTo(new CallerContext("tenant-1", CallerType.USER, "internal-user-42"));
    }

    @Test
    void shouldSnapshotTheSameAuthenticationAuthoritiesUsedByHttpAuthorization() {
        AuthenticatedUser principal = new AuthenticatedUser(
                "tenant-1", "internal-user-42", "alice", "test-hash",
                List.of("logistics:query"), true);
        principal.eraseCredentials();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null,
                        AuthorityUtils.createAuthorityList("chat:agent:invoke", "order:query", "order:query")));

        CallerAuthorization snapshot = provider.requireCurrentAuthorization();

        assertThat(snapshot.caller())
                .isEqualTo(new CallerContext("tenant-1", CallerType.USER, "internal-user-42"));
        assertThat(snapshot.permissionCodes()).containsExactlyInAnyOrder("chat:agent:invoke", "order:query");
        assertThat(snapshot.hasPermission("logistics:query")).isFalse();
        assertThat(provider.requireCurrentCaller()).isEqualTo(snapshot.caller());
    }

    @Test
    void shouldKeepPreviousSnapshotAfterTheRequestContextIsReplacedAndCleared() {
        AuthenticatedUser alice = new AuthenticatedUser(
                "tenant-a", "user-a", "alice", "test-hash", List.of("order:query"), true);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(alice, null, alice.getAuthorities()));
        CallerAuthorization first = provider.requireCurrentAuthorization();

        AuthenticatedUser bob = new AuthenticatedUser(
                "tenant-b", "user-b", "bob", "test-hash", List.of("logistics:query"), true);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(bob, null, bob.getAuthorities()));
        CallerAuthorization second = provider.requireCurrentAuthorization();
        SecurityContextHolder.clearContext();

        assertThat(first).isEqualTo(new CallerAuthorization(
                new CallerContext("tenant-a", CallerType.USER, "user-a"), Set.of("order:query")));
        assertThat(second).isEqualTo(new CallerAuthorization(
                new CallerContext("tenant-b", CallerType.USER, "user-b"), Set.of("logistics:query")));
    }
}
