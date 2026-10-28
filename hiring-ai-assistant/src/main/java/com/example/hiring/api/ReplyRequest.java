package com.example.hiring.api;

import jakarta.validation.constraints.NotBlank;

/**
 * API 层：候选人回复请求体。
 *
 * <p>同一个入口服务两个阶段，pipeline 按候选人当前状态路由：
 * INVITE_SENT → 分类约面答复（confirmed → 约面 / declined → WITHDRAWN）；
 * OFFER_SENT → 分类 offer 答复（accepted → HIRED / declined → WITHDRAWN）。
 *
 * @param text 回复正文
 * @author langchain4j-tutorial
 * @since v0.30
 */
public record ReplyRequest(
        @NotBlank(message = "text is required") String text) {
}
