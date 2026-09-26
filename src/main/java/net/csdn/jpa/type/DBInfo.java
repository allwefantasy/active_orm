package net.csdn.jpa.type;

import com.google.inject.Inject;
import net.csdn.common.Strings;
import net.csdn.common.settings.Settings;
import net.csdn.jpa.JPA;
import net.csdn.jpa.JdbcEngine;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * User: WilliamZhu
 * Date: 12-7-25
 * Time: 下午7:53
 *
 * Schema snapshot of the configured primary datasource. Metadata is read
 * through JDBC {@link DatabaseMetaData} bounded to the connected catalog and,
 * for PostgreSQL, to the configured schema only (exact TABLE_SCHEM match).
 * Column type names are normalized to the values the engine {@link DBType}
 * implementations understand. Refresh replaces the snapshot.
 */
public class DBInfo {
    public final List<String> tableNames = new ArrayList<String>();
    public final Map<String, Map<String, String>> tableColumns = new HashMap<String, Map<String, String>>();

    private final Settings settings;
    private final String engine;
    private final String schema;
    private final String host;
    private final String port;
    private final String database;
    private final String username;
    private final String password;
    private final String driver;
    private final String url;
    private final boolean disabled;

    @Inject
    public DBInfo(Settings settings) {
        this.settings = settings;
        JdbcEngine.Selection selection = JdbcEngine.primaryOrAbsent(settings, JPA.mode());
        if (selection == null) {
            engine = JdbcEngine.MYSQL;
            schema = "";
            host = "";
            port = "";
            database = "";
            username = "";
            password = "";
            driver = "";
            url = "";
            disabled = true;
            return;
        }
        this.engine = selection.engine();
        this.disabled = selection.disabled();
        Settings datasource = selection.group();
        this.host = datasource == null ? "" : value(datasource.get("host"));
        this.port = datasource == null ? "" : value(datasource.get("port"));
        this.database = datasource == null ? "" : value(datasource.get("database"));
        this.schema = JdbcEngine.POSTGRES.equals(engine) && datasource != null ? JdbcEngine.schema(datasource) : "";
        this.username = datasource == null ? "" : value(datasource.get("username"));
        this.password = datasource == null ? "" : value(datasource.get("password"));
        this.driver = datasource == null ? "" : value(datasource.get("driver", JdbcEngine.defaultDriver(engine)));
        this.url = datasource == null ? "" : JPA.properties(datasource, engine).get("url");
        if (!disabled) {
            info();
        }
    }

