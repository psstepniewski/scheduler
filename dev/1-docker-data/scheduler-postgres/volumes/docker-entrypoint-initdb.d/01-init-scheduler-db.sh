#!/bin/sh

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE USER devuser;
    CREATE DATABASE schedulerdb OWNER devuser;
    GRANT ALL PRIVILEGES ON DATABASE schedulerdb TO devuser;
    ALTER USER devuser WITH ENCRYPTED PASSWORD 'devpass';
EOSQL
