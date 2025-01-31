# Use the RHEL-based SQL Server image
FROM mcr.microsoft.com/mssql/rhel/server:2022-latest

# Set environment variable for initialization directory
ENV MSSQL_INIT_DIRECTORY=/var/opt/mssql/init.db

# Switch to root user to copy files and set permissions
USER root

# Copy setup script into the image
COPY . $MSSQL_INIT_DIRECTORY
RUN chmod +x $MSSQL_INIT_DIRECTORY/*.sh

# Switch back to the default SQL Server user
USER mssql

# Set the entrypoint to run the setup script and start SQL Server
ENTRYPOINT exec /bin/bash -c "$MSSQL_INIT_DIRECTORY/setup-database.sh & /opt/mssql/bin/sqlservr"
