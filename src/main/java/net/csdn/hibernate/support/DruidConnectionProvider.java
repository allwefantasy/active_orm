package net.csdn.hibernate.support;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.pool.DruidDataSourceFactory;

import org.hibernate.service.jdbc.connections.spi.ConnectionProvider;
import org.hibernate.service.spi.Configurable;
import org.hibernate.service.spi.Stoppable;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

/**
 * Hibernate {@link ConnectionProvider} backed by a {@link DruidDataSource}.
 * The pool is configured from the persistence properties (druid keys such as
 * {@code url}, {@code username}, {@code password}, {@code driverClassName},
 * {@code maxActive}, {@code filters}...).
 */
public class DruidConnectionProvider implements ConnectionProvider, Configurable, Stoppable {

    private static final long serialVersionUID = 1026193803901107651L;

    private DruidDataSource dataSource;

    public DruidConnectionProvider() {
        dataSource = new DruidDataSource();
    }

    @SuppressWarnings("rawtypes")
    @Override
    public boolean isUnwrappableAs(Class unwrapType) {
        return dataSource.isWrapperFor(unwrapType);
    }

    @Override
    public <T> T unwrap(Class<T> unwrapType) {
        return dataSource.unwrap(unwrapType);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void closeConnection(Connection conn) throws SQLException {
        // A pooled connection returned with an open transaction keeps its
        // locks and blocks schema changes; try to roll it back, close it even
        // if rollback fails, and surface the first failure to the caller.
        SQLException failure = null;
        try {
            if (!conn.getAutoCommit()) {
                conn.rollback();
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            failure = e;
        }
        try {
            conn.close();
        } catch (SQLException e) {
            if (failure == null) {
                failure = e;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public boolean supportsAggressiveRelease() {
        return false;
    }

    @SuppressWarnings("rawtypes")
    @Override
    public void configure(Map configurationValues) {
        try {
            DruidDataSourceFactory.config(dataSource, configurationValues);
        } catch (SQLException e) {
            throw new IllegalArgumentException("druid datasource config error", e);
        }
    }

    @Override
    public void stop() {
        dataSource.close();
    }

}
