package com.sprout.agent.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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
}
