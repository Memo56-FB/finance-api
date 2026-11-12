CREATE TABLE users (
    username VARCHAR(254) PRIMARY KEY,
    password VARCHAR(500) NOT NULL,
    enabled BOOLEAN NOT NULL,
    CONSTRAINT users_username_normalized CHECK (username = lower(trim(username)))
);

CREATE TABLE authorities (
    username VARCHAR(254) NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    authority VARCHAR(50) NOT NULL,
    PRIMARY KEY (username, authority)
);
