# Maven 多模块工程改造说明

## 范围和结果

本轮仅迁移工程结构、代码归属、组件装配和测试。保留 Java 17、Spring Boot 3.5.16、
原有接口和业务行为，不引入 Spring Cloud、数据库、认证授权或新模型协议。

根 POM 从单体 JAR 改为父工程 POM，原有 59 个生产类型迁移到有明确职责的模块。
两个旧 package-info 被模块职责说明替代；旧根源码目录不再参与编译。
八个子模块中六个为普通 JAR，两个为可执行应用。

`common-core`、`llm-client` 只有模块骨架及职责说明，分别等待公共类型和远程客户端阶段。
它们通过构建不代表已经实现身份能力或远程调用。

## 模块和依赖

| 模块 | 主要包 | 当前内部直接依赖 |
| --- | --- | --- |
| common-core | com.example.agent.common.core | 无 |
| common-web | com.example.agent.common.web | 无 |
| llm-api | com.example.agent.llm.api | 无 |
| llm-client | com.example.agent.llm.client | llm-api |
| agent-core | com.example.agent.agent | llm-api |
| llm-provider-responses | com.example.agent.llm.provider.responses | llm-api |
| llm-service | com.example.agent.llm.service | llm-api、llm-provider-responses |
| business-service | com.example.agent.business | llm-api、agent-core、common-web、llm-provider-responses |

llm-api 保留 Jackson 和 Lombok 编译支持，不引入 Spring。
agent-core 使用 Jackson、SLF4J 和 Lombok 编译支持，不引入 Spring 或具体业务。
common-web 当前只有 SseWriter，只需要 Jackson，不为模块名称额外引入 Web Starter。
Lombok 标记为可选依赖；运行组件不依赖它。

根工程只管理版本，不向所有模块继承 Web、Actuator 等运行依赖。
Maven Enforcer 在每个模块限制内部依赖白名单，并检查传递依赖；根规则禁止依赖两个启动应用。
模块循环由 Maven Reactor 检查。`scripts/verify_migration.py` 另核对精确依赖和源码边界。

## 生产代码归属

| 内容 | 归属 |
| --- | --- |
| ModelClient | llm-api 根包 |
| ModelInput、ModelResult、ModelTurn、ModelTool、ToolCall、ToolResult | llm-api.model |
| ToolType、JsonSchemaType | llm-api.enums |
| AgentRunner、DefaultAgentRunner、AgentRequest | agent-core 的 agent 包 |
| AgentTool、ToolRegistry、ToolCallExecutor | agent-core 的 agent.tool 包 |
| 工具执行结果、错误码、状态及 InvalidToolArgumentsException | agent-core 的工具子包 |
| Responses 客户端、Codec、StreamReader、HTTP 日志 | llm-provider-responses |
| ModelProperties、HTTP 配置及显式装配入口 | llm-provider-responses.config |
| ModelRequest、ModelResponse、消息、输出、协议枚举 | llm-provider-responses.protocol |
| SSE 写入 | common-web.sse |
| 聊天入口、DTO、应用服务、命令、执行策略 | business-service 的 chat 子包 |
| OrderTool、LogisticsTool | business-service 的 order / logistics 子包 |
| ShippingStatus、ToolName | business-service 的业务工具枚举 |
| Dify 客户端、配置、异常 | business-service 的 integration.dify 子包 |
| MVC 异步配置、业务异常处理 | business-service.web |

逐文件原路径与新路径见 [migration-files.json](migration-files.json)。
清单也记录拆出的业务测试、局部测试夹具以及两份应用配置。
ModelResult 的原始 outputItems 和深拷贝行为保持，以保留 reasoning 等必要续接内容；
它还不是已冻结的跨服务 wire schema，远程契约在下一阶段单独定义。

## Spring 装配

公共 Agent 类型移除组件注解，由业务侧 AgentConfiguration 创建 ToolCallExecutor 和 AgentRunner。
Responses 实现移除组件注解，ModelProperties 不再通过扫描注册。
ResponsesProviderConfiguration 通过 EnableConfigurationProperties、Import 和 Bean 显式装配。

两个启动类分别只扫描自身包：

- BusinessServiceApplication 扫描业务模块，BusinessLocalModelConfiguration 显式导入 Responses 配置。
- LlmServiceApplication 只扫描模型服务包，并显式导入 Responses 配置。

业务订单、物流、Dify 组件留在业务扫描范围；模型服务没有业务模块依赖，也不需要 Dify 密钥。
避免通过扫描整个 com.example.agent 来偶然装配跨模块组件。

## 当前运行方式和过渡点

```text
业务服务 → AgentRunner（本地）→ ModelClient → Responses 适配器（本地）→ 上游模型
                   └─ 订单、物流工具（本地）

模型服务 → 装配 Responses 适配器，仅提供健康检查
```

本次新增 llm-provider-responses 普通库，是为了在尚未远程化时保留业务行为，
同时让两个启动服务互不依赖。业务服务对它的依赖是明确的过渡安排。
下一阶段实现远程客户端后，业务侧替换本地模型装配，再移除这个依赖。

