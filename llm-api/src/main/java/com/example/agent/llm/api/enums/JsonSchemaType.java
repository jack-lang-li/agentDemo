package com.example.agent.llm.api.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 工具参数 JSON Schema 使用的数据类型。 */
@Getter
@RequiredArgsConstructor
public enum JsonSchemaType {
    STRING("string"),
    OBJECT("object");

    private final String value;
}
