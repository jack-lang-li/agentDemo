package com.example.agent.client;

import com.example.agent.client.model.ModelInput;
import com.example.agent.client.model.ModelResult;
import com.example.agent.client.model.ToolResult;
import com.example.agent.client.responses.ResponsesModelCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.example.agent.support.ModelFixtures.proposal;
import static org.assertj.core.api.Assertions.assertThat;

/** 保护多段正文提取与完整模型上下文回传，防止抽取公共能力时遗漏协议数据。 */
class ResponsesModelCodecTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ResponsesModelCodec codec = new ResponsesModelCodec(mapper);

    @Test
    void shouldJoinAllTextPartsAcrossMultipleMessages() throws Exception {
        var response = mapper.readTree("""
                {"status":"completed","output":[
                  {"type":"message","content":[{"type":"output_text","text":"第一段"},
                    {"type":"output_text","text":"第二段"}]},
                  {"type":"message","content":[{"type":"output_text","text":"第三段"}]}
                ]}
                """);

        assertThat(codec.decode(response).requireText()).isEqualTo("第一段第二段第三段");
    }

    @Test
    void shouldPreserveRawReasoningAndUnknownFieldsWithoutSharingMutableHistory() {
        var call = proposal("call-1", "business_tool", "{}");
        ObjectNode reasoning = mapper.createObjectNode().put("type", "reasoning")
                .put("id", "reasoning-1").put("provider_extension", "must-survive");
        List<com.fasterxml.jackson.databind.JsonNode> raw = new ArrayList<>();
        raw.add(reasoning);
        raw.addAll(call.outputItems());
        ModelResult response = new ModelResult("", call.toolCalls(), raw);

        // 修改最初的节点和读出的节点，都不能篡改结果中保存的下一轮上下文。
        reasoning.put("id", "changed-original");
        ((ObjectNode) response.outputItems().get(0)).put("provider_extension", "changed-copy");
        ModelInput initial = ModelInput.text("问题");
        ModelInput continued = initial.appendTurn(response, List.of(new ToolResult("call-1", "{\"ok\":true}")));
        var encoded = codec.encode(continued, "test-model", false);

        assertThat(initial.turns()).isEmpty();
        assertThat(encoded.getInput()).hasSize(4);
        assertThat(encoded.getInput().get(1).path("id").asText()).isEqualTo("reasoning-1");
        assertThat(encoded.getInput().get(1).path("provider_extension").asText()).isEqualTo("must-survive");
        assertThat(encoded.getInput().get(3).path("type").asText()).isEqualTo("function_call_output");
        assertThat(encoded.getInput().get(3).path("call_id").asText()).isEqualTo("call-1");
        assertThat(encoded.getInput().get(3).path("output").asText()).isEqualTo("{\"ok\":true}");
    }
}
