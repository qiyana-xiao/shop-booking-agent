-- H2（MySQL 模式）测试建表脚本：与 database/sql/01_schema.sql 结构等价。
-- 生产用 MySQL 原版脚本，此文件仅测试使用（去掉了 ENGINE/COMMENT/ON UPDATE 等 MySQL 方言语法）。

CREATE TABLE IF NOT EXISTS users (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  username      VARCHAR(20)  NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  role          VARCHAR(20)  NOT NULL,
  shop_id       BIGINT       NULL,
  phone         VARCHAR(20)  NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_username UNIQUE (username)
);
CREATE INDEX IF NOT EXISTS idx_role ON users (role);

CREATE TABLE IF NOT EXISTS shops (
  id                       BIGINT       NOT NULL AUTO_INCREMENT,
  name                     VARCHAR(100) NOT NULL,
  address                  VARCHAR(255) NULL,
  phone                    VARCHAR(30)  NULL,
  open_hours               TEXT         NULL,
  slot_granularity_minutes INT          NOT NULL DEFAULT 60,
  timezone                 VARCHAR(50)  NOT NULL DEFAULT 'Asia/Shanghai',
  setup_completed          TINYINT      NOT NULL DEFAULT 0,
  notification_enabled     TINYINT      NOT NULL DEFAULT 1,
  created_at               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS service_items (
  id                BIGINT        NOT NULL AUTO_INCREMENT,
  shop_id           BIGINT        NOT NULL,
  name              VARCHAR(100)  NOT NULL,
  capacity_per_unit INT           NOT NULL DEFAULT 1,
  unit_count        INT           NOT NULL DEFAULT 1,
  duration_minutes  INT           NOT NULL DEFAULT 60,
  price             DECIMAL(10,2) NULL,
  advance_days      INT           NOT NULL DEFAULT 30,
  cancel_policy     VARCHAR(200)  NULL,
  sort_order        INT           NOT NULL DEFAULT 0,
  status            VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
  created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_shop ON service_items (shop_id);

CREATE TABLE IF NOT EXISTS time_slots (
  id              BIGINT      NOT NULL AUTO_INCREMENT,
  shop_id         BIGINT      NOT NULL,
  service_item_id BIGINT      NOT NULL,
  slot_date       DATE        NOT NULL,
  start_time      TIME        NOT NULL,
  end_time        TIME        NOT NULL,
  capacity        INT         NOT NULL DEFAULT 1,
  booked_count    INT         NOT NULL DEFAULT 0,
  version         INT         NOT NULL DEFAULT 0,
  status          VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_item_date ON time_slots (service_item_id, slot_date, start_time);
CREATE INDEX IF NOT EXISTS idx_shop_date ON time_slots (shop_id, slot_date);

-- 生成列 + 唯一索引：仅生效中的预约占用 (slot_id, 顾客) 唯一键（NULL 不参与唯一约束）
CREATE TABLE IF NOT EXISTS bookings (
  id                  BIGINT      NOT NULL AUTO_INCREMENT,
  booking_no          VARCHAR(32) NOT NULL,
  shop_id             BIGINT      NOT NULL,
  slot_id             BIGINT      NOT NULL,
  service_item_id     BIGINT      NOT NULL,
  customer_session_id VARCHAR(64) NOT NULL,
  active_session_key  VARCHAR(70) AS (CASE WHEN status IN ('HELD','CONFIRMED','CHECKED_IN')
                                           THEN customer_session_id ELSE NULL END),
  customer_user_id    BIGINT      NULL,
  customer_name       VARCHAR(50) NULL,
  customer_phone      VARCHAR(20) NULL,
  party_size          INT         NOT NULL DEFAULT 1,
  status              VARCHAR(20) NOT NULL,
  hold_expire_at      DATETIME    NULL,
  remark              VARCHAR(500) NULL,
  source              VARCHAR(20) NOT NULL DEFAULT 'CHAT',
  cancel_reason       VARCHAR(200) NULL,
  checked_in_at       DATETIME    NULL,
  created_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_booking_no UNIQUE (booking_no),
  CONSTRAINT uk_slot_customer UNIQUE (slot_id, active_session_key)
);
CREATE INDEX IF NOT EXISTS idx_shop_status ON bookings (shop_id, status);
CREATE INDEX IF NOT EXISTS idx_phone ON bookings (customer_phone);
CREATE INDEX IF NOT EXISTS idx_user ON bookings (customer_user_id);

CREATE TABLE IF NOT EXISTS conversations (
  id              BIGINT      NOT NULL AUTO_INCREMENT,
  shop_id         BIGINT      NOT NULL,
  session_key     VARCHAR(64) NOT NULL,
  user_id         BIGINT      NULL,
  status          VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  turn_count      INT         NOT NULL DEFAULT 0,
  total_tokens    BIGINT      NOT NULL DEFAULT 0,
  summary         TEXT        NULL,
  last_message_at DATETIME    NULL,
  created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_conv_shop ON conversations (shop_id, status);
CREATE INDEX IF NOT EXISTS idx_conv_session ON conversations (session_key);

CREATE TABLE IF NOT EXISTS chat_messages (
  id              BIGINT      NOT NULL AUTO_INCREMENT,
  conversation_id BIGINT      NOT NULL,
  role            VARCHAR(20) NOT NULL,
  content         TEXT        NOT NULL,
  tool_name       VARCHAR(50) NULL,
  created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_msg_conv ON chat_messages (conversation_id);

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
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_step_conv ON agent_steps (conversation_id);

CREATE TABLE IF NOT EXISTS knowledge_items (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  shop_id       BIGINT       NOT NULL,
  question      VARCHAR(200) NOT NULL,
  answer        TEXT         NOT NULL,
  keywords      VARCHAR(200) NULL,
  category      VARCHAR(50)  NULL,
  hit_count     INT          NOT NULL DEFAULT 0,
  status        VARCHAR(10)  NOT NULL DEFAULT 'ON',
  source        VARCHAR(20)  NOT NULL DEFAULT 'MANUAL',
  escalation_id BIGINT       NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_knowledge_shop ON knowledge_items (shop_id, status);

CREATE TABLE IF NOT EXISTS escalations (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  shop_id          BIGINT       NOT NULL,
  conversation_id  BIGINT       NULL,
  reason           VARCHAR(200) NOT NULL,
  summary          TEXT         NULL,
  collected_fields TEXT         NULL,
  status           VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
  handler_id       BIGINT       NULL,
  resolution_note  VARCHAR(500) NULL,
  resolved_at      DATETIME     NULL,
  created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_esc_shop_status ON escalations (shop_id, status);
