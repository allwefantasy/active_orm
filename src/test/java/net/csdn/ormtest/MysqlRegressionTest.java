package net.csdn.ormtest;

import net.csdn.common.settings.ImmutableSettings;
import net.csdn.common.settings.Settings;
import net.csdn.jpa.JPA;
import net.csdn.jpa.model.Model;
import net.csdn.jpa.type.DBInfo;
import net.csdn.jpa.type.impl.MysqlType;
import net.csdn.ormtest.my.Customer;
import net.csdn.ormtest.my.ShopOrder;
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
 * MySQL regression through the same ActiveORM path: enhancement, generated
 * ids, CRUD, association, commit/rollback and the default pool. Opt-in via
 * {@code -Dsf.orm.mysql=true} or {@code SF_ORM_MYSQL=true}; the switch
 * requires {@code SF_COMPAT_ENV_FILE}. A skip is not acceptance.
 */
public class MysqlRegressionTest {

    private static Map<String, String> env;

    @BeforeClass
    public static void requireServiceAndConfigure() throws Exception {
        Assume.assumeTrue(
                "sf.orm.mysql is not enabled; a skip is not acceptance",
                mysqlRequested());
        String path = System.getenv("SF_COMPAT_ENV_FILE");
        if (path == null || path.length() == 0) {
            fail("sf.orm.mysql is enabled but SF_COMPAT_ENV_FILE is missing");
        }
        env = readEnv(new File(path));
        for (String key : new String[]{
                "SF_COMPAT_MYSQL_HOST", "SF_COMPAT_MYSQL_PORT", "SF_COMPAT_MYSQL_USER",
                "SF_COMPAT_MYSQL_PASSWORD", "SF_COMPAT_MYSQL_DATABASE"}) {
            if (env.get(key) == null || env.get(key).length() == 0) {
                fail("compat env is missing " + key);
            }
        }
        if (!"sf_compat".equals(env.get("SF_COMPAT_MYSQL_DATABASE"))) {
            fail("refusing to use a database other than sf_compat");
        }
        createTables();
        JPA.CSDNORMConfiguration configuration = new JPA.CSDNORMConfiguration(
                "development", mysqlSettings(), MysqlRegressionTest.class);
        JPA.configure(configuration);
    }

    @AfterClass
    public static void cleanup() throws Exception {
        JPA.shutdown();
        if (env != null) {
            assertNoOtherDatabaseConnections();
            dropTables();
        }
    }

    @Test
    public void metadataUsesMysql() {
        assertTrue(JPA.dbType() instanceof MysqlType);
        DBInfo info = JPA.dbInfo();
        assertFalse(info.isDisabled());
        assertEquals("mysql", info.engine());
        assertTrue(info.tableNames.contains("customer"));
        assertTrue(info.tableNames.contains("shop_order"));
        Map<String, String> customer = info.tableColumns.get("Customer");
        assertNotNull(customer);
        assertTrue(customer.containsKey("name"));
    }

    @Test
    public void crudIdsAssociationAndPagination() {
        Customer customer = new Customer();
        customer.setName("mysql-customer");
        assertTrue(customer.save());
        Integer customerId = customer.getId();
        assertNotNull(customerId);

        for (int i = 0; i < 3; i++) {
            ShopOrder order = new ShopOrder();
            order.setStatus("open");
            order.setRegion("my");
            order.attr("label", "my-" + i);
            order.setCustomer(customer);
            assertTrue(order.save());
            assertNotNull(order.getId());
        }
        commit();

        List<ShopOrder> page = ShopOrder.where(
                "status=:status and region=:region",
                params("status", "open", "region", "my"))
                .offset(1).limit(1).fetch();
        assertEquals(1, page.size());
        assertEquals("my-1", page.get(0).attr("label", String.class));
        assertEquals(customerId, page.get(0).getCustomer().getId());

        ShopOrder detached = (ShopOrder) ShopOrder.findById(page.get(0).getId());
        assertNotNull(detached);
        assertEquals("my-1", detached.attr("label", String.class));
    }

    @Test
    public void rollbackDropsRow() {
        ShopOrder rolled = new ShopOrder();
        rolled.setStatus("rolled");
        rolled.setRegion("my");
        rolled.attr("label", "rolled");
        assertTrue(rolled.save());
        Integer rolledId = rolled.getId();
        assertNotNull(rolledId);
        JPA.getJPAConfig().getJPAContext().closeTx(true);
        assertNull(ShopOrder.findById(rolledId));
    }

    @Test
    public void nativeSqlDefaultsToMysql() {
        List<Map> current = Model.findBySql("SELECT DATABASE() AS db_name");
        assertEquals("sf_compat", String.valueOf(current.get(0).get("db_name")));
        assertNotNull(Model.nativeSqlClient());
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

    private static Settings mysqlSettings() {
        return ImmutableSettings.settingsBuilder()
                .put("development.datasources.mysql.host", env.get("SF_COMPAT_MYSQL_HOST"))
                .put("development.datasources.mysql.port", env.get("SF_COMPAT_MYSQL_PORT"))
                .put("development.datasources.mysql.database", env.get("SF_COMPAT_MYSQL_DATABASE"))
                .put("development.datasources.mysql.username", env.get("SF_COMPAT_MYSQL_USER"))
                .put("development.datasources.mysql.password", env.get("SF_COMPAT_MYSQL_PASSWORD"))
                .put("development.datasources.mysql.show_sql", "false")
                .put("development.datasources.mysql.initialSize", "1")
                .put("development.datasources.mysql.minIdle", "0")
                .put("development.datasources.mysql.maxActive", "2")
                .put("application.model", "net.csdn.ormtest.my")
                .build();
    }

    private static void createTables() throws Exception {
        dropTables();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE customer (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(64) NULL)");
            statement.execute("CREATE TABLE shop_order (id INT AUTO_INCREMENT PRIMARY KEY, status VARCHAR(32) NULL, region VARCHAR(32) NULL, label VARCHAR(64) NULL, customer_id INT NULL)");
        }
    }

    private static void assertNoOtherDatabaseConnections() throws Exception {
        try (Connection connection = open(); Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT count(*) FROM information_schema.PROCESSLIST"
                             + " WHERE ID <> CONNECTION_ID() AND DB = DATABASE()")) {
            assertTrue(resultSet.next());
            assertEquals("database connections remained open after JPA.shutdown",
                    0, resultSet.getInt(1));
        }
    }

    private static void dropTables() throws Exception {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(15);
            statement.execute("SET SESSION lock_wait_timeout = 10");
            statement.execute("DROP TABLE IF EXISTS shop_order");
            statement.execute("DROP TABLE IF EXISTS customer");
        }
    }

    private static Connection open() throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        return DriverManager.getConnection(
                "jdbc:mysql://" + env.get("SF_COMPAT_MYSQL_HOST") + ":" + env.get("SF_COMPAT_MYSQL_PORT")
                        + "/" + env.get("SF_COMPAT_MYSQL_DATABASE") + "?useUnicode=true&characterEncoding=utf8&connectTimeout=5000",
                env.get("SF_COMPAT_MYSQL_USER"), env.get("SF_COMPAT_MYSQL_PASSWORD"));
    }

    private static boolean mysqlRequested() {
        return Boolean.parseBoolean(System.getProperty("sf.orm.mysql", "false"))
                || "true".equalsIgnoreCase(System.getenv("SF_ORM_MYSQL"));
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
