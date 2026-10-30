package com.liule.ticket.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 推拉结合的分界点：粉丝数不超过阈值才在发布时扇出。
 * 阈值本身可配置，这里直接构造不同阈值验证边界。
 */
class FeedPolicyTest {

    @Test
    void fansAtThresholdStillFanout() {
        FeedPolicy policy = new FeedPolicy(1000);

        assertThat(policy.shouldFanout(1000)).isTrue();
    }

    @Test
    void fansAboveThresholdAreNotPushed() {
        FeedPolicy policy = new FeedPolicy(1000);

        assertThat(policy.shouldFanout(1001)).isFalse();
    }

    @Test
    void zeroFansArePushed() {
        FeedPolicy policy = new FeedPolicy(1000);

        assertThat(policy.shouldFanout(0)).isTrue();
    }
}
