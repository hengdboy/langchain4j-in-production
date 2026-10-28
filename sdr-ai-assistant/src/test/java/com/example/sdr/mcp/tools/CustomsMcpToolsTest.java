package com.example.sdr.mcp.tools;

import com.example.sdr.tools.CustomsLookupTool;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ch24 CustomsMcpTools 单元测试。
 *
 * @author langchain4j-tutorial
 * @since v0.27
 */
@Slf4j
@DisplayName("CustomsMcpTools 单元测试")
class CustomsMcpToolsTest {

    private CustomsMcpTools tools;

    @BeforeEach
    void setup() {
        tools = new CustomsMcpTools(new CustomsLookupTool());
    }

    @Test
    @DisplayName("【命中】ACME Corp / US 返回进口品类")
    void testHit() {
        List<String> r = tools.customsLookup("ACME Corp", "US");
        assertThat(r).contains("smart home devices");
        log.info("✅ 命中：{}", r);
    }

    @Test
    @DisplayName("【未命中】未知公司返回空列表")
    void testMiss() {
        List<String> r = tools.customsLookup("Unknown Co Ltd", "XX");
        assertThat(r).isEmpty();
        log.info("✅ 未命中：空列表");
    }
}