    public void info() {
        if (disabled) {
            return;
        }
        loadDriver();
        List<String> names = new ArrayList<String>();
        Map<String, Map<String, String>> columnsByTable = new LinkedHashMap<String, Map<String, String>>();
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            String catalog = connection.getCatalog();
            if (catalog == null || catalog.length() == 0 || !catalog.equalsIgnoreCase(database)) {
                throw new IllegalStateException(
                        "connected catalog does not match configured database " + database);
            }
            String schemaPattern = JdbcEngine.POSTGRES.equals(engine) ? schema : null;
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet tables = metaData.getTables(catalog, schemaPattern, "%", new String[]{"TABLE"})) {
                while (tables.next()) {
                    String tableCatalog = tables.getString("TABLE_CAT");
                    String tableSchema = tables.getString("TABLE_SCHEM");
                    if (tableCatalog != null && !tableCatalog.equalsIgnoreCase(catalog)) {
                        continue;
                    }
                    if (JdbcEngine.POSTGRES.equals(engine) && !schema.equals(tableSchema)) {
                        continue;
                    }
                    String tableName = tables.getString("TABLE_NAME");
                    if (tableName == null || columnsByTable.containsKey(tableName)) {
                        continue;
                    }
                    names.add(tableName);
                    columnsByTable.put(tableName, new LinkedHashMap<String, String>());
                }
            }
            try (ResultSet columns = metaData.getColumns(catalog, schemaPattern, "%", "%")) {
                while (columns.next()) {
                    String tableCatalog = columns.getString("TABLE_CAT");
                    String tableSchema = columns.getString("TABLE_SCHEM");
                    if (tableCatalog != null && !tableCatalog.equalsIgnoreCase(catalog)) {
                        continue;
                    }
                    if (JdbcEngine.POSTGRES.equals(engine) && !schema.equals(tableSchema)) {
                        continue;
                    }
                    String tableName = columns.getString("TABLE_NAME");
                    String columnName = columns.getString("COLUMN_NAME");
                    String typeName = columns.getString("TYPE_NAME");
                    Map<String, String> table = columnsByTable.get(tableName);
                    if (table == null || columnName == null) {
                        continue;
                    }
                    table.put(columnName, normalizeTypeName(engine, typeName));
                }
            }
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (SQLException e) {
            throw new IllegalStateException("schema metadata was not read for " + engine + " datasource", e);
        }
        tableNames.clear();
        tableNames.addAll(names);
        tableColumns.clear();
        for (Map.Entry<String, Map<String, String>> entry : columnsByTable.entrySet()) {
            tableColumns.put(entry.getKey(), entry.getValue());
            tableColumns.put(Strings.toCamelCase(entry.getKey(), true), entry.getValue());
        }
    }

    public boolean isDisabled() {
        return disabled;
    }

    public String engine() {
        return engine;
    }

    private void loadDriver() {
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(engine + " driver " + driver + " is not on the classpath", e);
        }
    }

    private static String value(String text) {
        return text == null ? "" : text;
    }

    /**
     * Maps driver {@code TYPE_NAME} values onto the names {@code MysqlType} or
     * {@code PostgresType} look up. Unknown names are returned unchanged,
     * uppercased, so a new type is not silently remapped.
     */
    public static String normalizeTypeName(String engine, String typeName) {
        if (JdbcEngine.POSTGRES.equals(JdbcEngine.normalize(engine))) {
            return normalizePostgresTypeName(typeName);
        }
        return normalizeMysqlTypeName(typeName);
    }

    private static String normalizeMysqlTypeName(String typeName) {
        if (typeName == null) {
            return "";
        }
        String upper = typeName.trim().toUpperCase(Locale.ROOT);
        if (upper.startsWith("BIGINT")) {
            return "BIGINT";
        }
        if (upper.startsWith("SMALLINT")) {
            return "SMALLINT";
        }
        if (upper.startsWith("TINYINT") || upper.equals("BOOL") || upper.equals("BOOLEAN")) {
            if (upper.equals("BOOL") || upper.equals("BOOLEAN")) {
                return "BOOLEAN";
            }
            return "TINYINT";
        }
        if (upper.startsWith("MEDIUMINT") || upper.startsWith("INTEGER") || upper.startsWith("INT")) {
            return "INT";
        }
        if (upper.startsWith("VARCHAR")) {
            return "VARCHAR";
        }
        if (upper.startsWith("CHAR")) {
            return "CHAR";
        }
        if (upper.contains("MEDIUMTEXT")) {
            return "MEDIUMTEXT";
        }
        if (upper.contains("LONGTEXT")) {
            return "LONGTEXT";
        }
        if (upper.equals("TEXT") || upper.endsWith(" TEXT")) {
            return "TEXT";
        }
        if (upper.startsWith("DOUBLE")) {
            return "DOUBLE";
        }
        if (upper.startsWith("FLOAT")) {
            return "FLOAT";
        }
        if (upper.startsWith("DATETIME")) {
            return "DATETIME";
        }
        if (upper.startsWith("TIMESTAMP")) {
            return "TIMESTAMP";
        }
        if (upper.startsWith("DATE")) {
            return "DATE";
        }
        if (upper.startsWith("BIT")) {
            return "BIT";
        }
        return upper;
    }

    private static String normalizePostgresTypeName(String typeName) {
        if (typeName == null) {
            return "";
        }
        String upper = typeName.trim().toUpperCase(Locale.ROOT);
        if (upper.equals("INT8") || upper.equals("BIGSERIAL") || upper.startsWith("BIGINT")) {
            return "BIGINT";
        }
        if (upper.equals("INT2") || upper.startsWith("SMALLINT") || upper.equals("SMALLSERIAL")) {
            return "SMALLINT";
        }
        if (upper.equals("INT4") || upper.equals("SERIAL") || upper.startsWith("INTEGER") || upper.startsWith("INT")) {
            return "INT";
        }
        if (upper.equals("BOOL") || upper.equals("BOOLEAN")) {
            return "BOOLEAN";
        }
        if (upper.startsWith("VARCHAR") || upper.startsWith("CHARACTER VARYING")) {
            return "VARCHAR";
        }
        if (upper.equals("BPCHAR") || upper.startsWith("CHAR")) {
            return "CHAR";
        }
        if (upper.equals("TEXT")) {
            return "TEXT";
        }
        if (upper.equals("FLOAT4") || upper.equals("REAL")) {
            return "FLOAT";
        }
        if (upper.equals("FLOAT8") || upper.startsWith("DOUBLE")) {
            return "DOUBLE";
        }
        if (upper.startsWith("NUMERIC") || upper.startsWith("DECIMAL")) {
            return "NUMERIC";
        }
        if (upper.equals("TIMESTAMPTZ") || upper.startsWith("TIMESTAMP WITH")) {
            return "TIMESTAMPTZ";
        }
        if (upper.startsWith("TIMESTAMP")) {
            return "TIMESTAMP";
        }
        if (upper.startsWith("DATE")) {
            return "DATE";
        }
        if (upper.equals("BYTEA")) {
            return "BYTEA";
        }
        if (upper.equals("UUID")) {
            return "UUID";
        }
        return upper;
    }
}
