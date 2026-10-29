# 第二轮改造：复用模型能力，由业务提供工具

本轮将原 ModelClient 中混在一起的模型通信、协议解析、Agent 循环和工具执行分开。
普通业务可以直接注入 ModelClient；需要自动调用工具时，业务再向 AgentRunner 提交任务和工具。
现有 `/api/chat/chat`、`/api/chat/dify`、请求 JSON 和前端 SSE 事件协议保持兼容。

## 当前调用关系

```text
普通业务 Service → ModelClient 接口 → ResponsesModelClient → 模型 HTTP API

需要工具的业务 → AgentRunner 接口 → DefaultAgentRunner
                                  ├─ ModelClient：每轮调用一次模型
                                  └─ ToolCallExecutor：执行本次业务提供的工具

现有聊天业务：LocalAgentExecutor
             └─ 提供 OrderTool、LogisticsTool，调用 AgentRunner
```

ModelClient 不再依赖 ToolRegistry 或具体业务工具，不执行自动循环。
generate 和 generateStream 每次只向模型发送一个请求；模型提出工具请求时只返回数据，
由 AgentRunner 或调用方决定如何处理。

## 普通业务直接调用模型

下面的业务类可放进自己的业务包，复用项目已经配置的模型地址、模型名称和密钥。
业务不必再写 RestClient、Authorization、响应解析或 SSE 解析。

```java
package com.example.agent.business.summary;

import com.example.agent.client.ModelClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
public class SummaryService {

    private final ModelClient modelClient;

    /** 只进行一次生成，不携带订单、物流或其他业务工具。 */
    public String summarize(String content) {
        return modelClient.chat("你是摘要助手，请用三句话总结用户提供的内容。", content);
    }

    /**
     * onDelta 接收真实增量；返回值来自完整完成响应，便于业务后续保存或处理。
     * 业务自行决定输出到 SSE、文件、其他服务还是页面，不受聊天 Controller 限制。
     */
    public String summarizeStream(String content, Consumer<String> onDelta) {
        return modelClient.chatStream("你是摘要助手，请用三句话总结用户提供的内容。", content, onDelta);
    }
}
```

也可以直接调用 `modelClient.chat("解释 Java 接口")`。
上述便捷方法都不提供工具；如果上游仍然返回工具请求，会明确失败，不会静默当作最终回答。

需要读取模型提出的工具调用或自行管理续接流程时，使用：

```java
ModelResult result = modelClient.generate(ModelInput.text("问题"));
// result.text()：可展示正文
// result.toolCalls()：模型提出的工具请求，尚未执行
// result.outputItems()：供协议适配器续接的完整上下文，不作为页面正文展示
```

ModelInput、ModelResult 位于 `com.example.agent.client.model` 包。

## 业务如何描述并实现自己的工具

业务实现 AgentTool，提供三部分：

1. name：本次任务中唯一的字符串名称。
2. definition：名称、用途描述和参数 JSON Schema，交给模型理解何时调用。
3. execute：真实 Java 业务操作，负责字段校验与业务权限检查。

不需要向公共 ToolName 枚举添加名称。该枚举目前只给内置订单、物流工具复用常量。

下面是库存业务工具示例，返回的是演示数据；真正接入时，在 execute 内调用自己的库存服务。
示例类只写在本文档中，本轮没有将库存业务或新的 HTTP 接口加入工程。

```java
package com.example.agent.business.inventory;

import com.example.agent.client.model.ModelTool;
import com.example.agent.tool.AgentTool;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class StockLookupTool implements AgentTool {

    @Override
    public String name() {
        return "lookup_inventory";
    }

    @Override
    public ModelTool definition() {
        ModelTool definition = new ModelTool();
        definition.setType("function");
        definition.setName(name());
        definition.setDescription("根据商品 SKU 查询当前可售库存。用户询问是否有货时使用，不能编造库存。");
        definition.setParameters(Map.of(
                "type", "object",
                "properties", Map.of("sku", Map.of(
                        "type", "string", "description", "需要查询的商品 SKU")),
                "required", List.of("sku"),
                "additionalProperties", false
        ));
        return definition;
    }

    @Override
    public Object execute(JsonNode arguments) {
        JsonNode sku = arguments.path("sku");
        if (!sku.isTextual() || sku.asText().isBlank()) {
            throw new IllegalArgumentException("sku 必须是非空字符串");
        }
        // 此处接入当前业务的权限检查、库存服务和数据查询。
        // 示例仅返回演示数据；提示词和工具选择本身不能替代业务权限校验。
        return Map.of("sku", sku.asText(), "availableQuantity", 12);
    }
}
```

execute 可以返回 Map、列表、DTO、字符串等 Jackson 可序列化的对象，
公共 ToolCallExecutor 统一转换成 JSON 文本，业务不必自行拼装 function_call_output。

## 业务将工具交给通用 Agent

