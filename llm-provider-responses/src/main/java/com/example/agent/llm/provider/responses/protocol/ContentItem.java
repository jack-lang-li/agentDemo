package com.example.agent.llm.provider.responses.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ContentItem {

    private String type;

    private String text;

    /** Responses 的 refusal 内容保存在独立字段中，同步和流式都可提取为可展示正文。 */
    private String refusal;
}
