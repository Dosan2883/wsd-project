ALTER TABLE users ADD COLUMN suspension_reason VARCHAR(500);
ALTER TABLE users ADD COLUMN suspended_at TIMESTAMP WITH TIME ZONE;

-- 기존 정지 계정도 사유·시각 없이 안내되지 않도록 이관한다.
-- 과거 정지 시각은 알 수 없으므로 임의의 시각을 생성하지 않고 NULL로 보존한다.
UPDATE users SET suspension_reason = '기존 정지 계정입니다. 자세한 사유는 관리자에게 문의해 주세요.'
WHERE status = 'SUSPENDED';

ALTER TABLE users ADD CONSTRAINT ck_users_suspension_details CHECK (
    (status = 'ACTIVE' AND suspension_reason IS NULL AND suspended_at IS NULL)
    OR (status = 'SUSPENDED' AND suspension_reason IS NOT NULL AND LENGTH(TRIM(suspension_reason)) > 0)
);
