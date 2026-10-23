# spring-agent-learning

从 0 学习 Spring Boot 智能体开发的长期项目。**目前只完成阶段 1：Spring Boot 基础链路 + Mock ModelClient。**

## 项目定位与当前目标

先理解请求如何进入 Spring Boot、经过业务层、调用模型客户端并序列化为 JSON 响应。
当前使用 Mock，不发起任何模型 HTTP 请求，不需要 API Key，不依赖外部服务。

不直接引入 Spring AI、LangChain4j 或其他 Agent 框架，是为了在后续阶段亲自理解 HTTP、模型响应解析、Tool Calling 和 Agent Loop 的工作方式。

## 技术栈与依赖

- JDK 17、Maven 3.6.3+、Spring Boot 3.5.16。
- `spring-boot-starter-web`：Spring MVC、内嵌 Tomcat，以及 Jackson JSON 序列化/反序列化，无需重复声明 Jackson。
- `spring-boot-starter-validation`：Jakarta Bean Validation，校验 `message` 不能为空或纯空白。
- `spring-boot-starter-actuator`：基础健康检查与信息端点，仅暴露 `health` 和 `info`。
- `lombok`：生成 DTO 访问器和构造器，以及用于构造器注入的代码；打包时排除。
- `spring-boot-starter-test`：仅测试使用，提供 JUnit 5、MockMvc 等。

版本由 Spring Boot parent 统一管理。Java 与 Maven 兼容性参见 [Spring Boot 3.5 官方系统要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)。
当前不需要 HTTP 客户端依赖；阶段 2 可以使用 Spring Framework 自带的 `RestClient`。

## 当前调用链路

```text
用户 POST /api/chat
  → ChatController：接收 JSON，使用 @Valid 触发参数校验
  → ChatService：编排业务流程
  → ModelClient：生成 Mock 字符串
  → ChatService：组装 ChatResponse
  → ChatController：交由 Jackson 序列化并返回 JSON
```

所有依赖使用构造器注入。ModelClient 单独承担模型交互职责，以便阶段 2 加入 HTTP 调用，阶段 3 再抽象 ModelProvider；目前没有额外的模型接口或多模型实现。

## 目录结构

```text
spring-agent-learning/
├── .gitignore
├── pom.xml
├── README.md
└── src/
    ├── main/
    │   ├── java/com/example/agent/
    │   │   ├── SpringAgentLearningApplication.java
    │   │   ├── controller/
    │   │   │   └── ChatController.java
    │   │   ├── service/
    │   │   │   └── ChatService.java
    │   │   ├── client/
    │   │   │   └── ModelClient.java
    │   │   ├── dto/
    │   │   │   ├── ChatRequest.java
    │   │   │   └── ChatResponse.java
    │   │   ├── config/
    │   │   │   └── package-info.java
    │   │   ├── exception/
    │   │   │   └── GlobalExceptionHandler.java
    │   │   └── common/
    │   │       └── package-info.java
    │   └── resources/
    │       └── application.yml
    └── test/java/com/example/agent/
        └── ChatIntegrationTest.java
```

`config`、`common` 只通过包说明文件保留位置，当前没有业务实现。

## 日志使用与排查

项目使用 `spring-boot-starter-web` 已传递引入的 **SLF4J + Logback**，不需要新增日志依赖。任意业务类中可按以下方式定义并使用日志：

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

private static final Logger logger = LoggerFactory.getLogger(YourClass.class);

// 使用 {} 占位符传递数据。
logger.info("收到请求：message={}", message);
logger.debug("调试数据：{}", data);
logger.warn("参数不合法：{}", reason);
// 异常放在最后一个参数，输出完整堆栈。
logger.error("处理失败：id={}", id, exception);
```

当前已记录：

- `/api/chat` 校验通过后的输入消息和返回答案（INFO）。
- 模型 API 实际发送的 JSON 请求体、原始 JSON 响应体、HTTP 状态码和耗时（INFO）。响应在 JSON 解析前打印，错误响应也会记录。
- 参数校验、请求体读取失败（WARN），以及模型调用或响应转换异常的堆栈（ERROR）。

模型请求头不写入日志，避免输出 `Authorization` 中的 API Key。模型 HTTP 客户端使用响应缓冲，打印正文后仍可正常反序列化。上述模型日志适用于当前同步、非流式接口。

日志同时输出至控制台和启动工作目录下的 `logs/spring-agent-learning.log`，采用 UTF-8。文件按天或达到 10MB 时滚动，历史日志最多保留 7 天，总量上限 100MB；日志目录已加入 Git 忽略。

配置位于 `src/main/resources/application.yml`，默认业务日志级别为 INFO，可通过环境变量调整：

```powershell
$env:APP_LOG_LEVEL = 'DEBUG'
$env:LOG_FILE = 'logs/spring-agent-learning.log'
mvn spring-boot:run
```

请求和响应正文包含完整业务内容，日志会保存这些数据。如需关闭正文日志，可单独将 `logging.level.com.example.agent.client.ModelHttpLoggingInterceptor` 和 `logging.level.com.example.agent.controller.ChatController` 设为 `WARN`。

## 启动方式

先确认 `java -version` 和 `mvn -version` 都使用 JDK 17；首次构建需要联网下载 Maven 依赖。

```powershell
cd C:\GPTproject\codeAgentProject\spring-agent-learning
mvn spring-boot:run
```

也可以先运行测试并打包，再启动可执行 JAR：

```powershell
mvn clean verify
java -jar target/spring-agent-learning-0.0.1-SNAPSHOT.jar
```

默认端口为 `8080`，如有占用，可修改 `application.yml` 的 `server.port`，或启动 JAR 时追加 `--server.port=8081`。

## 测试接口与示例

接口：`POST http://localhost:8080/api/chat`，请求头：`Content-Type: application/json`。

