CREATE TABLE links (
    code varchar(32) CONSTRAINT links_pkey PRIMARY KEY,
    target_url varchar(2048) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
