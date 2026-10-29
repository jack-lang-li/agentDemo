# spring-agent-learning

从零学习 Spring Boot 智能体开发的后端工程。目前已实现真实模型调用、Tool Calling、
Java 工具执行、Agent 自动循环、SSE 增量输出，以及 Dify Workflow 接入。

前端为独立 Vue 工程，通过 Java 后端调用模型或 Dify，平台密钥保留在服务端。

## 当前架构

第一轮结构改造已将聊天业务入口与执行策略分开：

```text
ChatController
  → ChatService 接口
  → DefaultChatService
  → ChatExecutorRegistry
      ├─ LocalAgentExecutor → ModelClient → 模型 + ToolRegistry + Agent 循环
      └─ DifyWorkflowExecutor → DifyWorkflowClient → 已发布 Dify Workflow
```

- 自建 Agent 支持阻塞执行与真实流式输出；HTTP 入口使用流式方法。
- Dify 当前使用 blocking 模式，等待工作流完成后返回 JSON。
- 执行器通过稳定编码注册，新增实现可以复用相同业务入口与查找逻辑。
- Agent 上下文在本次请求内部维护，尚未提供跨请求的多轮会话记忆。

各文件职责、接口设计原因、回调规则、扩展步骤和本轮验证范围见
[第一轮改造说明](docs/chat-executor-refactor.md)。

## 技术栈

JDK 17、Maven、Spring Boot 3.5.16、Spring MVC、RestClient、Jackson、
Jakarta Bean Validation、Actuator、Lombok、JUnit 5 和 MockMvc。

版本由 Maven parent 统一管理；本轮未增加运行依赖。

## 启动与配置

在项目根目录执行：

```powershell
mvn spring-boot:run
```

默认端口为 8080。运行前在服务端配置或通过环境变量覆盖以下参数：

| 配置 | 用途 |
| --- | --- |
| agent.model.base-url | 模型服务地址，当前客户端在此基础上访问 /v1/responses |
| agent.model.api-key | 模型平台密钥 |
| agent.model.model | 请求使用的模型名称 |
| dify.base-url | Dify API 根地址，例如 https://api.dify.ai/v1 |
| dify.api-key | 已发布 Workflow 所属应用的 API Key |
| dify.connect-timeout-millis | Dify 建连超时 |
| dify.read-timeout-millis | Dify 读取超时 |

当前 ModelClient 使用 Responses 协议；修改 provider 名称本身不会自动切换模型通信协议。
Dify Workflow 的输入变量需包含 question，输出变量需包含非空字符串 answer。

当前没有登录系统，Controller 沿用测试用户 demo-user-1，并通过 ChatCommand 向下传递。
后续接入认证时，应从服务端认证上下文取得用户标识。

## HTTP 接口

两个聊天入口都接收以下 JSON，并要求 Content-Type: application/json：

```json
{"message":"查询订单 10001，并告诉我物流进度"}
```

| 方法与路径 | 响应 |
| --- | --- |
| POST /api/chat/chat | text/event-stream，自建 Agent 的增量输出 |
| POST /api/chat/dify | application/json，Dify 的完整回答 |
| GET /actuator/health | 健康状态，例如 {"status":"UP"} |
| GET /actuator/info | 当前应用信息 |

SSE 事件含义：

| event | data | 含义 |
| --- | --- | --- |
| status | {"message":"执行状态"} | 展示执行进度 |
| delta | {"text":"新增正文"} | 按顺序追加正文 |
| done | {} | 整个任务成功完成 |
| error | {"message":"失败说明"} | 本次任务失败；可能已有部分正文 |

前端通过 fetch 发起 POST，并增量读取 SSE。只有 done 表示成功；
HTTP 200 后出现 error 仍然属于失败。停止接收会断开浏览器连接，
上游计算能否立即停止取决于连接写入、读取状态及超时。

Dify 成功响应为：

```json
{"answer":"工作流返回的完整回答"}
```

message 缺失、null、空字符串或纯空白时，两个入口都返回 HTTP 400 ProblemDetail。
JSON 不合法或请求体缺失也返回 HTTP 400。Dify 调用失败沿用 HTTP 502 ProblemDetail。

## 工具、日志与流式边界

- OrderTool 使用模拟订单数据；订单 10001 可返回运单号 SF_TEST_10001。
- LogisticsTool 使用模拟物流数据，可根据上述运单号返回物流进度。
- ToolRegistry 收集工具定义并按名称查找执行实现。
- ModelClient 最多执行 5 轮模型请求；第一轮改造未调整内部 Agent 循环。
- 同步模型客户端使用缓冲响应打印原始 JSON 日志；流式客户端独立创建，
  不使用读取完整响应体的日志拦截器，以保留增量输出。
- 日志配置位于 application.yml，默认文件为 logs/spring-agent-learning.log。
  请求正文与工具结果可能包含业务内容；模型日志不打印 Authorization 请求头。

## 自动化验证

```powershell
mvn test
mvn clean verify
```

测试不需要真实 API Key，不调用真实模型或 Dify：

- ChatIntegrationTest：加载真实业务链，模拟外部客户端，验证 SSE、Dify JSON、
  错误响应、输入校验及健康检查。
- ChatExecutorRegistryTest：验证重复注册、未知编码、空注册和流式能力边界。
- DefaultChatServiceTest：验证新增策略扩展、回调顺序和写入异常传播。
- ModelClientTest：使用 MockRestServiceServer 验证模型协议、工具调用与轮数上限。
- ModelClientLoggingTest：使用本机临时 HTTP 服务验证请求、响应日志及错误处理。

打包后可运行：

```powershell
java -jar target/spring-agent-learning-0.0.1-SNAPSHOT.jar
```

## 下一轮边界

下一轮将拆分 ModelClient 内部的 Agent 编排、单次模型通信与上游流式解析职责。
动态模型/画布管理、ASR、TTS、权限体系和 Spring Cloud 等能力继续按学习计划逐项接入。
