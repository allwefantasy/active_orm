# PostgreSQL support

ActiveORM supports **MySQL** (the historical default) and **PostgreSQL** as the
primary datasource of a standalone application. Both engines share the same
enhanced-model API; the engine is chosen in settings, not code.

## Requirements

- **Java 8 minimum.** The build targets `1.8` and model classes must be Java-8
  bytecode — the enhancer rewrites them with javassist and rejects newer
  class-file versions. Verified on OpenJDK 8 (1.8.0_504) and OpenJDK 17
  (17.0.20.1): the live suites — `PostgresBusinessTest` (6 tests),
  `PostgresMixedSchemaTest` (5 tests) and `MysqlRegressionTest` (4 tests) —
  pass on both.
- **PostgreSQL driver** is `provided`-scoped (`org.postgresql:postgresql`
  42.7.13): applications must supply pgjdbc themselves.
- **MySQL driver** ships transitively as `com.mysql:mysql-connector-j` 8.4.0.
  The `mysql:mysql-connector-java` 5.1.6 that `csdn-common` used to drag in is
  excluded — it cannot talk to MySQL 8 (`tx_isolation` was renamed
  `transaction_isolation`). Custom `driver` settings still work.

## Configuration

Engine selection lives under `<mode>.datasources`:

```yaml
development:
  datasources:
    primary: postgres            # optional; absent = mysql (legacy default)
    mysql:
      disable: true              # groups may also be disabled outright
    postgres:
      host: 127.0.0.1
      port: 5432
      database: mydb
      schema: app                # optional; default "public"
      username: app
      password: secret
      # optional extras: driver, dialect, provider_class, maxActive,
      # initialSize, jdbc.<pgjdbc-option> (e.g. jdbc.connectTimeout)
```

- `postgres` is the canonical engine name; `postgresql` is accepted as an alias.
- `schema` may be written raw (`app`) or double-quoted (`"App"`) — surrounding
  quotes plus `""` escaping are accepted. JDBC metadata always sees the exact
  name, while `hibernate.default_schema` and the JDBC `currentSchema`
  (`search_path` startup parameter) render the same canonical identifier:
  plain lower-case names stay bare and everything else is emitted as a
  double-quoted identifier. Mixed-case schemas such as `SfvMixed` therefore
  work for both native SQL and enhanced-model writes; an unquoted rendering
  would fold to `sfvmixed` and fail.
- `jdbc.currentSchema` may be set explicitly, spelled raw or double-quoted,
  but it must name the same schema as `schema`; a disagreeing pair is an
  error, and a matching value is rewritten to the canonical rendering.
- Named extra pools work for both engines:
  `datasources.multi-postgres.<name>.*` and `datasources.multi-mysql.<name>.*`,
  reached via `Model.nativeSqlClient("<name>")`. The default native client
  (`Model.findBySql` / `Model.nativeSqlClient()`) follows `datasources.primary`.

## Engine notes

- Generated IDs use `GenerationType.IDENTITY` on both engines — MySQL
  `AUTO_INCREMENT` and PostgreSQL `IDENTITY`/`serial` columns.
- `java.util.UUID` fields map to PostgreSQL `uuid` columns via
  `PostgresUUIDType` (the Hibernate default would send `bytea`).
- Schema metadata (`JPA.dbInfo()`) is catalog- and schema-scoped on PostgreSQL,
  so `pg_catalog`/`information_schema` tables never leak into enhancement.

## Lifecycle

- `JPA.configure(...)` builds the Druid pools for every configured group.
- `JPA.shutdown()` closes every registered `JPAContext` (rolling back any
  still-open transaction), then the `EntityManagerFactory`, then the native
  pools, and reports the first failure instead of swallowing it.
- On JDK 9+ each model package needs an `ActiveOrmPackageAnchor` class so
  javassist can define the enhanced classes; JDK 8 does not.

## Building and testing

```bash
mvn -s <settings.xml> test
```

The live suites are opt-in and fail when their env is missing:

- `SF_ORM_PG=true` — needs `SF_COMPAT_PG_ENV_FILE` pointing at a file with
  `SF_COMPAT_PG_{HOST,PORT,USER,PASSWORD,DATABASE}` keys. Covers
  `PostgresBusinessTest` (lower-case `orm_a`/`orm_b` schemas) and
  `PostgresMixedSchemaTest` (mixed-case `SfvMixed`, including raw/quoted
  schema spellings and an explicit `jdbc.currentSchema`).
- `SF_ORM_MYSQL=true` — needs `SF_COMPAT_ENV_FILE` with `SF_COMPAT_MYSQL_*`
  keys.

Any private loopback PostgreSQL/MySQL works; the tests assert the configured
database is `sf_compat` and create/drop their own schemas (`orm_a`, `orm_b`,
`SfvMixed`) and tables. Cleanup is bounded (`lock_timeout`/`statement_timeout`/`setQueryTimeout`)
and asserts shutdown released every database session — it never terminates
backend sessions to force a pass.

## Limitations

- **Standalone, global configuration.** One `JPA.configure(...)` per JVM — this
  is a single global ORM context, not the framework's per-request/multi-context
  model. This is not parity with `serviceframework-orm`.
- **Never mix jars.** Do not put this artifact and `serviceframework-orm` on
  the same classpath; they are separate enhancer generations.
