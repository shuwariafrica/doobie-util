#!/bin/bash

sleep 5

function createDatabase() {
	echo "Creating database $MSSQL_TARGET_DATABASE"
	/opt/mssql-tools/bin/sqlcmd -S localhost -U sa -P "$MSSQL_SA_PASSWORD" -d master -Q "IF NOT EXISTS(SELECT * FROM sys.databases WHERE name = '$MSSQL_TARGET_DATABASE') BEGIN CREATE DATABASE [$MSSQL_TARGET_DATABASE]; END"
}

for i in {1..20}; do
	RESULT=$(/opt/mssql-tools/bin/sqlcmd -h -1 -t 1 -S localhost -U sa -P "$MSSQL_SA_PASSWORD" -Q "SET NOCOUNT ON; Select SUM(state) from sys.databases" | xargs)
	if [[ $RESULT = "0" ]]; then
		createDatabase
		break
	else
		if [[ i -lt 20 ]]; then
			echo "SQL Database cannot be contacted. Attempting again."
			sleep 3
		else
			echo "Unable to contact SQL Server." >&2
			exit 1
		fi
	fi
done
