# 第一轮改造：聊天业务接口与执行策略

本文保留第一轮的设计记录。第二轮已将 ModelClient 抽成单次调用接口，
并将 Agent 循环与业务工具选择分开；当前复用方式见 [第二轮改造说明](model-capabilities-refactor.md)。

本轮把聊天入口与执行方式分开，复用现有客户端，保持已联调的 HTTP 协议。
`ModelClient` 的 Agent 循环、工具执行、模型 HTTP 通信和上游 SSE 解析留到第二轮拆分。

## 改造后的调用链

```text
ChatController
  └─ ChatService（接口）
       └─ DefaultChatService
            └─ ChatExecutorRegistry
                 ├─ local-agent → LocalAgentExecutor → ModelClient
                 └─ dify-workflow → DifyWorkflowExecutor → DifyWorkflowClient
```

两个 HTTP 入口仍然由服务端固定选择执行策略：

| 入口 | 执行方式 | 响应 |
| --- | --- | --- |
| POST /api/chat/chat | local-agent | text/event-stream，实时正文与执行状态 |
| POST /api/chat/dify | dify-workflow | application/json，包含 answer |

两个入口的请求体都还是 `{"message":"你好"}`，前端不需要新增执行编码、用户标识或平台密钥。

## 各文件负责什么

以下路径均相对于项目根目录。

| 文件 | 职责 |
| --- | --- |
| `service/ChatService.java` | 统一的聊天应用服务接口，定义阻塞与流式业务入口 |
| `service/impl/DefaultChatService.java` | 选择策略、检查流式能力、组装 ChatResponse |
| `service/model/ChatCommand.java` | 一次任务的不可变业务参数，包含 message、userId |
| `executor/ChatExecutor.java` | 阻塞式聊天执行契约与唯一业务编码 |
| `executor/StreamingChatExecutor.java` | 在聊天契约上增加真正的流式能力 |
| `executor/ChatExecutorCodes.java` | 当前内置执行方式的共享编码常量 |
| `executor/ChatExecutorRegistry.java` | 收集实现、拒绝重复编码、查找执行器及流式能力 |
| `executor/impl/LocalAgentExecutor.java` | 委托现有 ModelClient，保留自建 Agent 行为 |
| `executor/impl/DifyWorkflowExecutor.java` | 委托现有 DifyWorkflowClient，传递问题与用户标识 |
| `controller/ChatController.java` | 保留 HTTP 路由、输入校验、SSE 帧格式及结束事件 |

Java 源文件都位于 `src/main/java/com/example/agent/` 下。

## 为什么需要两层接口

`ChatService` 表达应用对外提供的业务能力，当前只需要一个默认实现。
`ChatExecutor` 表达完成聊天任务的不同执行方式，可以同时存在多个实现。

Controller 注入 `ChatService`，默认服务注入注册表，注册表通过构造器接收
`List<ChatExecutor>`。Spring 会收集符合类型的组件，随后注册表用 `code()` 建立映射。
业务编码不依赖 Bean 名称或注册顺序，重命名实现类不会改变路由规则。

这个结构把“实现相同业务目标”和“选择哪个实现”分开：

1. 执行器负责平台适配与执行。
2. 注册表负责发现和查找。
3. 应用服务负责统一的业务入口。
4. HTTP 入口目前明确选择 local-agent 或 dify-workflow。

不会使用默认 Bean 优先级来替代每次调用的策略选择。

## 流式能力与异常传播

当前 `LocalAgentExecutor` 实现 `StreamingChatExecutor`，同时提供阻塞和流式方法。
`DifyWorkflowExecutor` 只实现 `ChatExecutor`，因为现有客户端使用 Dify blocking 模式。

调用不支持流式的策略时，注册表会在执行前抛出明确异常，不会调用其阻塞方法来模拟流式。
以后接入 Dify 的真实流式事件后，再由它实现流式接口。

流式方法在当前调用线程执行，回调按产生顺序通知上层；当前 HTTP 入口通过
`StreamingResponseBody` 使用 Spring MVC 异步执行线程，不要求执行器自行创建线程。

