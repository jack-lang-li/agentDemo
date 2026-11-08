package com.example.agent.testsupport;

import com.example.agent.business.logistics.adapter.persistence.model.LogisticsQueryRow;
import com.example.agent.business.order.adapter.persistence.model.OrderQueryRow;

/** 仅构造查询投影，不实现数据范围过滤，也不访问数据库。 */
public final class BusinessDataFixtures {

    private BusinessDataFixtures() {
    }

    public static OrderQueryRow orderRow(String orderId, String trackingNo) {
        OrderQueryRow row = new OrderQueryRow();
        row.setOrderId(orderId);
        row.setShippingStatus("SHIPPED");
        row.setCarrier("顺丰");
        row.setTrackingNo(trackingNo);
        return row;
    }

    public static LogisticsQueryRow logisticsRow(String trackingNo, String location) {
        LogisticsQueryRow row = new LogisticsQueryRow();
        row.setTrackingNo(trackingNo);
        row.setCurrentLocation(location);
        row.setLatestEvent("包裹已到达转运中心");
        return row;
    }
}
