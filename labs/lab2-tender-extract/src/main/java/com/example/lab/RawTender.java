package com.example.lab;

import java.util.List;

/**
 * Lab2 模型返回的原始字段——全部按字符串收（LLM 输出本质是文本），
 * 归一化交给 {@link TenderNormalizer}。字段越"弱类型"，测试越好写。
 * @author hengdboy
 */
public class RawTender {

    private String projectName;
    private String purchaserName;
    private String budgetYuan;
    private String submissionDeadline;
    private List<String> requiredQualifications;
    private String performanceBondYuan;

    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }
    public String getPurchaserName() { return purchaserName; }
    public void setPurchaserName(String purchaserName) { this.purchaserName = purchaserName; }
    public String getBudgetYuan() { return budgetYuan; }
    public void setBudgetYuan(String budgetYuan) { this.budgetYuan = budgetYuan; }
    public String getSubmissionDeadline() { return submissionDeadline; }
    public void setSubmissionDeadline(String submissionDeadline) { this.submissionDeadline = submissionDeadline; }
    public List<String> getRequiredQualifications() { return requiredQualifications; }
    public void setRequiredQualifications(List<String> requiredQualifications) { this.requiredQualifications = requiredQualifications; }
    public String getPerformanceBondYuan() { return performanceBondYuan; }
    public void setPerformanceBondYuan(String performanceBondYuan) { this.performanceBondYuan = performanceBondYuan; }
}
