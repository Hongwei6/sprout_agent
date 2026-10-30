package com.sprout.agent.agents.ppt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sprout.agent.agents.BaseAgent.ChatEvent;
import com.sprout.agent.prompts.PptBuilderPrompts;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
class SchemaStrategy implements PptStateStrategy {
    private final ObjectMapper mapper;
    private final PptImageService images;

    SchemaStrategy(ObjectMapper mapper, PptImageService images) {
        this.mapper = mapper;
        this.images = images;
    }

    @Override public PptInstStatus getTargetStatus() { return PptInstStatus.SCHEMA; }

    @Override public Flux<ChatEvent> execute(AiPptInst inst, String query, PptStateStrategyContext context) {
        return Flux.just(new ChatEvent("thinking", "正在生成 PPT 页面 Schema"))
                .concatWith(Flux.defer(() -> {
                    PptTemplateCatalog.Template template = context.templates().require(inst.getTemplateCode());
                    ObjectNode schema;
                    boolean cached = inst.getPptSchema() != null && !inst.getPptSchema().isBlank();
                    try {
                        String json = cached ? inst.getPptSchema() : context.client().prompt()
                                .user(PptBuilderPrompts.getSchemaGenerationPrompt(
                                        template.schema().toPrettyString(), inst.getOutline())).call().content();
                        if (json == null) throw new IllegalStateException("模型未生成 PPT Schema");
                        schema = (ObjectNode) mapper.readTree(json.strip().replaceAll("(?s)^```(?:json)?\\s*|\\s*```$", ""));
                    } catch (com.fasterxml.jackson.core.JsonProcessingException | ClassCastException ex) {
                        return Flux.error(new IllegalStateException("模型返回的 PPT Schema 不是合法 JSON 对象", ex));
                    }
                    validate(schema, template.schema(), cached);
                    long pages = java.util.regex.Pattern.compile("(?m)^--- Page \\d+ ---").matcher(inst.getOutline()).results().count();
                    if (pages > 0 && pages != schema.path("slides").size()) {
                        return Flux.error(new IllegalArgumentException("Schema 页数与已确认的大纲不一致"));
                    }
                    context.instances().updatePptSchema(inst.getId(), schema.toString(), PptInstStatus.SCHEMA);
                    return Flux.just(new ChatEvent("thinking", "Schema 已校验，正在生成并保存配图"))
                            .concatWith(Flux.create(emitter -> {
                                try {
                                    images.fillMissingImages(schema, inst.getConversationId(), updated ->
                                            context.instances().updatePptSchema(inst.getId(), updated.toString(), PptInstStatus.SCHEMA),
                                            stage -> emitter.next(new ChatEvent("thinking", stage)));
                                    if (!context.active().getAsBoolean()) {
                                        emitter.complete();
                                        return;
                                    }
                                    context.instances().updatePptSchema(inst.getId(), schema.toString(), PptInstStatus.RENDER);
                                    emitter.next(new ChatEvent("thinking", "Schema 和配图已保存；进入 PPT 渲染阶段"));
                                    emitter.complete();
                                } catch (Exception ex) {
                                    emitter.error(ex);
                                }
                            }));
                }));
    }

    static void validate(ObjectNode schema, JsonNode template, boolean cached) {
        JsonNode slides = schema.path("slides");
        JsonNode pages = template.path("slides");
        if (!slides.isArray() || slides.isEmpty() || slides.size() > 50) {
            throw new IllegalArgumentException("Schema slides 必须包含 1 至 50 页");
        }
        for (int i = 0; i < slides.size(); i++) {
            JsonNode slide = slides.get(i);
            if (!slide.path("pageDesc").isTextual() || slide.path("pageDesc").asText().isBlank()) {
                throw new IllegalArgumentException("第 " + (i + 1) + " 页缺少页面描述");
            }
            int index = slide.path("templatePageIndex").asInt(0);
            if (index < 1 || index > pages.size() || !slide.path("pageType").asText("")
                    .equals(pages.get(index - 1).path("pageType").asText())) {
                throw new IllegalArgumentException("第 " + (i + 1) + " 页引用了不存在或类型不匹配的模板页面");
            }
            JsonNode data = slide.path("data");
            JsonNode defined = pages.get(index - 1).path("data");
            if (!data.isObject() || data.size() != defined.size()) {
                throw new IllegalArgumentException("第 " + (i + 1) + " 页字段数量与模板不匹配");
            }
            for (var fields = defined.fields(); fields.hasNext();) {
                var expected = fields.next();
                JsonNode actual = data.path(expected.getKey());
                if (!actual.isObject() || !actual.path("type").asText("")
                        .equals(expected.getValue().path("type").asText())) {
                    throw new IllegalArgumentException("Schema 字段不匹配：" + expected.getKey());
                }
                if (!actual.path("content").isTextual()) {
                    throw new IllegalArgumentException("Schema 字段缺少文本或图片提示词：" + expected.getKey());
                }
                if ("text".equals(actual.path("type").asText())) {
                    int max = expected.getValue().path("fontLimit").asInt();
                    String text = actual.path("content").asText("");
                    if (max <= 0) throw new IllegalArgumentException("模板文字上限无效");
                    int length = text.codePointCount(0, text.length());
                    if (length > max) text = text.substring(0, text.offsetByCodePoints(0, max));
                    ((ObjectNode) actual).put("content", text).put("fontLimit", max);
                } else {
                    if (actual.path("content").asText("").isBlank()) {
                        throw new IllegalArgumentException("图片字段缺少生成提示词：" + expected.getKey());
                    }
                    if (!cached) ((ObjectNode) actual).put("url", "");
                }
            }
            // Reject unknown shape names instead of silently dropping user-visible content at render time.
            Set<String> names = new HashSet<>();
            defined.fieldNames().forEachRemaining(names::add);
            data.fieldNames().forEachRemaining(name -> {
                if (!names.contains(name)) throw new IllegalArgumentException("模板中没有形状：" + name);
            });
        }
    }
}
