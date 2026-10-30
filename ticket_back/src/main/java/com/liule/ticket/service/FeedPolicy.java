package com.liule.ticket.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Feed 推拉策略：粉丝数不超过阈值的作者在发布时扇出，超过阈值的走读时合并。
 */
@Component
public class FeedPolicy {

    private final int bigVFollowerThreshold;

    public FeedPolicy(@Value("${ticket.feed.big-v-follower-threshold:1000}") int bigVFollowerThreshold) {
        this.bigVFollowerThreshold = bigVFollowerThreshold;
    }

    public boolean shouldFanout(long fans) {
        return fans <= bigVFollowerThreshold;
    }
}
