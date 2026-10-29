# spring-agent-learning

Java 17 / Spring Boot 3.5.16 的 Maven 多模块工程。已保留真实模型调用、Tool Calling、
Java 工具执行、Agent 循环、SSE 增量输出和 Dify Workflow 接入。

当前完成的是工程结构与代码归属迁移。业务服务仍在本进程使用 Responses 适配库；
模型服务已可独立启动，但还没有模型生成 HTTP 接口。OpenFeign、服务间调用及认证授权尚未接入。

## 模块

| 模块 | 用途 |
| --- | --- |
| common-core | 基础公共能力边界，目前只有包职责说明 |
| common-web | SSE 写入辅助组件 |
| llm-api | ModelClient 与模型输入、结果、工具调用契约 |
| llm-client | 远程客户端边界，目前没有 Feign 或 HTTP 实现 |
| agent-core | Agent 循环、工具注册及执行机制，不依赖业务或 Spring |
| llm-provider-responses | Responses 协议、HTTP 客户端、模型配置及显式装配 |
| llm-service | 独立模型服务入口，默认 8081 |
| business-service | 聊天、Dify、订单和物流工具，默认 8080 |

模块依赖、逐文件迁移记录、测试对应和回退步骤见
[多模块改造说明](docs/multi-module-refactor.md)。

## 当前运行关系

```text
浏览器 → business-service
          ChatController → ChatService → ChatExecutorRegistry
            ├─ LocalAgentExecutor → agent-core → llm-api.ModelClient
            │                         ├─ 当前：本地 Responses 适配库 → 上游模型
            │                         └─ 订单、物流工具由业务显式提供
            └─ DifyWorkflowExecutor → DifyWorkflowClient → Dify

llm-service → 显式装配 Responses 适配库，仅提供健康检查和应用信息
```

模型与 Dify 密钥继续保留在服务端。当前没有登录系统，HTTP 入口仍使用开发标识
`demo-user-1`；统一调用者上下文及认证授权按后续阶段实施。

## 构建和测试

在父工程根目录执行，需要 JDK 17 和 Maven：

```powershell
mvn clean verify
```

仅构建六个库模块及其必要依赖，不构建两个启动服务：

```powershell
mvn -pl "common-core,common-web,llm-api,llm-client,agent-core,llm-provider-responses" -am clean verify
```

原有 105 个用例迁移后全部保留，另增加 4 个独立启动与隔离用例，总计 109 个。
外部模型和 Dify 使用 Mock；HTTP 传输及启动检查仅访问本机，不使用真实付费服务。

可选的 Python 审计及打包冒烟工具，不是 Maven 构建前置依赖：

```powershell
python scripts/verify_migration.py
python scripts/smoke_services.py
```

第一个命令应在完整 `mvn clean verify` 后执行；只构建库时改用
`python scripts/verify_migration.py --libraries-only`。
第二个命令自动使用模拟密钥、本机上游地址、回环监听和临时端口，并在结束后关闭它启动的服务。

## 启动

先完成构建，再从根目录分别启动：

```powershell
java -jar business-service/target/business-service-0.0.1-SNAPSHOT.jar
```

```powershell
java -jar llm-service/target/llm-service-0.0.1-SNAPSHOT.jar
```

业务服务读取 `business-service` 自身配置，模型服务读取 `llm-service` 自身配置。
运行期不需要另一个服务已启动。本轮业务服务仍暂时直接装配模型适配库。

使用 Maven 启动时，先安装所需兄弟模块，再单独执行启动目标：

```powershell
mvn -pl business-service -am install -DskipTests
mvn -pl business-service spring-boot:run
```

模型服务可将以上模块名替换为 `llm-service`。不要把 `-am` 和 `spring-boot:run`
组合使用，以免父工程和普通库也尝试启动。

## 配置

| 配置 | 用途 |
| --- | --- |
| OPENAI_API_KEY / agent.model.api-key | 模型服务密钥，两服务当前均需配置 |
| agent.model.base-url | 上游基础地址，当前访问其 /v1/responses |
| agent.model.model | 模型名称 |
| agent.model.connect-timeout-millis | 建连超时，默认 10000 毫秒 |
| agent.model.read-timeout-millis | 读取超时，默认 90000 毫秒 |
| DIFY_API_KEY | 仅业务服务需要，已发布 Workflow 的密钥 |
| DIFY_BASE_URL | 仅业务服务需要，默认 https://api.dify.ai/v1 |
| APP_LOG_LEVEL | 应用日志级别，默认 INFO |
| LOG_FILE | 覆盖当前进程日志文件，默认按服务名区分 |

两个服务同时运行时，若覆盖 LOG_FILE，应为各自进程设置不同文件。
模型服务不加载 Dify 配置。业务服务的模型配置将在后续远程调用接入时调整。

## 业务 HTTP 接口

业务端口默认 8080，两个聊天入口继续接收 `Content-Type: application/json`：

```json
{"message":"查询订单 10001，并告诉我物流进度"}
```

| 方法与路径 | 响应 |
| --- | --- |
| POST /api/chat/chat | text/event-stream，本地 Agent 增量输出 |
| POST /api/chat/dify | application/json，Dify 完整回答 |
| GET /actuator/health | 健康状态 |
| GET /actuator/info | 应用信息 |

模型端口默认 8081，本轮只暴露 `/actuator/health` 和 `/actuator/info`，没有聊天接口。

SSE 事件仍为 `status`、`delta`、`done`、`error`。前端通过 POST fetch 增量读取；
只有 `done` 表示成功，HTTP 200 后的 `error` 仍属于任务失败。
Dify 成功响应仍为 `{"answer":"完整回答"}`。

空消息或缺失消息返回 400 ProblemDetail；非法 JSON、缺少请求体仍为 400；
Dify 调用失败仍为 502。停止接收后的上游取消效果继续受连接状态和超时限制。

## 现有业务边界

- 订单和物流仍为模拟数据：订单 10001 对应 SF_TEST_10001。
- 工具按请求显式提供，Agent 不收集全局业务工具；默认最多执行 5 轮模型请求。
- ModelClient 每次只执行一次模型请求，Agent 负责循环和工具执行。
- 本次请求上下文独立维护，尚无跨请求会话记忆。
- 同步模型调用保留原始正文日志；流式客户端保持无缓冲、无整段正文日志拦截器。
- 请求正文和工具结果可能包含业务内容；现有模型日志不打印 Authorization 请求头。

历史设计见 [执行策略改造](docs/chat-executor-refactor.md) 和
[模型能力抽离](docs/model-capabilities-refactor.md)，其中的旧路径以本轮迁移清单为准。
