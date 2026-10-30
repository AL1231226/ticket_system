package com.liule.ticket;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan("com.liule.ticket.mapper")
@SpringBootApplication
@EnableAspectJAutoProxy(exposeProxy = true)
// 打开 @Scheduled：支付超时走 ZSet 延迟队列（10s）+ 扫表对账（30s）
@EnableScheduling

public class TicketApplication {

    public static void main(String[] args) {
        SpringApplication.run(TicketApplication.class, args);
    }

}
