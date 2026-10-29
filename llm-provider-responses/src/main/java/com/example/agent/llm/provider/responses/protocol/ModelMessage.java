package com.example.agent.llm.provider.responses.protocol;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ModelMessage {

    /**
     * 消息角色：
     * system / user / assistant / tool
     */
    private String role;

    /**
     * 消息内容
     */
    private String content;
}