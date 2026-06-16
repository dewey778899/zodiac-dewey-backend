-- Legacy SQLite compatibility patch.
-- This runs before Hibernate auto-update so older mounted databases can be upgraded safely.

ALTER TABLE pay_order ADD COLUMN IF NOT EXISTS channel varchar(20);
ALTER TABLE pay_order ADD COLUMN IF NOT EXISTS trade_type varchar(20);
ALTER TABLE pay_order ADD COLUMN IF NOT EXISTS subject varchar(255);
ALTER TABLE pay_order ADD COLUMN IF NOT EXISTS amount_fen INTEGER;

UPDATE pay_order
SET amount_fen = total_fee
WHERE amount_fen IS NULL
  AND total_fee IS NOT NULL;

UPDATE pay_order
SET subject = '深度解析支付订单'
WHERE subject IS NULL;

UPDATE pay_order
SET channel = 'WECHAT'
WHERE channel IS NULL
  AND (
    openid IS NOT NULL
    OR wechat_prepay_id IS NOT NULL
    OR wechat_code_url IS NOT NULL
    OR wechat_mweb_url IS NOT NULL
    OR wechat_transaction_id IS NOT NULL
  );

UPDATE pay_order
SET channel = 'ALIPAY'
WHERE channel IS NULL
  AND (
    alipay_trade_no IS NOT NULL
    OR alipay_form_html IS NOT NULL
  );

UPDATE pay_order
SET trade_type = 'JSAPI'
WHERE trade_type IS NULL
  AND openid IS NOT NULL;

UPDATE pay_order
SET trade_type = 'NATIVE'
WHERE trade_type IS NULL
  AND wechat_code_url IS NOT NULL;

UPDATE pay_order
SET trade_type = 'H5'
WHERE trade_type IS NULL
  AND wechat_mweb_url IS NOT NULL;

UPDATE pay_order
SET trade_type = 'WAP'
WHERE trade_type IS NULL
  AND alipay_form_html IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_pay_channel ON pay_order (channel);
ALTER TABLE pay_order ADD COLUMN IF NOT EXISTS referral_user_id BIGINT;
ALTER TABLE pay_order ADD COLUMN IF NOT EXISTS referral_settled BOOLEAN DEFAULT 0;
ALTER TABLE pay_order ADD COLUMN IF NOT EXISTS unlock_status VARCHAR(32) DEFAULT 'LOCKED';
ALTER TABLE pay_order ADD COLUMN IF NOT EXISTS unlock_source VARCHAR(64);
ALTER TABLE pay_order ADD COLUMN IF NOT EXISTS unlock_granted_at DATETIME;
ALTER TABLE pay_order ADD COLUMN IF NOT EXISTS unlock_granted_by VARCHAR(128);
ALTER TABLE pay_order ADD COLUMN IF NOT EXISTS unlock_remark VARCHAR(1000);

CREATE TABLE IF NOT EXISTS referral_user (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  phone VARCHAR(32) NOT NULL UNIQUE,
  wechat_openid VARCHAR(128),
  douyin_openid VARCHAR(128),
  unionid VARCHAR(128),
  invite_code VARCHAR(32) NOT NULL UNIQUE,
  display_name VARCHAR(100),
  source VARCHAR(64),
  device_token VARCHAR(128),
  balance_fen INTEGER NOT NULL DEFAULT 0,
  withdrawable_fen INTEGER NOT NULL DEFAULT 0,
  frozen_fen INTEGER NOT NULL DEFAULT 0,
  withdrawn_fen INTEGER NOT NULL DEFAULT 0,
  premium_paid_count INTEGER NOT NULL DEFAULT 0,
  inviter_eligible BOOLEAN NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS referral_binding (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  inviter_user_id BIGINT NOT NULL,
  invitee_user_id BIGINT NOT NULL UNIQUE,
  invite_code VARCHAR(32) NOT NULL,
  bind_source VARCHAR(64),
  bound_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS referral_reward (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  pay_order_id BIGINT NOT NULL UNIQUE,
  inviter_user_id BIGINT NOT NULL,
  invitee_user_id BIGINT NOT NULL,
  amount_fen INTEGER NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'AVAILABLE',
  withdrawal_id BIGINT,
  settled_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS referral_withdrawal (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id BIGINT NOT NULL,
  amount_fen INTEGER NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'APPLIED',
  withdraw_platform VARCHAR(32),
  payee_account_snapshot TEXT,
  remark VARCHAR(1000),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ref_user_phone ON referral_user (phone);
CREATE INDEX IF NOT EXISTS idx_ref_user_invite_code ON referral_user (invite_code);
CREATE INDEX IF NOT EXISTS idx_ref_user_wechat_openid ON referral_user (wechat_openid);
CREATE INDEX IF NOT EXISTS idx_ref_user_douyin_openid ON referral_user (douyin_openid);
CREATE INDEX IF NOT EXISTS idx_ref_binding_inviter ON referral_binding (inviter_user_id);
CREATE INDEX IF NOT EXISTS idx_ref_reward_inviter ON referral_reward (inviter_user_id);
CREATE INDEX IF NOT EXISTS idx_ref_reward_status ON referral_reward (status);
CREATE INDEX IF NOT EXISTS idx_ref_withdraw_user ON referral_withdrawal (user_id);
