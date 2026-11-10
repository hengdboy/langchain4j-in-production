package com.example.assistant.engineering;

import dev.langchain4j.exception.JsonException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具参数错误回给模型的文案。
 *
 * <p>只回稳定分类和参数名，原始异常消息留在日志里。异常消息是写给开发者的，
 * 可能带 SQL、下游响应或参数原值；直接回灌给模型会进聊天记忆，最后出现在用户看到的回答里。
 *
 * @author hengdboy
 */
public final class ToolErrorMessages {

    private static final Pattern MISSING_PARAM =
            Pattern.compile("Required parameter \"([^\"]+)\"");
    private static final Pattern BAD_ARG =
            Pattern.compile("Argument \"([^\"]+)\"");

    private ToolErrorMessages() {
    }

    public static String argumentError(String toolName, Throwable error) {
        return "工具 [" + toolName + "] 参数不合法：" + argumentHint(error)
                + "。请修正参数后重新调用；若缺少必要信息，先向用户询问，不要猜测或编造。";
    }

    static String argumentHint(Throwable error) {
        String message = error.getMessage() == null ? "" : error.getMessage();

        Matcher missing = MISSING_PARAM.matcher(message);
        if (missing.find()) {
            return "缺少必填参数 \"" + missing.group(1) + "\"";
        }
        Matcher bad = BAD_ARG.matcher(message);
        if (bad.find()) {
            return "参数 \"" + bad.group(1) + "\" 的类型或取值不符合要求";
        }
        if (isJsonError(error)) {
            return "参数必须是合法的 JSON 对象";
        }
        return "参数无法解析";
    }

    private static boolean isJsonError(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof JsonException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
