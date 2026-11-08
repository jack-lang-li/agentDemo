package com.example.agent.business.logistics.tool;

import com.example.agent.agent.tool.exception.InvalidToolArgumentsException;
import com.example.agent.business.logistics.application.LogisticsQueryService;
import com.example.agent.business.security.authorization.CallerAuthorization;
import com.example.agent.business.security.authorization.CallerAwareAgentTool;
import com.example.agent.business.tool.enums.ToolName;
import com.example.agent.llm.api.enums.JsonSchemaType;
import com.example.agent.llm.api.enums.ToolType;
import com.example.agent.llm.api.model.ModelTool;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class LogisticsTool implements CallerAwareAgentTool {

    private final LogisticsQueryService logisticsQueryService;

    public LogisticsTool(LogisticsQueryService logisticsQueryService) {
        this.logisticsQueryService = Objects.requireNonNull(
                logisticsQueryService,
                "logisticsQueryService 不能为空"
        );
    }

    @Override
    public String name() {
        return ToolName.QUERY_LOGISTICS.getValue();
    }

    @Override
    public ModelTool definition() {

        Map<String, Object> trackingNoSchema = Map.of(
                "type", JsonSchemaType.STRING.getValue(),
                "description", "物流运单号，来自用户提供的信息或订单查询结果"
        );

        Map<String, Object> parameters = Map.of(
                "type", JsonSchemaType.OBJECT.getValue(),
                "properties", Map.of(
                        "trackingNo", trackingNoSchema
                ),
                "required", List.of("trackingNo"),
                "additionalProperties", false
        );

        ModelTool tool = new ModelTool();
        tool.setType(ToolType.FUNCTION.getValue());
        tool.setName(name());
        tool.setDescription(
                "根据物流运单号查询包裹当前位置和最新物流记录。"
                        + "只有订单编号时，可先通过订单查询工具获取运单号。"
                        + "缺少运单号时不得编造。"
                        + "仅查询归属当前用户的订单物流；found=false表示未找到可访问记录。"
        );
        tool.setParameters(parameters);

        return tool;
    }

    @Override
    public Map<String, Object> execute(JsonNode arguments,
                                       CallerAuthorization authorization) {

        if (arguments == null || !arguments.isObject()) {
            throw new InvalidToolArgumentsException(
                    "物流工具参数必须是JSON对象"
            );
        }

        JsonNode trackingNoNode = arguments.get("trackingNo");

        if (trackingNoNode == null
                || !trackingNoNode.isTextual()
                || trackingNoNode.asText().isBlank()) {
            throw new InvalidToolArgumentsException(
                    "trackingNo必须是非空字符串"
            );
        }

        return logisticsQueryService.query(
                authorization,
                trackingNoNode.asText()
        );
    }

}
