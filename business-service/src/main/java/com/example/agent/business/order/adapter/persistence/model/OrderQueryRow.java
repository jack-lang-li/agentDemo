package com.example.agent.business.order.adapter.persistence.model;

import lombok.Getter;
import lombok.Setter;

/** 已按租户和归属用户筛选后的订单查询结果。 */
@Getter
@Setter
public class OrderQueryRow {

    private String orderId;

    private String shippingStatus;

    private String carrier;

    private String trackingNo;
}
