package com.example.agent.business.logistics.adapter.persistence.model;

import lombok.Getter;
import lombok.Setter;

/** 通过订单归属检查后的物流查询结果。 */
@Getter
@Setter
public class LogisticsQueryRow {

    private String trackingNo;

    private String currentLocation;

    private String latestEvent;
}
