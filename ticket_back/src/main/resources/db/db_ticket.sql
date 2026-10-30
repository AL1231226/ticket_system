-- ============================================================
-- 演出票务平台 · 建库建表 + 种子数据
-- 库名 db_ticket ｜ 字符集 utf8mb4 ｜ 引擎 InnoDB
--
-- 范围：目前 19 张表
--       Plan 1：用户 / 用户详情 / 场馆 / 分类 / 演出 / 想看 / 场次 / 票档
--       Plan 2：订单 / 票 / 限购兜底 / 观演人库 / 订单观演人
--       Plan 3：候补队列 / 站内信
--       Plan 4 社区：动态 / 评论 / 关注 / 点赞
--
-- ⚠ 这个脚本是【可重复执行】的，每张表都先 DROP。开发环境随便跑，
--   但它会清空表里已有的数据，包括手工注册的测试账号。
--
-- ⚠ 【导入时必须指定 utf8mb4】，否则中文注释和种子数据全部变乱码：
--     mysql -uroot -p123456 --default-character-set=utf8mb4 < db_ticket.sql
--   本机 Windows 的 mysql 客户端默认 character_set_client=gbk，
--   而本文件是 UTF-8：不指定的话，客户端会把 UTF-8 字节按 GBK 解释后再存成 UTF-8，
--   变成"缁犺〃"这种双重编码 —— 而且这个过程【不可逆】，部分字符会直接丢成 ?，
--   只能 DROP 重建，没法用 ALTER 把注释改回来。
--   （用 Navicat / DataGrip 这类图形客户端导入没这个问题，它们默认就是 utf8mb4。）
--
-- ⚠ 全部表【不建外键】。引用关系靠「本表索引 + 应用层」维护，
--   理由见《演出票务平台-设计文档》三、关键设计决策 ④。
--   代价：数据库不再拦 venue_id=999 这种不存在的引用，
--   所以种子数据要跑最后的"引用完整性校验"，别让孤儿行溜进去。
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
  nickname    VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '昵称，注册时自动生成 user_xxxxxxxx，前缀见 SystemConstants.USER_NICK_NAME_PREFIX',
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

