package net.csdn.ormtest;

import net.csdn.common.settings.ImmutableSettings;
import net.csdn.common.settings.Settings;
import net.csdn.jpa.JPA;
import net.csdn.jpa.JdbcEngine;
import net.csdn.jpa.model.Model;
import net.csdn.ormtest.pgmix.Customer;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Mixed-case PostgreSQL schema: the same schema name must survive in JDBC
 * metadata (exact TABLE_SCHEM match), Hibernate default_schema (quoted
 * identifier) and the JDBC currentSchema/search_path value, whether the
 * config spells it raw or double-quoted and whether a matching explicit
 * {@code jdbc.currentSchema} is present. A disagreeing currentSchema fails
 * clearly. Opt-in via {@code -Dsf.orm.pg=true} or {@code SF_ORM_PG=true};
 * the switch requires {@code SF_COMPAT_PG_ENV_FILE}. A skip is not acceptance.
 */
public class PostgresMixedSchemaTest {
    private static final String SCHEMA = "SfvMixed";

    private static Map<String, String> env;

    @BeforeClass
    public static void requireServiceAndCreateSchema() throws Exception {
        Assume.assumeTrue(
                "sf.orm.pg is not enabled; a skip is not acceptance",
                postgresRequested());
        String path = System.getenv("SF_COMPAT_PG_ENV_FILE");
        if (path == null || path.length() == 0) {
            fail("sf.orm.pg is enabled but SF_COMPAT_PG_ENV_FILE is missing");
        }
        env = readEnv(new File(path));
        for (String key : new String[]{
                "SF_COMPAT_PG_HOST", "SF_COMPAT_PG_PORT", "SF_COMPAT_PG_USER",
                "SF_COMPAT_PG_PASSWORD", "SF_COMPAT_PG_DATABASE"}) {
            if (env.get(key) == null || env.get(key).length() == 0) {
                fail("compat env is missing " + key);
            }
        }
        if (!"sf_compat".equals(env.get("SF_COMPAT_PG_DATABASE"))) {
            fail("refusing to use a database other than sf_compat");
        }
        createSchemaAndTable();
    }

    @AfterClass
    public static void cleanup() throws Exception {
        JPA.shutdown();
        if (env != null) {
            assertNoOtherDatabaseSessions();
            dropSchema();
        }
    }

    @Test
    public void rawSchemaName() throws Exception {
        exercise("SfvMixed", null);
    }

    @Test
    public void quotedSchemaName() throws Exception {
        exercise("\"SfvMixed\"", null);
    }

    @Test
    public void explicitRawCurrentSchema() throws Exception {
        exercise("SfvMixed", "SfvMixed");
    }

    @Test
    public void explicitQuotedCurrentSchema() throws Exception {
        exercise("SfvMixed", "\"SfvMixed\"");
    }

    @Test
    public void mismatchedCurrentSchemaFailsClearly() throws Exception {
        Settings group = ImmutableSettings.settingsBuilder()
                .put("host", env.get("SF_COMPAT_PG_HOST"))
                .put("port", env.get("SF_COMPAT_PG_PORT"))
                .put("database", env.get("SF_COMPAT_PG_DATABASE"))
                .put("schema", SCHEMA)
                .put("username", env.get("SF_COMPAT_PG_USER"))
                .put("password", env.get("SF_COMPAT_PG_PASSWORD"))
                .put("jdbc.currentSchema", "other_schema")
                .build();
        try {
            JPA.properties(group, JdbcEngine.POSTGRES);
            fail("a disagreeing jdbc.currentSchema must fail");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("currentSchema"));
        }

