package com.example.agent.tool;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class LogisticsTool {

    public Map<String, Object> queryLogistics(String trackingNo) {

        // 固定的模拟物流数据
        if ("SF_TEST_10001".equals(trackingNo)) {
            return Map.of(
                    "found", true,
                    "trackingNo", trackingNo,
                    "currentLocation", "南宁转运中心",
                    "latestEvent", "包裹已到达转运中心，等待发往下一站"
            );
        }

        return Map.of(
                "found", false,
                "trackingNo", trackingNo
        );
    }
}