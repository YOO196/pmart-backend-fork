-- 품질 증빙 사진(PDA BR-52 R18-2, 사용자 결정 2026-11-20 "사진은 mfc-quality"). 원본은 비공개 S3 호환 저장소(ADR-0016), 여기는 참조만.
-- evidence_ref 는 출처·요청 키·올린 사람·센터·내용 해시에서 정해진다(같은 요청 재전송 = 같은 사진).
CREATE TABLE quality_evidence_photo (
    evidence_ref   varchar(64)  NOT NULL,
    mfc_id         varchar(32)  NOT NULL,
    object_key     varchar(200) NOT NULL,
    content_type   varchar(20)  NOT NULL,
    size_bytes     integer      NOT NULL,
    sha256         varchar(64)  NOT NULL,
    uploaded_by    varchar(128) NOT NULL,
    uploaded_at    timestamptz  NOT NULL,
    CONSTRAINT pk_quality_evidence_photo PRIMARY KEY (evidence_ref),
    CONSTRAINT ck_quality_evidence_photo_type CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp')),
    CONSTRAINT ck_quality_evidence_photo_size CHECK (size_bytes > 0)
);
CREATE INDEX ix_quality_evidence_photo_mfc ON quality_evidence_photo (mfc_id, uploaded_at);
