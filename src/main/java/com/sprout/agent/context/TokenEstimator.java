package com.sprout.agent.context;

import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/**
 * 上下文 token 估算：不引入分词器，中英文分开按字符数折算。
 * 中文约 1.5 字符/token，英文约 4 字符/token，用于判断是否触发压缩，不需要精确值。
 */
public final class TokenEstimator {

    private static final double CHARS_PER_TOKEN_EN = 4.0;
    private static final double CHARS_PER_TOKEN_CJK = 1.5;

    private TokenEstimator() {
    }

    public static int estimateTokens(List<Message> messages) {
        int cjk = 0;
        int nonCjk = 0;
        for (Message message : messages) {
            if (message instanceof AssistantMessage assistant) {
                int[] text = countChars(assistant.getText());
                cjk += text[0];
                nonCjk += text[1];
                if (assistant.getToolCalls() != null) {
                    for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                        int[] args = countChars(call.arguments());
                        cjk += args[0];
                        nonCjk += args[1];
                    }
                }
            } else if (message instanceof ToolResponseMessage toolResponses) {
                for (ToolResponseMessage.ToolResponse response : toolResponses.getResponses()) {
                    int[] data = countChars(response.responseData());
                    cjk += data[0];
                    nonCjk += data[1];
                }
            } else {
                int[] text = countChars(message.getText());
                cjk += text[0];
                nonCjk += text[1];
            }
        }
        return (int) (cjk / CHARS_PER_TOKEN_CJK + nonCjk / CHARS_PER_TOKEN_EN);
    }

    private static int[] countChars(String text) {
        int cjk = 0;
        int nonCjk = 0;
        if (text == null) return new int[] {0, 0};
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isCjk(cp)) cjk++;
            else nonCjk++;
            i += Character.charCount(cp);
        }
        return new int[] {cjk, nonCjk};
    }

    private static boolean isCjk(int cp) {
        return switch (Character.UnicodeScript.of(cp)) {
            case HAN, HIRAGANA, KATAKANA, HANGUL -> true;
            default -> false;
        };
    }
}
