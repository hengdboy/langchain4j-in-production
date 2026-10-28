package com.example.sdr.mcp.tools;

import com.example.sdr.tools.SmtpVerifyTool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Ch24 v0.27 MCP：邮箱 SMTP 验真。
 *
 * <p>复用 v0.26 阶段二的 {@link SmtpVerifyTool}。
 *
 * @author langchain4j-tutorial
 * @since v0.27
 */
public class SmtpMcpTools {

    private final SmtpVerifyTool verify;

    public SmtpMcpTools(SmtpVerifyTool verify) {
        this.verify = verify;
    }

    @Tool("邮箱 SMTP 验真：返回 deliverable / undeliverable / unknown 三态及理由")
    public SmtpVerifyTool.SmtpStatus smtpVerify(@P("待验真邮箱") String email) {
        return verify.verify(email);
    }
}