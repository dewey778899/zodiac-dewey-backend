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
