package com.sprout.agent.agents.ppt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class SchemaStrategyTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rejectsUnknownPlaceholderAndTemplateMismatch() throws Exception {
        var template = mapper.readTree("""
                {"slides":[{"pageType":"COVER","data":{"标题":{"type":"text","fontLimit":3}}}]}
                """);
        var output = (ObjectNode) mapper.readTree("""
                {"slides":[{"pageType":"COVER","pageDesc":"封面","templatePageIndex":1,
                  "data":{"无效形状":{"type":"text","content":"占位文字","fontLimit":3}}}]}
                """);
        assertThrows(IllegalArgumentException.class, () -> SchemaStrategy.validate(output, template, false));
        ((ObjectNode) output.path("slides").get(0).path("data")).set("标题",
                ((ObjectNode) output.path("slides").get(0).path("data")).remove("无效形状"));
        ((ObjectNode) output.path("slides").get(0)).put("templatePageIndex", 2);
        assertThrows(IllegalArgumentException.class, () -> SchemaStrategy.validate(output, template, false));
    }

    @Test
    void truncatesByUnicodeCodePointAndPreservesUploadedImageOnResume() throws Exception {
        var template = mapper.readTree("""
                {"slides":[{"pageType":"COVER","data":{
                  "标题":{"type":"text","fontLimit":3},"配图":{"type":"image"}}}]}
                """);
        var output = (ObjectNode) mapper.readTree("""
                {"slides":[{"pageType":"COVER","pageDesc":"封面","templatePageIndex":1,
                  "data":{"标题":{"type":"text","content":"A😀中B","fontLimit":99},
                          "配图":{"type":"image","content":"绿色能源插画","url":"https://example.com/ppt/images/abc"}}}]}
                """);
        SchemaStrategy.validate(output, template, true);
        var data = output.path("slides").get(0).path("data");
        assertEquals("A😀中", data.path("标题").path("content").asText());
        assertEquals(3, data.path("标题").path("fontLimit").asInt());
        assertEquals("https://example.com/ppt/images/abc", data.path("配图").path("url").asText());
    }
}
