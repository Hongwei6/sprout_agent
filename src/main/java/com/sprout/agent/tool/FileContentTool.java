package com.sprout.agent.tool;

import com.sprout.agent.entity.UploadedFileEntity;
import com.sprout.agent.service.FileVectorService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public final class FileContentTool {
    private final UploadedFileEntity file;
    private final FileVectorService vectors;

    public FileContentTool(UploadedFileEntity file, FileVectorService vectors) {
        this.file = file;
        this.vectors = vectors;
    }

    @Tool(name = "loadContent", description = "读取当前附件中与问题相关的内容。首次回答前必须调用；需要查找其他信息时可用不同问题再次调用。")
    public String loadContent(
            @ToolParam(description = "当前附件的 fileId") String fileId,
            @ToolParam(description = "要从附件中查找的具体问题") String question) {
        if (!file.getId().equals(fileId)) {
            throw new IllegalArgumentException("只能读取当前附件");
        }
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("查询问题不能为空");
        }
        String content = file.isIndexed() ? vectors.retrieve(fileId, question) : file.getExtractedText();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("附件没有可用内容");
        }
        return content;
    }
}
