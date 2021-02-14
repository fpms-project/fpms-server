CREATE TABLE package (
    name VARCHAR(255) NOT NULL,
    version VARCHAR(255) NOT NULL,
    deps JSON,
    id integer NOT NULL,
    UNIQUE(name, version),
    PRIMARY KEY (id)
);