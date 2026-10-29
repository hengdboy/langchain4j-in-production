package com.example.hiring.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.StateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineConfigurationConfigurer;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;
import org.springframework.statemachine.guard.Guard;

import java.util.EnumSet;

/**
 * FSM 状态机配置（12 状态 + 13 事件 + 15 转换）。
 *
 * <p>本类是招聘 pipeline 流转的单一权威定义——任何状态转换规则变更必须改这里，
 * 并同步更新 ARCHITECTURE.md 的状态表。
 *
 * <p>设计要点：
 * <ul>
 * <li>@EnableStateMachineFactory 注册 StateMachineFactory Bean，供
 * CandidateStateMachineService 按 machineId 取实例（@EnableStateMachine 只注册
 * 单例机器，不提供 Factory Bean）</li>
 * <li>15 条转换里 11 条带 Guard：评估结论、回复决定、评价结论、offer 答复、
 * HR 复核这五类多路分支全部由 Guard 按业务值路由</li>
 * <li>REPLY_TIMEOUT_48H / OFFER_TIMEOUT_5D 不进状态机（自循环坑，见类注释），
 * 由 CandidateService 业务前置判断</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableStateMachineFactory
public class CandidateStateMachineConfig
        extends StateMachineConfigurerAdapter<CandidateStatus, FsmEvent> {

    @Override
    public void configure(StateMachineConfigurationConfigurer<CandidateStatus, FsmEvent> config)
            throws Exception {
        super.configure(config);
    }

    @Override
    public void configure(StateMachineStateConfigurer<CandidateStatus, FsmEvent> states)
            throws Exception {
        states.withStates()
                .initial(CandidateStatus.APPLIED)
                .states(EnumSet.allOf(CandidateStatus.class))
                .end(CandidateStatus.HIRED);
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<CandidateStatus, FsmEvent> tx)
            throws Exception {

        // ============================================================
        // 阶段一：APPLIED 的评估三分支（3 条，Guard 按 verdict 路由）
        // ============================================================

        tx.withExternal()
                .source(CandidateStatus.APPLIED).target(CandidateStatus.SHORTLISTED)
                .event(FsmEvent.SCREEN_PASS)
                .guard(verdictGuard("SHORTLIST"))
                .action(ctx -> log.info("[FSM] APPLIED → SHORTLISTED  candidateId={}",
                        ctx.getStateMachine().getId()))
                .and()

                .withExternal()
                .source(CandidateStatus.APPLIED).target(CandidateStatus.ON_HOLD)
                .event(FsmEvent.SCREEN_REVIEW)
                .guard(verdictGuard("REVIEW"))
                .action(ctx -> log.info("[FSM] APPLIED → ON_HOLD  candidateId={}",
                        ctx.getStateMachine().getId()))
                .and()

                .withExternal()
                .source(CandidateStatus.APPLIED).target(CandidateStatus.REJECTED)
                .event(FsmEvent.SCREEN_FAIL)
                .guard(verdictGuard("REJECT"))
                .action(ctx -> log.info("[FSM] APPLIED → REJECTED  candidateId={}",
                        ctx.getStateMachine().getId()))
                .and()

                // ============================================================
                // 阶段一点五：ON_HOLD 的人工复核（2 条，Guard 按 humanApproved 路由）
                // ============================================================

                .withExternal()
                .source(CandidateStatus.ON_HOLD).target(CandidateStatus.SHORTLISTED)
                .event(FsmEvent.SCREEN_PASS)
                .guard(humanApprovedGuard(true))
                .action(ctx -> log.info("[FSM] ON_HOLD → SHORTLISTED (HR 复核通过)  candidateId={}",
                        ctx.getStateMachine().getId()))
                .and()

                .withExternal()
                .source(CandidateStatus.ON_HOLD).target(CandidateStatus.REJECTED)
                .event(FsmEvent.SCREEN_FAIL)
                .guard(humanApprovedGuard(false))
                .action(ctx -> log.info("[FSM] ON_HOLD → REJECTED (HR 复核否决)  candidateId={}",
                        ctx.getStateMachine().getId()))
                .and()

                // ============================================================
                // 阶段二：约面邀请（1 条）
                // ============================================================

                .withExternal()
                .source(CandidateStatus.SHORTLISTED).target(CandidateStatus.INVITE_SENT)
                .event(FsmEvent.INVITE_SENT)
                .action(ctx -> log.info("[FSM] SHORTLISTED → INVITE_SENT  candidateId={}",
                        ctx.getStateMachine().getId()))
                .and()

                // ============================================================
                // 阶段三：INVITE_SENT 的回复分支（2 条）+ 无回复兜底（1 条）
                // ============================================================

                .withExternal()
                .source(CandidateStatus.INVITE_SENT).target(CandidateStatus.INTERVIEW_SCHEDULED)
                .event(FsmEvent.INVITE_REPLIED)
                .guard(replyDecisionGuard("confirmed"))
                .action(ctx -> log.info("[FSM] INVITE_SENT → INTERVIEW_SCHEDULED  candidateId={}",
                        ctx.getStateMachine().getId()))
                .and()

                .withExternal()
                .source(CandidateStatus.INVITE_SENT).target(CandidateStatus.WITHDRAWN)
                .event(FsmEvent.INVITE_REPLIED)
                .guard(replyDecisionGuard("declined"))
                .action(ctx -> log.info("[FSM] INVITE_SENT → WITHDRAWN (候选人放弃)  candidateId={}",
                        ctx.getStateMachine().getId()))
                .and()

                // 与上一版项目同一坑位：REPLY_TIMEOUT_48H 不做自循环，追发由
                // CandidateService.handleReplyTimeout 前置判断，轮次用尽才发本事件
                .withExternal()
                .source(CandidateStatus.INVITE_SENT).target(CandidateStatus.CLOSED_NO_RESPONSE)
                .event(FsmEvent.MAX_ROUNDS_REACHED)
                .action(ctx -> log.info("[FSM] INVITE_SENT → CLOSED_NO_RESPONSE  candidateId={}",
                        ctx.getStateMachine().getId()))
                .and()

                // ============================================================
                // 阶段四：面试（2 条）
                // ============================================================

                .withExternal()
                .source(CandidateStatus.INTERVIEW_SCHEDULED).target(CandidateStatus.INTERVIEWED)
                .event(FsmEvent.INTERVIEW_DONE)
                .action(ctx -> log.info("[FSM] INTERVIEW_SCHEDULED → INTERVIEWED  candidateId={}",
                        ctx.getStateMachine().getId()))
                .and()

                .withExternal()
                .source(CandidateStatus.INTERVIEWED).target(CandidateStatus.OFFER_SENT)
                .event(FsmEvent.EVAL_PASS)
                .guard(evalGuard("PASS"))
                .action(ctx -> log.info("[FSM] INTERVIEWED → OFFER_SENT  candidateId={}",
                        ctx.getStateMachine().getId()))
                .and()

                .withExternal()
                .source(CandidateStatus.INTERVIEWED).target(CandidateStatus.REJECTED)
                .event(FsmEvent.EVAL_FAIL)
                .guard(evalGuard("FAIL"))
                .action(ctx -> log.info("[FSM] INTERVIEWED → REJECTED (面试未通过)  candidateId={}",
                        ctx.getStateMachine().getId()))
                .and()

                // ============================================================
                // 阶段五：offer（3 条）
                // ============================================================

                .withExternal()
                .source(CandidateStatus.OFFER_SENT).target(CandidateStatus.HIRED)
                .event(FsmEvent.OFFER_REPLIED)
                .guard(offerGuard("accepted"))
                .action(ctx -> log.info("[FSM] OFFER_SENT → HIRED  candidateId={}",
                        ctx.getStateMachine().getId()))
                .and()

                .withExternal()
                .source(CandidateStatus.OFFER_SENT).target(CandidateStatus.WITHDRAWN)
                .event(FsmEvent.OFFER_REPLIED)
                .guard(offerGuard("declined"))
                .action(ctx -> log.info("[FSM] OFFER_SENT → WITHDRAWN (婉拒 offer)  candidateId={}",
                        ctx.getStateMachine().getId()))
                .and()

                .withExternal()
                .source(CandidateStatus.OFFER_SENT).target(CandidateStatus.EXPIRED)
                .event(FsmEvent.OFFER_EXPIRED)
                .action(ctx -> log.info("[FSM] OFFER_SENT → EXPIRED (催办后仍无答复)  candidateId={}",
                        ctx.getStateMachine().getId()));
    }

    // ============================================================
    // Guard 条件（ARCHITECTURE.md  表格中的 Guard 列）
    // ============================================================

    /** Guard：评估结论等于指定值（SHORTLIST / REVIEW / REJECT） */
    private Guard<CandidateStatus, FsmEvent> verdictGuard(String expected) {
        return ctx -> expected.equals(ctx.getExtendedState().get("verdict", String.class));
    }

    /** Guard：HR 复核结果（true → 通过，false → 否决） */
    private Guard<CandidateStatus, FsmEvent> humanApprovedGuard(boolean expected) {
        return ctx -> {
            Boolean approved = ctx.getExtendedState().get("humanApproved", Boolean.class);
            return approved != null && approved == expected;
        };
    }

    /** Guard：候选人回复决定（confirmed / declined） */
    private Guard<CandidateStatus, FsmEvent> replyDecisionGuard(String expected) {
        return ctx -> expected.equals(ctx.getExtendedState().get("replyDecision", String.class));
    }

    /** Guard：面试评价结论（PASS / FAIL） */
    private Guard<CandidateStatus, FsmEvent> evalGuard(String expected) {
        return ctx -> expected.equals(ctx.getExtendedState().get("evalOutcome", String.class));
    }

    /** Guard：offer 答复（accepted / declined） */
    private Guard<CandidateStatus, FsmEvent> offerGuard(String expected) {
        return ctx -> expected.equals(ctx.getExtendedState().get("offerDecision", String.class));
    }
}
