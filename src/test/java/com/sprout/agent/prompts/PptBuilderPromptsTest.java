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

    @Test
    void userVisiblePptStagesRequestChineseThoughtWithoutChangingStructuralMarkers() {
        assertTrue(PptBuilderPrompts.REQUIREMENT_PROMPT.contains("思考过程和用户可见的澄清说明均使用简体中文"));
        assertTrue(PptBuilderPrompts.getSearchInfoPrompt("英文主题").contains("思考过程及资料整理使用简体中文"));
        String outline = PptBuilderPrompts.getOutlinePrompt("英文主题", "模板字段", "模板", "资料");
        assertTrue(outline.contains("思考过程和大纲说明使用简体中文"));
        assertTrue(outline.contains("--- Page 1 ---"));
        assertTrue(outline.contains("类型：COVER"));
        assertTrue(PptBuilderPrompts.getSchemaGenerationPrompt("模板", "大纲")
                .contains("模板字段、JSON 键名与用户指定的内容语言保持原样"));
    }
}
