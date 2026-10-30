package com.liule.ticket.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 游标只做参数归一：lastId 为空表示第一页，offset 表示边界同毫秒已返回的条数。
 */
class FeedCursorTest {

    @Test
    void emptyCursorStartsFromFirstPage() {
        FeedCursor cursor = FeedCursor.empty();

        assertThat(cursor.isEmpty()).isTrue();
        assertThat(cursor.getLastId()).isNull();
        assertThat(cursor.getOffset()).isZero();
    }

    @Test
    void negativeOffsetIsNormalizedToZero() {
        FeedCursor cursor = FeedCursor.of(10L, -5);

        assertThat(cursor.getLastId()).isEqualTo(10L);
        assertThat(cursor.getOffset()).isZero();
    }

    @Test
    void providedValuesAreKept() {
        FeedCursor cursor = FeedCursor.of(10L, 2);

        assertThat(cursor.isEmpty()).isFalse();
        assertThat(cursor.getLastId()).isEqualTo(10L);
        assertThat(cursor.getOffset()).isEqualTo(2);
    }
}
