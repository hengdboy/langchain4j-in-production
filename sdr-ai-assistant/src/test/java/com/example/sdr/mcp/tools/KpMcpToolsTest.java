package com.example.sdr.mcp.tools;

import com.example.sdr.tools.KpFinderTool;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ch24 KpMcpTools 单元测试。
 *
 * @author langchain4j-tutorial
 * @since v0.27
 */
@Slf4j
@DisplayName("KpMcpTools 单元测试")
class KpMcpToolsTest {

    private KpMcpTools tools;

    @BeforeEach
    void setup() {
        tools = new KpMcpTools(new KpFinderTool());
    }

    @Test
    @DisplayName("【命中】ACME Corp 找到 KP")
    void testHit() {
        KpFinderTool.KpInfo r = tools.kpFind("ACME Corp");
        assertThat(r.email()).isEqualTo("sarah.chen@acme.com");
        log.info("✅ KP：{} <{}>", r.name(), r.email());
    }

    @Test
    @DisplayName("【未命中】未知公司返回 null 字段")
    void testMiss() {
        KpFinderTool.KpInfo r = tools.kpFind("Unknown Co");
        assertThat(r.email()).isNull();
        log.info("✅ 未命中 KP");
    }
}