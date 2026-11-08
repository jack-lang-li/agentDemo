package com.example.agent.business.order.tool;

import com.example.agent.agent.tool.exception.InvalidToolArgumentsException;
import com.example.agent.business.order.application.OrderQueryService;
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
public class OrderTool implements CallerAwareAgentTool {

    private final OrderQueryService orderQueryService;

    public OrderTool(OrderQueryService orderQueryService) {
        this.orderQueryService = Objects.requireNonNull(
                orderQueryService,
                "orderQueryService 不能为空"
        );
    }

    @Override
    public String name() {
        return ToolName.QUERY_ORDER.getValue();
    }

    @Override
    public ModelTool definition() {

        Map<String, Object> orderIdSchema = Map.of(
                "type", JsonSchemaType.STRING.getValue(),
                "description", "需要查询的订单编号"
        );

        Map<String, Object> parameters = Map.of(
                "type", JsonSchemaType.OBJECT.getValue(),
                "properties", Map.of(
                        "orderId", orderIdSchema
                ),
                "required", List.of("orderId"),
                "additionalProperties", false
        );

        ModelTool tool = new ModelTool();
        tool.setType(ToolType.FUNCTION.getValue());
        tool.setName(name());
        tool.setDescription(
                "根据订单编号查询订单状态、物流公司和运单号。"
                        + "需要查询订单物流，但尚无运单号时，可先使用此工具。"
                        + "仅查询当前用户归属的订单；found=false表示未找到可访问记录。"
        );
        tool.setParameters(parameters);

        return tool;
    }

    @Override
    public Map<String, Object> execute(JsonNode arguments,
                                       CallerAuthorization authorization) {

        if (arguments == null || !arguments.isObject()) {
            throw new InvalidToolArgumentsException(
                    "订单工具参数必须是JSON对象"
            );
        }

        JsonNode orderIdNode = arguments.get("orderId");

        if (orderIdNode == null
                || !orderIdNode.isTextual()
                || orderIdNode.asText().isBlank()) {
            throw new InvalidToolArgumentsException(
                    "orderId必须是非空字符串"
            );
        }

        return orderQueryService.query(
                authorization,
                orderIdNode.asText()
        );
    }


}
