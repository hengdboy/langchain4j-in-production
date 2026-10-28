package com.example.sdr.mcp.tools;

import com.example.sdr.tools.KpFinderTool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Ch24 v0.27 MCP：决策人 KP 挖掘。
 *
 * <p>复用 v0.26 阶段二的 {@link KpFinderTool}。
 *
 * @author langchain4j-tutorial
 * @since v0.27
 */
public class KpMcpTools {

    private final KpFinderTool finder;

    public KpMcpTools(KpFinderTool finder) {
        this.finder = finder;
    }

    @Tool("按公司名查找决策人 KP 联系信息（姓名/邮箱/职位）")
    public KpFinderTool.KpInfo kpFind(@P("公司名") String companyName) {
        return finder.find(companyName);
    }
}