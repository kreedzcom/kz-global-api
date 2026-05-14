CREATE TABLE IF NOT EXISTS game_server (
    id               SERIAL PRIMARY KEY,
    name             VARCHAR(255) NOT NULL UNIQUE,
    access_key       BYTEA        NOT NULL UNIQUE,
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    last_connected_at TIMESTAMP WITH TIME ZONE,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS plugin_version (
    id               SERIAL PRIMARY KEY,
    semver           VARCHAR(50)  NOT NULL,
    checksum_linux   BYTEA        NOT NULL,
    checksum_windows BYTEA        NOT NULL,
    is_cutoff        BOOLEAN                  NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS player (
    steamid        VARCHAR(32)  PRIMARY KEY,
    last_nickname  VARCHAR(64)  NOT NULL,
    ip_address     VARCHAR(45),
    first_seen_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_seen_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS map (
    name       VARCHAR(255) PRIMARY KEY,
    checksum   VARCHAR(64),
    type       VARCHAR(32),
    length     REAL,
    difficulty INTEGER
);

CREATE TABLE IF NOT EXISTS map_minimum_time (
    map_name    VARCHAR(255) PRIMARY KEY REFERENCES map(name),
    min_time_ms BIGINT       NOT NULL,
    updated_by  VARCHAR(255) NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS event_log (
    id          BIGSERIAL    PRIMARY KEY,
    server_id   INTEGER      REFERENCES game_server(id),
    event_type  VARCHAR(64)  NOT NULL,
    payload     TEXT         NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_event_log_event_type ON event_log(event_type);
CREATE INDEX IF NOT EXISTS idx_event_log_created_at ON event_log(created_at);
