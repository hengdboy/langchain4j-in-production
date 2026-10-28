package com.example.hiring.core;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 候选人状态枚举语义单元测试。
 *
 * @author langchain4j-tutorial
 * @since v0.30
 */
@Slf4j
class CandidateStatusTest {

    @Test
    @DisplayName("【12 状态】枚举齐全")
    void testAllStates() {
        assertThat(CandidateStatus.values()).hasSize(12);
    }

    @Test
    @DisplayName("【活跃】流程中 6 态 isActive，等待回复只认 INVITE_SENT/OFFER_SENT")
    void testActiveAndAwaiting() {
        for (CandidateStatus s : new CandidateStatus[]{
                CandidateStatus.APPLIED, CandidateStatus.SHORTLISTED, CandidateStatus.INVITE_SENT,
                CandidateStatus.INTERVIEW_SCHEDULED, CandidateStatus.INTERVIEWED,
                CandidateStatus.OFFER_SENT}) {
            assertThat(s.isActive()).as(s.name()).isTrue();
        }
        assertThat(CandidateStatus.INVITE_SENT.isAwaitingReply()).isTrue();
        assertThat(CandidateStatus.OFFER_SENT.isAwaitingReply()).isTrue();
        assertThat(CandidateStatus.INTERVIEWED.isAwaitingReply()).isFalse();
    }

    @Test
    @DisplayName("【终态】5 个终态不再流转")
    void testTerminal() {
        for (CandidateStatus s : new CandidateStatus[]{
                CandidateStatus.HIRED, CandidateStatus.REJECTED, CandidateStatus.WITHDRAWN,
                CandidateStatus.CLOSED_NO_RESPONSE, CandidateStatus.EXPIRED}) {
            assertThat(s.isTerminal()).as(s.name()).isTrue();
            assertThat(s.isActive()).as(s.name()).isFalse();
        }
    }

    @Test
    @DisplayName("【待定】ON_HOLD 不是活跃态也不是终态")
    void testOnHold() {
        assertThat(CandidateStatus.ON_HOLD.isOnHold()).isTrue();
        assertThat(CandidateStatus.ON_HOLD.isActive()).isFalse();
        assertThat(CandidateStatus.ON_HOLD.isTerminal()).isFalse();
    }
}
