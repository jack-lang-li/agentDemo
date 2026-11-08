package com.example.agent.business.identity.security;

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
    }

    @Test
    void shouldRejectAnonymousEvenThoughAnonymousTokenIsAuthenticated() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "test-key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        assertThatThrownBy(provider::requireCurrentCaller)
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void shouldRejectAuthenticationBeforePasswordVerification() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.unauthenticated("alice", "unverified"));
        assertThatThrownBy(provider::requireCurrentCaller)
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void shouldNotTreatAnArbitraryAuthenticatedPrincipalAsInternalIdentity() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of()));
        assertThatThrownBy(provider::requireCurrentCaller)
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
}
