package com.sprout.agent.agents.ppt;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("ai_ppt_inst")
public class AiPptInst {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String conversationId;
    private String query;
    private PptInstStatus status;
    private PptInstStatus failedStage;
    private String requirement;
    private String searchInfo;
    private String templateCode;
    private String outline;
    private String pptSchema;
    private String error;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public PptInstStatus getStatus() { return status; }
    public void setStatus(PptInstStatus status) { this.status = status; }
    public PptInstStatus getFailedStage() { return failedStage; }
    public void setFailedStage(PptInstStatus failedStage) { this.failedStage = failedStage; }
    public String getRequirement() { return requirement; }
    public void setRequirement(String requirement) { this.requirement = requirement; }
    public String getSearchInfo() { return searchInfo; }
    public void setSearchInfo(String searchInfo) { this.searchInfo = searchInfo; }
    public String getTemplateCode() { return templateCode; }
    public void setTemplateCode(String templateCode) { this.templateCode = templateCode; }
    public String getOutline() { return outline; }
    public void setOutline(String outline) { this.outline = outline; }
    public String getPptSchema() { return pptSchema; }
    public void setPptSchema(String pptSchema) { this.pptSchema = pptSchema; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
