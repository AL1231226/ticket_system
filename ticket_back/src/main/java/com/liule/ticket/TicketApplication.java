package com.liule.ticket;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan("com.liule.ticket.mapper")
@SpringBootApplication
@EnableAspectJAutoProxy(exposeProxy = true)
// 打开 @Scheduled：超时订单释放靠定时任务扫表
@EnableScheduling

public class TicketApplication {

    public static void main(String[] args) {
        SpringApplication.run(TicketApplication.class, args);
    }

}
