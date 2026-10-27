package com.sprout.agent.prompts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PptBuilderPromptsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void schemaPromptsShowValidJsonWithinTextLimits() throws Exception {
        for (String prompt : new String[] {
                PptBuilderPrompts.getSchemaGenerationPrompt("模板", "大纲"),
                PptBuilderPrompts.getSchemaModifyPrompt("修改标题", "已有数据") }) {
            String example = prompt.split("输出JSON格式，结构如下：", 2)[1]
                    .split("## 字段属性说明", 2)[0].trim();
            JsonNode slide = mapper.readTree(example).path("slides").get(0);
            assertEquals("COVER", slide.path("pageType").asText());
            JsonNode title = slide.path("data").path("title");
            assertTrue(title.path("content").asText().length() <= title.path("fontLimit").asInt());
        }
    }
}
