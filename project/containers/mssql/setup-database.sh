#!/bin/bash

# Utility to log messages with timestamps
function log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" >&2
}

# Utility to find the sqlcmd executable
function findExecutable() {
    local name=$1
    shift

    for dir in "$@"; do
        if [[ -x "$dir/$name" ]]; then
            echo "$dir/$name"
            return 0
        fi
    done

    return 1
}

# Wait for SQL Server to be ready
function waitForSqlServer() {
    local retries=15
    local wait=5

    log "Waiting for SQL Server to start..."

    for i in $(seq 1 $retries); do
        log "Checking SQL Server responsiveness (Attempt $i)..."

        # Run sqlcmd to check server readiness
        $SQLCMD -S 127.0.0.1 -U sa -P "$MSSQL_SA_PASSWORD" -C -Q "SELECT 1;" > /tmp/sqlcmd_check.log 2>&1
        local sqlcmd_status=$?

        if [[ $sqlcmd_status -eq 0 ]]; then
            log "SQL Server is responsive."
            return 0
        else
            log "SQL Server not ready. SQLCMD Output:"
            cat /tmp/sqlcmd_check.log  # Debug sqlcmd output
        fi

        log "Retrying in $wait seconds..."
        sleep "$wait"
    done

    log "SQL Server did not start within the expected time frame." >&2
    exit 1
}

# Create the target database if it doesn't exist
function createDatabase() {
    local db=$MSSQL_TARGET_DATABASE

    log "Checking if database '$db' exists..."
    $SQLCMD -S 127.0.0.1 -U sa -P "$MSSQL_SA_PASSWORD" -C -Q \
        "IF NOT EXISTS (SELECT name FROM sys.databases WHERE name = N'$db') BEGIN CREATE DATABASE [$db]; END;" > /tmp/sqlcmd_create.log 2>&1

    if [[ $? -eq 0 ]]; then
        log "Database '$db' created successfully or already exists."
    else
        log "Failed to create or verify database '$db'. SQLCMD Output:" >&2
        cat /tmp/sqlcmd_create.log
        exit 1
    fi
}

# Main script logic
function main() {
    # Locate the sqlcmd executable
    SQLCMD=$(findExecutable "sqlcmd" "/opt/mssql-tools18/bin" "/opt/mssql-tools/bin")
    if [[ -z "$SQLCMD" ]]; then
        log "Error: sqlcmd executable not found. Exiting."
        exit 1
    fi

    log "Using sqlcmd located at: $SQLCMD"

    # Wait for SQL Server to start
    waitForSqlServer

    # Create the target database if not exists
    if [[ -z "$MSSQL_TARGET_DATABASE" ]]; then
        log "Error: Environment variable 'MSSQL_TARGET_DATABASE' is not set. Exiting."
        exit 1
    fi

    createDatabase
}

# Execute the script
main
