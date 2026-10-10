-- ============================================================
-- 演出票务平台 · 建库建表
-- 本次范围：登录模块用到的两张用户表
-- 库名 db_ticket ｜ 字符集 utf8mb4 ｜ 引擎 InnoDB
-- ============================================================

CREATE DATABASE IF NOT EXISTS db_ticket
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE db_ticket;

-- ------------------------------------------------------------
-- 1. 用户（登录主体）
--    只放认证与展示必需的东西；业务属性全在 tb_user_info
-- ------------------------------------------------------------
DROP TABLE IF EXISTS tb_user;
CREATE TABLE tb_user (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  phone       VARCHAR(11)  NOT NULL COMMENT '手机号，登录账号',
  password    VARCHAR(128) NOT NULL DEFAULT '' COMMENT '密码（加盐MD5，形如 {salt}@{digest}）',
  nickname    VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '昵称，注册时自动生成"乐迷_xxxxxxxx"',
  icon        VARCHAR(255) NOT NULL DEFAULT '' COMMENT '头像URL',
  create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ------------------------------------------------------------
-- 2. 用户详情（注册时同事务预建一行，不做懒创建）
--    主键就是 user_id，一张表恒一行，所以没必要再建代理主键
-- ------------------------------------------------------------
DROP TABLE IF EXISTS tb_user_info;
CREATE TABLE tb_user_info (
  user_id     BIGINT UNSIGNED  NOT NULL COMMENT '用户ID，与 tb_user.id 一对一',
  city        VARCHAR(32)      NOT NULL DEFAULT '' COMMENT '常用城市，默认取注册地',
  introduce   VARCHAR(128)     NOT NULL DEFAULT '' COMMENT '个人简介',
  gender      TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0未知 1男 2女',
  birthday    DATE             NULL COMMENT '生日',
  credits     INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '积分，签到/消费累积',
  level       TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '会员等级，1起步',
  fans        INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '粉丝数（冗余计数）',
  followee    INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '关注数（冗余计数）',
  show_count  INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '观演场次（足迹）',
  create_time TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户详情表';
