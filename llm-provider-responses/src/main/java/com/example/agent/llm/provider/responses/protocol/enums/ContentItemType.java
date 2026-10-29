package com.example.agent.llm.provider.responses.protocol.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 模型消息内容的类型。 */
@Getter
@RequiredArgsConstructor
public enum ContentItemType {
    OUTPUT_TEXT("output_text"),
    REFUSAL("refusal");

    private final String value;
}
