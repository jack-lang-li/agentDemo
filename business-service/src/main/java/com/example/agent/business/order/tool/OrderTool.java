package com.example.agent.business.order.tool;

import com.example.agent.agent.tool.AgentTool;

import com.example.agent.llm.api.enums.JsonSchemaType;
import com.example.agent.business.tool.enums.ShippingStatus;
import com.example.agent.business.tool.enums.ToolName;
import com.example.agent.llm.api.enums.ToolType;
import com.example.agent.llm.api.model.ModelTool;
import com.example.agent.agent.tool.exception.InvalidToolArgumentsException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class OrderTool implements AgentTool {

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
        );
        tool.setParameters(parameters);

        return tool;
    }

    @Override
    public Map<String, Object> execute(JsonNode arguments) {

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

        // 参数校验通过后，才执行实际查询。
        return queryOrder(orderIdNode.asText());
    }

    public Map<String, Object> queryOrder(String orderId) {

        // 固定的模拟订单数据
        if ("10001".equals(orderId)) {
            return Map.of(
                    "found", true,
                    "orderId", orderId,
                    "shippingStatus", ShippingStatus.SHIPPED.getValue(),
                    "carrier", "顺丰",
                    "trackingNo", "SF_TEST_10001"
            );
        }

        return Map.of(
                "found", false,
                "orderId", orderId
        );
    }

}
