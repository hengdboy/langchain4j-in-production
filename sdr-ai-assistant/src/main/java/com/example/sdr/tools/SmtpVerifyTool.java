package com.example.sdr.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Ch24 阶段二 Mock 工具：邮箱 SMTP 验真。
 *
 * <h2>v0.26 角色</h2>
 * <p>{@link com.example.sdr.agent.LeadResearchAgent} 调用此工具判定邮箱是否可达。
 *
 * <h2>Mock 规则</h2>
 * <ul>
 *   <li>邮箱域在白名单内 → deliverable=true</li>
 *   <li>含无效字符 / 一次性域名 → deliverable=false</li>
 *   <li>其他 → 返回中性结果（catch-all 不可判定），由 LLM 综合判定</li>
 * </ul>
 *
 * <p>生产应替换为真实 SMTP 验真服务（如 ZeroBounce / NeverBounce）。
 *
 * @author langchain4j-tutorial
 * @since v0.26
 */
@Slf4j
@Component
public class SmtpVerifyTool {

    /** 教学白名单：常见企业邮箱域（实际可达） */
    private static final Set<String> DELIVERABLE_DOMAINS = Set.of(
            "gmail.com", "outlook.com", "yahoo.com", "hotmail.com",
            "acme.com", "global-trade.com", "example.com", "qq.com", "163.com"
    );

    /** 已知无效域名（教学用） */
    private static final Set<String> UNDELIVERABLE_DOMAINS = Set.of(
            "mailinator.com", "tempmail.com", "10minutemail.com", "trashmail.com"
    );

    /**
     * 验真邮箱可达性。
     *
     * @param email 待验真的邮箱
     * @return SmtpStatus（deliverable / undeliverable / unknown）
     */
    @Tool("邮箱 SMTP 验真：返回 deliverable / undeliverable / unknown 三态之一")
    public SmtpStatus verify(@P("待验真邮箱") String email) {
        log.info("🔧 [SmtpVerify] email={}", email);
        if (email == null || !email.contains("@")) {
            return new SmtpStatus(false, "invalid format", "undeliverable");
        }
        String domain = email.substring(email.indexOf('@') + 1).trim().toLowerCase(Locale.ROOT);
        if (DELIVERABLE_DOMAINS.contains(domain)) {
            return new SmtpStatus(true, "in deliverable domains whitelist", "deliverable");
        }
        if (UNDELIVERABLE_DOMAINS.contains(domain)) {
            return new SmtpStatus(false, "in disposable domains blocklist", "undeliverable");
        }
        return new SmtpStatus(false, "catch-all domain, cannot definitively verify", "unknown");
    }

    /**
     * SMTP 验真结果。
     *
     * @param deliverable 是否可达（unknown 时为 false）
     * @param reason      判定理由
     * @param status      三态：deliverable / undeliverable / unknown
     */
    public record SmtpStatus(boolean deliverable, String reason, String status) {}
}