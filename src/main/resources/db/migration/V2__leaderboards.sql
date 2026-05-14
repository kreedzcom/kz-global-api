CREATE TABLE IF NOT EXISTS map_record (
    id                UUID         PRIMARY KEY,
    server_id         INTEGER      NOT NULL REFERENCES game_server(id),
    player_steamid    VARCHAR(32)  NOT NULL REFERENCES player(steamid),
    map_name          VARCHAR(255) NOT NULL REFERENCES map(name),
    time_ms           BIGINT       NOT NULL,
    checkpoints       INTEGER      NOT NULL,
    gochecks          INTEGER      NOT NULL,
    local_uid         VARCHAR(64)  NOT NULL UNIQUE,
    replay_r2_key     VARCHAR(255),
    flagged           BOOLEAN      NOT NULL DEFAULT FALSE,
    reviewed          BOOLEAN      NOT NULL DEFAULT FALSE,
    plugin_version_id INTEGER      NOT NULL REFERENCES plugin_version(id),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_map_record_player_map    ON map_record(player_steamid, map_name);
CREATE INDEX IF NOT EXISTS idx_map_record_map           ON map_record(map_name);
CREATE INDEX IF NOT EXISTS idx_map_record_time          ON map_record(time_ms);
CREATE INDEX IF NOT EXISTS idx_map_record_flagged       ON map_record(flagged);

CREATE TABLE IF NOT EXISTS best_nub_record (
    player_steamid VARCHAR(32)  NOT NULL REFERENCES player(steamid),
    map_name       VARCHAR(255) NOT NULL REFERENCES map(name),
    record_id      UUID         NOT NULL REFERENCES map_record(id) ON DELETE CASCADE,
    PRIMARY KEY (player_steamid, map_name)
);

CREATE INDEX IF NOT EXISTS idx_best_nub_record_id ON best_nub_record(record_id);

CREATE TABLE IF NOT EXISTS best_pro_record (
    player_steamid VARCHAR(32)  NOT NULL REFERENCES player(steamid),
    map_name       VARCHAR(255) NOT NULL REFERENCES map(name),
    record_id      UUID         NOT NULL REFERENCES map_record(id) ON DELETE CASCADE,
    PRIMARY KEY (player_steamid, map_name)
);

CREATE INDEX IF NOT EXISTS idx_best_pro_record_id ON best_pro_record(record_id);

-- category: 'nub' or 'pro'
CREATE TABLE IF NOT EXISTS world_record (
    map_name   VARCHAR(255) NOT NULL REFERENCES map(name),
    category   VARCHAR(3)   NOT NULL CHECK (category IN ('nub', 'pro')),
    record_id  UUID         NOT NULL REFERENCES map_record(id) ON DELETE CASCADE,
    PRIMARY KEY (map_name, category)
);
