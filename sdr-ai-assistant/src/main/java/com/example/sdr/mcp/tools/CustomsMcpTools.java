package com.example.sdr.mcp.tools;

import com.example.sdr.tools.CustomsLookupTool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.List;

/**
 * Ch24 v0.27 MCP：海关进出口记录查询。
 *
 * <p>复用 v0.26 阶段二的 {@link CustomsLookupTool}——同一份业务能力，对内（SDR Agent）和
 * 对外（MCP 客户端：Claude Desktop / Cursor）共用，避免重复实现。
 *
 * <h2>暴露给 MCP 客户端的 @Tool</h2>
 * <ul>
 *   <li>{@link #customsLookup(String, String)} — 按公司名+国家查海关进口记录</li>
 * </ul>
 *
 * <h2>使用示例（Claude Desktop 配置后）</h2>
 * <pre>{@code
 * "查一下 ACME Corp 在美国的海关进口记录"
 * }</pre>
 *
 * @author langchain4j-tutorial
 * @since v0.27
 */
public class CustomsMcpTools {

    private final CustomsLookupTool lookup;

    public CustomsMcpTools(CustomsLookupTool lookup) {
        this.lookup = lookup;
    }

    @Tool("按公司名+国家查海关进出口记录，返回进口品类列表（空列表=无记录）")
    public List<String> customsLookup(@P("公司名") String companyName,
                                       @P("国家代码 ISO 3166-1（如 US/DE/BR）") String country) {
        return lookup.lookup(companyName, country);
    }
}