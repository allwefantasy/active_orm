package net.csdn.modules.persist.mysql;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.pool.DruidDataSourceFactory;
import com.google.inject.Inject;
import net.csdn.common.logging.CSLogger;
import net.csdn.common.logging.Loggers;
import net.csdn.common.settings.Settings;
import net.csdn.jpa.JPA;
import net.csdn.jpa.JdbcEngine;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * User: WilliamZhu
 * Date: 12-6-1
 * Time: 下午9:11
 *
 * Builds Druid pools for every configured datasource group:
 * {@code <mode>.datasources.mysql|postgres}, and every named child of
 * {@code <mode>.datasources.multi-mysql|multi-postgres}. Groups marked
 * {@code disable: true} are skipped. Group listing uses getByPrefix because
 * the scalar {@code datasources.primary} selector would make getGroups throw.
 */
public class DataSourceManager {

    private Map<String, DataSource> dataSourceMap;
    private Settings settings;
    private CSLogger logger = Loggers.getLogger(DataSourceManager.class);

    @Inject
    public DataSourceManager(Settings _settings) {
        this.settings = _settings;
        dataSourceMap = buildDataSourceMap();
    }

    public DataSource datasource(String name) {
        return dataSourceMap.get(name);
    }

    public Map<String, DataSource> dataSourceMap() {
        return dataSourceMap;
    }


    private Map<String, DataSource> buildDataSourceMap() {
        Map<String, DataSource> tempDataSourceMap = new LinkedHashMap<String, DataSource>();
        String datasourcesPrefix = JPA.mode() + ".datasources.";
        Map<String, String> flat = settings.getByPrefix(datasourcesPrefix).getAsMap();
        Set<String> groups = new LinkedHashSet<String>();
        for (String key : flat.keySet()) {
            int dot = key.indexOf('.');
            if (dot > 0) {
                groups.add(key.substring(0, dot));
            }
        }
        for (String groupName : groups) {
            if (JdbcEngine.MYSQL.equals(groupName) || JdbcEngine.POSTGRES.equals(groupName)) {
                Settings group = settings.getByPrefix(datasourcesPrefix + groupName + ".");
                addPool(tempDataSourceMap, groupName, group, JdbcEngine.configuredEngine(group, groupName));
            } else if (groupName.startsWith("multi-")) {
                String engine = groupName.substring("multi-".length());
                String multiPrefix = datasourcesPrefix + groupName + ".";
                Map<String, String> multiFlat = settings.getByPrefix(multiPrefix).getAsMap();
                Set<String> names = new LinkedHashSet<String>();
                for (String key : multiFlat.keySet()) {
                    int dot = key.indexOf('.');
                    if (dot > 0) {
                        names.add(key.substring(0, dot));
                    }
                }
                for (String name : names) {
                    Settings group = settings.getByPrefix(multiPrefix + name + ".");
                    addPool(tempDataSourceMap, name, group, JdbcEngine.configuredEngine(group, engine));
                }
            }
        }
        return tempDataSourceMap;
    }

    private void addPool(Map<String, DataSource> pools, String name, Settings group, String engine) {
        if (Boolean.TRUE.equals(group.getAsBoolean("disable", false))) {
            logger.info("datasource " + name + " is disabled; skip pool");
            return;
        }
        logger.info("初始化连接池:" + name);
        pools.put(name, buildPool(group, engine));
    }

    private DataSource buildPool(Settings datasourceSetting, String engine) {
        try {
            DruidDataSource dataSource = (DruidDataSource) DruidDataSourceFactory.createDataSource(JPA.properties(datasourceSetting, engine));
            return dataSource;
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("can not create datasource for engine " + engine, e);
        }
    }
}
