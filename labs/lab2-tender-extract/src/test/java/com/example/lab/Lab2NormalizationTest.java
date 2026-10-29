package com.example.lab;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lab2 离线测试：不调真实模型——stub 返回"模型最可能给出的脏 JSON"，
 * 归一化器必须把它洗成可比对的强类型记录。8 种真实公告里的写法逐条钉死。
 * @author hengdboy
 */
@DisplayName("Lab2 招投标字段归一化")
class Lab2NormalizationTest {

    private static RawTender raw(String budget, String deadline, List<String> quals, String bond) {
        RawTender r = new RawTender();
        r.setBudgetYuan(budget);
        r.setSubmissionDeadline(deadline);
        r.setRequiredQualifications(quals);
        r.setPerformanceBondYuan(bond);
        return r;
    }

    @Test
    @DisplayName("预算的五种真实写法全部归一到元")
    void budgetVariants() {
        assertThat(TenderNormalizer.parseBudgetYuan("3,500万元")).isEqualTo(35_000_000L);
        assertThat(TenderNormalizer.parseBudgetYuan("280万")).isEqualTo(2_800_000L);
        assertThat(TenderNormalizer.parseBudgetYuan("12000000元")).isEqualTo(12_000_000L);
        assertThat(TenderNormalizer.parseBudgetYuan("450 万元")).isEqualTo(4_500_000L);
        assertThat(TenderNormalizer.parseBudgetYuan("1900万元整")).isEqualTo(19_000_000L);
        assertThat(TenderNormalizer.parseBudgetYuan("")).isNull();
    }

    @Test
    @DisplayName("截止日期：三种写法统一成 ISO，时分丢弃")
    void deadlineVariants() {
        assertThat(TenderNormalizer.parseDeadline("2026年11月20日 09:30")).isEqualTo("2026-11-20");
        assertThat(TenderNormalizer.parseDeadline("2026-12-01")).isEqualTo("2026-12-01");
        assertThat(TenderNormalizer.parseDeadline("2026 年 12 月 15 日")).isEqualTo("2026-12-15");
        assertThat(TenderNormalizer.parseDeadline("2026年11月28日14:00")).isEqualTo("2026-11-28");
    }

    @Test
    @DisplayName("资质列表：去序号、分号再切、丢弃空项")
    void qualificationCleaning() {
        assertThat(TenderNormalizer.parseQualifications(List.of(
                "1. 具有独立法人资格；", "2. 制造商 ISO9001 认证；", "")))
                .containsExactly("具有独立法人资格", "制造商ISO9001认证");
        assertThat(TenderNormalizer.parseQualifications(
                List.of("医疗器械经营许可证；放射性设备使用许可")))
                .containsExactly("医疗器械经营许可证", "放射性设备使用许可");
        assertThat(TenderNormalizer.parseQualifications(null)).isEmpty();
    }

    @Test
    @DisplayName("保证金三分：固定金额 / 不收取=0 / 比例型=null")
    void bondVariants() {
        assertThat(TenderNormalizer.parsePerformanceBond("50万元")).isEqualTo(500_000L);
        assertThat(TenderNormalizer.parsePerformanceBond("8万元")).isEqualTo(80_000L);
        assertThat(TenderNormalizer.parsePerformanceBond("本项目不收取投标保证金")).isZero();
        assertThat(TenderNormalizer.parsePerformanceBond("采用信用承诺制")).isZero();
        assertThat(TenderNormalizer.parsePerformanceBond("合同金额的 5%")).isNull();
    }

    @Test
    @DisplayName("端到端：脏 JSON → 归一化 → 与金标一致")
    void endToEndMessyModelOutput() {
        StubChatModel stub = new StubChatModel("""
                {"projectName":"数据中心 UPS 系统更新改造项目","purchaserName":"浙江云枢数据科技有限公司",
                 "budgetYuan":"450 万元","submissionDeadline":"2026年11月28日14:00",
                 "requiredQualifications":["ISO9001 质量管理体系认证；电力设施承装（修、试）许可"],
                 "performanceBondYuan":"10万元"}
                """);
        TenderNormalizer.TenderRecord rec = new TenderExtractionService(stub).extract("任意公告");
        assertThat(rec.budgetYuan()).isEqualTo(4_500_000L);
        assertThat(rec.submissionDeadline()).isEqualTo("2026-11-28");
        assertThat(rec.requiredQualifications()).containsExactly(
                "ISO9001质量管理体系认证", "电力设施承装（修、试）许可");
        assertThat(rec.performanceBondYuan()).isEqualTo(100_000L);
    }
}
