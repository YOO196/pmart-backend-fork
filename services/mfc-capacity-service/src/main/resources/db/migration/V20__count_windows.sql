-- 재고 실사 시간대(사용자 결정 2026-11-20 "아침 7시~8시, 저녁 8시~9시 디폴트이되 변경 가능하게"): 센터마다 바꾼 값만 둔다.
-- 행이 없으면 기본값(코드 CountWindowService.DEFAULT_WINDOWS). windows = "07:00-08:00,20:00-21:00"(빈 문자열 = 실사 시간대 없음) — 서울 시각, 자정을 넘지 않음.
-- 그 시간대에는 다음 작업 받기가 실사 자격이 있는 작업자에게 실사를 먼저 준다(없으면 다른 일).
CREATE TABLE count_window_setting (
    mfc_id      varchar(32)  NOT NULL,
    windows     varchar(200) NOT NULL,
    version     integer      NOT NULL,
    updated_by  varchar(64)  NOT NULL,
    updated_at  timestamptz  NOT NULL,
    CONSTRAINT pk_count_window_setting PRIMARY KEY (mfc_id)
);
