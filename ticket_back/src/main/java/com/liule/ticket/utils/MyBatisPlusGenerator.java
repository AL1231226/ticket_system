package com.liule.ticket.utils;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.generator.FastAutoGenerator;
import com.baomidou.mybatisplus.generator.config.OutputFile;
import com.baomidou.mybatisplus.generator.config.rules.DateType;
import com.baomidou.mybatisplus.generator.engine.VelocityTemplateEngine;
import com.baomidou.mybatisplus.generator.fill.Column;

import java.util.Collections;

/**
 * 代码自动生成器（新版 FastAutoGenerator，适用于 MyBatis-Plus 3.5.x）
 */
public class MyBatisPlusGenerator {
    public static void main(String[] args) {
        String url = "jdbc:mysql://127.0.0.1:3306/db_ticket?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true";
        String username = "root";
        String password = "123456";

        FastAutoGenerator.create(url, username, password)
                // 1、全局配置
                .globalConfig(builder -> {
                    builder.author("龙源lll")                          // 作者
                            .outputDir(System.getProperty("user.dir") + "/src/main/java") // 输出目录
                            .dateType(DateType.ONLY_DATE)             // 时间策略
                            .disableOpenDir();                        // 生成后不自动打开目录
                })
                // 2、包的配置
                .packageConfig(builder -> {
                    builder.parent("com.example.Elderly_care_Platfrom")     // 父包名
                            .entity("entity")                         // Entity 包名
                            .service("service")                       // Service 包名
                            .serviceImpl("service.impl")              // ServiceImpl 包名
                            .mapper("mapper")                         // Mapper 包名
                            .controller("controller")                 // Controller 包名
                            .pathInfo(Collections.singletonMap(
                                    OutputFile.xml,
                                    System.getProperty("user.dir") + "/src/main/resources/mapper")); // XML 输出路径
                })
                // 3、策略配置
                .strategyConfig(builder -> {
                    builder.addInclude("service_item")                        // 设置要映射的表名
                            .entityBuilder()
                                .enableLombok()                       // 自动 Lombok
                                .enableTableFieldAnnotation()         // 字段加 @TableField
                                // 自动填充（字段名需与表里的列一致）
                                .addTableFills(new Column("create_time", FieldFill.INSERT),
                                               new Column("update_time", FieldFill.INSERT_UPDATE))
                            .controllerBuilder()
                                .enableRestStyle()                    // 生成 @RestController
                                .enableHyphenStyle()                  // URL 下划线转中划线
                            .mapperBuilder()
                                .enableMapperAnnotation();            // 生成 @Mapper 注解
                })
                // 4、模板引擎：Velocity（需引入 velocity-engine-core 依赖）
                .templateEngine(new VelocityTemplateEngine())
                .execute();                                           // 执行
    }
}
