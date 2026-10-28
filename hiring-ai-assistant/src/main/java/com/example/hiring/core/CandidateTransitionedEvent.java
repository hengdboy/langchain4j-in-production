package com.example.hiring.core;

/**
 * 事件驱动编排：FSM 状态迁移事件（Spring ApplicationEvent）。
 *
 * <p>{@link CandidateService} 每次 accepted 转换后发布，由
 * {@link com.example.hiring.service.CandidatePipelineService} 监听，触发下一个 Agent：
 * <ul>
 * <li>→ QUALIFIED：触发 {@code OutreachContentAgent} 生成开发信（CONTENT_READY）</li>
 * <li>→ READY：触发 {@link com.example.hiring.tools.MailGateway} 发送首发邮件（EMAIL_SENT）</li>
 * </ul>
 *
 * <p>这样 Agent 不是被脚本"拉着走"，而是被 FSM 状态迁移"推着走"（事件驱动）。
 *
 * @param candidateId    线索 ID
 * @param from      迁移前状态
 * @param to        迁移后状态
 * @param event     触发迁移的事件
 * @author langchain4j-tutorial
 * @since v0.26
 */
public record CandidateTransitionedEvent(Long candidateId, CandidateStatus from, CandidateStatus to, FsmEvent event) {
}
