-- =====================================================================
-- 店小约（Shop Booking Agent）建表脚本
-- 作用：建库 + 10 张空表。不含任何业务数据（无示例店、无测试账号）。
-- 开发者首次部署时执行一次；此后所有业务数据一律由前端页面产生。
-- 历史脚本永不改写，结构变更一律新增编号脚本（02_xxx.sql）。
-- =====================================================================

CREATE DATABASE IF NOT EXISTS shop_booking
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE shop_booking;

-- 1. 用户表：三类角色 customer / staff / owner
CREATE TABLE IF NOT EXISTS users (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  username      VARCHAR(20)  NOT NULL COMMENT '用户名 2-20 位中文/字母/数字/下划线',
  password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希',
  role          VARCHAR(20)  NOT NULL COMMENT 'customer / staff / owner',
  shop_id       BIGINT       NULL COMMENT '所属店铺（顾客为下单店铺，店员/老板为本店）',
  phone         VARCHAR(20)  NULL COMMENT '顾客手机号（用于按手机号归并历史预约）',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username),
  KEY idx_role (role)
) ENGINE = InnoDB COMMENT = '用户与角色';

-- 2. 店铺表：一店一档，营业时间按星期存 JSON
CREATE TABLE IF NOT EXISTS shops (
  id                      BIGINT       NOT NULL AUTO_INCREMENT,
  name                    VARCHAR(100) NOT NULL COMMENT '店铺名称',
  address                 VARCHAR(255) NULL COMMENT '店铺地址',
  phone                   VARCHAR(30)  NULL COMMENT '联系电话',
  open_hours              TEXT         NULL COMMENT '按星期营业时间 JSON：{"MONDAY":{"open":"10:00","close":"22:00","closed":false},...}',
  slot_granularity_minutes INT         NOT NULL DEFAULT 60 COMMENT '档期粒度（分钟）',
  timezone                VARCHAR(50)  NOT NULL DEFAULT 'Asia/Shanghai',
  setup_completed         TINYINT      NOT NULL DEFAULT 0 COMMENT '开店向导是否完成',
  notification_enabled    TINYINT      NOT NULL DEFAULT 1 COMMENT '通知开关',
  created_at              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
) ENGINE = InnoDB COMMENT = '店铺配置';

-- 3. 服务项目表：桌型 / 服务项
CREATE TABLE IF NOT EXISTS service_items (
  id              BIGINT        NOT NULL AUTO_INCREMENT,
  shop_id         BIGINT        NOT NULL,
  name            VARCHAR(100)  NOT NULL COMMENT '名称，如：小包间 6 人',
  capacity_per_unit INT         NOT NULL DEFAULT 1 COMMENT '单单元容纳人数（几位/桌/次）',
  unit_count      INT           NOT NULL DEFAULT 1 COMMENT '并行单元数量（几张桌/几位技师）',
  duration_minutes INT          NOT NULL DEFAULT 60 COMMENT '单次时长（分钟）',
  price           DECIMAL(10,2) NULL COMMENT '价格（可空）',
  advance_days    INT           NOT NULL DEFAULT 30 COMMENT '可预约提前天数',
  cancel_policy   VARCHAR(200)  NULL COMMENT '取消政策说明',
  sort_order      INT           NOT NULL DEFAULT 0,
  status          VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / INACTIVE',
  created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_shop (shop_id)
) ENGINE = InnoDB COMMENT = '服务项目';

-- 4. 档期库存表：并发控制落点（行级版本乐观锁）
CREATE TABLE IF NOT EXISTS time_slots (
  id              BIGINT      NOT NULL AUTO_INCREMENT,
  shop_id         BIGINT      NOT NULL,
  service_item_id BIGINT      NOT NULL,
  slot_date       DATE        NOT NULL,
  start_time      TIME        NOT NULL,
  end_time        TIME        NOT NULL,
  capacity        INT         NOT NULL DEFAULT 1 COMMENT '容量（单元数）',
  booked_count    INT         NOT NULL DEFAULT 0 COMMENT '已占数',
  version         INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  status          VARCHAR(20) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN / CLOSED',
  created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_item_date (service_item_id, slot_date, start_time),
  KEY idx_shop_date (shop_id, slot_date)
) ENGINE = InnoDB COMMENT = '档期库存';

