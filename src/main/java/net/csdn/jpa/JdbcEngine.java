package net.csdn.jpa;

import net.csdn.common.settings.Settings;

import java.util.Locale;
import java.util.Map;

/**
 * Engine selection shared by metadata, Hibernate properties and pools.
 * Canonical PostgreSQL spelling is {@code postgres}; {@code postgresql} is an
 * accepted selector alias.
 */
public final class JdbcEngine {
    public static final String MYSQL = "mysql";
    public static final String POSTGRES = "postgres";

    private JdbcEngine() {
    }

    public static Selection primary(Settings settings, String mode) {
        if (settings == null || mode == null || mode.length() == 0) {
            throw new IllegalArgumentException("settings and mode are required");
        }
        String selected = trim(settings.get(mode + ".datasources.primary"));
        boolean explicit = selected != null;
        String engine = explicit ? normalize(selected) : MYSQL;
        Settings group = group(settings, mode, engine);
        boolean disabled = group != null && Boolean.TRUE.equals(group.getAsBoolean("disable", false));
        if (group == null && !explicit && Boolean.TRUE.equals(settings.getAsBoolean(
                mode + ".datasources.mysql.disable", false))) {
            return new Selection(MYSQL, null, true, false);
        }
        if (group == null) {
            throw new IllegalArgumentException("selected datasource " + engine
                    + " is missing; configure " + mode + ".datasources." + groupName(engine));
        }
        requireGroupEngine(group, engine, mode + ".datasources." + groupName(engine));
        return new Selection(engine, group, disabled, explicit);
    }

    public static boolean primaryDisabled(Settings settings, String mode) {
        return primary(settings, mode).disabled();
    }

    /**
     * Resolves the primary datasource, or returns {@code null} when the
     * configuration contains no datasource at all. Validation failures of a
     * configured datasource still propagate.
     */
    public static Selection primaryOrAbsent(Settings settings, String mode) {
        if (settings == null) {
            return null;
        }
        String selected = trim(settings.get(mode + ".datasources.primary"));
        if (selected != null) {
            return primary(settings, mode);
        }
        if (group(settings, mode, MYSQL) == null && group(settings, mode, POSTGRES) == null) {
            if (Boolean.TRUE.equals(settings.getAsBoolean(mode + ".datasources.mysql.disable", false))) {
                return new Selection(MYSQL, null, true, false);
            }
            return null;
        }
        return primary(settings, mode);
    }

    /**
     * Engine recorded on a datasource group ({@code engine} or {@code type}),
     * or {@code defaultEngine} when the group does not declare one.
     */
    public static String configuredEngine(Settings group, String defaultEngine) {
        if (group == null) {
            return normalize(defaultEngine);
        }
        String configured = trim(group.get("engine"));
        if (configured == null) {
            configured = trim(group.get("type"));
        }
        return configured == null ? normalize(defaultEngine) : normalize(configured);
    }

    public static Settings group(Settings settings, String mode, String engine) {
        Settings group = settings.getByPrefix(mode + ".datasources." + groupName(engine) + ".");
        return group.getAsMap().isEmpty() ? null : group;
    }

