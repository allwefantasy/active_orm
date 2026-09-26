package net.csdn.jpa.context;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * BlogInfo: WilliamZhu
 * Date: 12-6-26
 * Time: 下午9:19
 */
public class JPAConfig {
    private final String configName;
    private EntityManagerFactory entityManagerFactory = null;
    private ThreadLocal<JPAContext> local = new ThreadLocal<JPAContext>();
    private final Set<JPAContext> contexts =
            Collections.synchronizedSet(new HashSet<JPAContext>());

    public JPAConfig(Map<String, String> _properties, String _configName) {
        this.configName = _configName;
        entityManagerFactory = Persistence.createEntityManagerFactory(configName, translate(_properties));
        if (entityManagerFactory == null) {
            throw new IllegalStateException(
                    "persistence unit " + configName + " was not created; check META-INF/persistence.xml generation");
        }
    }

    private JPAConfig(String configName) {
        this.configName = configName;
    }

    /**
     * A config with no EntityManagerFactory, used when the primary datasource
     * is disabled. isEnabled() stays false and entity access fails fast.
     */
    public static JPAConfig disabled(String configName) {
        return new JPAConfig(configName);
    }

    /**
     * Maps the legacy short-name property map onto the keys Hibernate and the
     * Druid connection provider actually read. The original keys are kept so
     * the Druid pool (configured through hibernate.connection.provider_class)
     * still sees {@code url}/{@code username}/{@code password}/{@code maxActive}
     * and friends.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Map translate(Map<String, String> legacy) {
        Properties properties = new Properties();
        if (legacy == null) {
            return properties;
        }
        for (Map.Entry<String, String> entry : legacy.entrySet()) {
            if (entry.getValue() != null) {
                properties.setProperty(entry.getKey(), entry.getValue());
            }
        }
        String driver = first(legacy, "driver_class", "driverClassName", "javax.persistence.jdbc.driver", "hibernate.connection.driver_class");
        String url = first(legacy, "url", "javax.persistence.jdbc.url", "hibernate.connection.url");
        String user = first(legacy, "username", "javax.persistence.jdbc.user", "hibernate.connection.username");
        String password = first(legacy, "password", "javax.persistence.jdbc.password", "hibernate.connection.password");
        String dialect = first(legacy, "dialect", "hibernate.dialect");
        put(properties, "javax.persistence.jdbc.driver", driver);
        put(properties, "javax.persistence.jdbc.url", url);
        put(properties, "javax.persistence.jdbc.user", user);
        put(properties, "javax.persistence.jdbc.password", password);
        put(properties, "hibernate.connection.driver_class", driver);
        put(properties, "hibernate.connection.url", url);
        put(properties, "hibernate.connection.username", user);
        put(properties, "hibernate.connection.password", password);
        put(properties, "hibernate.dialect", dialect);
        put(properties, "hibernate.show_sql", first(legacy, "show_sql", "hibernate.show_sql"));
        put(properties, "hibernate.format_sql", first(legacy, "format_sql", "hibernate.format_sql"));
        put(properties, "hibernate.hbm2ddl.auto", "none");
        put(properties, "hibernate.temp.use_jdbc_metadata_defaults", "false");
        put(properties, "hibernate.archive.autodetection", "none");
        if (driver != null) {
            properties.setProperty("driverClassName", driver);
        }
        return properties;
    }

    private static void put(Properties properties, String key, String value) {
        if (value != null) {
            properties.setProperty(key, value);
        }
    }

    private static String first(Map<String, String> legacy, String... keys) {
        for (String key : keys) {
            String value = legacy.get(key);
            if (value != null && value.length() > 0) {
                return value;
            }
        }
        return null;
    }

    public String getConfigName() {
        return configName;
    }

    public void close() {
        RuntimeException failure = null;
        try {
            closeAllJPAContexts();
        } catch (RuntimeException e) {
            failure = e;
        }
        if (isEnabled()) {
            try {
                entityManagerFactory.close();
            } catch (Exception e) {
                if (failure == null) {
                    failure = e instanceof RuntimeException
                            ? (RuntimeException) e
                            : new IllegalStateException("entity manager factory close failed", e);
                }
            }
            entityManagerFactory = null;
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * @return true 如果 entityManagerFactory 已经启动
     */
    public boolean isEnabled() {
        return entityManagerFactory != null;
    }


    public EntityManager newEntityManager() {
        if (!isEnabled()) {
            throw new IllegalStateException("datasource " + configName + " is disabled; no EntityManagerFactory");
        }
        return entityManagerFactory.createEntityManager();
    }

    public JPAContext getJPAContext() {
        JPAContext context = local.get();
        //因为是本地线程的，所以没必要担心多线程问题
        if (context == null) {
            context = new JPAContext(this);
            contexts.add(context);
            local.set(context);
        }
        return context;
    }

    /*
      @return old jpacontext
     */
    public JPAContext reInitJPAContext() {
        JPAContext oldContext = local.get();
        JPAContext context = new JPAContext(this);
        contexts.add(context);
        local.set(context);
        return oldContext;
    }

    public void setJPAContext(JPAContext context) {
        if (context == null) {
            local.remove();
            return;
        }
        contexts.add(context);
        local.set(context);
    }

    public void clearJPAContext() {
        JPAContext context = local.get();
        if (context != null) {
            try {
                context.close();
            } catch (Exception e) {
                // Let's it fail
            }
            local.remove();
        }
    }

    public void closeAllJPAContexts() {
        List<JPAContext> openContexts;
        synchronized (contexts) {
            openContexts = new ArrayList<JPAContext>(contexts);
        }
        RuntimeException failure = null;
        for (JPAContext context : openContexts) {
            try {
                context.close();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                }
            }
        }
        local.remove();
        if (failure != null) {
            throw failure;
        }
    }

    void unregisterContext(JPAContext context) {
        contexts.remove(context);
    }


}