-- 5. 预约单表：锁座/确认/取消/核销状态机
-- 说明：唯一索引建在生成列 active_session_key 上——仅生效中（HELD/CONFIRMED/CHECKED_IN）
-- 的预约才占用 (slot_id, 顾客) 唯一键；已取消/过期行的键为 NULL 不参与唯一约束，
-- 因此顾客取消后可以重新预约同一时段、也可以改期到曾取消过的时段，防重复占座的保证不变。
CREATE TABLE IF NOT EXISTS bookings (
  id                  BIGINT       NOT NULL AUTO_INCREMENT,
  booking_no          VARCHAR(32)  NOT NULL COMMENT '预约单号，如 SB20260915A1B2C3',
  shop_id             BIGINT       NOT NULL,
  slot_id             BIGINT       NOT NULL,
  service_item_id     BIGINT       NOT NULL COMMENT '冗余，便于查询',
  customer_session_id VARCHAR(64)  NOT NULL COMMENT '顾客会话标识（游客 sessionId 或 u:{userId}）',
  active_session_key  VARCHAR(70)  GENERATED ALWAYS AS (
                        CASE WHEN status IN ('HELD','CONFIRMED','CHECKED_IN')
                             THEN customer_session_id ELSE NULL END) STORED,
  customer_user_id    BIGINT       NULL COMMENT '登录顾客的用户 ID',
  customer_name       VARCHAR(50)  NULL,
  customer_phone      VARCHAR(20)  NULL COMMENT '明文仅存本表且受权限保护，展示一律脱敏',
  party_size          INT          NOT NULL DEFAULT 1,
  status              VARCHAR(20)  NOT NULL COMMENT 'HELD/CONFIRMED/CHECKED_IN/NO_SHOW/CANCELLED/EXPIRED',
  hold_expire_at      DATETIME     NULL COMMENT '锁座到期时间',
  remark              VARCHAR(500) NULL,
  source              VARCHAR(20)  NOT NULL DEFAULT 'CHAT' COMMENT 'CHAT / MANUAL',
  cancel_reason       VARCHAR(200) NULL,
  checked_in_at       DATETIME     NULL,
  created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_booking_no (booking_no),
  UNIQUE KEY uk_slot_customer (slot_id, active_session_key) COMMENT '生效预约防同一顾客重复占座，数据库兜底',
  KEY idx_shop_status (shop_id, status),
  KEY idx_phone (customer_phone),
  KEY idx_user (customer_user_id)
) ENGINE = InnoDB COMMENT = '预约单';

-- 6. 会话表
CREATE TABLE IF NOT EXISTS conversations (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  shop_id         BIGINT       NOT NULL,
  session_key     VARCHAR(64)  NOT NULL COMMENT '前端会话标识（localStorage UUID）',
  user_id         BIGINT       NULL COMMENT '登录顾客用户 ID',
  status          VARCHAR(20)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN / CLOSED',
  turn_count      INT          NOT NULL DEFAULT 0,
  total_tokens    BIGINT       NOT NULL DEFAULT 0,
  summary         TEXT         NULL COMMENT '滚动摘要（超轮次后压缩早期消息）',
  last_message_at DATETIME     NULL,
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_shop (shop_id, status),
  KEY idx_session (session_key)
) ENGINE = InnoDB COMMENT = '会话';

-- 7. 消息表
CREATE TABLE IF NOT EXISTS chat_messages (
  id              BIGINT      NOT NULL AUTO_INCREMENT,
  conversation_id BIGINT      NOT NULL,
  role            VARCHAR(20) NOT NULL COMMENT 'user / assistant / tool',
  content         TEXT        NOT NULL,
  tool_name       VARCHAR(50) NULL COMMENT 'role=tool 时记录工具名',
  created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_conv (conversation_id)
) ENGINE = InnoDB COMMENT = '会话消息';

-- 8. Agent 轨迹表：每一轮调了什么工具、返回什么、耗时
CREATE TABLE IF NOT EXISTS agent_steps (
  id              BIGINT      NOT NULL AUTO_INCREMENT,
  conversation_id BIGINT      NOT NULL,
  turn            INT         NOT NULL,
  tool_name       VARCHAR(50) NOT NULL,
  args            TEXT        NULL,
  result          TEXT        NULL,
  duration_ms     INT         NOT NULL DEFAULT 0,
  hit_idempotent  TINYINT     NOT NULL DEFAULT 0,
  success         TINYINT     NOT NULL DEFAULT 1,
  created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_conv (conversation_id)
) ENGINE = InnoDB COMMENT = 'Agent 轨迹';

-- 9. 知识库表：店铺 FAQ
CREATE TABLE IF NOT EXISTS knowledge_items (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  shop_id       BIGINT       NOT NULL,
  question      VARCHAR(200) NOT NULL,
  answer        TEXT         NOT NULL,
  keywords      VARCHAR(200) NULL COMMENT '匹配关键词，逗号分隔',
  category      VARCHAR(50)  NULL,
  hit_count     INT          NOT NULL DEFAULT 0,
  status        VARCHAR(10)  NOT NULL DEFAULT 'ON' COMMENT 'ON / OFF（下架后 AI 不再引用）',
  source        VARCHAR(20)  NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL / TEMPLATE / FEEDBACK（未接住回流）',
  escalation_id BIGINT       NULL COMMENT '来源工单',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_shop (shop_id, status)
) ENGINE = InnoDB COMMENT = '店铺 FAQ 知识库';

-- 10. 转人工工单表
CREATE TABLE IF NOT EXISTS escalations (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  shop_id          BIGINT       NOT NULL,
  conversation_id  BIGINT       NULL,
  reason           VARCHAR(200) NOT NULL COMMENT '转人工原因',
  summary          TEXT         NULL COMMENT '会话摘要，顾客不用重讲',
  collected_fields TEXT         NULL COMMENT '已收集字段 JSON（姓名/手机/意向时段等）',
  status           VARCHAR(20)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN / PROCESSING / RESOLVED',
  handler_id       BIGINT       NULL COMMENT '处理人',
  resolution_note  VARCHAR(500) NULL,
  resolved_at      DATETIME     NULL,
  created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_shop_status (shop_id, status)
) ENGINE = InnoDB COMMENT = '转人工工单';
