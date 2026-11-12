#!/bin/sh
# Runs once, when the chama_pgdata volume is first created (never again, even after a password change in .env).
# Creates the owner the API and Flyway connect as. It is not a superuser, so row-level security applies to the app;
# CREATEROLE lets migration V9 create the restricted role chama_app and grant it to this owner.
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<SQL
CREATE ROLE chama_owner LOGIN NOSUPERUSER NOBYPASSRLS CREATEROLE PASSWORD '${CHAMA_DB_PASSWORD}';
CREATE DATABASE chama_db OWNER chama_owner;
SQL
