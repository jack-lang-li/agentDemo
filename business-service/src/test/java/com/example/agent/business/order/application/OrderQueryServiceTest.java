package com.example.agent.business.order.application;

import com.example.agent.business.order.adapter.persistence.mapper.OrderQueryMapper;
import com.example.agent.business.order.adapter.persistence.model.OrderQueryRow;
import com.example.agent.business.security.authorization.CallerAuthorization;
import com.example.agent.common.core.context.CallerContext;
import com.example.agent.common.core.context.CallerType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.access.AccessDeniedException;

import java.util.Map;
import java.util.Set;

import static com.example.agent.testsupport.BusinessDataFixtures.orderRow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 服务始终绑定可信身份；SQL 的实际过滤由 Mapper 检查和真实 MySQL 手测分别验证。 */
class OrderQueryServiceTest {

    private final OrderQueryMapper mapper = mock(OrderQueryMapper.class);
    private final OrderQueryService service = new OrderQueryService(mapper);
    private final CallerContext caller = new CallerContext("tenant-a", CallerType.USER, "internal-user-a");
    private final CallerAuthorization authorization = new CallerAuthorization(caller, Set.of("order:query"));

    @Test
    void shouldQueryWithTrustedTenantAndInternalUserIdAndReturnOnlyBusinessFields() {
        when(mapper.findOwnedOrder("tenant-a", "internal-user-a", "10001"))
                .thenReturn(orderRow("10001", "TRACK-A"));

        Map<String, Object> result = service.query(authorization, "10001");

        assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of(
                "found", true, "orderId", "10001", "shippingStatus", "SHIPPED",
                "carrier", "顺丰", "trackingNo", "TRACK-A"));
        verify(mapper).findOwnedOrder("tenant-a", "internal-user-a", "10001");
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "known-foreign-order"})
    void shouldReturnTheSamePublicShapeForMissingAndInaccessibleOrders(String orderId) {
        assertThat(service.query(authorization, orderId))
                .containsExactlyInAnyOrderEntriesOf(Map.of("found", false, "orderId", orderId));
        verify(mapper).findOwnedOrder("tenant-a", "internal-user-a", orderId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "logistics:query"})
    void shouldDenyMissingOrUnrelatedPermissionBeforeReadingData(String permission) {
        CallerAuthorization denied = new CallerAuthorization(caller,
                permission.isEmpty() ? Set.of() : Set.of(permission));

        assertThatThrownBy(() -> service.query(denied, "10001"))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void shouldDenyApplicationIdentityEvenWithOrderPermission() {
        CallerAuthorization application = new CallerAuthorization(
                new CallerContext("tenant-a", CallerType.APPLICATION, "internal-user-a"), Set.of("order:query"));
        assertThatThrownBy(() -> service.query(application, "10001"))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void shouldRejectAbsentAuthorizationBeforeReadingData() {
        assertThatThrownBy(() -> service.query(null, "10001")).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(mapper);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void shouldRejectBlankIdentifiersBeforeReadingData(String orderId) {
        assertThatThrownBy(() -> service.query(authorization, orderId))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void shouldReturnAnUnshippedOrderWithoutRequiringShipmentFields() {
        OrderQueryRow row = new OrderQueryRow();
        row.setOrderId("10003");
        row.setShippingStatus("NOT_SHIPPED");
        when(mapper.findOwnedOrder("tenant-a", "internal-user-a", "10003")).thenReturn(row);

        assertThat(service.query(authorization, "10003"))
                .containsEntry("found", true).containsEntry("shippingStatus", "NOT_SHIPPED")
                .containsEntry("carrier", null).containsEntry("trackingNo", null);
    }

    @Test
    void shouldPropagateStorageFailureInsteadOfPretendingTheOrderIsMissing() {
        var failure = new DataAccessResourceFailureException("private-storage-error");
        when(mapper.findOwnedOrder("tenant-a", "internal-user-a", "10001")).thenThrow(failure);

        assertThatThrownBy(() -> service.query(authorization, "10001")).isSameAs(failure);
    }

    @Test
    void shouldReadCurrentOwnershipAgainWithoutReplacingTheAuthorizationSnapshot() {
        when(mapper.findOwnedOrder("tenant-a", "internal-user-a", "10001"))
                .thenReturn(orderRow("10001", "TRACK-A"), null);

        assertThat(service.query(authorization, "10001")).containsEntry("found", true);
        assertThat(service.query(authorization, "10001")).containsEntry("found", false);

        verify(mapper, times(2)).findOwnedOrder("tenant-a", "internal-user-a", "10001");
    }

    @Test
    void shouldKeepTheSameUserIdInDifferentTenantsAsSeparateLookupScopes() {
        CallerAuthorization anotherTenant = new CallerAuthorization(
                new CallerContext("tenant-b", CallerType.USER, "internal-user-a"), Set.of("order:query"));
        when(mapper.findOwnedOrder("tenant-a", "internal-user-a", "10001"))
                .thenReturn(orderRow("10001", "TRACK-A"));
        when(mapper.findOwnedOrder("tenant-b", "internal-user-a", "10001"))
                .thenReturn(orderRow("10001", "TRACK-B"));

        assertThat(service.query(authorization, "10001")).containsEntry("trackingNo", "TRACK-A");
        assertThat(service.query(anotherTenant, "10001")).containsEntry("trackingNo", "TRACK-B");
        verify(mapper).findOwnedOrder("tenant-a", "internal-user-a", "10001");
        verify(mapper).findOwnedOrder("tenant-b", "internal-user-a", "10001");
    }
}
