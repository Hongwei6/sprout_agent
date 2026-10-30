package com.sprout.agent.agents.ppt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.List;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class PptTemplateCatalog {

    private final List<Template> templates;

    public PptTemplateCatalog() throws IOException {
        // Only templates physically present in the repository are selectable.
        var resource = new ClassPathResource("templates/ai.pptx");
        try (var input = resource.getInputStream(); var presentation = new XMLSlideShow(input)) {
            List<XSLFSlide> slides = presentation.getSlides();
            if (slides.isEmpty()) throw new IOException("PPT 模板没有幻灯片");
            ObjectNode schema = new ObjectMapper().createObjectNode();
            var pages = schema.putArray("slides");
            StringBuilder structure = new StringBuilder();
            for (int i = 0; i < slides.size(); i++) {
                String type = i == 0 ? "COVER" : i == slides.size() - 1 ? "END"
                        : i == 1 ? "CATALOG" : i == 2 ? "COMPARE" : "CONTENT";
                var page = pages.addObject();
                page.put("pageType", type);
                page.put("templatePageIndex", i + 1);
                ObjectNode fields = page.putObject("data");
                for (XSLFShape shape : slides.get(i).getShapes()) addShape(fields, shape);
                List<String> names = new java.util.ArrayList<>();
                fields.fieldNames().forEachRemaining(names::add);
                structure.append("页面 ").append(i + 1).append(" (类型 ").append(type).append(")：")
                        .append(String.join("、", names)).append('\n');
            }
            this.templates = List.of(new Template("ai", "AI 演示模板", slides.size(), structure.toString(), schema));
        }
    }
    private static void addShape(ObjectNode fields, XSLFShape shape) {
        if (shape instanceof XSLFGroupShape group) {
            for (XSLFShape nested : group.getShapes()) addShape(fields, nested);
            return;
        }
        String name = shape.getShapeName();
        if (name == null || name.isBlank() || fields.has(name)) return;
        if (shape instanceof XSLFTextShape text && !text.getText().isBlank()) {
            int limit = Math.max(8, Math.min(120, text.getText().strip().length() * 2));
            fields.putObject(name).put("type", "text").put("fontLimit", limit);
        } else if (shape instanceof XSLFPictureShape) {
            fields.putObject(name).put("type", "image");
        }
    }


    public List<Template> available() {
        return templates;
    }

    public Template require(String code) {
        return templates.stream().filter(template -> template.code().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("PPT 模板不存在：" + code));
    }

    public record Template(String code, String name, int slideCount, String structure, JsonNode schema) {
    }
}
