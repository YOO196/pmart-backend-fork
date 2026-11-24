-- 재고 실시간 스트림(0.46.0, 2026-11-24): 재고 수량이 바뀐 확정 단위마다 한 줄. Ledger.write 가 같은 트랜잭션에서 쓰고
-- pg_notify('mfc_stock_stream', stream_id) — 커밋 뒤에만 알림이 가므로 인스턴스마다 LISTEN 해 구독자에게 보낸다.
-- Last-Event-ID 이어 받기용으로만 남긴다(보존 1시간, 주기 삭제) — 원장이 정본.
CREATE TABLE stock_stream_event (
    stream_id   BIGSERIAL    PRIMARY KEY,
    mfc_id      VARCHAR(40)  NOT NULL,
    commit_id   VARCHAR(64)  NOT NULL,
    commit_type VARCHAR(40)  NOT NULL,
    occurred_at TIMESTAMPTZ  NOT NULL,
    payload     JSONB        NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX stock_stream_event_created_idx ON stock_stream_event (created_at);