    public static String normalize(String value) {
        String normalized = trim(value);
        if (normalized == null) {
            throw new IllegalArgumentException("datasource engine is required");
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        if (MYSQL.equals(normalized)) {
            return MYSQL;
        }
        if (POSTGRES.equals(normalized) || "postgresql".equals(normalized)) {
            return POSTGRES;
        }
        throw new IllegalArgumentException("unsupported datasource engine " + value + "; use mysql or postgres");
    }

    public static String groupName(String engine) {
        return normalize(engine);
    }

    public static String groupEngine(Settings group, String defaultEngine) {
        if (group == null) {
            throw new IllegalArgumentException("datasource settings are required");
        }
        String configured = trim(group.get("engine"));
        if (configured == null) {
            configured = trim(group.get("type"));
        }
        String engine = configured == null ? normalize(defaultEngine) : normalize(configured);
        if (!engine.equals(normalize(defaultEngine))) {
            throw new IllegalArgumentException("datasource engine " + engine
                    + " does not match expected engine " + normalize(defaultEngine));
        }
        return engine;
    }

    public static void requireGroupEngine(Settings group, String engine, String location) {
        try {
            groupEngine(group, engine);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("invalid engine for " + location + ": " + failure.getMessage(), failure);
        }
    }

    public static String defaultDriver(String engine) {
        return POSTGRES.equals(normalize(engine)) ? "org.postgresql.Driver" : "com.mysql.cj.jdbc.Driver";
    }

    public static String defaultDialect(String engine) {
        return POSTGRES.equals(normalize(engine))
                ? "org.hibernate.dialect.PostgreSQLDialect"
                : "org.hibernate.dialect.MySQLDialect";
    }

    public static String defaultDbType(String engine) {
        return POSTGRES.equals(normalize(engine))
                ? "net.csdn.jpa.type.impl.PostgresType"
                : "net.csdn.jpa.type.impl.MysqlType";
    }

    public static String schema(Settings group) {
        String configured = trim(group == null ? null : group.get("schema"));
        String schema = configured == null ? "public" : configured;
        if (schema.length() > 1 && schema.charAt(0) == '"' && schema.charAt(schema.length() - 1) == '"') {
            schema = schema.substring(1, schema.length() - 1).replace("\"\"", "\"");
        }
        if (schema.length() == 0 || schema.indexOf(',') >= 0 || schema.indexOf(';') >= 0) {
            throw new IllegalArgumentException("postgres schema must be one schema name");
        }
        return schema;
    }

    /**
     * Schema rendered for identifier contexts: the Hibernate default_schema
     * property and the JDBC currentSchema/search_path value. A name that is
     * not a plain lower-case identifier is emitted as a double-quoted
     * identifier so mixed case survives both engines' identifier folding.
     */
    public static String schemaIdentifier(Settings group) {
        return searchPathValue(schema(group));
    }

    public static String jdbcUrl(Settings group, String engineName) {
        String engine = normalize(engineName);
        requireGroupEngine(group, engine, "datasource");
        String host = required(group, "host");
        String port = required(group, "port");
        String database = required(group, "database");
        Map<String, String> jdbcOpts = group.getByPrefix("jdbc.").getAsMap();
        StringBuilder query = new StringBuilder();
        if (MYSQL.equals(engine)) {
            query.append("?useUnicode=true&characterEncoding=utf8");
        } else {
            String schema = schema(group);
            String currentSchema = trim(jdbcOpts.get("currentSchema"));
            if (currentSchema != null && !schema.equals(unquote(currentSchema))) {
                throw new IllegalArgumentException("postgres schema and jdbc.currentSchema disagree");
            }
            appendOption(query, "currentSchema", searchPathValue(schema));
        }
        for (Map.Entry<String, String> entry : jdbcOpts.entrySet()) {
            if (POSTGRES.equals(engine) && "currentSchema".equals(entry.getKey())) {
                continue;
            }
            appendOption(query, entry.getKey(), entry.getValue());
        }
        String prefix = POSTGRES.equals(engine) ? "jdbc:postgresql://" : "jdbc:mysql://";
        return prefix + host + ":" + port + "/" + database + query;
    }

    private static String searchPathValue(String schema) {
        if (schema.matches("[a-z_][a-z0-9_]*")) {
            return schema;
        }
        return '"' + schema.replace("\"", "\"\"") + '"';
    }

    private static String unquote(String value) {
        String text = trim(value);
        if (text != null && text.length() > 1 && text.charAt(0) == '"' && text.charAt(text.length() - 1) == '"') {
            return text.substring(1, text.length() - 1).replace("\"\"", "\"");
        }
        return text;
    }

    private static void appendOption(StringBuilder query, String key, String value) {
        try {
            query.append(query.length() == 0 ? '?' : '&');
            query.append(key).append('=');
            query.append(java.net.URLEncoder.encode(value == null ? "" : value, "UTF-8"));
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException("jdbc option was not encoded", e);
        }
    }

    private static String required(Settings group, String key) {
        String value = trim(group.get(key));
        if (value == null) {
            throw new IllegalArgumentException("datasource " + key + " is required");
        }
        return value;
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() == 0 ? null : trimmed;
    }

    public static final class Selection {
        private final String engine;
        private final Settings group;
        private final boolean disabled;
        private final boolean explicit;

        private Selection(String engine, Settings group, boolean disabled, boolean explicit) {
            this.engine = engine;
            this.group = group;
            this.disabled = disabled;
            this.explicit = explicit;
        }

        public String engine() {
            return engine;
        }

        public String groupName() {
            return JdbcEngine.groupName(engine);
        }

        public Settings group() {
            return group;
        }

        public boolean disabled() {
            return disabled;
        }

        public boolean explicit() {
            return explicit;
        }
    }
}
