package net.csdn.ormtest;

import net.csdn.common.settings.ImmutableSettings;
import net.csdn.common.settings.Settings;
import net.csdn.jpa.JPA;
import net.csdn.jpa.context.JPAConfig;
import net.csdn.jpa.model.Model;
import net.csdn.jpa.type.DBInfo;
import net.csdn.jpa.type.impl.PostgresType;
import net.csdn.ormtest.pg.Customer;
import net.csdn.ormtest.pg.ShopOrder;
import net.csdn.ormtest.pg.TypesRecord;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;

/**
 * Real PostgreSQL path for standalone ActiveORM: model enhancement, generated
 * ids, CRUD/query/pagination, commit/rollback, a @ManyToOne association,
 * schema-isolated metadata and named pools. Opt-in via {@code -Dsf.orm.pg=true}
 * or {@code SF_ORM_PG=true}; the switch requires {@code SF_COMPAT_PG_ENV_FILE}.
 * A skip when the switch is absent is not acceptance.
 */
public class PostgresBusinessTest {
    private static final String PRIMARY_SCHEMA = "orm_a";
    private static final String REPORT_SCHEMA = "orm_b";

    private static Map<String, String> env;

    @BeforeClass
    public static void requireServiceAndConfigure() throws Exception {
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
        createSchemasAndTables();
        JPA.CSDNORMConfiguration configuration = new JPA.CSDNORMConfiguration(
                "development", postgresSettings(), PostgresBusinessTest.class);
        JPA.configure(configuration);
    }

    @AfterClass
    public static void cleanup() throws Exception {
        JPA.shutdown();
        if (env != null) {
            assertNoOtherDatabaseSessions();
            dropSchemas();
        }
    }

    @Test
    public void metadataIsScopedToSelectedSchema() {
        assertTrue(JPA.dbType() instanceof PostgresType);
        DBInfo info = JPA.dbInfo();
        assertFalse(info.isDisabled());
        assertEquals("postgres", info.engine());
        assertTrue(info.tableNames.contains("customer"));
        assertTrue(info.tableNames.contains("shop_order"));
        assertTrue(info.tableNames.contains("types_record"));
        assertTrue(info.tableNames.contains("same_named"));
        for (String table : info.tableNames) {
            assertFalse(table, table.startsWith("pg_"));
            assertFalse(table, table.startsWith("sql_"));
            assertFalse(table, "information_schema".equals(table));
            assertFalse(table, "report_order".equals(table));
        }
        Map<String, String> sameNamed = info.tableColumns.get("same_named");
        assertNotNull(sameNamed);
        assertEquals("VARCHAR", sameNamed.get("marker"));
        assertFalse(sameNamed.containsKey("other"));
        assertFalse(sameNamed.containsKey("note"));
        // camel-case alias used by the enhancer for entity lookup
        assertSame(sameNamed, info.tableColumns.get("SameNamed"));
        assertEquals("INT", sameNamed.get("id"));
    }

    @Test
    public void crudIdsAssociationAndPagination() {
        Customer customer = new Customer();
        customer.setName("pg-customer");
        assertTrue(customer.save());
        Integer customerId = customer.getId();
        assertNotNull(customerId);

        for (int i = 0; i < 3; i++) {
            ShopOrder order = new ShopOrder();
            order.setStatus("open");
            order.setRegion("pg");
            order.attr("label", "pg-" + i);
            order.setCustomer(customer);
            assertTrue(order.save());
            assertNotNull(order.getId());
        }
        commit();

        List<ShopOrder> page = ShopOrder.where(
                "status=:status and region=:region",
                params("status", "open", "region", "pg"))
                .offset(1).limit(1).fetch();
        assertEquals(1, page.size());
        assertEquals("pg-1", page.get(0).attr("label", String.class));
        assertEquals(customerId, page.get(0).getCustomer().getId());
        assertTrue(ShopOrder.count() >= 3);

        ShopOrder detached = (ShopOrder) ShopOrder.findById(page.get(0).getId());
        assertNotNull(detached);
        assertEquals("pg-1", detached.attr("label", String.class));
    }

    @Test
    public void typesRoundTrip() {
        UUID token = UUID.randomUUID();
        byte[] payload = new byte[]{0, 1, 2, (byte) 0xff};
        java.util.Date happenedAt = new java.util.Date(1700000000000L);
        java.util.Date day = java.sql.Date.valueOf("2024-01-02");
        BigDecimal amount = new BigDecimal("1234.56");

        TypesRecord record = new TypesRecord();
        record.setBigValue(9223372036854775800L);
        record.setFlag(Boolean.TRUE);
        record.setAmount(amount);
        record.setHappenedAt(happenedAt);
        record.setDay(day);
        record.setPayload(payload);
        record.setToken(token);
        record.attr("label", "pg-types");
        assertTrue(record.save());
        Integer id = record.getId();
        assertNotNull(id);
        commit();

        TypesRecord loaded = (TypesRecord) TypesRecord.findById(id);
        assertNotNull(loaded);
        assertEquals(Long.valueOf(9223372036854775800L), loaded.getBigValue());
        assertEquals(Boolean.TRUE, loaded.getFlag());
        assertEquals(amount, loaded.getAmount());
        assertEquals(happenedAt, loaded.getHappenedAt());
        assertArrayEquals(payload, loaded.getPayload());
        assertEquals(token, loaded.getToken());
        assertEquals("pg-types", loaded.attr("label", String.class));
    }

    @Test
    public void rollbackDropsRow() {
        ShopOrder rolled = new ShopOrder();
        rolled.setStatus("rolled");
        rolled.setRegion("pg");
        rolled.attr("label", "rolled");
        assertTrue(rolled.save());
        Integer rolledId = rolled.getId();
        assertNotNull(rolledId);
        JPA.getJPAConfig().getJPAContext().closeTx(true);
        assertNull(ShopOrder.findById(rolledId));
    }

