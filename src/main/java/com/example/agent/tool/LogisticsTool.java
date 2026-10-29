package com.example.agent.tool;

import com.example.agent.client.enums.JsonSchemaType;
import com.example.agent.client.enums.ToolName;
import com.example.agent.client.enums.ToolType;
import com.example.agent.client.model.ModelTool;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class LogisticsTool implements AgentTool {

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
        );
        tool.setParameters(parameters);

        return tool;
    }

    @Override
    public Map<String, Object> execute(JsonNode arguments) {

        if (arguments == null || !arguments.isObject()) {
            throw new IllegalArgumentException(
                    "物流工具参数必须是JSON对象"
            );
        }

        JsonNode trackingNoNode = arguments.get("trackingNo");

        if (trackingNoNode == null
                || !trackingNoNode.isTextual()
                || trackingNoNode.asText().isBlank()) {
            throw new IllegalArgumentException(
                    "trackingNo必须是非空字符串"
            );
        }

        return queryLogistics(trackingNoNode.asText());
    }

    public Map<String, Object> queryLogistics(String trackingNo) {

        // 固定的模拟物流数据
        if ("SF_TEST_10001".equals(trackingNo)) {
            return Map.of(
                    "found", true,
                    "trackingNo", trackingNo,
                    "currentLocation", "南宁转运中心",
                    "latestEvent", "包裹已到达转运中心，等待发往下一站"
            );
        }

        return Map.of(
                "found", false,
                "trackingNo", trackingNo
        );
    }
}
