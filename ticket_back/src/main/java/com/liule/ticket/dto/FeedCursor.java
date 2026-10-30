package com.liule.ticket.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Feed 分页游标。
 * <p>
 * lastId 是上一页最后一条动态，offset 是边界时间（同一毫秒）里已经返回过的条数。
 * 只用 lastId 会在同毫秒多条动态时漏页或重复，offset 用来把边界内已返回的部分跳过去。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FeedCursor {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long lastId;

    private Integer offset;

    public static FeedCursor empty() {
        return new FeedCursor(null, 0);
    }

    public static FeedCursor of(Long lastId, Integer offset) {
        int safeOffset = offset == null || offset < 0 ? 0 : offset;
        return new FeedCursor(lastId, safeOffset);
    }

    public boolean isEmpty() {
        return lastId == null;
    }
}