    @Test
    public void nativeSqlPools() {
        List<Map> current = Model.findBySql("SELECT current_schema() AS schema_name");
        assertEquals(PRIMARY_SCHEMA, String.valueOf(current.get(0).get("schema_name")));
        assertNull(Model.nativeSqlClient("mysql"));
        List<Map> report = Model.nativeSqlClient("report").query("SELECT note FROM report_order WHERE id=1");
        assertEquals("orm_b_note", report.get(0).get("note"));
    }

    @Test
    public void shutdownDisablesConfig() throws Exception {
        ShopOrder pending = new ShopOrder();
        pending.setStatus("pending-close");
        pending.setRegion("pg");
        pending.attr("label", "pending-close");
        assertTrue(pending.save());

        JPAConfig config = JPA.getJPAConfig();
        assertTrue(config.isEnabled());
        JPA.shutdown();
        assertFalse(config.isEnabled());
        assertNoOtherDatabaseSessions();
        // restore for @AfterClass-driven runs sharing the JVM
        JPA.CSDNORMConfiguration configuration = new JPA.CSDNORMConfiguration(
                "development", postgresSettings(), PostgresBusinessTest.class);
        JPA.configure(configuration);
    }

    private static void commit() {
        JPA.getJPAConfig().getJPAContext().closeTx(false);
    }

    private static Map<String, Object> params(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put(k1, v1);
        params.put(k2, v2);
        return params;
    }

    private static Settings postgresSettings() {
        return ImmutableSettings.settingsBuilder()
                .put("development.datasources.primary", "postgres")
                .put("development.datasources.mysql.disable", "true")
                .put("development.datasources.postgres.host", env.get("SF_COMPAT_PG_HOST"))
                .put("development.datasources.postgres.port", env.get("SF_COMPAT_PG_PORT"))
                .put("development.datasources.postgres.database", env.get("SF_COMPAT_PG_DATABASE"))
                .put("development.datasources.postgres.schema", PRIMARY_SCHEMA)
                .put("development.datasources.postgres.username", env.get("SF_COMPAT_PG_USER"))
                .put("development.datasources.postgres.password", env.get("SF_COMPAT_PG_PASSWORD"))
                .put("development.datasources.postgres.show_sql", "false")
                .put("development.datasources.postgres.initialSize", "1")
                .put("development.datasources.postgres.minIdle", "0")
                .put("development.datasources.postgres.maxActive", "2")
                .put("development.datasources.postgres.jdbc.connectTimeout", "5")
                .put("development.datasources.multi-postgres.report.host", env.get("SF_COMPAT_PG_HOST"))
                .put("development.datasources.multi-postgres.report.port", env.get("SF_COMPAT_PG_PORT"))
                .put("development.datasources.multi-postgres.report.database", env.get("SF_COMPAT_PG_DATABASE"))
                .put("development.datasources.multi-postgres.report.schema", REPORT_SCHEMA)
                .put("development.datasources.multi-postgres.report.username", env.get("SF_COMPAT_PG_USER"))
                .put("development.datasources.multi-postgres.report.password", env.get("SF_COMPAT_PG_PASSWORD"))
                .put("development.datasources.multi-postgres.report.show_sql", "false")
                .put("development.datasources.multi-postgres.report.initialSize", "1")
                .put("development.datasources.multi-postgres.report.minIdle", "0")
                .put("development.datasources.multi-postgres.report.maxActive", "1")
                .put("application.model", "net.csdn.ormtest.pg")
                .build();
    }

    private static void createSchemasAndTables() throws Exception {
        dropSchemas();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + PRIMARY_SCHEMA);
            statement.execute("CREATE SCHEMA " + REPORT_SCHEMA);
            statement.execute("CREATE TABLE " + PRIMARY_SCHEMA + ".customer (id INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, name VARCHAR(64) NULL)");
            statement.execute("CREATE TABLE " + PRIMARY_SCHEMA + ".shop_order (id INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, status VARCHAR(32) NULL, region VARCHAR(32) NULL, label VARCHAR(64) NULL, customer_id INTEGER NULL)");
            statement.execute("CREATE TABLE " + PRIMARY_SCHEMA + ".types_record (id INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, big_value BIGINT NULL, flag BOOLEAN NULL, amount NUMERIC(12,2) NULL, happened_at TIMESTAMP NULL, day DATE NULL, payload BYTEA NULL, token UUID NULL, label VARCHAR(64) NULL)");
            statement.execute("CREATE TABLE " + PRIMARY_SCHEMA + ".same_named (id INTEGER PRIMARY KEY, marker VARCHAR(8))");
            statement.execute("CREATE TABLE " + REPORT_SCHEMA + ".same_named (id INTEGER PRIMARY KEY, other VARCHAR(8), note VARCHAR(8))");
            statement.execute("CREATE TABLE " + REPORT_SCHEMA + ".report_order (id INTEGER PRIMARY KEY, note VARCHAR(32))");
            statement.execute("INSERT INTO " + REPORT_SCHEMA + ".report_order VALUES (1, 'orm_b_note')");
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

    private static void dropSchemas() throws Exception {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(15);
            statement.execute("SET statement_timeout = '15s'");
            statement.execute("SET lock_timeout = '5s'");
            statement.execute("DROP SCHEMA IF EXISTS " + PRIMARY_SCHEMA + " CASCADE");
            statement.execute("DROP SCHEMA IF EXISTS " + REPORT_SCHEMA + " CASCADE");
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
