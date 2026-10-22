package com.example.agent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChatRequest {

    // 同时拒绝 null、空字符串和纯空白输入。
    @NotBlank(message = "message 不能为空")
    private String message;
}
