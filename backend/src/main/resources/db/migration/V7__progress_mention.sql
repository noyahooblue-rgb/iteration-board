-- 1.0.4：进展说明支持 @ 提及（与 comment_mention 同构），被 @ 者收到 MENTION 通知。
CREATE TABLE progress_mention (
    progress_update_id BIGINT NOT NULL REFERENCES progress_update(id),
    user_id            BIGINT NOT NULL,
    PRIMARY KEY (progress_update_id, user_id)
);
