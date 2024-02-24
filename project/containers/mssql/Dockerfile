FROM mcr.microsoft.com/mssql/rhel/server:2022-latest


USER root

ENV MSSQL_INIT_DIRECTORY=/var/opt/mssql/init.db
COPY . $MSSQL_INIT_DIRECTORY
RUN chmod +x $MSSQL_INIT_DIRECTORY/*.sh

USER mssql

ENTRYPOINT $MSSQL_INIT_DIRECTORY/setup-database.sh & \
    /opt/mssql/bin/sqlservr