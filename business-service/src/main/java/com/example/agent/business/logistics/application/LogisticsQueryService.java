package com.example.agent.business.logistics.application;

import com.example.agent.business.logistics.adapter.persistence.mapper.LogisticsQueryMapper;
import com.example.agent.business.logistics.adapter.persistence.model.LogisticsQueryRow;
import com.example.agent.business.security.authorization.CallerAuthorization;
import com.example.agent.business.security.permission.BusinessPermissions;
import com.example.agent.common.core.context.CallerContext;
import com.example.agent.common.core.context.CallerType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;

/** 通过所属订单归属校验后查询物流。 */
@Service
public class LogisticsQueryService {

    private final LogisticsQueryMapper mapper;

    public LogisticsQueryService(LogisticsQueryMapper mapper) {
        this.mapper = Objects.requireNonNull(
                mapper, "mapper 不能为空"
        );
    }

    public Map<String, Object> query(
            CallerAuthorization authorization,
            String trackingNo) {

        Objects.requireNonNull(
                authorization, "authorization 不能为空"
        );

        CallerContext caller = authorization.caller();

        if (caller.callerType() != CallerType.USER) {
            throw new AccessDeniedException(
                    "当前查询仅支持用户主体"
            );
        }

        authorization.requirePermission(
                BusinessPermissions.LOGISTICS_QUERY
        );

        if (trackingNo == null || trackingNo.isBlank()) {
            throw new IllegalArgumentException(
                    "trackingNo 不能为空"
            );
        }

        LogisticsQueryRow row = mapper.findOwnedLogistics(
                caller.tenantId(),
                caller.callerId(),
                trackingNo
        );

        if (row == null) {
            return Map.of(
                    "found", false,
                    "trackingNo", trackingNo
            );
        }

        return Map.of(
                "found", true,
                "trackingNo", row.getTrackingNo(),
                "currentLocation", row.getCurrentLocation(),
                "latestEvent", row.getLatestEvent()
        );
    }
}