package com.liule.ticket.utils;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 事务提交后执行 Redis 镜像写入。
 * <p>
 * DB 是事实来源：事务回滚时不写 Redis；Redis 失败只由调用方记日志，不回滚业务。
 */
public final class AfterCommit {

    private AfterCommit() {
    }

    public static void run(Runnable task) {
        if (task == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            task.run();
                        }
                    });
        } else {
            task.run();
        }
    }
}
