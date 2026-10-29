package com.example.agent.business.tool.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ShippingStatus {

    NOT_SHIPPED("NOT_SHIPPED"),
    SHIPPED("SHIPPED");

    private final String value;
}