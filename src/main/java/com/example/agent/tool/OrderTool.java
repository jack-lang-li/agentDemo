package com.example.agent.tool;

import com.example.agent.client.enums.ShippingStatus;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class OrderTool {

    public Map<String, Object> queryOrder(String orderId) {

        // 固定的模拟订单数据
        if ("10001".equals(orderId)) {
            return Map.of(
                    "found", true,
                    "orderId", orderId,
                    "shippingStatus", ShippingStatus.SHIPPED.getValue(),
                    "carrier", "顺丰",
                    "trackingNo", "SF_TEST_10001"
            );
        }

        return Map.of(
                "found", false,
                "orderId", orderId
        );
    }
}