package com.example.sdr.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * Ch24 阶段二 Mock 工具：海关进出口记录查询。
 *
 * <h2>v0.26 角色</h2>
 * <p>{@link com.example.sdr.agent.LeadResearchAgent} 通过
 * {@code AgenticServices.agentBuilder().tools(...)} 挂载此工具，
 * 让 LLM 按需调用并基于结果判定背调是否通过。
 *
 * <h2>Mock 数据策略</h2>
 * <p>教学版用预置公司名单表做"模拟海关记录"——
 * 命中表内公司返回历史进口记录（置信度 0.85+）；
 * 未命中返回空列表（视作新客 / 弱背调）。
 *
 * <p>生产应替换为真实海关 API（如 ImportGenius / Panjiva / 海关总署）。
 *
 * @author langchain4j-tutorial
 * @since v0.26
 */
@Slf4j
@Component
public class CustomsLookupTool {

    /** 模拟数据：已知有采购记录的公司 → 进口品类 */
    private static final Map<String, List<String>> MOCK_RECORDS = Map.of(
            "acme corp", List.of("smart home devices", "IoT gateway"),
            "global trade ltd", List.of("consumer electronics"),
            "shenzhen importer", List.of("smart home devices", "LED lighting")
    );

    /**
     * 查询公司海关进出口记录。
     *
     * @param companyName 公司名（用于模糊匹配）
     * @param country     国家代码（ISO 3166-1 alpha-2，如 US / DE / BR）
     * @return 进口品类列表；空列表表示无记录
     */
    @Tool("查询公司海关进出口记录（按公司名+国家）。返回进口品类列表；空列表表示无记录")
    public List<String> lookup(@P("公司名") String companyName, @P("国家代码ISO 3166-1") String country) {
        log.info("🔧 [CustomsLookup] company={} country={}", companyName, country);
        if (companyName == null) return List.of();
        return MOCK_RECORDS.getOrDefault(companyName.trim().toLowerCase(Locale.ROOT), List.of());
    }
}