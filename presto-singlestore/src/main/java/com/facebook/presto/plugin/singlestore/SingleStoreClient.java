/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.plugin.singlestore;

import com.facebook.presto.common.type.TimestampType;
import com.facebook.presto.common.type.Type;
import com.facebook.presto.common.type.VarcharType;
import com.facebook.presto.plugin.jdbc.BaseJdbcClient;
import com.facebook.presto.plugin.jdbc.BaseJdbcConfig;
import com.facebook.presto.plugin.jdbc.DriverConnectionFactory;
import com.facebook.presto.plugin.jdbc.JdbcColumnHandle;
import com.facebook.presto.plugin.jdbc.JdbcConnectorId;
import com.facebook.presto.plugin.jdbc.JdbcIdentity;
import com.facebook.presto.plugin.jdbc.JdbcTableHandle;
import com.facebook.presto.plugin.jdbc.mapping.WriteMapping;
import com.facebook.presto.plugin.jdbc.mapping.functions.LongWriteFunction;
import com.facebook.presto.plugin.jdbc.mapping.functions.SliceWriteFunction;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.ConnectorTableMetadata;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.SchemaTableName;
import com.google.common.collect.ImmutableSet;
import com.singlestore.jdbc.Driver;

import javax.inject.Inject;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Optional;
import java.util.Properties;

import static com.facebook.presto.common.type.RealType.REAL;
import static com.facebook.presto.common.type.TimeWithTimeZoneType.TIME_WITH_TIME_ZONE;
import static com.facebook.presto.common.type.TimestampType.TIMESTAMP;
import static com.facebook.presto.common.type.TimestampWithTimeZoneType.TIMESTAMP_WITH_TIME_ZONE;
import static com.facebook.presto.common.type.UuidType.UUID;
import static com.facebook.presto.common.type.VarbinaryType.VARBINARY;
import static com.facebook.presto.common.type.Varchars.isVarcharType;
import static com.facebook.presto.plugin.jdbc.JdbcErrorCode.JDBC_ERROR;
import static com.facebook.presto.plugin.jdbc.mapping.StandardColumnMappings.realColumnMapping;
import static com.facebook.presto.plugin.jdbc.mapping.StandardColumnMappings.timestampColumnMapping;
import static com.facebook.presto.plugin.jdbc.mapping.StandardColumnMappings.varbinaryColumnMapping;
import static com.facebook.presto.plugin.jdbc.mapping.StandardColumnMappings.varcharColumnMapping;
import static com.facebook.presto.plugin.jdbc.mapping.WriteMapping.longMapping;
import static com.facebook.presto.plugin.jdbc.mapping.WriteMapping.sliceMapping;
import static com.facebook.presto.spi.StandardErrorCode.ALREADY_EXISTS;
import static com.facebook.presto.spi.StandardErrorCode.NOT_SUPPORTED;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.lang.String.format;
import static java.util.Locale.ENGLISH;