```java
package com.example.agent.business.inventory;

import com.example.agent.agent.AgentRequest;
import com.example.agent.agent.AgentRunner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
public class InventoryAssistantService {

    private final AgentRunner agentRunner;
    private final StockLookupTool stockLookupTool;

    public String answer(String question) {
        AgentRequest request = new AgentRequest(
                "你是库存助手。库存数据必须来自库存查询工具，缺少 SKU 时先向用户询问。",
                question,
                List.of(stockLookupTool)
        );
        return agentRunner.run(request);
    }

    public void answerStream(String question, Consumer<String> onDelta, Consumer<String> onStatus) {
        AgentRequest request = new AgentRequest("你是库存助手，依据工具结果回答。", question,
                List.of(stockLookupTool));
        agentRunner.runStream(request, onDelta, onStatus);
    }
}
```

这个业务只提供库存工具，模型看不到订单和物流工具；即使模型提出其他工具名称，执行器也会拒绝。
另一业务可以使用完全不同的工具集合，也可以在不同任务中为同名工具提供不同实现。
同一次任务中的名称必须唯一。

AgentRequest 默认最多请求模型 5 轮。需要其他限制时，使用四参数构造器：

```java
AgentRequest request = new AgentRequest("业务提示词", "用户问题", List.of(stockLookupTool), 3);
```

最后一轮若仍要求工具，不再执行该轮工具，因为已经没有下一轮可回传结果。

## 公共组件的职责

| 组件 | 负责内容 |
| --- | --- |
| ModelClient | 单次模型调用接口，以及无需工具的文本生成便捷方法 |
| ResponsesModelClient | 当前 Responses 协议的 HTTP 传输实现，读取服务端模型配置 |
| ResponsesModelCodec | 请求编码、完整响应校验、正文与工具请求提取、历史续接编码 |
| ResponsesStreamReader | 上游 SSE 分帧、正文增量、终止事件、EOF 与异常处理 |
| AgentRunner / DefaultAgentRunner | 同步与流式共用的 Agent 自动循环及轮数限制 |
| AgentRequest | 本次业务提示词、问题、工具实例与轮数限制 |
| ToolRegistry | 本次请求的工具说明与执行实例映射，不是全局 Spring 工具容器 |
| ToolCallExecutor | 检查 callId、工具范围、JSON 参数结构，执行并序列化结果 |
| LocalAgentExecutor | 当前订单物流业务的适配器，明确提供 OrderTool、LogisticsTool |

工具 JSON 对象内部的字段及业务规则仍由工具自己检查；公共层不额外引入 JSON Schema 验证库。
本轮所有调用的名称和 JSON 结构先检查再执行，但多个工具的实际业务操作没有自动事务回滚。

## 上下文、流式与并发边界

- ModelInput 的续接轮次使用新列表，每次 Agent 运行单独维护工具集合与模型历史。
- 完整模型 output 保留并回传，包括 reasoning 和未知扩展字段；页面只收到正文与状态。
- ModelResult 对原始 JSON 输出节点进行深拷贝，避免外部修改已保留的模型历史。
- 工具说明应按请求稳定提供；工具实例需保持无状态，或由业务按当前请求单独创建。
- 同步与流式走同一个 Agent 循环，状态通知和工具结果处理不会维护两套逻辑。
- 上游必须返回完整 response.completed；失败事件或提前断流会向上传播异常。
- 只在完整响应校验后执行工具，不执行尚未接收完整的函数参数片段。
- 正文回调写入失败也向上传播，不吞掉异常后继续生成或执行工具。
- SSE 的 status、delta、done、error 仍由原 Controller 写入；Dify 仍返回 JSON。

同步与流式 RestClient 分别创建，共用 `agent.model.connect-timeout-millis`
和 `agent.model.read-timeout-millis`，默认分别为 10000 和 90000。
同步客户端保留原始正文日志；流式客户端不使用缓冲和读取完整正文的拦截器。

## 已验证内容

改造后完整测试 93 项通过，测试使用模拟服务或本机 HTTP 服务，不访问真实模型或 Dify。

- 原有模型协议、工具参数校验、多个工具回传、完整历史和 5 轮上限回归。
- 普通业务不提供工具时的同步和流式模型调用。
- 不同业务及并发请求的工具、提示词、历史隔离。
- 请求外工具拒绝、重复 callId、同名工具按请求隔离、DTO 和列表结果序列化。
- SSE 的 UTF-8 分片、CRLF、多行 data、心跳、拒答、错误事件、提前 EOF 和回调失败。
- Spring 正确注入真实模型适配器及两个独立 RestClient。
- 本机 HTTP 握手证明首段增量在完成响应发送前到达客户端。
- 原有 HTTP 接口、SSE 结束语义、Dify JSON/502、输入校验及健康检查。

## 后续扩展边界

当前仍使用服务端配置中的一个模型目标和 Responses 协议实现。
其他协议需要新增 ModelClient 适配器并明确选择规则；仅修改 provider 字段不会自动转换协议。
动态模型/画布配置、跨请求会话记忆、权限系统及其他生态接入继续按后续学习安排推进。

协议实现核对了 OpenAI 官方文档中的
[Function calling](https://developers.openai.com/api/docs/guides/function-calling) 和
[Responses streaming events](https://developers.openai.com/api/reference/resources/responses/streaming-events)。
本轮保持当前服务地址、模型名称与模型接口路径，未执行真实平台调用。
