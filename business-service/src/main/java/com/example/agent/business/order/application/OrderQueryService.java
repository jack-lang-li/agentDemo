package com.example.agent.business.order.application;

import com.example.agent.business.order.adapter.persistence.mapper.OrderQueryMapper;
import com.example.agent.business.order.adapter.persistence.model.OrderQueryRow;
import com.example.agent.business.security.authorization.CallerAuthorization;
import com.example.agent.business.security.permission.BusinessPermissions;
import com.example.agent.common.core.context.CallerContext;
import com.example.agent.common.core.context.CallerType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 当前用户所属租户内、归属本人的订单查询入口。 */
@Service
public class OrderQueryService {

    private final OrderQueryMapper mapper;

    public OrderQueryService(OrderQueryMapper mapper) {
        this.mapper = Objects.requireNonNull(
                mapper, "mapper 不能为空"
        );
    }

    public Map<String, Object> query(
            CallerAuthorization authorization,
            String orderId) {

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
                BusinessPermissions.ORDER_QUERY
        );

        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException(
                    "orderId 不能为空"
            );
        }

        OrderQueryRow row = mapper.findOwnedOrder(
                caller.tenantId(),
                caller.callerId(),
                orderId
        );

        // 不存在和不在可见范围内，统一返回相同结果。
        if (row == null) {
            return Map.of(
                    "found", false,
                    "orderId", orderId
            );
        }

        // 未创建运单时，carrier、trackingNo 可以为 null。
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", true);
        result.put("orderId", row.getOrderId());
        result.put("shippingStatus", row.getShippingStatus());
        result.put("carrier", row.getCarrier());
        result.put("trackingNo", row.getTrackingNo());

        return Collections.unmodifiableMap(result);
    }
}