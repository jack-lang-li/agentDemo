package com.example.agent.business.logistics.application;

import com.example.agent.business.logistics.adapter.persistence.mapper.LogisticsQueryMapper;
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

import static com.example.agent.testsupport.BusinessDataFixtures.logisticsRow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 知道运单号不能省略调用者范围，物流权限也不能由订单权限替代。 */
class LogisticsQueryServiceTest {

    private final LogisticsQueryMapper mapper = mock(LogisticsQueryMapper.class);
    private final LogisticsQueryService service = new LogisticsQueryService(mapper);
    private final CallerContext caller = new CallerContext("tenant-a", CallerType.USER, "internal-user-a");
    private final CallerAuthorization authorization = new CallerAuthorization(caller, Set.of("logistics:query"));

    @Test
    void shouldBindTrustedTenantAndOwnerWhileAllowingLogisticsOnlyPermission() {
        when(mapper.findOwnedLogistics("tenant-a", "internal-user-a", "TRACK-A"))
                .thenReturn(logisticsRow("TRACK-A", "南宁转运中心"));

        assertThat(service.query(authorization, "TRACK-A")).containsExactlyInAnyOrderEntriesOf(Map.of(
                "found", true, "trackingNo", "TRACK-A", "currentLocation", "南宁转运中心",
                "latestEvent", "包裹已到达转运中心"));
        verify(mapper).findOwnedLogistics("tenant-a", "internal-user-a", "TRACK-A");
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "known-foreign-waybill"})
    void shouldReturnTheSamePublicShapeForMissingAndInaccessibleWaybills(String trackingNo) {
        assertThat(service.query(authorization, trackingNo))
                .containsExactlyInAnyOrderEntriesOf(Map.of("found", false, "trackingNo", trackingNo));
        verify(mapper).findOwnedLogistics("tenant-a", "internal-user-a", trackingNo);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "order:query"})
    void shouldDenyMissingOrUnrelatedPermissionBeforeReadingData(String permission) {
        CallerAuthorization denied = new CallerAuthorization(caller,
                permission.isEmpty() ? Set.of() : Set.of(permission));
        assertThatThrownBy(() -> service.query(denied, "TRACK-A"))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void shouldDenyApplicationIdentityEvenWithLogisticsPermission() {
        CallerAuthorization application = new CallerAuthorization(
                new CallerContext("tenant-a", CallerType.APPLICATION, "internal-user-a"), Set.of("logistics:query"));
        assertThatThrownBy(() -> service.query(application, "TRACK-A"))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void shouldRejectAbsentAuthorizationBeforeReadingData() {
        assertThatThrownBy(() -> service.query(null, "TRACK-A")).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(mapper);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void shouldRejectBlankIdentifiersBeforeReadingData(String trackingNo) {
        assertThatThrownBy(() -> service.query(authorization, trackingNo))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void shouldPropagateStorageFailureInsteadOfPretendingTheWaybillIsMissing() {
        var failure = new DataAccessResourceFailureException("private-storage-error");
        when(mapper.findOwnedLogistics("tenant-a", "internal-user-a", "TRACK-A")).thenThrow(failure);
        assertThatThrownBy(() -> service.query(authorization, "TRACK-A")).isSameAs(failure);
    }

    @Test
    void shouldReadCurrentOrderOwnershipAgainForEachWaybillQuery() {
        when(mapper.findOwnedLogistics("tenant-a", "internal-user-a", "TRACK-A"))
                .thenReturn(logisticsRow("TRACK-A", "南宁转运中心"), null);
        assertThat(service.query(authorization, "TRACK-A")).containsEntry("found", true);
        assertThat(service.query(authorization, "TRACK-A")).containsEntry("found", false);
        verify(mapper, times(2)).findOwnedLogistics("tenant-a", "internal-user-a", "TRACK-A");
    }
}
