package com.example.agent.business.security.authorization;

import com.example.agent.agent.tool.AgentTool;
import com.example.agent.common.core.context.CallerContext;
import com.example.agent.common.core.context.CallerType;
import com.example.agent.llm.api.model.ModelTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.same;

/** 即使误将未授权的包装器注册给 Agent，实际业务仍不得执行。 */
class AuthorizedAgentToolTest {

    private final CallerAwareAgentTool delegate = mock(CallerAwareAgentTool.class);
    private final CallerContext caller = new CallerContext("tenant-1", CallerType.USER, "user-1");

    @ParameterizedTest
    @ValueSource(strings = {"", "logistics:query"})
    void shouldDenyBeforeEnteringTheBusinessTool(String grantedPermission) {
        CallerAuthorization authorization = authorization(
                grantedPermission.isEmpty() ? Set.of() : Set.of(grantedPermission));
        AgentTool guarded = new AuthorizedAgentTool(delegate, authorization, "order:query");

        assertThatThrownBy(() -> guarded.execute(null)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(delegate);
    }

    @Test
    void shouldPreserveDefinitionAndDelegateArgumentsOnlyAfterAuthorization() {
        ModelTool definition = new ModelTool();
        var arguments = new ObjectMapper().createObjectNode().put("orderId", "10001");
        Map<String, Object> data = Map.of("orderId", "10001");
        CallerAuthorization authorization = authorization(Set.of("order:query"));
        when(delegate.name()).thenReturn("query_order");
        when(delegate.definition()).thenReturn(definition);
        when(delegate.execute(arguments, authorization)).thenReturn(data);
        AgentTool guarded = new AuthorizedAgentTool(delegate, authorization, "order:query");

        assertThat(guarded.name()).isEqualTo("query_order");
        assertThat(guarded.definition()).isSameAs(definition);
        assertThat(guarded.execute(arguments)).isSameAs(data);
        verify(delegate).execute(same(arguments), same(authorization));
    }

    @Test
    void shouldPropagateBusinessFailureInsteadOfReportingSuccess() {
        var arguments = new ObjectMapper().createObjectNode();
        IllegalStateException failure = new IllegalStateException("business-failure");
        CallerAuthorization authorization = authorization(Set.of("order:query"));
        when(delegate.execute(arguments, authorization)).thenThrow(failure);
        AgentTool guarded = new AuthorizedAgentTool(delegate, authorization, "order:query");

        assertThatThrownBy(() -> guarded.execute(arguments)).isSameAs(failure);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", " order:query", "order:query "})
    void shouldFailFastForInvalidRequiredPermission(String permission) {
        assertThatThrownBy(() -> new AuthorizedAgentTool(delegate, authorization(Set.of()), permission))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(delegate);
    }

    @Test
    void shouldRejectMissingDelegateOrAuthorization() {
        assertThatThrownBy(() -> new AuthorizedAgentTool(null, authorization(Set.of()), "order:query"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AuthorizedAgentTool(delegate, null, "order:query"))
                .isInstanceOf(NullPointerException.class);
    }

    private CallerAuthorization authorization(Set<String> permissions) {
        return new CallerAuthorization(caller, permissions);
    }
}
