-- MFC 수요예측·보충추천 데모 결과(데모 전용 DB pmart_mfc_demo). 운영 DB 에 만들지 않는다.
-- run_id = 'run-' + Spring Batch 작업 인스턴스 ID(같은 인스턴스를 다시 시작하면 같은 run_id).

CREATE TABLE demo_run (
    run_id           VARCHAR(40) PRIMARY KEY,
    as_of            TIMESTAMP    NOT NULL,
    mfc_id           VARCHAR(40),
    run_label        VARCHAR(80)  NOT NULL,
    app_version      VARCHAR(20)  NOT NULL,
    input_snapshot   VARCHAR(64)  NOT NULL,   -- 입력 파일들의 SHA-256
    sales_basis      VARCHAR(60),
    source_timezone  VARCHAR(40),
    data_mode        VARCHAR(40)  NOT NULL,
    started_at       TIMESTAMP    NOT NULL,
    finished_at      TIMESTAMP,
    status           VARCHAR(20)  NOT NULL
);

CREATE TABLE demo_clean_report (
    run_id     VARCHAR(40) NOT NULL REFERENCES demo_run(run_id),
    item       VARCHAR(60) NOT NULL,
    line_count BIGINT      NOT NULL,
    PRIMARY KEY (run_id, item)
);

CREATE TABLE demo_sales_aggregate (
    run_id            VARCHAR(40) NOT NULL REFERENCES demo_run(run_id),
    mfc_id            VARCHAR(40) NOT NULL,
    sku_id            VARCHAR(40) NOT NULL,
    period_type       VARCHAR(10) NOT NULL,
    period_key        VARCHAR(20) NOT NULL,
    units             BIGINT      NOT NULL,
    normal_days       INT NOT NULL,
    zero_assumed_days INT NOT NULL,
    stockout_days     INT NOT NULL,
    missing_days      INT NOT NULL,
    closed_days       INT NOT NULL,
    pre_launch_days   INT NOT NULL,
    unknown_days      INT NOT NULL,
    PRIMARY KEY (run_id, mfc_id, sku_id, period_type, period_key)
);

CREATE TABLE demo_forecast (
    run_id          VARCHAR(40) NOT NULL REFERENCES demo_run(run_id),
    as_of           TIMESTAMP   NOT NULL,
    mfc_id          VARCHAR(40) NOT NULL,
    sku_id          VARCHAR(40) NOT NULL,
    forecast_date   DATE        NOT NULL,
    point_forecast  NUMERIC(14,4) NOT NULL CHECK (point_forecast >= 0),
    forecast_scope  VARCHAR(20) NOT NULL,
    model_id        VARCHAR(40) NOT NULL,
    model_version   VARCHAR(20) NOT NULL,
    status          VARCHAR(20) NOT NULL,
    fallback_reason TEXT,
    PRIMARY KEY (run_id, mfc_id, sku_id, forecast_date)
);

CREATE TABLE demo_recommendation (
    id                    BIGSERIAL PRIMARY KEY,
    run_id                VARCHAR(40) NOT NULL REFERENCES demo_run(run_id),
    active                BOOLEAN     NOT NULL,
    as_of                 TIMESTAMP   NOT NULL,
    mfc_id                VARCHAR(40) NOT NULL,
    sku_id                VARCHAR(40) NOT NULL,
    proposed_ready_at     TIMESTAMP   NOT NULL,
    protection_end        TIMESTAMP   NOT NULL,
    protection_method     VARCHAR(10) NOT NULL,
    granularity           VARCHAR(10) NOT NULL,
    policy_version        VARCHAR(40),
    target_method         VARCHAR(40) NOT NULL,
    forecast_model        VARCHAR(40),
    h_qty                 BIGINT NOT NULL,
    h_expiring_qty        BIGINT NOT NULL,
    c_qty                 BIGINT NOT NULL,
    i_qty                 BIGINT NOT NULL,
    p_qty                 BIGINT NOT NULL,
    f_qty                 NUMERIC(14,4),
    s_qty                 NUMERIC(14,4),
    ip_qty                NUMERIC(14,4) NOT NULL,
    n_qty                 NUMERIC(14,4),
    m_qty                 INT,
    w_qty                 BIGINT,
    b_qty                 BIGINT,
    k_qty                 BIGINT,
    q_qty                 BIGINT,
    unmet_qty             NUMERIC(14,4),
    excess_qty            NUMERIC(14,4),
    first_shortage_at     TIMESTAMP,
    max_shortage          NUMERIC(14,4) NOT NULL,
    recovery_at           TIMESTAMP,
    shortage_before_proposed_ready        NUMERIC(14,4) NOT NULL,
    residual_shortage_after_replenishment NUMERIC(14,4) NOT NULL,
    shared_cap_group      VARCHAR(40),
    status                VARCHAR(20) NOT NULL,
    reason                TEXT        NOT NULL,
    data_mode             VARCHAR(40) NOT NULL
);

-- 활성 추천은 기준시각·센터·SKU 마다 하나(명세 F07 — 재실행해도 중복 없음).
CREATE UNIQUE INDEX ux_demo_recommendation_active
    ON demo_recommendation (as_of, mfc_id, sku_id) WHERE active;

CREATE TABLE demo_failure (
    id      BIGSERIAL PRIMARY KEY,
    run_id  VARCHAR(40) NOT NULL REFERENCES demo_run(run_id),
    step    VARCHAR(20) NOT NULL,
    mfc_id  VARCHAR(40),
    sku_id  VARCHAR(40),
    error   TEXT        NOT NULL
);

CREATE TABLE demo_backtest (
    run_id             VARCHAR(40) NOT NULL REFERENCES demo_run(run_id),
    model_id           VARCHAR(40) NOT NULL,
    clipped            BOOLEAN     NOT NULL,
    metric_kind        VARCHAR(20) NOT NULL,
    windows            BIGINT      NOT NULL,
    excluded_windows   BIGINT      NOT NULL,
    unavailable        BIGINT      NOT NULL,
    mae                NUMERIC(14,4),
    bias               NUMERIC(14,4),
    wape               NUMERIC(14,6),
    coverage           NUMERIC(8,6),
    zero_assumed_share NUMERIC(8,6),
    PRIMARY KEY (run_id, model_id, clipped, metric_kind)
);

CREATE TABLE demo_simulation (
    run_id               VARCHAR(40) NOT NULL REFERENCES demo_run(run_id),
    model_id             VARCHAR(40) NOT NULL,
    reviews              BIGINT NOT NULL,
    orders               BIGINT NOT NULL,
    no_forecast_reviews  BIGINT NOT NULL,
    stockout_rate        NUMERIC(8,6),
    average_on_hand      NUMERIC(14,4),
    data_mode            VARCHAR(40) NOT NULL,
    PRIMARY KEY (run_id, model_id)
);
