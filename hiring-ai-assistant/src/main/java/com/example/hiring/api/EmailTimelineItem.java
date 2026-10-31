package com.example.hiring.api;

import java.time.Instant;

/**
 * API 层：候选人邮件往来时间线条目（sent_email + received_email 合并视图，按时间升序）。
 *
 * <p>演示页「过程剧本」用：邀约信、offer 信全文来自 sent 侧，
 * 候选人回复原文与分类结论来自 received 侧。
 *
 * @param direction  sent=系统发出 / received=候选人回复
 * @param roundNo    发送轮次（0=事务邮件，1=首发邀请，2=追发）；回复侧无轮次
 * @param subject    邮件主题
 * @param body       邮件正文
 * @param decision   回复分类结论（confirmed/declined 等）；发出邮件无此字段
 * @param status     发送状态（sent/bounced/opened）；回复侧无此字段
 * @param fromEmail  发件人
 * @param toEmail    收件人（回复侧没有独立收件人字段）
 * @param occurredAt 发生时间
 * @author hengdboy
 * @since v0.31
 */
public record EmailTimelineItem(
        String direction,
        Integer roundNo,
        String subject,
        String body,
        String decision,
        String status,
        String fromEmail,
        String toEmail,
        Instant occurredAt) {
}
