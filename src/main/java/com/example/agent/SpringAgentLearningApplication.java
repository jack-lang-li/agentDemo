package com.example.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 阶段 1 只验证分层调用链路，暂不引入 Agent 框架，以便逐步理解底层实现。
 */
@SpringBootApplication
public class SpringAgentLearningApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringAgentLearningApplication.class, args);
    }
}
