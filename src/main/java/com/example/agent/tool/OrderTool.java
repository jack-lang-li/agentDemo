package com.example.agent.tool;

import org.springframework.stereotype.Component;

@Component
public class OrderTool {

    public String queryOrder(String orderId) {

        // 本节使用固定的模拟数据
        if ("10001".equals(orderId)) {
            return "订单10001，状态为已发货，物流公司为顺丰。";
        }
        //模拟数据库查询结果
        return "未找到订单" + orderId + "。";
    }
}