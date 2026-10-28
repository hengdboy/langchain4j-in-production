package com.example.sdr.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * Ch24 阶段二 Mock 工具：决策人 KP（Key Person）挖掘。
 *
 * <h2>v0.26 角色</h2>
 * <p>{@link com.example.sdr.agent.LeadResearchAgent} 调用此工具从公司域推断可能的 KP 邮箱。
 *
 * <h2>Mock 策略</h2>
 * <p>教学版按"公司名 + 常见邮箱前缀"生成候选 KP。
 * 真实场景可接 LinkedIn Sales Navigator / Apollo / ZoomInfo 等。
 *
 * @author langchain4j-tutorial
 * @since v0.26
 */
@Slf4j
@Component
public class KpFinderTool {

    private static final Map<String, String> MOCK_KPS = Map.of(
            "acme corp", "sarah.chen@acme.com",
            "global trade ltd", "procurement@global-trade.com",
            "shenzhen importer", "liwei@shenzhen-importer.com"
    );

    /**
     * 查找公司决策人（KP）联系信息。
     *
     * @param companyName 公司名
     * @return KpInfo（含 name / email / title）；未找到时 name 为 null
     */
    @Tool("按公司名查找决策人 KP 联系信息（邮箱+职位）")
    public KpInfo find(@P("公司名") String companyName) {
        log.info("🔧 [KpFinder] company={}", companyName);
        if (companyName == null) return new KpInfo(null, null, null);
        String email = MOCK_KPS.get(companyName.trim().toLowerCase(Locale.ROOT));
        if (email == null) return new KpInfo(null, null, null);
        return new KpInfo(
                email.substring(0, email.indexOf('@')),
                email,
                "Procurement Manager"
        );
    }

    /**
     * 决策人联系信息。
     *
     * @param name  姓名（不含邮箱前缀视为 null）
     * @param email KP 邮箱
     * @param title 职位
     */
    public record KpInfo(String name, String email, String title) {}
}