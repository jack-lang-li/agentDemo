package com.example.agent.business.order.adapter.persistence.mapper;

import com.example.agent.business.order.adapter.persistence.model.OrderQueryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrderQueryMapper {

    OrderQueryRow findOwnedOrder(
            @Param("tenantId") String tenantId,
            @Param("ownerUserId") String ownerUserId,
            @Param("orderId") String orderId);
}
