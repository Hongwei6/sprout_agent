package com.sprout.agent.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ChatTurnDetailMapper {

    @Insert("""
            INSERT INTO chat_turn_details
                (assistant_message_id, thinking, tools_json, references_json, first_response_ms, total_response_ms)
            VALUES (#{messageId}, #{thinking}, #{toolsJson}, #{referencesJson}, #{firstResponseMs}, #{totalResponseMs})
            """)
    int insert(@Param("messageId") Long messageId,
            @Param("thinking") String thinking,
            @Param("toolsJson") String toolsJson,
            @Param("referencesJson") String referencesJson,
            @Param("firstResponseMs") Long firstResponseMs,
            @Param("totalResponseMs") long totalResponseMs);

    @Select("""
            SELECT d.assistant_message_id, d.references_json
            FROM chat_turn_details d
            JOIN chat_messages m ON m.id = d.assistant_message_id
            WHERE m.conversation_id = #{conversationId}
            """)
    @ConstructorArgs({
            @Arg(column = "assistant_message_id", javaType = Long.class),
            @Arg(column = "references_json", javaType = String.class)
    })
    List<ReferenceRow> findReferences(@Param("conversationId") String conversationId);

    record ReferenceRow(Long assistantMessageId, String referencesJson) {
    }
}
