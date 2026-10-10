package com.liule.ticket.utils;

public interface Ilock {
    boolean tryLock(long timeout);
    void unlock();
}