示例请求：

```json
{
  "message": "什么是智能体？"
}
```

HTTP 200 响应：

```json
{
  "answer": "模型收到你的问题：什么是智能体？"
}
```

Windows PowerShell 调用（显式使用 UTF-8 字节，避免中文编码问题）：

```powershell
$body = @{ message = '什么是智能体？' } | ConvertTo-Json
Invoke-RestMethod -Uri 'http://localhost:8080/api/chat' -Method Post -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($body))
```

macOS / Linux 的 curl 调用：

```bash
curl -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"你好"}'
```

`message` 缺失、为 `null`、空字符串或纯空白时返回 HTTP 400，例如：

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "message 不能为空",
  "instance": "/api/chat"
}
```

请求体缺失或 JSON 不合法也返回 HTTP 400，由轻量的 `GlobalExceptionHandler` 使用 Spring `ProblemDetail` 统一返回。

健康检查：`GET http://localhost:8080/actuator/health`，正常返回 `{"status":"UP"}`。
基础信息端点：`GET http://localhost:8080/actuator/info`，当前未添加信息贡献项，默认返回 `{}`。

## 自动化测试

```powershell
mvn test
```

`ChatIntegrationTest` 加载完整 Spring 上下文，通过 MockMvc 经过 Controller 和 Service，并替换 ModelClient 以避免访问真实模型 API，验证：

- `{"message":"你好"}` 返回 HTTP 200 和 `模型收到你的问题：你好`。
- 缺失、null、空字符串、纯空白 message 均返回 HTTP 400。
- 非法 JSON、缺失请求体返回 HTTP 400。
- Actuator 健康检查返回 `UP`。

`ModelClientLoggingTest` 使用本机临时 HTTP 服务验证真实模型客户端的日志链路：原始请求、响应正文可见且不输出认证请求头；打印后仍能解析中文答案；HTTP 错误响应及 JSON 解析失败也能保留原始正文。测试不使用真实 API Key 或外部模型服务。

## 当前配置

`application.yml` 只包含服务端口、应用名称、基础 Actuator 配置，以及以下模型配置：

```yaml
agent:
  model:
    provider: mock
```

目前只支持 `mock`。显式配置成其他 provider 不会自动接入模型，应用会因缺少 ModelClient Bean 而启动失败。没有任何真实密钥，也没有绑定真实模型的配置类。

## 当前未实现内容

- OpenAI、Claude、DeepSeek、Qwen 或本地模型 API，以及多模型适配。
- Tool Calling / Function Calling、Java Tool、Tool Registry、Tool Executor、Agent Loop、Agent 调度、Multi-Agent。
- 多轮上下文、Prompt 模板管理、Memory、RAG、Embedding、向量数据库、MCP。
- Redis、MySQL、MongoDB、Elasticsearch、Kafka、RabbitMQ。
- SSE、WebSocket、用户体系、OAuth2、JWT、企业级权限、安全、审计与可观测性。
- Spring AI、LangChain4j、Semantic Kernel、Dify SDK、Coze SDK、AgentScope 或其他 Agent 框架。
- Docker、Kubernetes、微服务、网关。

当前只有一次请求与一次固定规则回复，没有真正的模型推理、工具执行或自主循环，因此还不能算真正的 Agent。

## 后续学习路线

按顺序逐阶段推进，本次仅完成阶段 1：

| 阶段 | 学习内容 | 状态 |
| --- | --- | --- |
| 1 | Spring Boot 基础链路 + Mock ModelClient | 已完成 |
| 2 | 接入真实 LLM HTTP API | 未实现 |
| 3 | 抽象 ModelProvider，支持多模型 | 未实现 |
| 4 | 学习 Tool Calling | 未实现 |
| 5 | 实现 Java Tool | 未实现 |
| 6 | 实现 Tool Registry | 未实现 |
| 7 | 实现 Tool Executor | 未实现 |
| 8 | 实现 Agent Loop | 未实现 |
| 9 | 多轮上下文 | 未实现 |
| 10 | Memory | 未实现 |
| 11 | RAG | 未实现 |
| 12 | 向量数据库 | 未实现 |
| 13 | MCP | 未实现 |
| 14 | Redis | 未实现 |
| 15 | Kafka / 消息系统 | 未实现 |
| 16 | SSE 流式输出 | 未实现 |
| 17 | 多 Agent | 未实现 |
| 18 | 企业级安全、审计、日志、监控 | 未实现 |

下一阶段只学习真实 LLM HTTP API：在 ModelClient 中使用 RestClient，理解请求头、JSON 请求体、响应解析、超时及基础调用失败处理；凭证从环境变量读取。此处仅记录学习计划，尚未实现。
