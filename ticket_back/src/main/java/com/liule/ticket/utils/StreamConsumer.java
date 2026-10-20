package com.liule.ticket.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis Stream 消费者组的薄封装：建组、阻塞读、ACK、pending 重投、异常退避、启停。
 * <p>
 * 它只认「stream + 组 + 消费者」这套机制，<b>不认识任何业务</b> —— 消息怎么解析、
 * 落库失败要不要补偿，都由构造时传进来的 {@link MessageHandler} 决定。
 * <p>
 * 典型用法：
 * <pre>{@code
 * StreamConsumer c = new StreamConsumer(redisTemplate, "stream.orders", "g1", "c1", this::onMessage);
 * c.start();          // 通常在 @PostConstruct 里
 * c.stop();           // 通常在 @PreDestroy 里
 * }</pre>
 */
@Slf4j
public class StreamConsumer {

    /**
     * 没消息时阻塞的时长。
     * <p>
     * 它同时是 {@link #stop()} 的最长等待时间 —— 停下时线程正卡在 read 上，
     * 要等这次阻塞超时返回，循环才会看到中断标志退出。所以别设太大。
     */
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(2);

    /**
     * 消费异常后的退避时长。
     * <p>
     * 没有退避的话，Redis 一挂这个循环会以每秒几万次的速度抛异常 + 刷日志，
     * CPU 打满、日志爆掉，反而把真正的故障淹了。
     */
    private static final long RETRY_BACKOFF_MS = 1000L;

    /**
     * pending 消息的重投检查间隔。
     * <p>
     * 由主循环每轮检查一次来限频，不用另起定时线程 —— 消费线程本来就因为
     * {@link #BLOCK_TIMEOUT} 每 2 秒醒一次，顺手扫一下 pending 成本极低。
     */
    private static final long PENDING_RETRY_INTERVAL_MS = 5000L;

    private final StringRedisTemplate stringRedisTemplate;
    private final String streamKey;
    private final String group;
    private final String consumerName;
    private final MessageHandler handler;

    private final ExecutorService executor;
    private final AtomicBoolean started = new AtomicBoolean(false);

    public StreamConsumer(StringRedisTemplate stringRedisTemplate,
                          String streamKey,
                          String group,
                          String consumerName,
                          MessageHandler handler) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.streamKey = streamKey;
        this.group = group;
        this.consumerName = consumerName;
        this.handler = handler;
        this.executor = Executors.newSingleThreadExecutor(
                r -> new Thread(r, "stream-consumer-" + streamKey + "-" + consumerName));
    }

    /**
     * 业务侧只需要实现这一个方法。
     */
    @FunctionalInterface
    public interface MessageHandler {
        /**
         * 处理一条消息。
         * <p>
         * <b>正常返回 = 这条消息会被 ACK</b>；<b>抛异常 = 不 ACK</b>，
         * 消息留在 pending 里，等下一次 pending 重投。
         * <p>
         * 所以"注定失败、重试多少次都一样"的情况（比如库存不足）要在实现里
         * 自己消化掉再正常返回 —— 抛出去会被当成暂时性故障反复重试。
         */
        void handle(MapRecord<String, Object, Object> record);
    }

    /**
     * 建组并启动消费线程。重复调用只生效一次。
     */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        ensureConsumerGroup();
        // 启动消费线程
        executor.submit(this::loop);
        log.info("Stream 消费者已启动：{}/{}/{}", streamKey, group, consumerName);
    }

    /**
     * 停掉消费线程。最多等一次阻塞超时（{@link #BLOCK_TIMEOUT}）。
     */
    public void stop() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(BLOCK_TIMEOUT.toSeconds() + 1, TimeUnit.SECONDS)) {
                log.warn("Stream 消费者未在超时内停止：{}/{}", streamKey, consumerName);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("Stream 消费者已停止：{}/{}", streamKey, consumerName);
    }
//消费实现

    private void loop() {

        long nextPendingRetryAt = 0;
        // 在pending里的消息，每隔五秒扫一次重投
        while (!Thread.currentThread().isInterrupted()) {
            // 每隔五秒扫一次 pending
            if (System.currentTimeMillis() >= nextPendingRetryAt) {
                retryPending();
                nextPendingRetryAt = System.currentTimeMillis() + PENDING_RETRY_INTERVAL_MS;
            }
            //正常处理消息
            try {
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from(group, consumerName),
                        StreamReadOptions.empty().count(1).block(BLOCK_TIMEOUT),
                        // ">" = 只读"从未投递给任何消费者"的新消息
                        StreamOffset.create(streamKey, ReadOffset.lastConsumed()));
                // 正常阻塞超时，没消息
                if (list == null || list.isEmpty()) {
                    continue;
                }
                // 处理消息，正常返回 = 这条消息会被 ACK；抛异常 = 不 ACK，留在 pending 里，等下一次 pending 重投
                dispatch(list.get(0));
            } catch (Exception e) {
                log.error("消费消息异常，{} ms 后重试：{}/{}", RETRY_BACKOFF_MS, streamKey, group, e);
                // 线程休眠1秒，然后重试
                sleepQuietly();
            }
        }
    }
    // 处理消息，正常返回 = 这条消息会被 ACK；抛异常 = 不 ACK，留在 pending 里，等下一次 pending 重投
    private void dispatch(MapRecord<String, Object, Object> record) {
        try {
            handler.handle(record);
        } catch (Exception e) {

            log.error("消息处理失败，留在 pending 待重投：id={}", record.getId(), e);
            return;
        }
        acknowledge(record);
    }

    private void acknowledge(MapRecord<String, Object, Object> record) {
        stringRedisTemplate.opsForStream().acknowledge(streamKey, group, record.getId());
    }

    /**
     * 捞回 pending 消息重投。
     * <p>
     * XREADGROUP 的 ">" 只投递从未投递过的新消息；已经投递但没 ACK 的（pending）
     * 它永远不会再给。系统抖动一次就会有一批消息永久卡在 pending 里，
     * 必须用 "0" 显式读回来。
     */
    private void retryPending() {
        try {
            List<MapRecord<String, Object, Object>> pending = stringRedisTemplate.opsForStream().read(
                    Consumer.from(group, consumerName),
                    // 一次捞一小批：单条失败不会挡住后面的，失败的那条下一轮还会再出现
                    StreamReadOptions.empty().count(10),
                    StreamOffset.create(streamKey, ReadOffset.from("0")));
            if (pending == null || pending.isEmpty()) {
                return;
            }
            for (MapRecord<String, Object, Object> record : pending) {
                dispatch(record);
            }
        } catch (Exception e) {
            log.error("重投 pending 消息异常：{}/{}", streamKey, group, e);
        }
    }

    // 确保消费者组存在
    private void ensureConsumerGroup() {
        try {
            stringRedisTemplate.opsForStream().createGroup(streamKey, group);
        } catch (Exception e) {
            // 组已存在（BUSYGROUP）是最常见的情况，正常忽略
            log.info("消费者组 {}/{} 已存在，跳过创建", streamKey, group);
        }
    }

    // 线程休眠，然后重试

    private void sleepQuietly() {
        try {
            Thread.sleep(RETRY_BACKOFF_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
