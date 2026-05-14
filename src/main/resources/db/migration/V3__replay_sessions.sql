CREATE TABLE IF NOT EXISTS replay_upload_session (
    local_uid        VARCHAR(64) PRIMARY KEY,
    server_id        INTEGER     NOT NULL REFERENCES game_server(id),
    received_chunks  INTEGER     NOT NULL DEFAULT 0,
    total_chunks     INTEGER     NOT NULL,
    started_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
