package com.xiaoyiyuan.study.ai;

/**
 * 一条对话消息（OpenAI 兼容格式里的 {@code messages[]} 元素）。
 *
 * @param role    角色，如 {@code system} / {@code user} / {@code assistant}
 * @param content 消息正文
 */
public record AiMessage(String role, String content) {
}