-- ------------------------------------------------------------
-- 3. 场馆与分类
-- ------------------------------------------------------------
DROP TABLE IF EXISTS tb_venue;
CREATE TABLE tb_venue (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name        VARCHAR(64)  NOT NULL COMMENT '场馆名，如"上海梅赛德斯奔驰文化中心"',
  city        VARCHAR(32)  NOT NULL COMMENT '城市',
  address     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '详细地址',
  x           DOUBLE       NOT NULL DEFAULT 0 COMMENT '经度',
  y           DOUBLE       NOT NULL DEFAULT 0 COMMENT '纬度',
  capacity    INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '容纳人数',
  images      VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '场馆图片，逗号分隔',
  create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_city (city)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='场馆表';

DROP TABLE IF EXISTS tb_show_category;
CREATE TABLE tb_show_category (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name        VARCHAR(32) NOT NULL COMMENT '分类名：演唱会/音乐节/话剧/脱口秀/展览',
  icon        VARCHAR(255) NOT NULL DEFAULT '' COMMENT '分类图标',
  sort        INT NOT NULL DEFAULT 0 COMMENT '排序，越小越靠前',
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='演出分类表';

-- ------------------------------------------------------------
-- 4. 演出
--    city 是冗余字段：列表页几乎全是"按城市筛"，冗余一列省掉一次 JOIN
--    category_id / venue_id 都不建外键，但必须建索引（外键本来会白送的那个）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS tb_show;
CREATE TABLE tb_show (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name        VARCHAR(128) NOT NULL COMMENT '演出名，如"周杰伦嘉年华世界巡回演唱会"',
  artist      VARCHAR(128) NOT NULL DEFAULT '' COMMENT '艺人/演出团体',
  venue_id    BIGINT UNSIGNED NOT NULL COMMENT '场馆ID',
  city        VARCHAR(32)  NOT NULL COMMENT '冗余城市，便于筛选',
  category_id BIGINT UNSIGNED NOT NULL COMMENT '分类ID',
  poster      VARCHAR(512) NOT NULL DEFAULT '' COMMENT '海报（列表大图）',
  images      VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '详情图，逗号分隔',
  start_date  DATE         NOT NULL COMMENT '巡演开始日期',
  end_date    DATE         NOT NULL COMMENT '巡演结束日期',
  want_count  INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '"想看"人数',
  status      TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0下架 1上架',
  description TEXT         NULL COMMENT '演出介绍',
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_city_category (city, category_id),
  KEY idx_venue (venue_id),
  KEY idx_start_date (start_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='演出表';

DROP TABLE IF EXISTS tb_show_want;
CREATE TABLE tb_show_want (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id     BIGINT UNSIGNED NOT NULL,
  show_id     BIGINT UNSIGNED NOT NULL,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  -- 唯一索引在这里代替了外键的"防重复关联"职责：同一人对同一演出只能有一条
  UNIQUE KEY uk_user_show (user_id, show_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='想看记录表';

-- ------------------------------------------------------------
-- 5. 场次与票档
--    演出 → 场次 → 票档 三层。库存挂在【票档】上，不挂演出也不挂场次：
--    周六场的"内场VIP"和周日场的"内场VIP"是两个独立库存单元。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS tb_show_session;
CREATE TABLE tb_show_session (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  show_id        BIGINT UNSIGNED NOT NULL COMMENT '演出ID',
  session_name   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '场次名，如"周六 19:30"',
  session_time   DATETIME     NOT NULL COMMENT '开演时间',
  on_sale_time   DATETIME     NOT NULL COMMENT '本场开票时间',
  purchase_limit TINYINT UNSIGNED NOT NULL DEFAULT 6
                 COMMENT '每账号在本场次的【累计】限购上限。票档表的 max_per_order 是单笔上限，两层独立',
  status         TINYINT UNSIGNED NOT NULL DEFAULT 0
                 COMMENT '0未开票 1预售中 2已开票 3已售罄 4已结束 5已取消',
  create_time  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_show (show_id),
  KEY idx_on_sale_time (on_sale_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='演出场次表';

DROP TABLE IF EXISTS tb_ticket_tier;
CREATE TABLE tb_ticket_tier (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  session_id     BIGINT UNSIGNED NOT NULL COMMENT '场次ID',
  show_id        BIGINT UNSIGNED NOT NULL COMMENT '冗余演出ID，便于查询',
  name           VARCHAR(64)  NOT NULL COMMENT '票档名：内场VIP / 看台A',
  price          DECIMAL(10,2) NOT NULL COMMENT '票价',
  seat_area      VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '座位区域描述',
  seat_type      TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0对号入座 1站票/通票；站票出票时不生成 tb_ticket.seat_no',
  total_stock    INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '总库存',
  stock          INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '可售库存（Redis 秒杀扣的就是这一列）',
  lock_stock     INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '被待支付订单占用；取消/超时归还 stock，支付成功则不再归还',
  max_per_order  TINYINT UNSIGNED NOT NULL DEFAULT 2 COMMENT '单笔订单上限（每单最多买几张）；累计限购在场次表',
  status         TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0下架 1上架',
  create_time    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_session (session_id),
  KEY idx_show (show_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='票档表（库存单元）';

-- ------------------------------------------------------------
-- 6. 订单与票
--    主键【不是自增】—— 订单号和票号都由 RedisIdWorker 生成，
--    一个 64 位 long：高 32 位是秒级时间戳（递增，方便按时间范围查），
--    低 32 位是当天自增序列（同一天内不重复）。自增主键做不到"分布式唯一"。
--
--    tb_ticket_order 上的 idx_status_expire 是给延迟队列扫描准备的：
--    定时任务要按 status + expire_time 捞出到期的待支付订单。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS tb_ticket_order;
CREATE TABLE tb_ticket_order (
  id          BIGINT UNSIGNED NOT NULL COMMENT '订单号（RedisIdWorker 全局唯一ID）',
  user_id     BIGINT UNSIGNED NOT NULL,
  show_id     BIGINT UNSIGNED NOT NULL,
  session_id  BIGINT UNSIGNED NOT NULL,
  tier_id     BIGINT UNSIGNED NOT NULL,
  quantity    TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '购买数量',
  amount      DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '订单金额',
  status      TINYINT UNSIGNED NOT NULL DEFAULT 0
              COMMENT '0待支付 1已支付 2已取消 3已退款 4已失效(超时)',
  phone       VARCHAR(11)  NOT NULL DEFAULT '' COMMENT '联系电话',
  source      TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0正常抢票 1候补补位',
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expire_time DATETIME  NOT NULL COMMENT '支付截止时间（创建 + 15min）',
  pay_time    DATETIME  NULL,
  cancel_time DATETIME  NULL,
  refund_time DATETIME  NULL COMMENT '退款时间；Plan 3 退票接口写入',
  PRIMARY KEY (id),
  KEY idx_user (user_id),
  KEY idx_session (session_id),
  KEY idx_status_expire (status, expire_time) COMMENT '扫描超时订单'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

DROP TABLE IF EXISTS tb_ticket;
CREATE TABLE tb_ticket (
  id          BIGINT UNSIGNED NOT NULL COMMENT '票号（全局唯一ID）',
  order_id    BIGINT UNSIGNED NOT NULL,
  user_id     BIGINT UNSIGNED NOT NULL,
  show_id     BIGINT UNSIGNED NOT NULL,
  session_id  BIGINT UNSIGNED NOT NULL,
  tier_id     BIGINT UNSIGNED NOT NULL,
  seat_no     VARCHAR(32) NOT NULL DEFAULT '' COMMENT '座位号；站票/通票（tier.seat_type=1）留空',
  real_name   VARCHAR(32) NOT NULL DEFAULT '' COMMENT '实际观演人',
  id_card     VARCHAR(32) NOT NULL DEFAULT '',
  qr_code     VARCHAR(128) NOT NULL DEFAULT '' COMMENT '核验二维码（动态刷新）',
  status      TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0未使用 1已核销 2已退',
  check_time  DATETIME NULL COMMENT '核销时间',
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_order (order_id),
  KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='票表';

-- ------------------------------------------------------------
-- 7. 限购兜底表
--    Redis 的 seckill:ordered:{sessionId} 是限购的【主判据】—— 快、原子。
--    但 Redis 可能被清空或重启丢数据，DB 这边要有一份兜底：
--    (user_id, session_id) 唯一索引 + ON DUPLICATE KEY UPDATE 原子累加，
--    和扣库存放在同一个事务里，超过了就抛异常整体回滚。
--
--    ⚠ 这个唯一索引不是"建议加"，是并发安全的全部基础：
--    没有它，同一用户的两个并发下单会各自插一行，累加结果少一半。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS tb_user_purchase;
CREATE TABLE tb_user_purchase (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id     BIGINT UNSIGNED NOT NULL,
  session_id  BIGINT UNSIGNED NOT NULL,
  quantity    INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '该用户在本次场次累计购买数',
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_session (user_id, session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户场次购买汇总（限购兜底）';

-- ------------------------------------------------------------
-- 12. 用户常用观演人（账号级实名信息库）
--     抢票前用户就在这里把观演人录好并完成实名认证；下单时是从这里
--     "勾选"，不是在抢票请求里现填 —— 实名信息不该出现在秒杀热路径上。
--
--     ⚠ 若执行"一个证件只能绑一个账号"的平台策略，把 idx_idcard
--       升级成 UNIQUE KEY 即可（代价是解绑必须走客服）。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS tb_user_attendee;
CREATE TABLE tb_user_attendee (
  id            BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
  user_id       BIGINT UNSIGNED  NOT NULL COMMENT '归属账号',
  real_name     VARCHAR(32)      NOT NULL COMMENT '观演人姓名',
  id_card       VARCHAR(32)      NOT NULL COMMENT '证件号（真实项目应加密存储，这里明文演示）',
  id_card_type  TINYINT UNSIGNED NOT NULL DEFAULT 1
                COMMENT '1身份证 2港澳居民来往内地通行证 3台湾居民来往大陆通行证 4护照',
  verify_status TINYINT UNSIGNED NOT NULL DEFAULT 0
                COMMENT '0未认证 1已认证 2认证失败；强实名场次只有 1 能下单',
  create_time   TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time   TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_idcard (user_id, id_card) COMMENT '同一账号不能重复添加同一个人',
  KEY idx_idcard (id_card)                     COMMENT '客服/风控按证件反查'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户常用观演人（实名信息库）';

-- ------------------------------------------------------------
-- 13. 订单观演人（一票一人）
--     订单买 N 张票就有 N 行，seq 就是第几张。
--
--     ★ uk_session_idcard 是"一证一票"的最终兜底：同一场次、同一个证件
--       只能出现一次。并发插入会直接撞唯一索引失败 —— 不需要分布式锁。
--
--     ⚠ 取消/超时释放时必须 DELETE 这些行，否则证件被永久占用、用户重买不了。
--       想保留历史的话，把唯一键改成 (session_id, id_card, active)：
--       active=1 表示有效占用，NULL 表示已释放（MySQL 唯一索引允许多个 NULL）。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS tb_order_attendee;
CREATE TABLE tb_order_attendee (
  id          BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
  order_id    BIGINT UNSIGNED  NOT NULL COMMENT '订单号',
  session_id  BIGINT UNSIGNED  NOT NULL COMMENT '冗余场次ID；"一证一票"的唯一约束靠它划作用域',
  attendee_id BIGINT UNSIGNED  NOT NULL DEFAULT 0 COMMENT 'tb_user_attendee.id；0=没有关联观演人库',
  seq         TINYINT UNSIGNED NOT NULL COMMENT '第几张票，从 1 开始',
  real_name   VARCHAR(32)      NOT NULL COMMENT '观演人姓名（冗余，出票时直接取）',
  id_card     VARCHAR(32)      NOT NULL COMMENT '证件号（冗余，唯一索引要用它）',
  create_time TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_seq      (order_id, seq)       COMMENT '一单内位置唯一',
  UNIQUE KEY uk_session_idcard (session_id, id_card) COMMENT '一证一票：同场次同证件只能一张',
  KEY idx_idcard (id_card)                           COMMENT '按证件反查'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单观演人（一证一票的唯一约束在这里）';

-- ------------------------------------------------------------
-- 14. 候补队列（Plan 3）
--     ZSet 负责排队顺序，这张表负责业务状态和补位订单关联。
--     ★ uk_user_tier：同一用户同一票档只能排一次；过期后重新加入要
--       更新原行（status→0、join_time 重置），不能直接 insert。
--     ★ status=1 时 order_id 指向补位生成的 source=1 订单。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS tb_waitlist;
CREATE TABLE tb_waitlist (
  id          BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
  user_id     BIGINT UNSIGNED  NOT NULL COMMENT '候补用户',
  tier_id     BIGINT UNSIGNED  NOT NULL COMMENT '候补的票档',
  session_id  BIGINT UNSIGNED  NOT NULL,
  quantity    TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '想候补几张',
  status      TINYINT UNSIGNED NOT NULL DEFAULT 0
              COMMENT '0排队中 1已通知待支付 2已购票 3已过期 4已取消',
  order_id    BIGINT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '补位生成的订单号；0=尚未补位',
  notify_time DATETIME         NULL COMMENT '被通知补位的时间',
  expire_time DATETIME         NULL COMMENT '补位支付截止时间',
  join_time   TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入队时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_tier   (user_id, tier_id)   COMMENT '同票档只能排一次',
  KEY        idx_tier_status (tier_id, status)   COMMENT '取队首/查同票档候补',
  KEY        idx_user_status (user_id, status)   COMMENT '我的候补列表',
  KEY        idx_order       (order_id)          COMMENT '按补位订单反查'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='候补队列表';

-- ------------------------------------------------------------
-- 15. 站内信（Plan 3）
--     候补补位成功时写一条；前端轮询未读数拿红点。
--     不做 WebSocket，避免连接管理复杂度。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS tb_notification;
CREATE TABLE tb_notification (
  id          BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
  user_id     BIGINT UNSIGNED  NOT NULL COMMENT '接收者',
  type        TINYINT UNSIGNED NOT NULL DEFAULT 0
              COMMENT '0系统 1候补补位成功 2开票提醒 3订单状态变更',
  title       VARCHAR(128)     NOT NULL DEFAULT '',
  content     VARCHAR(512)     NOT NULL DEFAULT '',
  biz_id      BIGINT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '关联业务ID（如候补ID/订单ID）',
  is_read     TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0未读 1已读',
  create_time TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_user_read (user_id, is_read) COMMENT '未读数/我的消息'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站内信表';

-- ============================================================
-- 种子数据
-- 量按 Plan 1 的范围来（够演出浏览演示即可）。
-- 设计文档里"30 场演出 / 1000 个用户"是压测用的量级，留到压测阶段再灌。
--
-- 插入顺序有讲究：先父后子。没有外键兜底，顺序错了不会报错，
-- 只会静默产生孤儿行 —— 所以末尾专门有一段引用完整性校验。
-- ============================================================

INSERT INTO tb_venue (id, name, city, address, x, y, capacity, images) VALUES
(1, '星海体育馆',   '上海', '浦东新区世博大道1200号', 121.4894, 31.1878, 18000, 'https://picsum.photos/seed/venue1/800/500'),
(2, '首都艺术中心', '北京', '朝阳区北辰西路8号',     116.3906, 39.9928, 20000, 'https://picsum.photos/seed/venue2/800/500'),
(3, '珠江大剧院',   '广州', '天河区珠江新城华夏路', 113.3245, 23.1170,  3500, 'https://picsum.photos/seed/venue3/800/500'),
(4, '天府音乐厅',   '成都', '武侯区人民南路四段',   104.0665, 30.5723,  2800, 'https://picsum.photos/seed/venue4/800/500');

INSERT INTO tb_show_category (id, name, icon, sort) VALUES
(1, '演唱会', '🎤', 1),
(2, '音乐节', '🎸', 2),
(3, '话剧',   '🎭', 3),
(4, '脱口秀', '🎙️', 4),
(5, '展览',   '🖼️', 5);

INSERT INTO tb_show (id, name, artist, venue_id, city, category_id, poster, images, start_date, end_date, want_count, status, description) VALUES
(1, '星野乐队「夜航」2026巡回演唱会', '星野乐队', 1, '上海', 1,
 'https://picsum.photos/seed/show1/800/1100',
 'https://picsum.photos/seed/show1a/1200/800,https://picsum.photos/seed/show1b/1200/800',
 '2026-11-14', '2026-11-16', 12840, 1,
 '「夜航」是星野乐队成团十年的第六张录音室专辑。本轮巡演将完整呈现新专辑曲目，并重新编排《长夜》《灯塔》等经典作品。舞台采用 360 度环形设计，全息投影与实时灯光联动。'),
(2, '冷杉计划「南方的南方」巡演·广州站', '冷杉计划', 3, '广州', 1,
 'https://picsum.photos/seed/show2/800/1100',
 'https://picsum.photos/seed/show2a/1200/800',
 '2026-12-05', '2026-12-05', 5620, 1,
 '冷杉计划带着第四张专辑回到南方。这次他们放弃了大型场馆，选择在剧院里做一场更靠近观众的演出。'),
(3, '陈屿 2026 个人作品音乐会', '陈屿', 2, '北京', 1,
 'https://picsum.photos/seed/show3/800/1100',
 'https://picsum.photos/seed/show3a/1200/800',
 '2027-01-09', '2027-01-10', 8930, 1,
 '从独立厂牌到主流视野，陈屿用八年时间完成了自己的转身。本场音乐会将与 40 人管弦乐团合作，重新演绎他的创作生涯。'),
(4, '草莓星球音乐节 2026', '多组艺人', 4, '成都', 2,
 'https://picsum.photos/seed/show4/800/1100',
 'https://picsum.photos/seed/show4a/1200/800',
 '2026-11-28', '2026-11-29', 21450, 1,
 '两天三舞台，二十四组乐队。西南地区规模最大的户外音乐节。'),
(5, '话剧《暗涌》', '青禾剧社', 1, '上海', 3,
 'https://picsum.photos/seed/show5/800/1100',
 'https://picsum.photos/seed/show5a/1200/800',
 '2026-11-20', '2026-11-22', 3120, 1,
 '一个关于三兄妹在老宅拆迁前最后一次团聚的故事。'),
(6, '脱口秀专场《今晚不加班》', '笑场喜剧', 2, '北京', 4,
 'https://picsum.photos/seed/show6/800/1100',
 'https://picsum.photos/seed/show6a/1200/800',
 '2026-10-25', '2026-10-26', 1580, 1,
 '打工人专属专场。建议带着工牌来看，现场凭工牌可换一杯酒。'),
(7, '印象派真迹大展', '', 1, '上海', 5,
 'https://picsum.photos/seed/show7/800/1100',
 'https://picsum.photos/seed/show7a/1200/800',
 '2026-10-18', '2027-02-28', 4260, 1,
 '展出 42 件印象派及后印象派真迹，其中 12 件为首次在国内展出。'),
(8, '山海民谣夜', '山海厂牌', 4, '成都', 2,
 'https://picsum.photos/seed/show8/800/1100',
 'https://picsum.photos/seed/show8a/1200/800',
 '2027-02-14', '2027-02-14', 2260, 1,
 '情人节特别场。六组民谣音乐人，一把木吉他，一个不插电的夜晚。'),
(9, '候补测试专场（余票 3 张）', '测试艺人', 1, '上海', 1,
 'https://picsum.photos/seed/waitlist-test/800/1100',
 'https://picsum.photos/seed/waitlist-testa/1200/800',
 '2026-12-19', '2026-12-19', 3, 1,
 '用于验证候补流程的测试演出。场次已开票，票档仅剩 3 张；买完 3 张后票档售罄，详情页会出现加入候补，可继续测试补位、超时和回流。');

-- 场次（演出 1：星野乐队，三场）
-- 场次 1、2 已开票（status=2），用于抢票/支付/票夹演示；
-- 场次 3 保持未开票（status=0），on_sale_time 设为未来时间，用于开票倒计时演示。
INSERT INTO tb_show_session (id, show_id, session_name, session_time, on_sale_time, purchase_limit, status) VALUES
(1, 1, '周六 19:30', '2026-11-14 19:30:00', '2026-10-01 12:00:00', 6, 2),
(2, 1, '周日 19:30', '2026-11-15 19:30:00', '2026-10-01 12:00:00', 6, 2),
(3, 1, '周一 19:00', '2026-11-16 19:00:00', '2026-11-01 12:00:00', 6, 0);

-- 票档（场次 1-3，体育馆对号入座 seat_type=0）
-- stock 初值 = total_stock（还没卖出去一张）
INSERT INTO tb_ticket_tier (session_id, show_id, name, price, seat_area, seat_type, total_stock, stock, max_per_order, status) VALUES
(1, 1, '内场 VIP', 1280.00, '舞台前区', 0,  400,  400, 2, 1),
(1, 1, '看台 A',    880.00, '一层看台', 0, 1500, 1500, 4, 1),
(1, 1, '看台 B',    580.00, '二层看台', 0, 3000, 3000, 4, 1);

INSERT INTO tb_ticket_tier (session_id, show_id, name, price, seat_area, seat_type, total_stock, stock, max_per_order, status) VALUES
(2, 1, '内场 VIP', 1280.00, '舞台前区', 0,  400,  400, 2, 1),
(2, 1, '看台 A',    880.00, '一层看台', 0, 1500, 1500, 4, 1),
(2, 1, '看台 B',    580.00, '二层看台', 0, 3000, 3000, 4, 1);

INSERT INTO tb_ticket_tier (session_id, show_id, name, price, seat_area, seat_type, total_stock, stock, max_per_order, status) VALUES
(3, 1, '内场 VIP', 1180.00, '舞台前区', 0,  400,  400, 2, 1),
(3, 1, '看台 A',    780.00, '一层看台', 0, 1500, 1500, 4, 1),
(3, 1, '看台 B',    480.00, '二层看台', 0, 3000, 3000, 4, 1);

-- 场次（演出 4：草莓星球音乐节，两天）
INSERT INTO tb_show_session (id, show_id, session_name, session_time, on_sale_time, purchase_limit, status) VALUES
(4, 4, 'Day 1 · 11月28日', '2026-11-28 13:00:00', '2026-11-01 20:00:00', 6, 0),
(5, 4, 'Day 2 · 11月29日', '2026-11-29 13:00:00', '2026-11-01 20:00:00', 6, 0);

-- 票档（场次 4-5，音乐节全场站席 seat_type=1 —— 出票时不会生成座位号）
INSERT INTO tb_ticket_tier (session_id, show_id, name, price, seat_area, seat_type, total_stock, stock, max_per_order, status) VALUES
(4, 4, '单日预售票', 380.00, '全场站席', 1, 8000, 8000, 4, 1),
(4, 4, 'VIP 通票',   880.00, '前排区',   1, 1000, 1000, 2, 1),
(5, 4, '单日预售票', 380.00, '全场站席', 1, 8000, 8000, 4, 1),
(5, 4, 'VIP 通票',   880.00, '前排区',   1, 1000, 1000, 2, 1);

-- 演出 9：候补流程手工验收专用
-- 场次 6 已开票（status=2）；票档只有 3 张，买完即售罄，便于手工验证候补。
INSERT INTO tb_show_session (id, show_id, session_name, session_time, on_sale_time, purchase_limit, status) VALUES
(6, 9, '周六 19:30', '2026-12-19 19:30:00', '2026-10-01 12:00:00', 6, 2);

INSERT INTO tb_ticket_tier (session_id, show_id, name, price, seat_area, seat_type, total_stock, stock, max_per_order, status) VALUES
(6, 9, '候补测试票', 199.00, '测试区', 0, 3, 3, 3, 1);

-- ============================================================
-- Plan 4 · 社区：表结构 + 演示种子
-- 活库迁移：
--   sed -n '/plan4-community:start/,/plan4-community:end/p' db_ticket.sql | mysql ...
-- 注意：本块含 DROP TABLE，只能执行一次。
-- ============================================================
-- >>> plan4-community:start

DROP TABLE IF EXISTS tb_post;
CREATE TABLE tb_post (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id     BIGINT UNSIGNED NOT NULL COMMENT '发布者',
  show_id     BIGINT UNSIGNED NULL COMMENT '关联演出，可空',
  session_id  BIGINT UNSIGNED NULL COMMENT '关联场次，可空',
  title       VARCHAR(128)    NOT NULL DEFAULT '' COMMENT '标题，可空',
  content     TEXT            NULL COMMENT '正文',
  images      VARCHAR(2048)   NOT NULL DEFAULT '' COMMENT '图片URL，逗号分隔，最多9张',
  liked       INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '点赞数冗余',
  comments    INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '评论数冗余',
  status      TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0正常 1下架',
  create_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_user_time (user_id, create_time),
  KEY idx_show_time (show_id, create_time),
  KEY idx_status_time (status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='观演动态表';

DROP TABLE IF EXISTS tb_post_comment;
CREATE TABLE tb_post_comment (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  post_id     BIGINT UNSIGNED NOT NULL,
  user_id     BIGINT UNSIGNED NOT NULL,
  parent_id   BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '父评论ID，0为一级评论',
  answer_id   BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '回复目标评论ID，v1恒为0',
  content     VARCHAR(512)    NOT NULL,
  liked       INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT 'v1不接评论点赞',
  status      TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0正常 1折叠（软删除）',
  create_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_post_time (post_id, create_time),
  KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='动态评论表';

DROP TABLE IF EXISTS tb_follow;
CREATE TABLE tb_follow (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id        BIGINT UNSIGNED NOT NULL COMMENT '关注者',
  follow_user_id BIGINT UNSIGNED NOT NULL COMMENT '被关注者',
  create_time    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_follow (user_id, follow_user_id),
  KEY idx_follow_user (follow_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注表';

DROP TABLE IF EXISTS tb_post_like;
CREATE TABLE tb_post_like (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  post_id     BIGINT UNSIGNED NOT NULL,
  user_id     BIGINT UNSIGNED NOT NULL,
  create_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_post_user (post_id, user_id),
  KEY idx_user_time (user_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='动态点赞表';

-- 演示用户：固定 ID 10001–10005，手机号 13800000001–13800000005。
-- 注意：tb_user.id 是 AUTO_INCREMENT，插入后下一个注册用户会从 10006 开始，这是预期行为。
INSERT INTO tb_user (id, phone, password, nickname, icon) VALUES
(10001, '13800000001', '', '夜航员',       'https://picsum.photos/seed/avatar1/200/200'),
(10002, '13800000002', '', '南方观众',     'https://picsum.photos/seed/avatar2/200/200'),
(10003, '13800000003', '', '剧场常客',     'https://picsum.photos/seed/avatar3/200/200'),
(10004, '13800000004', '', '音乐节难民',   'https://picsum.photos/seed/avatar4/200/200'),
(10005, '13800000005', '', '新乐迷',       'https://picsum.photos/seed/avatar5/200/200');

INSERT INTO tb_user_info
  (user_id, city, introduce, gender, credits, level, fans, followee, show_count)
VALUES
(10001, '上海', '看现场的人，记录每一次灯亮。',       1, 300, 3, 4, 3, 12),
(10002, '广州', '南方音乐节常驻，民谣和摇滚都听。',   2, 120, 2, 2, 2,  5),
(10003, '北京', '话剧和脱口秀观众，散场后写 repo。',   1,  80, 2, 2, 2,  7),
(10004, '成都', '音乐节两天全勤，鞋已经废了三双。',   1, 260, 3, 4, 1, 15),
(10005, '上海', '刚入坑，正在补现场。',               2,   0, 1, 0, 4,  0);

INSERT INTO tb_post
  (id, user_id, show_id, session_id, title, content, images, liked, comments, status, create_time) VALUES
(1, 10001, 1, 1, '夜航首场，灯亮那一刻值回票价',
 '星野乐队的状态比录音室还好。环形舞台一开，全场站起来那一下真的起鸡皮疙瘩。安可唱了《灯塔》，散场后耳朵还在嗡。',
 'https://picsum.photos/seed/repo1a/900/700,https://picsum.photos/seed/repo1b/900/700',
 2, 2, 0, '2026-10-24 22:10:00'),
(2, 10002, 4, NULL, '草莓 Day1 穿搭和避坑',
 '下午两点进场最晒，帽子和防晒一定要带。前排站区水卖得很快，建议自己带空杯。晚上气温掉得厉害，薄外套别存包。',
 'https://picsum.photos/seed/repo2a/900/700',
 1, 1, 0, '2026-10-24 18:20:00'),
(3, 10003, 5, NULL, '《暗涌》repo：三个人的老宅',
 '舞台只有一张桌子和三把椅子，但三兄妹的每一句台词都在往老宅的墙里钉钉子。结尾灯暗下来的时候，后排有人在哭。',
 '', 0, 0, 0, '2026-10-23 23:05:00'),
(4, 10004, 4, NULL, '音乐节第二天，鞋废了',
 '从入口走到主舞台要二十分钟，草坪全是泥。建议穿旧鞋，别穿白鞋。但落日那一刻，一切都值。',
 'https://picsum.photos/seed/repo4a/900/700,https://picsum.photos/seed/repo4b/900/700',
 1, 1, 0, '2026-10-23 14:40:00'),
(5, 10001, 7, NULL, '印象派真迹，值得请半天假',
 '工作日下午人少很多。莫奈那几幅的笔触近看完全是另一种东西，建议租讲解器。出口的周边比想象中克制。',
 'https://picsum.photos/seed/repo5a/900/700',
 1, 1, 0, '2026-10-22 16:30:00'),
(6, 10005, 6, NULL, '今晚不加班，笑到拍桌子',
 '打工人专场，每个段子都在工位上发生过。建议坐中间偏后，互动不会太频繁，但笑点一个不落。',
 '', 0, 1, 0, '2026-10-22 00:15:00'),
(7, 10002, 1, 2, '星野乐队歌单预测',
 '按前几站的路透，开场应该是新专辑第一首，中段会有一首不插电。《长夜》大概率在安可，想听《灯塔》的可以期待一下。',
 '', 1, 1, 0, '2026-10-21 21:00:00'),
(8, 10003, 5, NULL, '话剧散场后的夜宵地图',
 '剧场后门那条街有四家开到凌晨的店。散场别急着打车，走十分钟比排队快。',
 '', 0, 0, 0, '2026-10-21 12:10:00'),
(9, 10004, 7, NULL, '展览拍照机位分享',
 '入口右手边那面灰墙光最好，下午三点人最少。展厅里不能用闪光灯，手机记得关。',
 'https://picsum.photos/seed/repo9a/900/700,https://picsum.photos/seed/repo9b/900/700',
 1, 1, 0, '2026-10-20 19:45:00'),
(10, 10005, 1, NULL, '第一次看演唱会，求建议',
 '买了看台 B，想问一下需要提前多久到？散场地铁还开吗？',
 '', 0, 0, 0, '2026-10-20 09:30:00');

INSERT INTO tb_post_comment (id, post_id, user_id, content, status, create_time) VALUES
(1, 1, 10002, '同场！安可那段直接泪目', 0, '2026-10-24 22:30:00'),
(2, 1, 10005, '想问下看台 B 视角怎么样？', 0, '2026-10-24 22:45:00'),
(3, 2, 10001, '求穿搭链接', 0, '2026-10-24 19:00:00'),
(4, 4, 10005, '鞋：我承受了太多', 0, '2026-10-23 15:00:00'),
(5, 5, 10003, '工作日人少吗？', 0, '2026-10-22 17:00:00'),
(6, 6, 10002, '这个专场真的值', 0, '2026-10-22 01:00:00'),
(7, 7, 10004, '希望有《灯塔》', 0, '2026-10-21 21:30:00'),
(8, 9, 10005, '机位收藏了', 0, '2026-10-20 20:00:00');

INSERT INTO tb_post_like (id, post_id, user_id, create_time) VALUES
(1, 1, 10002, '2026-10-24 22:20:00'),
(2, 1, 10003, '2026-10-24 22:25:00'),
(3, 2, 10001, '2026-10-24 18:40:00'),
(4, 4, 10005, '2026-10-23 15:10:00'),
(5, 5, 10002, '2026-10-22 17:10:00'),
(6, 7, 10004, '2026-10-21 21:40:00'),
(7, 9, 10003, '2026-10-20 20:10:00');

INSERT INTO tb_follow (id, user_id, follow_user_id, create_time) VALUES
(1,  10001, 10002, '2026-10-18 10:00:00'),
(2,  10001, 10003, '2026-10-18 10:01:00'),
(3,  10001, 10004, '2026-10-18 10:02:00'),
(4,  10002, 10001, '2026-10-18 10:10:00'),
(5,  10002, 10004, '2026-10-18 10:11:00'),
(6,  10003, 10001, '2026-10-18 10:20:00'),
(7,  10003, 10004, '2026-10-18 10:21:00'),
(8,  10004, 10001, '2026-10-18 10:30:00'),
(9,  10005, 10001, '2026-10-19 09:00:00'),
(10, 10005, 10002, '2026-10-19 09:01:00'),
(11, 10005, 10003, '2026-10-19 09:02:00'),
(12, 10005, 10004, '2026-10-19 09:03:00');

-- <<< plan4-community:end

-- ============================================================
-- 引用完整性校验
-- 全表没有外键（见文件头说明），数据库不会拦孤儿行，
-- 所以手动查一遍。以下每条都必须返回 0 行，有输出就是种子数据写错了 ID。
-- ============================================================

-- 演出引用了不存在的场馆
SELECT '孤儿: tb_show.venue_id' AS chk, s.id, s.venue_id
FROM tb_show s LEFT JOIN tb_venue v ON v.id = s.venue_id
WHERE v.id IS NULL;

-- 演出引用了不存在的分类
SELECT '孤儿: tb_show.category_id' AS chk, s.id, s.category_id
FROM tb_show s LEFT JOIN tb_show_category c ON c.id = s.category_id
WHERE c.id IS NULL;

-- 场次引用了不存在的演出
SELECT '孤儿: tb_show_session.show_id' AS chk, ss.id, ss.show_id
FROM tb_show_session ss LEFT JOIN tb_show s ON s.id = ss.show_id
WHERE s.id IS NULL;

-- 票档引用了不存在的场次 / 演出
SELECT '孤儿: tb_ticket_tier.session_id' AS chk, t.id, t.session_id
FROM tb_ticket_tier t LEFT JOIN tb_show_session ss ON ss.id = t.session_id
WHERE ss.id IS NULL;

SELECT '孤儿: tb_ticket_tier.show_id' AS chk, t.id, t.show_id
FROM tb_ticket_tier t LEFT JOIN tb_show s ON s.id = t.show_id
WHERE s.id IS NULL;

-- 同一场次下，票档的 session_id 和 show_id 必须对得上
-- （tb_ticket_tier.show_id 是冗余列，冗余就会有不一致的风险，冗余了就得校验）
SELECT '不一致: tier.show_id 与 session.show_id' AS chk,
       t.id, t.show_id, ss.show_id AS session_real_show_id
FROM tb_ticket_tier t JOIN tb_show_session ss ON ss.id = t.session_id
WHERE t.show_id <> ss.show_id;

-- 库存自洽：可售 + 待支付占用不可能超过总量
-- （UNSIGNED 已挡住负数，这里只查超量。不存 sold 列，所以这里不对 sold 做检查）
SELECT '异常: stock + lock_stock > total_stock' AS chk,
       id, total_stock, stock, lock_stock
FROM tb_ticket_tier WHERE stock + lock_stock > total_stock;

-- 库存与订单账本对账：tb_ticket_order 才是完整的账本，
-- tb_ticket_tier.lock_stock 只是"待支付订单数量之和"的汇总缓存。
-- 两边对不上说明有库存更新漏了或重复了。应该返回 0 行。
SELECT '不一致: lock_stock 与待支付订单之和' AS chk,
       t.id, t.lock_stock, COALESCE(SUM(o.quantity), 0) AS order_locked
FROM tb_ticket_tier t
LEFT JOIN tb_ticket_order o ON o.tier_id = t.id AND o.status = 0
GROUP BY t.id, t.lock_stock
HAVING t.lock_stock <> order_locked;

-- 有多少票档还处于下架状态（种子里应该全是 1 上架，返回 0 才对）
SELECT '下架票档数（应为 0）' AS chk, COUNT(*) AS cnt
FROM tb_ticket_tier WHERE status = 0;

-- 订单引用了不存在的用户 / 演出 / 场次 / 票档
SELECT '孤儿: tb_ticket_order.user_id' AS chk, o.id, o.user_id
FROM tb_ticket_order o LEFT JOIN tb_user u ON u.id = o.user_id
WHERE u.id IS NULL;

SELECT '孤儿: tb_ticket_order.tier_id' AS chk, o.id, o.tier_id
FROM tb_ticket_order o LEFT JOIN tb_ticket_tier t ON t.id = o.tier_id
WHERE t.id IS NULL;

-- 票引用了不存在的订单
SELECT '孤儿: tb_ticket.order_id' AS chk, t.id, t.order_id
FROM tb_ticket t LEFT JOIN tb_ticket_order o ON o.id = t.order_id
WHERE o.id IS NULL;

-- 限购兜底表引用了不存在的用户
SELECT '孤儿: tb_user_purchase.user_id' AS chk, p.id, p.user_id
FROM tb_user_purchase p LEFT JOIN tb_user u ON u.id = p.user_id
WHERE u.id IS NULL;

-- 观演人库引用了不存在的用户
SELECT '孤儿: tb_user_attendee.user_id' AS chk, a.id, a.user_id
FROM tb_user_attendee a LEFT JOIN tb_user u ON u.id = a.user_id
WHERE u.id IS NULL;

-- 订单观演人引用了不存在的订单
SELECT '孤儿: tb_order_attendee.order_id' AS chk, a.id, a.order_id
FROM tb_order_attendee a LEFT JOIN tb_ticket_order o ON o.id = a.order_id
WHERE o.id IS NULL;

-- 订单观演人引用了不存在的观演人（attendee_id=0 表示没关联观演人库，跳过）
SELECT '孤儿: tb_order_attendee.attendee_id' AS chk, a.id, a.attendee_id
FROM tb_order_attendee a LEFT JOIN tb_user_attendee ua ON ua.id = a.attendee_id
WHERE a.attendee_id <> 0 AND ua.id IS NULL;

-- 订单与票的三条冗余列必须和票档对得上
-- （订单表冗余了 show_id / session_id，票表又冗余了一遍，冗余就要校验）
SELECT '不一致: order.tier_id 与 order.session_id' AS chk,
       o.id, o.session_id, t.session_id AS tier_real_session_id
FROM tb_ticket_order o JOIN tb_ticket_tier t ON t.id = o.tier_id
WHERE o.session_id <> t.session_id;

SELECT '不一致: ticket 与所属订单的 tier_id' AS chk,
       k.id, k.tier_id, o.tier_id AS order_tier_id
FROM tb_ticket k JOIN tb_ticket_order o ON o.id = k.order_id
WHERE k.tier_id <> o.tier_id;

-- ============================================================
-- Plan 4 · 社区完整性校验
-- ============================================================

-- 动态引用了不存在的用户 / 演出
SELECT '孤儿: tb_post.user_id' AS chk, p.id, p.user_id
FROM tb_post p LEFT JOIN tb_user u ON u.id = p.user_id
WHERE u.id IS NULL;

SELECT '孤儿: tb_post.show_id' AS chk, p.id, p.show_id
FROM tb_post p LEFT JOIN tb_show s ON s.id = p.show_id
WHERE p.show_id IS NOT NULL AND s.id IS NULL;

-- 评论 / 关注 / 点赞引用了不存在的行
SELECT '孤儿: tb_post_comment.post_id' AS chk, c.id, c.post_id
FROM tb_post_comment c LEFT JOIN tb_post p ON p.id = c.post_id
WHERE p.id IS NULL;

SELECT '孤儿: tb_post_comment.user_id' AS chk, c.id, c.user_id
FROM tb_post_comment c LEFT JOIN tb_user u ON u.id = c.user_id
WHERE u.id IS NULL;

SELECT '孤儿: tb_follow.user_id' AS chk, f.id, f.user_id
FROM tb_follow f LEFT JOIN tb_user u ON u.id = f.user_id
WHERE u.id IS NULL;

SELECT '孤儿: tb_follow.follow_user_id' AS chk, f.id, f.follow_user_id
FROM tb_follow f LEFT JOIN tb_user u ON u.id = f.follow_user_id
WHERE u.id IS NULL;

SELECT '孤儿: tb_post_like.post_id' AS chk, l.id, l.post_id
FROM tb_post_like l LEFT JOIN tb_post p ON p.id = l.post_id
WHERE p.id IS NULL;

SELECT '孤儿: tb_post_like.user_id' AS chk, l.id, l.user_id
FROM tb_post_like l LEFT JOIN tb_user u ON u.id = l.user_id
WHERE u.id IS NULL;

-- 冗余计数必须与明细一致
SELECT '不一致: tb_post.liked 与点赞明细' AS chk, p.id, p.liked, COUNT(l.id) AS real_liked
FROM tb_post p LEFT JOIN tb_post_like l ON l.post_id = p.id
GROUP BY p.id, p.liked
HAVING p.liked <> real_liked;

SELECT '不一致: tb_post.comments 与评论明细' AS chk, p.id, p.comments, COUNT(c.id) AS real_comments
FROM tb_post p LEFT JOIN tb_post_comment c ON c.post_id = p.id AND c.status = 0
GROUP BY p.id, p.comments
HAVING p.comments <> real_comments;

SELECT '不一致: tb_user_info.fans 与关注明细' AS chk, i.user_id, i.fans, COUNT(f.id) AS real_fans
FROM tb_user_info i LEFT JOIN tb_follow f ON f.follow_user_id = i.user_id
WHERE i.user_id BETWEEN 10001 AND 10005
GROUP BY i.user_id, i.fans
HAVING i.fans <> real_fans;

SELECT '不一致: tb_user_info.followee 与关注明细' AS chk, i.user_id, i.followee, COUNT(f.id) AS real_followee
FROM tb_user_info i LEFT JOIN tb_follow f ON f.user_id = i.user_id
WHERE i.user_id BETWEEN 10001 AND 10005
GROUP BY i.user_id, i.followee
HAVING i.followee <> real_followee;

SELECT '异常: 自己关注自己' AS chk, id, user_id
FROM tb_follow WHERE user_id = follow_user_id;