public class SingleStoreClient
        extends BaseJdbcClient
{
    private static final String SQL_STATE_ER_TABLE_EXISTS_ERROR = "42S01";

    @Inject
    public SingleStoreClient(JdbcConnectorId connectorId, BaseJdbcConfig config)
    {
        super(connectorId, config, "`",
                new DriverConnectionFactory(new Driver(), config.getConnectionUrl(), Optional.ofNullable(config.getUserCredentialName()),
                        Optional.ofNullable(config.getPasswordCredentialName()), connectionProperties(config)));
    }

    private static Properties connectionProperties(BaseJdbcConfig config)
    {
        Properties connectionProperties = DriverConnectionFactory.basicConnectionProperties(config);
        String connectionAttributes = String.format("_connector_name:%s", "SingleStore Presto Connector");
        connectionProperties.setProperty("connectionAttributes", connectionAttributes);
        return connectionProperties;
    }

    @Override
    protected Collection<String> listSchemas(Connection connection)
    {
        try (ResultSet resultSet = connection.getMetaData().getCatalogs()) {
            ImmutableSet.Builder<String> schemaNames = ImmutableSet.builder();
            while (resultSet.next()) {
                String schemaName = resultSet.getString("TABLE_CAT");
                // skip internal schemas
                if (!listSchemasIgnoredSchemas.contains(schemaName.toLowerCase(ENGLISH))) {
                    schemaNames.add(schemaName);
                }
            }
            return schemaNames.build();
        }
        catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void abortReadConnection(Connection connection)
            throws SQLException
    {
        // Abort connection before closing. Without this, the driver
        // attempts to drain the connection by reading all the results.
        connection.abort(directExecutor());
    }

    @Override
    protected ResultSet getTables(Connection connection, Optional<String> schemaName,
            Optional<String> tableName)
            throws SQLException
    {
        DatabaseMetaData metadata = connection.getMetaData();
        Optional<String> escape = Optional.ofNullable(metadata.getSearchStringEscape());
        return metadata.getTables(
                schemaName.orElse(null),
                null,
                escapeNamePattern(tableName, escape).orElse(null),
                new String[] {"TABLE", "VIEW"});
    }

    @Override
    protected String getTableSchemaName(ResultSet resultSet)
            throws SQLException
    {
        return resultSet.getString("TABLE_CAT");
    }

    @Override
    public WriteMapping toWriteMapping(Type type)
    {
        if (REAL.equals(type)) {
            return longMapping("float", (LongWriteFunction) realColumnMapping().getWriteFunction());
        }
        if (TIME_WITH_TIME_ZONE.equals(type) ||
                TIMESTAMP_WITH_TIME_ZONE.equals(type) || UUID.equals(type)) {
            throw new PrestoException(NOT_SUPPORTED, "Unsupported column type: " + type.getDisplayName());
        }
        if (TIMESTAMP.equals(type)) {
            return longMapping("datetime", (LongWriteFunction) timestampColumnMapping((TimestampType) type).getWriteFunction());
        }
        if (VARBINARY.equals(type)) {
            return sliceMapping("mediumblob", (SliceWriteFunction) varbinaryColumnMapping().getWriteFunction());
        }
        if (isVarcharType(type)) {
            VarcharType varcharType = (VarcharType) type;
            String dataType;
            if (varcharType.isUnbounded()) {
                dataType = "longtext";
            }
            else if (varcharType.getLengthSafe() <= 255) {
                dataType = "tinytext";
            }
            else if (varcharType.getLengthSafe() <= 65535) {
                dataType = "text";
            }
            else if (varcharType.getLengthSafe() <= 16777215) {
                dataType = "mediumtext";
            }
            else {
                dataType = "longtext";
            }
            return sliceMapping(dataType, (SliceWriteFunction) varcharColumnMapping(varcharType).getWriteFunction());
        }
        return super.toWriteMapping(type);
    }

    @Override
    public void createTable(ConnectorSession session, ConnectorTableMetadata tableMetadata)
    {
        try {
            createTable(tableMetadata, session, tableMetadata.getTable().getTableName());
        }
        catch (SQLException e) {
            if (SQL_STATE_ER_TABLE_EXISTS_ERROR.equals(e.getSQLState())) {
                throw new PrestoException(ALREADY_EXISTS, e);
            }
            throw new PrestoException(JDBC_ERROR, e);
        }
    }

    @Override
    public void renameColumn(ConnectorSession session, JdbcIdentity identity, JdbcTableHandle handle,
            JdbcColumnHandle jdbcColumn, String newColumnName)
    {
        try (Connection connection = connectionFactory.openConnection(identity)) {
            DatabaseMetaData metadata = connection.getMetaData();
            if (metadata.storesUpperCaseIdentifiers()) {
                newColumnName = newColumnName.toUpperCase(ENGLISH);
            }
            String sql = format(
                    "ALTER TABLE %s CHANGE %s %s",
                    quoted(handle.getCatalogName(), handle.getSchemaName(), handle.getTableName()),
                    quoted(jdbcColumn.getColumnName()),
                    quoted(newColumnName));
            execute(connection, sql);
        }
        catch (SQLException e) {
            throw new PrestoException(JDBC_ERROR, e);
        }
    }

    @Override
    protected void renameTable(JdbcIdentity identity, String catalogName, SchemaTableName oldTable,
            SchemaTableName newTable)
    {
        super.renameTable(identity, null, oldTable, newTable);
    }
}
