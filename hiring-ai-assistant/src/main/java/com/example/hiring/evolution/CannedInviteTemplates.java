package com.example.hiring.evolution;

import java.util.List;
import java.util.Map;

/**
 * v0.30 约面邀请模板（按 arm_id 选 tone + cta）。
 *
 * <h2>臂空间</h2>
 * <p>6 个臂：{@code tone ∈ {formal, warm, concise}} × {@code cta ∈ {calendar-link, reply-slot}}
 * × {@code channel=email}。每个臂一个固定模板，canned 模式直接渲染；
 * 真实模式下臂信息作为 prompt 参数交给 {@code InviteContentAgent}。
 *
 * <p>哪种话术回复率高，由 Bandit 用数据说话。
 *
 * @author langchain4j-tutorial
 * @since v0.30
 */
public final class CannedInviteTemplates {

    private CannedInviteTemplates() {}

    /** 6 个臂 ID（与 BanditOptimizer.knownArms 一致） */
    public static final List<String> ARM_IDS = List.of(
            "formal:calendar-link:email", "warm:calendar-link:email", "concise:calendar-link:email",
            "formal:reply-slot:email", "warm:reply-slot:email", "concise:reply-slot:email"
    );

    /** arm_id → {subject, body}（占位：{{name}} / {{job}} / {{company}}） */
    private static final Map<String, Template> TEMPLATES = Map.ofEntries(
            Map.entry("formal:calendar-link:email", new Template(
                    "{{company}}「{{job}}」职位面试邀请",
                    """
                            {{name}} 您好：

                            感谢您应聘 {{company}}「{{job}}」职位。您的经历与岗位要求匹配度较高，
                            我们诚挚邀请您参加技术面试。请点击选座链接自选面试时间：
                            https://cal.example.com/xinghang-interview（教学版链接）。

                            —— {{company}} 招聘团队
                            """.strip())),
            Map.entry("warm:calendar-link:email", new Template(
                    "{{name}} 你好呀，{{job}} 面试时间你来挑",
                    """
                            {{name}} 你好！

                            你的简历我们很喜欢！想邀请你来 {{company}} 聊聊「{{job}}」这个岗位。
                            时间你挑：https://cal.example.com/xinghang-interview（教学版链接），
                            团队小伙伴都盼着见到你～

                            —— {{company}} 招聘团队
                            """.strip())),
            Map.entry("concise:calendar-link:email", new Template(
                    "{{company}} {{job}} 面试邀约",
                    """
                            {{name}}：
                            {{company}}「{{job}}」面试邀请。选时间：
                            https://cal.example.com/xinghang-interview（教学版链接）。
                            —— {{company}} 招聘
                            """.strip())),
            Map.entry("formal:reply-slot:email", new Template(
                    "{{company}}「{{job}}」职位面试邀请",
                    """
                            {{name}} 您好：

                            感谢您应聘 {{company}}「{{job}}」职位，我们诚挚邀请您参加技术面试。
                            请回复本邮件并提供两个您方便的时间段，我们将据此安排面试。

                            —— {{company}} 招聘团队
                            """.strip())),
            Map.entry("warm:reply-slot:email", new Template(
                    "{{name}}，来聊聊「{{job}}」吧",
                    """
                            {{name}} 你好！

                            看完你的简历，团队很想约你聊聊「{{job}}」。回复两个你方便的时间段，
                            我们来对齐～期待见到你！

                            —— {{company}} 招聘团队
                            """.strip())),
            Map.entry("concise:reply-slot:email", new Template(
                    "{{company}} {{job}} 面试邀约",
                    """
                            {{name}}：
                            {{company}}「{{job}}」面试邀请。回复两个可选时间段即可。
                            —— {{company}} 招聘
                            """.strip()))
    );

    /** 按 arm_id 取模板（不在 6 个内抛 IllegalArgumentException）。 */
    public static Template forArm(String armId) {
        Template t = TEMPLATES.get(armId);
        if (t == null) {
            throw new IllegalArgumentException("unknown arm: " + armId);
        }
        return t;
    }

    /** 简单模板：subject + body（带占位符） */
    public record Template(String subject, String body) {}

    /** 占位符替换（极简；教学版不引入 StringSubstitutor 依赖） */
    public static Template render(Template t, String name, String job, String company) {
        return new Template(
                t.subject().replace("{{name}}", name == null ? "候选人" : name)
                        .replace("{{job}}", job == null ? "" : job)
                        .replace("{{company}}", company == null ? "" : company),
                t.body().replace("{{name}}", name == null ? "候选人" : name)
                        .replace("{{job}}", job == null ? "" : job)
                        .replace("{{company}}", company == null ? "" : company)
        );
    }
}