- `onDelta` 只传递新增正文；Controller 将它包装成 delta 事件。
- `onStatus` 传递执行进度；Controller 将它包装成 status 事件。
- 整个任务正常返回后，Controller 才发送 done。
- 任务失败时异常向上传播，Controller 发送 error，不再发送 done。
- 回调因连接写入失败而抛出的异常也继续传播，不能吞掉异常后继续生成。

HTTP 头、事件名称、JSON 格式、每个事件写完后的 flush，以及部分正文后的失败行为保持原有约定：

```text
event: status
data: {"message":"请求已接收"}

event: delta
data: {"text":"新增正文"}

event: done
data: {}

```

失败时的终止事件是 `error`，其中包含 `message`。流式响应已经开始后，HTTP 200
也可能以 error 事件结束，前端应继续以事件判断任务是否成功。

## 参数、用户标识与并发

HTTP DTO `ChatRequest` 继续只有 message，原有 Bean Validation 错误格式不变。
进入业务层时转换成 `ChatCommand`，把业务参数与 HTTP 传输对象分开。

`ChatCommand` 使用 Java 17 record，每个请求创建自己的实例；它不是会话历史或权限对象。
当前 Controller 沿用 `demo-user-1`，Dify 执行器将该用户标识传递给客户端。
以后接入认证时，在入口从认证上下文获取真实用户标识，避免把前端提交的任意标识当作身份凭证。

执行器是 Spring 单例组件，只持有客户端依赖，不把单次请求的消息、回调或历史保存在实例字段中。
注册表启动后只读；新增执行器也应遵守同样的并发约束。

## 后续如何增加执行方式

1. 在 Spring 扫描范围内新增一个组件，实现 `ChatExecutor`。
2. `code()` 返回新的、稳定的非空编码；同名编码会导致应用启动失败。
3. `execute(ChatCommand)` 返回完整回答，执行失败抛出异常。
4. 如果上游支持真正的增量生成，再实现 `StreamingChatExecutor`。
5. 在明确的入口或后续配置中选择新编码；新增组件并不代表现有入口会自动切换到它。

注册表与 `DefaultChatService` 不需要为新实现增加 if/switch 分支。
共享常量类只记录内置编码，新实现也可以声明自己的唯一编码。

非 HTTP 调用者也可以使用同一应用服务，例如：

```java
ChatResponse response = chatService.chat(
        ChatExecutorCodes.DIFY_WORKFLOW,
        new ChatCommand("解释一下 Java 接口", "user-42")
);
```

这段代码会实际执行已配置的工作流，需要有效的服务端配置。
ASR、TTS 等输入输出契约不同的能力后续分别定义接口，再由业务流程组合，不强行放进聊天执行接口。

## 本轮验证与未展开的范围

运行 `mvn test` 验证：

- Spring 能按接口注入默认服务，两个内置策略能被真实注册表发现。
- `/api/chat/chat` 返回原有 SSE 头、状态与正文事件，成功才发送 done。
- 流式执行失败时保留已经输出的正文，发送 error 且不发送 done。
- `/api/chat/dify` 返回原有 JSON，传递相同的服务端测试用户；调用失败仍为 HTTP 502。
- 两个入口都在请求不合法时返回原有 HTTP 400 ProblemDetail。
- 重复编码、未知编码以及不支持的流式能力有明确失败行为。
- 使用第三种执行策略时不需要修改默认服务或注册表，回调顺序与异常传播保持正确。
- 原有 Agent 循环、工具参数校验、轮数限制及模型 HTTP 日志测试继续执行。

测试使用模拟客户端、MockRestServiceServer 或本机临时 HTTP 服务，不访问真实模型或 Dify。
原测试中被注释的 ModelClient 初始化和已过期的工具结果断言已同步到现有 ToolRegistry 契约。

本轮没有新增模型协议、动态模型/画布管理、Dify 流式适配、认证权限或前端改动。
第二轮将针对 ModelClient 内部职责进行独立改造。
