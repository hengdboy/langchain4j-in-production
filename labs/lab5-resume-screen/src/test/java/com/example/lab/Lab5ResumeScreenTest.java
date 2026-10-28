package com.example.lab;

import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lab5 离线测试：脱敏质量、等级归一化、以及"模型只见打码版"的证据链。
 * 桩模型的 fallback 钩子能抓到真实发出去的请求——这是"脱敏在 LLM 之前"的实证方式。
 */
@DisplayName("Lab5 简历初筛：PII 脱敏 + 等级归一化")
class Lab5ResumeScreenTest {

    private static final String JD = "5 年以上 Java，Spring Cloud 微服务，K8s 优先";

    @Test
    @DisplayName("三类 PII 全部打码；输出侧 isClean 过检")
    void piiMasked() {
        String masked = PiiRedactor.redact("电话 13812345678，邮箱 zhoulicheng@example.com，身份证 330106199203154612");
        assertThat(masked).contains("138****5678").contains("z***@example.com").contains("330106********4612");
        assertThat(PiiRedactor.isClean(masked)).isTrue();
        assertThat(PiiRedactor.isClean("联系方式 13812345678")).isFalse();
    }

    @Test
    @DisplayName("等级归一化：A/a/A级/A（高度匹配）都归 A；认不出进 UNKNOWN")
    void gradeNormalization() {
        assertThat(ResumeScreeningService.normalizeGrade("A")).isEqualTo(ResumeScreeningService.Grade.A);
        assertThat(ResumeScreeningService.normalizeGrade("a")).isEqualTo(ResumeScreeningService.Grade.A);
        assertThat(ResumeScreeningService.normalizeGrade("A级")).isEqualTo(ResumeScreeningService.Grade.A);
        assertThat(ResumeScreeningService.normalizeGrade("B（待复核）")).isEqualTo(ResumeScreeningService.Grade.B);
        assertThat(ResumeScreeningService.normalizeGrade("完全不符合")).isEqualTo(ResumeScreeningService.Grade.UNKNOWN);
        assertThat(ResumeScreeningService.normalizeGrade(null)).isEqualTo(ResumeScreeningService.Grade.UNKNOWN);
    }

    @Test
    @DisplayName("证据链：模型收到的请求里是打码版简历，原文 PII 根本没出门")
    void modelNeverSeesRawPii() {
        AtomicReference<ChatRequest> seen = new AtomicReference<>();
        StubChatModel stub = new StubChatModel(req -> {
            seen.set(req);
            return "{\"matchScore\":88,\"grade\":\"A\",\"strengthsSummary\":\"微服务与 K8s 经验扎实\",\"risksSummary\":\"无\",\"recommendInterview\":true}";
        });
        ResumeScreeningService.ScreeningResult r = new ResumeScreeningService(stub)
                .screen(JD, "电话 13812345678，8 年 Java 微服务经验");
        assertThat(r.getNormalizedGrade()).isEqualTo(ResumeScreeningService.Grade.A);
        assertThat(r.getMatchScore()).isEqualTo(88);
        String sentToModel = seen.get().messages().toString();
        assertThat(sentToModel).contains("138****5678");
        assertThat(sentToModel).doesNotContain("13812345678");
    }

    @Test
    @DisplayName("输出兜底：模型在总结里复述了完整手机号 → 整条结果作废")
    void piiInOutputBlocks() {
        StubChatModel stub = new StubChatModel(
                "{\"matchScore\":70,\"grade\":\"B\",\"strengthsSummary\":\"请回拨 13812345678 确认\",\"risksSummary\":\"无\",\"recommendInterview\":true}");
        assertThatThrownBy(() -> new ResumeScreeningService(stub).screen(JD, "电话 13812345678"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PII");
    }

    @Test
    @DisplayName("分数越界裁剪：模型给 150 归 100，给 -5 归 0")
    void scoreClamped() {
        StubChatModel stub = new StubChatModel(
                "{\"matchScore\":150,\"grade\":\"A\",\"strengthsSummary\":\"x\",\"risksSummary\":\"y\",\"recommendInterview\":true}");
        assertThat(new ResumeScreeningService(stub).screen(JD, "简历").getMatchScore()).isEqualTo(100);
        StubChatModel stub2 = new StubChatModel(
                "{\"matchScore\":-5,\"grade\":\"C\",\"strengthsSummary\":\"x\",\"risksSummary\":\"y\",\"recommendInterview\":false}");
        assertThat(new ResumeScreeningService(stub2).screen(JD, "简历").getMatchScore()).isZero();
    }
}
