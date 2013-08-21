package net.csdn.modules.persist.mysql;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.pool.DruidDataSourceFactory;
import com.google.inject.Inject;
import net.csdn.common.settings.Settings;
import net.csdn.jpa.JPA;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * User: WilliamZhu
 * Date: 12-6-1
 * Time: 下午9:11
 */
public class DataSourceManager {

    private Map<String, DataSource> dataSourceMap;
    private Settings settings;


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
        Map<String, DataSource> tempDataSourceMap = new HashMap<String, DataSource>();
        Map<String, Settings> groups = settings.getGroups(JPA.mode() + ".datasources");
        for (Map.Entry<String, Settings> group : groups.entrySet()) {
            if (group.getKey().equals("mysql")) {
                tempDataSourceMap.put(group.getKey(), buildPool(group.getValue()));
            }
        }
        return tempDataSourceMap;
    }

    private DataSource buildPool(Settings mysqlSetting) {
        try {
            DruidDataSource dataSource = (DruidDataSource) DruidDataSourceFactory.createDataSource(JPA.properties());
            return dataSource;
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("can not create datasource");
        }
    }
}