        try {
            JPA.CSDNORMConfiguration configuration = new JPA.CSDNORMConfiguration(
                    "development", postgresSettings(SCHEMA, "other_schema"), PostgresMixedSchemaTest.class);
            JPA.configure(configuration);
            fail("configure must fail when schema and jdbc.currentSchema disagree");
        } catch (RuntimeException e) {
            assertTrue(describe(e), describe(e).contains("currentSchema"));
        } finally {
            JPA.shutdown();
        }
    }

    private void exercise(String schemaConfig, String currentSchema) throws Exception {
        JPA.CSDNORMConfiguration configuration = new JPA.CSDNORMConfiguration(
                "development", postgresSettings(schemaConfig, currentSchema), PostgresMixedSchemaTest.class);
        JPA.configure(configuration);
        try {
            Map<String, String> properties = JPA.properties();
            assertEquals('"' + SCHEMA + '"', properties.get("hibernate.default_schema"));
            assertTrue(properties.get("url"),
                    properties.get("url").contains("currentSchema=%22" + SCHEMA + "%22"));

            Customer customer = new Customer();
            customer.setName("pg-mixed");
            assertTrue(customer.save());
            Integer id = customer.getId();
            assertNotNull(id);
            JPA.getJPAConfig().getJPAContext().closeTx(false);

            Customer loaded = (Customer) Customer.findById(id);
            assertNotNull(loaded);
            assertEquals("pg-mixed", loaded.getName());

            List<Map> current = Model.findBySql("SELECT current_schema() AS schema_name");
            assertEquals(SCHEMA, String.valueOf(current.get(0).get("schema_name")));
        } finally {
            JPA.shutdown();
        }
    }

    private static String describe(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable cursor = failure; cursor != null; cursor = cursor.getCause()) {
            if (cursor.getMessage() != null) {
                text.append(cursor.getMessage()).append('\n');
            }
        }
        return text.toString();
    }

    private static Settings postgresSettings(String schema, String currentSchema) {
        ImmutableSettings.Builder builder = ImmutableSettings.settingsBuilder()
                .put("development.datasources.primary", "postgres")
                .put("development.datasources.mysql.disable", "true")
                .put("development.datasources.postgres.host", env.get("SF_COMPAT_PG_HOST"))
                .put("development.datasources.postgres.port", env.get("SF_COMPAT_PG_PORT"))
                .put("development.datasources.postgres.database", env.get("SF_COMPAT_PG_DATABASE"))
                .put("development.datasources.postgres.schema", schema)
                .put("development.datasources.postgres.username", env.get("SF_COMPAT_PG_USER"))
                .put("development.datasources.postgres.password", env.get("SF_COMPAT_PG_PASSWORD"))
                .put("development.datasources.postgres.show_sql", "false")
                .put("development.datasources.postgres.initialSize", "1")
                .put("development.datasources.postgres.minIdle", "0")
                .put("development.datasources.postgres.maxActive", "2")
                .put("development.datasources.postgres.jdbc.connectTimeout", "5")
                .put("application.model", "net.csdn.ormtest.pgmix");
        if (currentSchema != null) {
            builder.put("development.datasources.postgres.jdbc.currentSchema", currentSchema);
        }
        return builder.build();
    }

    private static void createSchemaAndTable() throws Exception {
        dropSchema();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA \"" + SCHEMA + "\"");
            statement.execute("CREATE TABLE \"" + SCHEMA + "\".customer (id INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, name VARCHAR(64) NULL)");
        }
    }

    private static void assertNoOtherDatabaseSessions() throws Exception {
        try (Connection connection = open(); Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT count(*) FROM pg_stat_activity"
                             + " WHERE datname = current_database() AND pid <> pg_backend_pid()")) {
            assertTrue(resultSet.next());
            assertEquals("database sessions remained open after JPA.shutdown",
                    0, resultSet.getInt(1));
        }
    }

    private static void dropSchema() throws Exception {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(15);
            statement.execute("SET statement_timeout = '15s'");
            statement.execute("SET lock_timeout = '5s'");
            statement.execute("DROP SCHEMA IF EXISTS \"" + SCHEMA + "\" CASCADE");
        }
    }

    private static Connection open() throws Exception {
        Class.forName("org.postgresql.Driver");
        return DriverManager.getConnection(
                "jdbc:postgresql://" + env.get("SF_COMPAT_PG_HOST") + ":" + env.get("SF_COMPAT_PG_PORT")
                        + "/" + env.get("SF_COMPAT_PG_DATABASE") + "?connectTimeout=5",
                env.get("SF_COMPAT_PG_USER"), env.get("SF_COMPAT_PG_PASSWORD"));
    }

    private static boolean postgresRequested() {
        return Boolean.parseBoolean(System.getProperty("sf.orm.pg", "false"))
                || "true".equalsIgnoreCase(System.getenv("SF_ORM_PG"));
    }

    private static Map<String, String> readEnv(File file) {
        try {
            Map<String, String> values = new LinkedHashMap<String, String>();
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.length() == 0 || line.charAt(0) == '#') {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                values.put(line.substring(0, eq), line.substring(eq + 1));
            }
            return values;
        } catch (Exception e) {
            throw new IllegalStateException("compat env file could not be read", e);
        }
    }
}
