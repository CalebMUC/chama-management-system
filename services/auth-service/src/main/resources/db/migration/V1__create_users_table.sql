-- src/main/resources/db/migration/V1__create_users_table.sql

CREATE TABLE users (
                       id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                       email       VARCHAR(255) NOT NULL UNIQUE,
                       password    VARCHAR(255) NOT NULL,
                       first_name  VARCHAR(100) NOT NULL,
                       last_name   VARCHAR(100) NOT NULL,
                       is_enabled  BOOLEAN NOT NULL DEFAULT true,
                       created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
                       updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE roles (
                       id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                       name VARCHAR(50) NOT NULL UNIQUE
);

CREATE TABLE user_roles (
                            user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
                            PRIMARY KEY (user_id, role_id)
);

CREATE TABLE refresh_tokens (
                                id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                                token       VARCHAR(500) NOT NULL UNIQUE,
                                expiry_date TIMESTAMP NOT NULL,
                                revoked     BOOLEAN NOT NULL DEFAULT false,
                                created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Seed default roles
INSERT INTO roles (id, name) VALUES
                                 (gen_random_uuid(), 'ROLE_MEMBER'),
                                 (gen_random_uuid(), 'ROLE_TREASURER'),
                                 (gen_random_uuid(), 'ROLE_SECRETARY'),
                                 (gen_random_uuid(), 'ROLE_CHAIRMAN'),
                                 (gen_random_uuid(), 'ROLE_ADMIN');