没有将 AgentTool 实例或 Consumer 当作 HTTP 请求字段；当前也没有生成用的模型 HTTP 接口。
CallerContext、开发身份 Provider、Spring Security、SSO、数据库均尚未实施。
业务入口暂时保留 demo-user-1，与迁移前一致。

## 配置与启动

两个应用各自持有 application.yml，普通库不放同名应用配置。
业务端口 8080，模型端口 8081，默认日志分别为 logs/business-service.log 和 logs/llm-service.log。
既有 agent.model.* 和 dify.* 配置键保持；模型应用删除 Dify 配置段。

从根目录完成构建后：

```powershell
mvn clean verify
java -jar business-service/target/business-service-0.0.1-SNAPSHOT.jar
```

另一个终端可以独立启动：

```powershell
java -jar llm-service/target/llm-service-0.0.1-SNAPSHOT.jar
```

真实使用按 README 配置服务端环境变量；同机同时启动时，LOG_FILE 覆盖值不要指向同一文件。
`mvn -pl business-service spring-boot:run` 需要先安装兄弟库和父 POM，例如：

```powershell
mvn -pl business-service -am install -DskipTests
mvn -pl business-service spring-boot:run
```

模型服务同理。不要对整个 Reactor 执行 spring-boot:run。

## 测试基线与迁移

迁移前：12 个测试类，105 个用例，失败/错误/跳过均为 0。
逐方法基线保存在 [migration-test-baseline.json](migration-test-baseline.json)。

| 迁移后位置 | 原场景数 | 新增场景数 |
| --- | ---: | ---: |
| agent-core | 12 | 0 |
| llm-provider-responses | 26 | 0 |
| business-service | 67 | 2 |
| llm-service | 0 | 2 |
| 合计 | 105 | 4 |

拆分记录：

- DefaultAgentRunnerTest 原 7 个场景：6 个通用场景留在核心，订单纠错场景进入 OrderToolAgentIntegrationTest。
- ToolCallExecutorTest 原 19 个场景：6 个通用场景留在核心，13 个业务场景进入 BusinessToolCallExecutorTest。
- AgentRunnerProtocolTest 包含真实业务工具和 Responses 协议，在业务模块保留 25 个场景。
- 其余原测试按能力归属迁移，参数化数据和业务断言保留。
- ModelClientWiringTest 改为验证公共适配库的正式装配入口，仍校验唯一模型客户端与两种 HTTP 传输分离。
- ModelFixtures、RecordingTool 仅保存在需要它们的测试源码中，未引入生产依赖或跨模块测试 JAR。

另外逐方法对照 Git 中的迁移前源码：原 71 个测试方法中，70 个方法体原样保留；
唯一调整的是模型接线测试，改为验证新的正式装配入口。

新增的两个启动测试类共 4 个用例，验证真实组件装配、HTTP 健康检查、模型服务业务隔离。
现有 SSE 首段及时输出、工具隔离、日志、错误传播与轮数限制测试继续执行。

## 验收命令与实际结果

完整构建：

```powershell
mvn clean verify
python scripts/verify_migration.py --output logs/migration-verification.json
```

结果：八个模块和父工程构建成功；109 个测试通过，失败/错误/跳过均为 0。
审计逐方法及参数化调用次数核对原有 105 个场景，并检查依赖拓扑、源码边界和 JAR 形态。

库模块独立构建：

```powershell
mvn -pl "common-core,common-web,llm-api,llm-client,agent-core,llm-provider-responses" -am clean verify
python scripts/verify_migration.py --libraries-only --output logs/library-verification.json
```

该命令仅构建父工程和六个库，不构建两个启动服务。
实际执行 38 个用例，失败、错误、跳过均为 0。
普通库无 BOOT-INF，只有两个应用生成可执行 Boot JAR。

打包后冒烟：

```powershell
python scripts/smoke_services.py
```

脚本用 java -jar 同时启动两个成品 JAR，仅监听回环地址并使用临时端口，
覆盖模拟密钥和本机不可用上游地址。模型进程还移除 DIFY_API_KEY / DIFY_BASE_URL。
两个健康检查均为 UP；业务聊天空输入为 400，模型服务无聊天入口为 404。
所有进程在结束时关闭。日志和 JSON 证据写入已忽略的 logs 目录，不调用真实付费平台。

Python 仅为可选审计工具，不参与 Maven 生命周期。
在重新执行 clean 后，应重新构建对应模块再检查其 JAR 和测试报告，避免使用旧报告。

## 交付与回退

1. 当前改动尚未提交或推送，由项目所有者检查并提交。
2. Git 会以旧路径删除和新路径新增展示尚未暂存的移动；暂存后通常可识别重命名。
3. 提交前核对根 POM、八个模块、新测试、迁移文档和脚本；target、logs 不应提交。
4. 如需回退已提交迁移，使用正常的 revert 提交整体回退，保留历史。
5. 如尚未提交，先保存个人新增改动，再对照迁移清单恢复旧文件并移除本轮新增模块。
   不要无差别清理工作区；独立业务改动和配置应另行保留。

原有两份阶段文档作为历史设计保留，旧路径以本次迁移清单为准。
