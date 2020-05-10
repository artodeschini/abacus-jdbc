/*
 * Copyright (C) 2015 HaiYang Li
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.landawn.abacus.util;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.landawn.abacus.DataSet;
import com.landawn.abacus.DataSourceManager;
import com.landawn.abacus.DataSourceSelector;
import com.landawn.abacus.DirtyMarker;
import com.landawn.abacus.EntityId;
import com.landawn.abacus.IsolationLevel;
import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.condition.Condition;
import com.landawn.abacus.condition.ConditionFactory.CF;
import com.landawn.abacus.core.DirtyMarkerUtil;
import com.landawn.abacus.core.Seid;
import com.landawn.abacus.dataSource.SQLDataSource;
import com.landawn.abacus.exception.DuplicatedResultException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.logging.Logger;
import com.landawn.abacus.logging.LoggerFactory;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.EntityInfo;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.type.TypeFactory;
import com.landawn.abacus.util.ExceptionalStream.StreamE;
import com.landawn.abacus.util.Fn.IntFunctions;
import com.landawn.abacus.util.Fn.Suppliers;
import com.landawn.abacus.util.JdbcUtil.BiParametersSetter;
import com.landawn.abacus.util.JdbcUtil.BiRowMapper;
import com.landawn.abacus.util.JdbcUtil.NamedQuery;
import com.landawn.abacus.util.JdbcUtil.RowExtractor;
import com.landawn.abacus.util.JdbcUtil.RowFilter;
import com.landawn.abacus.util.JdbcUtil.RowMapper;
import com.landawn.abacus.util.SQLBuilder.NAC;
import com.landawn.abacus.util.SQLBuilder.NLC;
import com.landawn.abacus.util.SQLBuilder.NSC;
import com.landawn.abacus.util.SQLBuilder.PAC;
import com.landawn.abacus.util.SQLBuilder.PLC;
import com.landawn.abacus.util.SQLBuilder.PSC;
import com.landawn.abacus.util.SQLBuilder.SP;
import com.landawn.abacus.util.SQLTransaction.CreatedBy;
import com.landawn.abacus.util.StringUtil.Strings;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.Tuple.Tuple3;
import com.landawn.abacus.util.u.Nullable;
import com.landawn.abacus.util.u.Optional;
import com.landawn.abacus.util.u.OptionalBoolean;
import com.landawn.abacus.util.u.OptionalByte;
import com.landawn.abacus.util.u.OptionalChar;
import com.landawn.abacus.util.u.OptionalDouble;
import com.landawn.abacus.util.u.OptionalFloat;
import com.landawn.abacus.util.u.OptionalInt;
import com.landawn.abacus.util.u.OptionalLong;
import com.landawn.abacus.util.u.OptionalShort;
import com.landawn.abacus.util.function.BiConsumer;
import com.landawn.abacus.util.function.BiFunction;
import com.landawn.abacus.util.function.BinaryOperator;
import com.landawn.abacus.util.function.Consumer;
import com.landawn.abacus.util.function.Function;
import com.landawn.abacus.util.function.Predicate;
import com.landawn.abacus.util.function.Supplier;
import com.landawn.abacus.util.stream.Collector;
import com.landawn.abacus.util.stream.ObjIteratorEx;
import com.landawn.abacus.util.stream.Stream;
import com.landawn.abacus.util.stream.Stream.StreamEx;

// TODO: Auto-generated Javadoc
/**
 * SQLExecutor is a simple sql/jdbc utility class. SQL is supported with different format: <br />
 *
 * <pre>
 *
 * <li> <code>INSERT INTO account (first_name, last_name, gui, last_update_time, create_time) VALUES (?,  ?,  ?,  ?,  ?)</code></li>
 * <li> <code>INSERT INTO account (first_name, last_name, gui, last_update_time, create_time) VALUES (#{firstName}, #{lastName}, #{gui}, #{lastUpdateTime}, #{createTime})</code></li>
 * <li> <code>INSERT INTO account (first_name, last_name, gui, last_update_time, create_time) VALUES (:firstName, :lastName, :gui, :lastUpdateTime, :createTime)</code></li>
 *
 * All these kinds of SQLs can be generated by <code>SQLBuilder</code> conveniently. Parameters with format of Object[]/List parameters are supported for parameterized SQL({@code id = ?}).
 * Parameters with format of Object[]/List/Map/Entity are supported for named parameterized SQL({@code id = :id}).
 * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
 * </pre>
 *
 * Here is sample of CRUD(create/read/update/delete):
 * <br />========================================================================
 * <pre>
 * <code>
 * static final DataSource dataSource = JdbcUtil.createDataSource(...);
 * static final SQLExecutor sqlExecutor = new SQLExecutor(dataSource);
 * ...
 * Account account = createAccount();
 *
 * // create
 * String sql_insert = NE.insert(GUI, FIRST_NAME, LAST_NAME, LAST_UPDATE_TIME, CREATE_TIME).into(Account.class).sql();
 * N.println(sql_insert);
 * sqlExecutor.insert(sql_insert, account);
 *
 * // read
 * String sql_selectByGUI = NE.selectFrom(Account.class, N.asSet(DEVICES)).where(L.eq(GUI, L.QME)).sql();
 * N.println(sql_selectByGUI);
 * Account dbAccount = sqlExecutor.findFirst(Account.class, sql_selectByGUI, account);
 * assertEquals(account.getFirstName(), dbAccount.getFirstName());
 *
 * // update
 * String sql_updateByLastName = NE.update(Account.class).set(FIRST_NAME).where(L.eq(LAST_NAME, L.QME)).sql();
 * N.println(sql_updateByLastName);
 * dbAccount.setFirstName("newFirstName");
 * sqlExecutor.update(sql_updateByLastName, dbAccount);
 *
 * // delete
 * String sql_deleteByFirstName = NE.deleteFrom(Account.class).where(L.eq(FIRST_NAME, L.QME)).sql();
 * N.println(sql_deleteByFirstName);
 * sqlExecutor.update(sql_deleteByFirstName, dbAccount);
 *
 * dbAccount = sqlExecutor.findFirst(Account.class, sql_selectByGUI, account);
 * assertNull(dbAccount);
 * </code>
 * </pre>
 * ========================================================================
 * <br />
 * <br />
 * If {@code conn} argument is null or not specified, {@code SQLExecutor} is responsible to get the connection from the
 * internal {@code DataSource}, start and commit/roll back transaction for batch operations if needed, and close the
 * connection finally. otherwise it's user's responsibility to do such jobs if {@code conn} is specified and not null. <br />
 * <br />
 *
 * Transaction can be started:
 * <pre>
 * <code>
 * final SQLTransaction tran = sqlExecutor.beginTransaction(IsolationLevel.READ_COMMITTED);
 *
 * try {
 *     // sqlExecutor.insert(...);
 *     // sqlExecutor.update(...);
 *     // sqlExecutor.query(...);
 *
 *     tran.commit();
 * } finally {
 *     // The connection will be automatically closed after the transaction is committed or rolled back.
 *     tran.rollbackIfNotCommitted();
 * }
 * </code>
 * </pre>
 *
 *
 * Spring Transaction is also supported and Integrated.
 * If a method of this class is called where a Spring transaction is started with the {@code DataSource} inside this {@code SQLExecutor}, without {@code Connection} parameter specified,
 * the {@code Connection} started the Spring Transaction will be used. Otherwise a {@code Connection} directly from the inside {@code DataSource}(Connection pool) will be borrowed and used.
 *
 *
 * SQLExecutor is tread-safe.<br /><br />
 *
 * @author Haiyang Li
 * @see <a href="./JdbcUtil.html">JdbcUtil</a>
 * @see com.landawn.abacus.annotation.ReadOnly
 * @see com.landawn.abacus.annotation.ReadOnlyId
 * @see com.landawn.abacus.annotation.NonUpdatable
 * @see com.landawn.abacus.annotation.Transient
 * @see com.landawn.abacus.annotation.Table
 * @see com.landawn.abacus.annotation.Column
 * @see com.landawn.abacus.condition.ConditionFactory
 * @see com.landawn.abacus.condition.ConditionFactory.CF
 * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/Connection.html">http://docs.oracle.com/javase/8/docs/api/java/sql/Connection.html</a>
 * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/Statement.html">http://docs.oracle.com/javase/8/docs/api/java/sql/Statement.html</a>
 * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/PreparedStatement.html">http://docs.oracle.com/javase/8/docs/api/java/sql/PreparedStatement.html</a>
 * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/ResultSet.html">http://docs.oracle.com/javase/8/docs/api/java/sql/ResultSet.html</a>
 * @since 0.8
 */
public class SQLExecutor {

    /** The Constant logger. */
    private static final Logger logger = LoggerFactory.getLogger(SQLExecutor.class);

    /** The Constant ID. */
    static final String ID = "id";

    /** The Constant QUERY_WITH_DATA_SOURCE. */
    static final String QUERY_WITH_DATA_SOURCE = "queryWithDataSource";

    /** The Constant EXISTS_RESULT_SET_EXTRACTOR. */
    private static final ResultExtractor<Boolean> EXISTS_RESULT_SET_EXTRACTOR = new ResultExtractor<Boolean>() {
        @Override
        public Boolean apply(final ResultSet rs, final JdbcSettings jdbcSettings) throws SQLException {
            JdbcUtil.skip(rs, jdbcSettings.getOffset());

            return rs.next();
        }
    };

    /** The Constant COUNT_RESULT_SET_EXTRACTOR. */
    private static final ResultExtractor<Integer> COUNT_RESULT_SET_EXTRACTOR = new ResultExtractor<Integer>() {
        @Override
        public Integer apply(final ResultSet rs, final JdbcSettings jdbcSettings) throws SQLException {
            JdbcUtil.skip(rs, jdbcSettings.getOffset());

            int cnt = 0;

            while (rs.next()) {
                cnt++;
            }

            return cnt;
        }
    };

    /** The Constant SINGLE_BOOLEAN_EXTRACTOR. */
    private static final ResultExtractor<OptionalBoolean> SINGLE_BOOLEAN_EXTRACTOR = new ResultExtractor<OptionalBoolean>() {
        @Override
        public OptionalBoolean apply(final ResultSet rs, final JdbcSettings jdbcSettings) throws SQLException {
            JdbcUtil.skip(rs, jdbcSettings.getOffset());

            if (rs.next()) {
                return OptionalBoolean.of(rs.getBoolean(1));
            }

            return OptionalBoolean.empty();
        }
    };

    /** The Constant charType. */
    private static final Type<Character> charType = TypeFactory.getType(char.class);

    /** The Constant SINGLE_CHAR_EXTRACTOR. */
    private static final ResultExtractor<OptionalChar> SINGLE_CHAR_EXTRACTOR = new ResultExtractor<OptionalChar>() {
        @Override
        public OptionalChar apply(final ResultSet rs, final JdbcSettings jdbcSettings) throws SQLException {
            JdbcUtil.skip(rs, jdbcSettings.getOffset());

            if (rs.next()) {
                return OptionalChar.of(charType.get(rs, 1));
            }

            return OptionalChar.empty();
        }
    };

    /** The Constant SINGLE_BYTE_EXTRACTOR. */
    private static final ResultExtractor<OptionalByte> SINGLE_BYTE_EXTRACTOR = new ResultExtractor<OptionalByte>() {
        @Override
        public OptionalByte apply(final ResultSet rs, final JdbcSettings jdbcSettings) throws SQLException {
            JdbcUtil.skip(rs, jdbcSettings.getOffset());

            if (rs.next()) {
                return OptionalByte.of(rs.getByte(1));
            }

            return OptionalByte.empty();
        }
    };

    /** The Constant SINGLE_SHORT_EXTRACTOR. */
    private static final ResultExtractor<OptionalShort> SINGLE_SHORT_EXTRACTOR = new ResultExtractor<OptionalShort>() {
        @Override
        public OptionalShort apply(final ResultSet rs, final JdbcSettings jdbcSettings) throws SQLException {
            JdbcUtil.skip(rs, jdbcSettings.getOffset());

            if (rs.next()) {
                return OptionalShort.of(rs.getShort(1));
            }

            return OptionalShort.empty();
        }
    };

    /** The Constant SINGLE_INT_EXTRACTOR. */
    private static final ResultExtractor<OptionalInt> SINGLE_INT_EXTRACTOR = new ResultExtractor<OptionalInt>() {
        @Override
        public OptionalInt apply(final ResultSet rs, final JdbcSettings jdbcSettings) throws SQLException {
            JdbcUtil.skip(rs, jdbcSettings.getOffset());

            if (rs.next()) {
                return OptionalInt.of(rs.getInt(1));
            }

            return OptionalInt.empty();
        }
    };

    /** The Constant SINGLE_LONG_EXTRACTOR. */
    private static final ResultExtractor<OptionalLong> SINGLE_LONG_EXTRACTOR = new ResultExtractor<OptionalLong>() {
        @Override
        public OptionalLong apply(final ResultSet rs, final JdbcSettings jdbcSettings) throws SQLException {
            JdbcUtil.skip(rs, jdbcSettings.getOffset());

            if (rs.next()) {
                return OptionalLong.of(rs.getLong(1));
            }

            return OptionalLong.empty();
        }
    };

    /** The Constant SINGLE_FLOAT_EXTRACTOR. */
    private static final ResultExtractor<OptionalFloat> SINGLE_FLOAT_EXTRACTOR = new ResultExtractor<OptionalFloat>() {
        @Override
        public OptionalFloat apply(final ResultSet rs, final JdbcSettings jdbcSettings) throws SQLException {
            JdbcUtil.skip(rs, jdbcSettings.getOffset());

            if (rs.next()) {
                return OptionalFloat.of(rs.getFloat(1));
            }

            return OptionalFloat.empty();
        }
    };

    /** The Constant SINGLE_DOUBLE_EXTRACTOR. */
    private static final ResultExtractor<OptionalDouble> SINGLE_DOUBLE_EXTRACTOR = new ResultExtractor<OptionalDouble>() {
        @Override
        public OptionalDouble apply(final ResultSet rs, final JdbcSettings jdbcSettings) throws SQLException {
            JdbcUtil.skip(rs, jdbcSettings.getOffset());

            if (rs.next()) {
                return OptionalDouble.of(rs.getDouble(1));
            }

            return OptionalDouble.empty();
        }
    };

    /** The Constant SINGLE_BIG_DECIMAL_EXTRACTOR. */
    private static final ResultExtractor<Nullable<BigDecimal>> SINGLE_BIG_DECIMAL_EXTRACTOR = new ResultExtractor<Nullable<BigDecimal>>() {
        @Override
        public Nullable<BigDecimal> apply(final ResultSet rs, final JdbcSettings jdbcSettings) throws SQLException {
            JdbcUtil.skip(rs, jdbcSettings.getOffset());

            if (rs.next()) {
                return Nullable.of(rs.getBigDecimal(1));
            }

            return Nullable.empty();
        }
    };

    /** The Constant SINGLE_STRING_EXTRACTOR. */
    private static final ResultExtractor<Nullable<String>> SINGLE_STRING_EXTRACTOR = new ResultExtractor<Nullable<String>>() {
        @Override
        public Nullable<String> apply(final ResultSet rs, final JdbcSettings jdbcSettings) throws SQLException {
            JdbcUtil.skip(rs, jdbcSettings.getOffset());

            if (rs.next()) {
                return Nullable.of(rs.getString(1));
            }

            return Nullable.empty();
        }
    };

    /** The Constant SINGLE_DATE_EXTRACTOR. */
    private static final ResultExtractor<Nullable<Date>> SINGLE_DATE_EXTRACTOR = new ResultExtractor<Nullable<Date>>() {
        @Override
        public Nullable<Date> apply(final ResultSet rs, final JdbcSettings jdbcSettings) throws SQLException {
            JdbcUtil.skip(rs, jdbcSettings.getOffset());

            if (rs.next()) {
                return Nullable.of(rs.getDate(1));
            }

            return Nullable.empty();
        }
    };

    /** The Constant SINGLE_TIME_EXTRACTOR. */
    private static final ResultExtractor<Nullable<Time>> SINGLE_TIME_EXTRACTOR = new ResultExtractor<Nullable<Time>>() {
        @Override
        public Nullable<Time> apply(final ResultSet rs, final JdbcSettings jdbcSettings) throws SQLException {
            JdbcUtil.skip(rs, jdbcSettings.getOffset());

            if (rs.next()) {
                return Nullable.of(rs.getTime(1));
            }

            return Nullable.empty();
        }
    };

    /** The Constant SINGLE_TIMESTAMP_EXTRACTOR. */
    private static final ResultExtractor<Nullable<Timestamp>> SINGLE_TIMESTAMP_EXTRACTOR = new ResultExtractor<Nullable<Timestamp>>() {
        @Override
        public Nullable<Timestamp> apply(final ResultSet rs, final JdbcSettings jdbcSettings) throws SQLException {
            JdbcUtil.skip(rs, jdbcSettings.getOffset());

            if (rs.next()) {
                return Nullable.of(rs.getTimestamp(1));
            }

            return Nullable.empty();
        }
    };

    /** The Constant factor. */
    private static final int factor = Math.min(Math.max(1, IOUtil.MAX_MEMORY_IN_MB / 1024), 8);

    /** The Constant CACHED_SQL_LENGTH. */
    private static final int CACHED_SQL_LENGTH = 1024 * factor;

    /** The Constant SQL_CACHE_SIZE. */
    private static final int SQL_CACHE_SIZE = 1000 * factor;

    /** The Constant _sqlColumnLabelPool. */
    private static final Map<String, ImmutableList<String>> _sqlColumnLabelPool = new ConcurrentHashMap<>();

    /** The table column name pool. */
    private final Map<String, ImmutableList<String>> _tableColumnNamePool = new ConcurrentHashMap<>();

    /** The ds. */
    private final DataSource _ds;

    /** The dsm. */
    private final DataSourceManager _dsm;

    /** The dss. */
    private final DataSourceSelector _dss;

    /** The jdbc settings. */
    private final JdbcSettings _jdbcSettings;

    /** The sql mapper. */
    private final SQLMapper _sqlMapper;

    /** The naming policy. */
    private final NamingPolicy _namingPolicy;

    /** The async executor. */
    private final AsyncExecutor _asyncExecutor;

    /** The is read only. */
    private final boolean _isReadOnly;

    /** The db proudct name. */
    private final String _dbProudctName;

    /** The db proudct version. */
    private final String _dbProudctVersion;

    /** The db version. */
    private final DBVersion _dbVersion;

    /** The default isolation level. */
    private final IsolationLevel _defaultIsolationLevel;

    /** The async SQL executor. */
    private final AsyncSQLExecutor _asyncSQLExecutor;

    @SuppressWarnings("rawtypes")
    private final Map<Class<?>, Mapper> mapperPool = new ConcurrentHashMap<>();

    @SuppressWarnings("rawtypes")
    private final Map<Class<?>, MapperL> mapperLPool = new ConcurrentHashMap<>();

    @SuppressWarnings("rawtypes")
    private final Map<Class<?>, MapperEx> mapperExPool = new ConcurrentHashMap<>();

    @SuppressWarnings("rawtypes")
    private final Map<Class<?>, MapperLEx> mapperExLPool = new ConcurrentHashMap<>();

    /**
     * Instantiates a new SQL executor.
     *
     * @param dataSource
     * @see JdbcUtil#createDataSource(String)
     * @see JdbcUtil#createDataSource(java.io.InputStream)
     */
    public SQLExecutor(final DataSource dataSource) {
        this(dataSource, null);
    }

    /**
     * Instantiates a new SQL executor.
     *
     * @param dataSource
     * @param jdbcSettings
     * @see JdbcUtil#createDataSource(String)
     * @see JdbcUtil#createDataSource(java.io.InputStream)
     */
    public SQLExecutor(final DataSource dataSource, final JdbcSettings jdbcSettings) {
        this(dataSource, jdbcSettings, null);
    }

    /**
     * Instantiates a new SQL executor.
     *
     * @param dataSource
     * @param jdbcSettings
     * @param sqlMapper
     * @see JdbcUtil#createDataSource(String)
     * @see JdbcUtil#createDataSource(java.io.InputStream)
     */
    public SQLExecutor(final DataSource dataSource, final JdbcSettings jdbcSettings, final SQLMapper sqlMapper) {
        this(dataSource, jdbcSettings, sqlMapper, null);
    }

    /**
     * Instantiates a new SQL executor.
     *
     * @param dataSource
     * @param jdbcSettings
     * @param sqlMapper
     * @param namingPolicy
     * @see JdbcUtil#createDataSourceManager(String)
     * @see JdbcUtil#createDataSourceManager(java.io.InputStream)
     */
    public SQLExecutor(final DataSource dataSource, final JdbcSettings jdbcSettings, final SQLMapper sqlMapper, final NamingPolicy namingPolicy) {
        this(dataSource, jdbcSettings, sqlMapper, namingPolicy, null);
    }

    /**
     * Instantiates a new SQL executor.
     *
     * @param dataSource
     * @param jdbcSettings
     * @param sqlMapper
     * @param namingPolicy
     * @param asyncExecutor
     * @see JdbcUtil#createDataSource(String)
     * @see JdbcUtil#createDataSource(java.io.InputStream)
     */
    public SQLExecutor(final DataSource dataSource, final JdbcSettings jdbcSettings, final SQLMapper sqlMapper, final NamingPolicy namingPolicy,
            final AsyncExecutor asyncExecutor) {
        this(null, dataSource, jdbcSettings, sqlMapper, namingPolicy, asyncExecutor, false);
    }

    /**
     * Instantiates a new SQL executor.
     *
     * @param dataSourceManager
     * @see JdbcUtil#createDataSourceManager(String)
     * @see JdbcUtil#createDataSourceManager(java.io.InputStream)
     */
    public SQLExecutor(final DataSourceManager dataSourceManager) {
        this(dataSourceManager, null);
    }

    /**
     * Instantiates a new SQL executor.
     *
     * @param dataSourceManager
     * @param jdbcSettings
     * @see JdbcUtil#createDataSourceManager(String)
     * @see JdbcUtil#createDataSourceManager(java.io.InputStream)
     */
    public SQLExecutor(final DataSourceManager dataSourceManager, final JdbcSettings jdbcSettings) {
        this(dataSourceManager, jdbcSettings, null);
    }

    /**
     * Instantiates a new SQL executor.
     *
     * @param dataSourceManager
     * @param jdbcSettings
     * @param sqlMapper
     * @see JdbcUtil#createDataSourceManager(String)
     * @see JdbcUtil#createDataSourceManager(java.io.InputStream)
     */
    public SQLExecutor(final DataSourceManager dataSourceManager, final JdbcSettings jdbcSettings, final SQLMapper sqlMapper) {
        this(dataSourceManager, jdbcSettings, sqlMapper, null);
    }

    /**
     * Instantiates a new SQL executor.
     *
     * @param dataSourceManager
     * @param jdbcSettings
     * @param sqlMapper
     * @param namingPolicy
     * @see JdbcUtil#createDataSourceManager(String)
     * @see JdbcUtil#createDataSourceManager(java.io.InputStream)
     */
    public SQLExecutor(final DataSourceManager dataSourceManager, final JdbcSettings jdbcSettings, final SQLMapper sqlMapper, final NamingPolicy namingPolicy) {
        this(dataSourceManager, jdbcSettings, sqlMapper, namingPolicy, null);
    }

    /**
     * Instantiates a new SQL executor.
     *
     * @param dataSourceManager
     * @param jdbcSettings
     * @param sqlMapper
     * @param namingPolicy
     * @param asyncExecutor
     * @see JdbcUtil#createDataSourceManager(String)
     * @see JdbcUtil#createDataSourceManager(java.io.InputStream)
     */
    public SQLExecutor(final DataSourceManager dataSourceManager, final JdbcSettings jdbcSettings, final SQLMapper sqlMapper, final NamingPolicy namingPolicy,
            final AsyncExecutor asyncExecutor) {
        this(dataSourceManager, null, jdbcSettings, sqlMapper, namingPolicy, asyncExecutor, false);
    }

    /**
     * Instantiates a new SQL executor.
     *
     * @param dataSourceManager
     * @param dataSource
     * @param jdbcSettings
     * @param sqlMapper
     * @param namingPolicy
     * @param asyncExecutor
     * @param isReadOnly
     */
    protected SQLExecutor(final DataSourceManager dataSourceManager, final DataSource dataSource, final JdbcSettings jdbcSettings, final SQLMapper sqlMapper,
            final NamingPolicy namingPolicy, final AsyncExecutor asyncExecutor, final boolean isReadOnly) {

        if (dataSourceManager == null) {
            this._ds = dataSource;
            this._dsm = null;
            this._dss = null;
        } else {
            this._ds = dataSourceManager.getPrimaryDataSource();
            this._dsm = dataSourceManager;
            this._dss = dataSourceManager.getDataSourceSelector();
        }

        this._jdbcSettings = (jdbcSettings == null) ? JdbcSettings.create() : jdbcSettings.copy();

        if (_jdbcSettings.getBatchSize() == 0) {
            _jdbcSettings.setBatchSize(JdbcSettings.DEFAULT_BATCH_SIZE);
        }

        _jdbcSettings.freeze();

        this._sqlMapper = sqlMapper == null ? new SQLMapper() : sqlMapper;
        this._namingPolicy = namingPolicy == null ? NamingPolicy.LOWER_CASE_WITH_UNDERSCORE : namingPolicy;
        this._asyncExecutor = asyncExecutor == null ? new AsyncExecutor(8, Math.max(32, IOUtil.CPU_CORES), 180L, TimeUnit.SECONDS) : asyncExecutor;
        this._isReadOnly = isReadOnly;

        int originalIsolationLevel;
        Connection conn = getConnection();

        try {
            _dbProudctName = conn.getMetaData().getDatabaseProductName();
            _dbProudctVersion = conn.getMetaData().getDatabaseProductVersion();
            _dbVersion = JdbcUtil.getDBVersion(conn);
            originalIsolationLevel = conn.getTransactionIsolation();
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            closeConnection(conn);
        }

        final IsolationLevel tmp = this._ds instanceof SQLDataSource ? ((SQLDataSource) this._ds).getDefaultIsolationLevel() : IsolationLevel.DEFAULT;
        _defaultIsolationLevel = tmp == IsolationLevel.DEFAULT ? IsolationLevel.valueOf(originalIsolationLevel) : tmp;

        this._asyncSQLExecutor = new AsyncSQLExecutor(this, _asyncExecutor);
    }

    //    public static SQLExecutor create(final String dataSourceFile) {
    //        return new SQLExecutor(JdbcUtil.createDataSourceManager(dataSourceFile));
    //    }
    //
    //    public static SQLExecutor create(final InputStream dataSourceInputStream) {
    //        return new SQLExecutor(JdbcUtil.createDataSourceManager(dataSourceInputStream));
    //    }
    //
    //    public static SQLExecutor create(final String url, final String user, final String password) {
    //        return new SQLExecutor(JdbcUtil.createDataSource(url, user, password));
    //    }
    //
    //    public static SQLExecutor create(final String driver, final String url, final String user, final String password) {
    //        return new SQLExecutor(JdbcUtil.createDataSource(driver, url, user, password));
    //    }
    //
    //    public static SQLExecutor create(final Class<? extends Driver> driverClass, final String url, final String user, final String password) {
    //        return new SQLExecutor(JdbcUtil.createDataSource(driverClass, url, user, password));
    //    }
    //
    //    /**
    //     *
    //     * @param props refer to Connection.xsd for the supported properties.
    //     * @return
    //     */
    //    public static SQLExecutor create(final Map<String, ?> props) {
    //        return new SQLExecutor(JdbcUtil.createDataSource(props));
    //    }
    //
    //    public static SQLExecutor create(final DataSource sqlDataSource) {
    //        return new SQLExecutor(JdbcUtil.wrap(sqlDataSource));
    //    }

    //
    //    public SQLMapper sqlMapper() {
    //        return _sqlMapper;
    //    }

    /**
     *
     * @param url
     * @param user
     * @param password
     * @return
     */
    @Beta
    public static SQLExecutor create(final String url, final String user, final String password) {
        return new SQLExecutor(JdbcUtil.createDataSource(url, user, password));
    }

    /**
     *
     * @param driver
     * @param url
     * @param user
     * @param password
     * @return
     */
    @Beta
    public static SQLExecutor create(final String driver, final String url, final String user, final String password) {
        return new SQLExecutor(JdbcUtil.createDataSource(driver, url, user, password));
    }

    /**
     *
     * @param driverClass
     * @param url
     * @param user
     * @param password
     * @return
     */
    @Beta
    public static SQLExecutor create(final Class<? extends Driver> driverClass, final String url, final String user, final String password) {
        return new SQLExecutor(JdbcUtil.createDataSource(driverClass, url, user, password));
    }

    /**
     *
     * @param driverClass
     * @param url
     * @param user
     * @param password
     * @return
     */
    @Beta
    public static SQLExecutor create(final DataSource dataSource) {
        return new SQLExecutor(dataSource);
    }

    /**
     *
     * @param <T>
     * @param <ID>
     * @param entityClass the id class
     * @param idClass the id class type of target id property.
     * It should be {@code Void} class if there is no id property defined for the target entity, or {@code EntityId} class if there is zero or multiple id properties.
     * @return
     */
    public <T, ID> Mapper<T, ID> mapper(final Class<T> entityClass, final Class<ID> idClass) {
        synchronized (mapperPool) {
            Mapper<T, ID> mapper = mapperPool.get(entityClass);

            if (mapper == null) {
                mapper = new Mapper<>(entityClass, idClass, this, this._namingPolicy);
                mapperPool.put(entityClass, mapper);
            } else if (!mapper.idClass.equals(idClass)) {
                throw new IllegalArgumentException("Mapper for entity \"" + ClassUtil.getSimpleClassName(entityClass)
                        + "\" has already been created with different id class: " + mapper.idClass);
            }

            return mapper;
        }
    }

    public <T> MapperL<T> mapper(final Class<T> entityClass) {
        synchronized (mapperLPool) {
            MapperL<T> mapper = mapperLPool.get(entityClass);

            if (mapper == null) {
                mapper = new MapperL<>(entityClass, this, this._namingPolicy);
                mapperLPool.put(entityClass, mapper);
            }

            return mapper;
        }
    }

    /**
     *
     * @param <T>
     * @param <ID>
     * @param entityClass the id class
     * @param idClass the id class type of target id property.
     * It should be {@code Void} class if there is no id property defined for the target entity, or {@code EntityId} class if there is zero or multiple id properties.
     * @return
     */
    public <T, ID> MapperEx<T, ID> mapperEx(final Class<T> entityClass, final Class<ID> idClass) {
        synchronized (mapperExPool) {
            MapperEx<T, ID> mapper = mapperExPool.get(entityClass);

            if (mapper == null) {
                mapper = new MapperEx<>(entityClass, idClass, this, this._namingPolicy);
                mapperExPool.put(entityClass, mapper);
            } else if (!mapper.idClass.equals(idClass)) {
                throw new IllegalArgumentException("MapperEx for entity \"" + ClassUtil.getSimpleClassName(entityClass)
                        + "\" has already been created with different id class: " + mapper.idClass);
            }

            return mapper;
        }
    }

    public <T> MapperLEx<T> mapperEx(final Class<T> entityClass) {
        synchronized (mapperExLPool) {
            MapperLEx<T> mapper = mapperExLPool.get(entityClass);

            if (mapper == null) {
                mapper = new MapperLEx<>(entityClass, this, this._namingPolicy);
                mapperExLPool.put(entityClass, mapper);
            }

            return mapper;
        }
    }

    /**
     *
     * @return
     */
    public AsyncSQLExecutor async() {
        return _asyncSQLExecutor;
    }

    /**
     *
     * @return
     */
    public DataSource dataSource() {
        return _ds;
    }

    /**
     *
     * @return
     */
    public JdbcSettings jdbcSettings() {
        return _jdbcSettings;
    }

    /**
     * Db proudct name.
     *
     * @return
     */
    public String dbProudctName() {
        return _dbProudctName;
    }

    /**
     * Db proudct version.
     *
     * @return
     */
    public String dbProudctVersion() {
        return _dbProudctVersion;
    }

    /**
     *
     * @return
     */
    public DBVersion dbVersion() {
        return _dbVersion;
    }

    /**
     *
     * @param <ID>
     * @param sql
     * @param parameters
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SafeVarargs
    public final <ID> ID insert(final String sql, final Object... parameters) throws UncheckedSQLException {
        return insert(sql, StatementSetter.DEFAULT, parameters);
    }

    /**
     *
     * @param <ID>
     * @param sql
     * @param statementSetter
     * @param parameters
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SafeVarargs
    public final <ID> ID insert(final String sql, final StatementSetter statementSetter, final Object... parameters) throws UncheckedSQLException {
        return insert(sql, statementSetter, null, parameters);
    }

    /**
     *
     * @param <ID>
     * @param sql
     * @param jdbcSettings
     * @param parameters
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SafeVarargs
    public final <ID> ID insert(final String sql, final JdbcSettings jdbcSettings, final Object... parameters) throws UncheckedSQLException {
        return insert(sql, StatementSetter.DEFAULT, jdbcSettings, parameters);
    }

    /**
     *
     * @param <ID>
     * @param sql
     * @param statementSetter
     * @param jdbcSettings
     * @param parameters
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SafeVarargs
    public final <ID> ID insert(final String sql, final StatementSetter statementSetter, final JdbcSettings jdbcSettings, final Object... parameters)
            throws UncheckedSQLException {
        return insert(sql, statementSetter, null, jdbcSettings, parameters);
    }

    /**
     *
     * @param <ID>
     * @param sql
     * @param statementSetter
     * @param autoGeneratedKeyExtractor
     * @param jdbcSettings
     * @param parameters
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SafeVarargs
    public final <ID> ID insert(final String sql, final StatementSetter statementSetter, final JdbcUtil.BiRowMapper<ID> autoGeneratedKeyExtractor,
            final JdbcSettings jdbcSettings, final Object... parameters) throws UncheckedSQLException {
        return insert(null, sql, statementSetter, autoGeneratedKeyExtractor, jdbcSettings, parameters);
    }

    /**
     *
     * @param <ID>
     * @param conn
     * @param sql
     * @param parameters
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SafeVarargs
    public final <ID> ID insert(final Connection conn, final String sql, final Object... parameters) throws UncheckedSQLException {
        return insert(conn, sql, StatementSetter.DEFAULT, parameters);
    }

    /**
     *
     * @param <ID>
     * @param conn
     * @param sql
     * @param statementSetter
     * @param parameters
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SafeVarargs
    public final <ID> ID insert(final Connection conn, final String sql, final StatementSetter statementSetter, final Object... parameters)
            throws UncheckedSQLException {
        return insert(conn, sql, statementSetter, null, parameters);
    }

    /**
     *
     * @param <ID>
     * @param conn
     * @param sql
     * @param jdbcSettings
     * @param parameters
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public final <ID> ID insert(final Connection conn, final String sql, final JdbcSettings jdbcSettings, final Object... parameters)
            throws UncheckedSQLException {
        return insert(conn, sql, StatementSetter.DEFAULT, jdbcSettings, parameters);
    }

    /**
     *
     * @param <ID>
     * @param conn
     * @param sql
     * @param statementSetter
     * @param jdbcSettings
     * @param parameters
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SafeVarargs
    public final <ID> ID insert(final Connection conn, final String sql, StatementSetter statementSetter, JdbcSettings jdbcSettings, final Object... parameters)
            throws UncheckedSQLException {
        return insert(conn, sql, statementSetter, null, jdbcSettings, parameters);
    }

    /**
     *
     * @param <ID>
     * @param conn
     * @param sql
     * @param statementSetter
     * @param autoGeneratedKeyExtractor
     * @param jdbcSettings
     * @param parameters
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @see #batchInsert(Connection, String, StatementSetter, JdbcSettings, String, Object[])
     */
    @SuppressWarnings({ "unchecked", "deprecation" })
    @SafeVarargs
    public final <ID> ID insert(final Connection conn, final String sql, StatementSetter statementSetter, JdbcUtil.BiRowMapper<ID> autoGeneratedKeyExtractor,
            JdbcSettings jdbcSettings, final Object... parameters) throws UncheckedSQLException {
        final ParsedSql parsedSql = getParsedSql(sql);
        final boolean isEntityOrMapParameter = isEntityOrMapParameter(parsedSql, parameters);
        final boolean isEntity = isEntityOrMapParameter && ClassUtil.isEntity(parameters[0].getClass());
        final Collection<String> idPropNames = isEntity ? ClassUtil.getIdFieldNames(parameters[0].getClass()) : null;
        final boolean autoGeneratedKeys = isEntity == false || (N.notNullOrEmpty(idPropNames) && !parsedSql.getNamedParameters().containsAll(idPropNames));

        statementSetter = checkStatementSetter(parsedSql, statementSetter);
        jdbcSettings = checkJdbcSettings(jdbcSettings, parsedSql, _sqlMapper.getAttrs(sql));
        autoGeneratedKeyExtractor = checkGeneratedKeysExtractor(autoGeneratedKeyExtractor, jdbcSettings, parameters);

        DataSource ds = null;
        Connection localConn = null;
        Object id = null;
        PreparedStatement stmt = null;

        try {
            ds = getDataSource(parsedSql.getParameterizedSql(), parameters, jdbcSettings);

            localConn = getConnection(conn, ds, jdbcSettings, SQLOperation.INSERT);

            stmt = prepareStatement(ds, localConn, parsedSql, statementSetter, jdbcSettings, autoGeneratedKeys, false, parameters);

            id = executeInsert(parsedSql, stmt, autoGeneratedKeyExtractor, autoGeneratedKeys);
        } catch (SQLException e) {
            String msg = ExceptionUtil.getMessage(e) + ". [SQL] " + parsedSql.sql();
            throw new UncheckedSQLException(msg, e);
        } finally {
            close(stmt);
            close(localConn, conn, ds);
        }

        if (isEntityOrMapParameter && isEntity) {
            final Object entity = parameters[0];

            if (id == null) {
                id = getIdGetter(entity).apply(entity);
            } else {
                getIdSetter(entity).accept(id, entity);
            }

            if (entity instanceof DirtyMarker) {
                DirtyMarkerUtil.dirtyPropNames((DirtyMarker) parameters[0]).clear();
            }
        }

        return (ID) id;
    }

    static <ID> JdbcUtil.BiRowMapper<ID> checkGeneratedKeysExtractor(JdbcUtil.BiRowMapper<ID> autoGeneratedKeyExtractor, final JdbcSettings jdbcSettings,
            final Object... parameters) {
        if ((autoGeneratedKeyExtractor == null || autoGeneratedKeyExtractor == JdbcUtil.SINGLE_BI_GENERATED_KEY_EXTRACTOR
                || autoGeneratedKeyExtractor == JdbcUtil.MULTI_BI_GENERATED_KEY_EXTRACTOR) //
                && N.notNullOrEmpty(parameters) && parameters.length == 1 && parameters[0] != null && ClassUtil.isEntity(parameters[0].getClass())) {
            return (JdbcUtil.BiRowMapper<ID>) JdbcUtil.getIdGeneratorGetterSetter(parameters[0].getClass(), NamingPolicy.LOWER_CASE_WITH_UNDERSCORE)._1;
        } else if (autoGeneratedKeyExtractor == null) {
            if (jdbcSettings != null && ((N.notNullOrEmpty(jdbcSettings.getReturnedColumnIndexes()) && jdbcSettings.getReturnedColumnIndexes().length > 1)
                    || (N.notNullOrEmpty(jdbcSettings.getReturnedColumnNames()) && jdbcSettings.getReturnedColumnNames().length > 1))) {
                return (JdbcUtil.BiRowMapper<ID>) JdbcUtil.MULTI_BI_GENERATED_KEY_EXTRACTOR;
            } else {
                return (JdbcUtil.BiRowMapper<ID>) JdbcUtil.SINGLE_BI_GENERATED_KEY_EXTRACTOR;
            }
        }

        return autoGeneratedKeyExtractor;
    }

    static <ID> Function<Object, ID> getIdGetter(final Object entity) {
        return (Function<Object, ID>) JdbcUtil.getIdGeneratorGetterSetter(entity == null ? null : entity.getClass(),
                NamingPolicy.LOWER_CASE_WITH_UNDERSCORE)._2;
    }

    static <ID> BiConsumer<ID, Object> getIdSetter(final Object entity) {
        return (BiConsumer<ID, Object>) JdbcUtil.getIdGeneratorGetterSetter(entity == null ? null : entity.getClass(),
                NamingPolicy.LOWER_CASE_WITH_UNDERSCORE)._3;
    }

    protected <ID> ID executeInsert(final ParsedSql parsedSql, final PreparedStatement stmt, final JdbcUtil.BiRowMapper<ID> autoGeneratedKeyExtractor,
            final boolean autoGeneratedKeys) throws SQLException {
        if (_isReadOnly) {
            throw new RuntimeException("This SQL Executor is configured for read-only");
        }

        JdbcUtil.executeUpdate(stmt);

        ID id = null;

        if (autoGeneratedKeys) {
            ResultSet rs = null;

            try {
                rs = stmt.getGeneratedKeys();
                id = rs.next() ? autoGeneratedKeyExtractor.apply(rs, JdbcUtil.getColumnLabelList(rs)) : null;
            } catch (SQLException e) {
                logger.error("Failed to retrieve the auto-generated Ids", e);
            } finally {
                close(rs);
            }
        }

        return id;
    }

    /**
     *
     * @param <ID>
     * @param sql
     * @param parametersList
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public <ID> List<ID> batchInsert(final String sql, final List<?> parametersList) throws UncheckedSQLException {
        return batchInsert(sql, StatementSetter.DEFAULT, parametersList);
    }

    /**
     *
     * @param <ID>
     * @param sql
     * @param statementSetter
     * @param parametersList
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public <ID> List<ID> batchInsert(final String sql, final StatementSetter statementSetter, final List<?> parametersList) throws UncheckedSQLException {
        return batchInsert(sql, statementSetter, null, parametersList);
    }

    /**
     *
     * @param <ID>
     * @param sql
     * @param jdbcSettings
     * @param parametersList
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public <ID> List<ID> batchInsert(final String sql, final JdbcSettings jdbcSettings, final List<?> parametersList) throws UncheckedSQLException {
        return batchInsert(sql, StatementSetter.DEFAULT, jdbcSettings, parametersList);
    }

    /**
     *
     * @param <ID>
     * @param sql
     * @param statementSetter
     * @param jdbcSettings
     * @param parametersList
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public <ID> List<ID> batchInsert(final String sql, final StatementSetter statementSetter, final JdbcSettings jdbcSettings, final List<?> parametersList)
            throws UncheckedSQLException {
        return batchInsert(sql, statementSetter, null, jdbcSettings, parametersList);
    }

    /**
     *
     * @param <ID>
     * @param sql
     * @param statementSetter
     * @param autoGeneratedKeyExtractor
     * @param jdbcSettings
     * @param parametersList
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public <ID> List<ID> batchInsert(final String sql, final StatementSetter statementSetter, final JdbcUtil.BiRowMapper<ID> autoGeneratedKeyExtractor,
            final JdbcSettings jdbcSettings, final List<?> parametersList) throws UncheckedSQLException {
        return batchInsert(null, sql, statementSetter, autoGeneratedKeyExtractor, jdbcSettings, parametersList);
    }

    /**
     *
     * @param <ID>
     * @param conn
     * @param sql
     * @param parametersList
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public <ID> List<ID> batchInsert(final Connection conn, final String sql, final List<?> parametersList) throws UncheckedSQLException {
        return batchInsert(conn, sql, StatementSetter.DEFAULT, parametersList);
    }

    /**
     *
     * @param <ID>
     * @param conn
     * @param sql
     * @param statementSetter
     * @param parametersList
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public <ID> List<ID> batchInsert(final Connection conn, final String sql, final StatementSetter statementSetter, final List<?> parametersList)
            throws UncheckedSQLException {
        return batchInsert(conn, sql, statementSetter, null, parametersList);
    }

    /**
     *
     * @param <ID>
     * @param conn
     * @param sql
     * @param jdbcSettings
     * @param parametersList
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public <ID> List<ID> batchInsert(final Connection conn, final String sql, final JdbcSettings jdbcSettings, final List<?> parametersList)
            throws UncheckedSQLException {
        return batchInsert(conn, sql, StatementSetter.DEFAULT, jdbcSettings, parametersList);
    }

    /**
     *
     * @param <ID>
     * @param conn
     * @param sql
     * @param statementSetter
     * @param jdbcSettings
     * @param parametersList
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public <ID> List<ID> batchInsert(final Connection conn, final String sql, StatementSetter statementSetter, JdbcSettings jdbcSettings,
            final List<?> parametersList) throws UncheckedSQLException {
        return batchInsert(conn, sql, statementSetter, null, jdbcSettings, parametersList);
    }

    /**
     *
     * @param <ID>
     * @param conn
     * @param sql
     * @param statementSetter
     * @param autoGeneratedKeyExtractor
     * @param jdbcSettings
     * @param parametersList
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("deprecation")
    public <ID> List<ID> batchInsert(final Connection conn, final String sql, StatementSetter statementSetter,
            JdbcUtil.BiRowMapper<ID> autoGeneratedKeyExtractor, JdbcSettings jdbcSettings, final List<?> parametersList) throws UncheckedSQLException {
        N.checkArgNotNullOrEmpty(parametersList, "parametersList");

        final ParsedSql parsedSql = getParsedSql(sql);
        final Object parameters_0 = parametersList.get(0);
        final boolean isEntityOrMapParameter = isEntityOrMapParameter(parsedSql, parameters_0);
        final boolean isEntity = isEntityOrMapParameter && ClassUtil.isEntity(parameters_0.getClass());
        final Collection<String> idPropNames = isEntity ? ClassUtil.getIdFieldNames(parameters_0.getClass()) : null;
        final boolean autoGeneratedKeys = isEntity == false || (N.notNullOrEmpty(idPropNames) && !parsedSql.getNamedParameters().containsAll(idPropNames));

        statementSetter = checkStatementSetter(parsedSql, statementSetter);
        jdbcSettings = checkJdbcSettings(jdbcSettings, parsedSql, _sqlMapper.getAttrs(sql));
        autoGeneratedKeyExtractor = checkGeneratedKeysExtractor(autoGeneratedKeyExtractor, jdbcSettings, parametersList.get(0));

        final int len = parametersList.size();
        final int batchSize = getBatchSize(jdbcSettings);

        List<ID> ids = new ArrayList<>(len);

        DataSource ds = null;
        Connection localConn = null;
        PreparedStatement stmt = null;
        int originalIsolationLevel = 0;
        boolean autoCommit = true;
        final Object[] parameters = new Object[1];

        try {
            ds = getDataSource(parsedSql.getParameterizedSql(), parametersList, jdbcSettings);

            localConn = getConnection(conn, ds, jdbcSettings, SQLOperation.INSERT);

            try {
                originalIsolationLevel = localConn.getTransactionIsolation();
                autoCommit = localConn.getAutoCommit();
            } catch (SQLException e) {
                close(localConn, conn, ds);
                throw new UncheckedSQLException(e);
            }

            if ((conn == null) && (len > batchSize)) {
                localConn.setAutoCommit(false);

                setIsolationLevel(jdbcSettings, localConn);
            }

            stmt = prepareStatement(ds, localConn, parsedSql, statementSetter, jdbcSettings, autoGeneratedKeys, true, parametersList);

            if (len <= batchSize) {
                for (int i = 0; i < len; i++) {
                    parameters[0] = parametersList.get(i);

                    statementSetter.accept(parsedSql, stmt, parameters);
                    stmt.addBatch();
                }

                executeBatchInsert(ids, parsedSql, stmt, autoGeneratedKeyExtractor, autoGeneratedKeys);
            } else {
                int num = 0;

                for (int i = 0; i < len; i++) {
                    parameters[0] = parametersList.get(i);

                    statementSetter.accept(parsedSql, stmt, parameters);
                    stmt.addBatch();
                    num++;

                    if ((num % batchSize) == 0) {
                        executeBatchInsert(ids, parsedSql, stmt, autoGeneratedKeyExtractor, autoGeneratedKeys);
                    }
                }

                if ((num % batchSize) > 0) {
                    executeBatchInsert(ids, parsedSql, stmt, autoGeneratedKeyExtractor, autoGeneratedKeys);
                }
            }

            if ((conn == null) && (len > batchSize) && autoCommit == true) {
                localConn.commit();
            }
        } catch (SQLException e) {
            if ((conn == null) && (len > batchSize) && autoCommit == true) {
                if (logger.isWarnEnabled()) {
                    logger.warn("Trying to roll back ...");
                }

                try {
                    localConn.rollback();

                    if (logger.isWarnEnabled()) {
                        logger.warn("succeeded to roll back");
                    }
                } catch (SQLException e1) {
                    logger.error("Failed to roll back", e1);
                }
            }

            String msg = ExceptionUtil.getMessage(e) + ". [SQL] " + parsedSql.sql();
            throw new UncheckedSQLException(msg, e);
        } finally {
            if ((conn == null) && (len > batchSize)) {
                try {
                    localConn.setAutoCommit(autoCommit);
                    localConn.setTransactionIsolation(originalIsolationLevel);
                } catch (SQLException e) {
                    logger.error("Failed to reset AutoCommit", e);
                }
            }

            close(stmt);
            close(localConn, conn, ds);
        }

        if (N.notNullOrEmpty(ids) && Stream.of(ids).allMatch(Fn.isNull())) {
            ids = new ArrayList<>();
        }

        if (N.notNullOrEmpty(ids) && ids.size() != parametersList.size()) {
            if (logger.isWarnEnabled()) {
                logger.warn("The size of returned id list: {} is different from the size of input parameter list: {}", ids.size(), parametersList.size());
            }
        }

        if (parametersList.get(0) != null && isEntityOrMapParameter(parsedSql, parametersList.get(0)) && ClassUtil.isEntity(parametersList.get(0).getClass())) {
            final Object entity = parametersList.get(0);

            if (N.isNullOrEmpty(ids)) {
                final Function<Object, ID> idGetter = getIdGetter(entity);

                ids = Stream.of(parametersList).map(idGetter).toList();
            } else {
                final BiConsumer<ID, Object> idSetter = getIdSetter(entity);

                if (ids.size() == len) {
                    for (int i = 0; i < len; i++) {
                        idSetter.accept(ids.get(i), parametersList.get(i));
                    }
                } else {
                    if (logger.isWarnEnabled()) {
                        logger.warn(
                                "Failed to set the returned id property to entity/map. because the size of returned key not equals the lenght of the input arrray");
                    }
                }
            }

            if (entity instanceof DirtyMarker) {
                for (Object e : parametersList) {
                    DirtyMarkerUtil.dirtyPropNames((DirtyMarker) e).clear();
                }
            }
        }

        return ids;
    }

    /**
     * Sets the isolation level.
     *
     * @param jdbcSettings
     * @param localConn
     * @throws SQLException the SQL exception
     */
    private void setIsolationLevel(JdbcSettings jdbcSettings, Connection localConn) throws SQLException {
        final int isolationLevel = jdbcSettings.getIsolationLevel() == null || jdbcSettings.getIsolationLevel() == IsolationLevel.DEFAULT
                ? _defaultIsolationLevel.intValue()
                : jdbcSettings.getIsolationLevel().intValue();

        if (isolationLevel == localConn.getTransactionIsolation()) {
            // ignore.
        } else {
            localConn.setTransactionIsolation(isolationLevel);
        }
    }

    /**
     * Execute batch insert.
     *
     * @param <ID>
     * @param resultIdList
     * @param parsedSql
     * @param stmt
     * @param autoGeneratedKeyExtractor
     * @param autoGeneratedKeys
     * @throws SQLException the SQL exception
     */
    protected <ID> void executeBatchInsert(final List<ID> resultIdList, final ParsedSql parsedSql, final PreparedStatement stmt,
            final JdbcUtil.BiRowMapper<ID> autoGeneratedKeyExtractor, final boolean autoGeneratedKeys) throws SQLException {
        if (_isReadOnly) {
            throw new RuntimeException("This SQL Executor is configured for read-only");
        }

        executeBatch(stmt);

        if (autoGeneratedKeys) {
            ResultSet rs = null;

            try {
                rs = stmt.getGeneratedKeys();
                final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

                while (rs.next()) {
                    resultIdList.add(autoGeneratedKeyExtractor.apply(rs, columnLabels));
                }
            } catch (SQLException e) {
                logger.error("Failed to retrieve the auto-generated Ids", e);
            } finally {
                close(rs);
            }
        }
    }

    private int[] executeBatch(final PreparedStatement stmt) throws SQLException {
        return JdbcUtil.executeBatch(stmt);
    }

    /**
     *
     * @param sql
     * @param parameters
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SafeVarargs
    public final int update(final String sql, final Object... parameters) throws UncheckedSQLException {
        return update(sql, StatementSetter.DEFAULT, parameters);
    }

    /**
     *
     * @param sql
     * @param statementSetter
     * @param parameters
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SafeVarargs
    public final int update(final String sql, final StatementSetter statementSetter, final Object... parameters) throws UncheckedSQLException {
        return update(sql, statementSetter, null, parameters);
    }

    /**
     *
     * @param sql
     * @param jdbcSettings
     * @param parameters
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SafeVarargs
    public final int update(final String sql, final JdbcSettings jdbcSettings, final Object... parameters) throws UncheckedSQLException {
        return update(sql, StatementSetter.DEFAULT, jdbcSettings, parameters);
    }

    /**
     *
     * @param sql
     * @param statementSetter
     * @param jdbcSettings
     * @param parameters
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SafeVarargs
    public final int update(final String sql, final StatementSetter statementSetter, final JdbcSettings jdbcSettings, final Object... parameters)
            throws UncheckedSQLException {
        return update(null, sql, statementSetter, jdbcSettings, parameters);
    }

    /**
     *
     * @param conn
     * @param sql
     * @param parameters
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SafeVarargs
    public final int update(final Connection conn, final String sql, final Object... parameters) throws UncheckedSQLException {
        return update(conn, sql, StatementSetter.DEFAULT, parameters);
    }

    /**
     *
     * @param conn
     * @param sql
     * @param statementSetter
     * @param parameters
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SafeVarargs
    public final int update(final Connection conn, final String sql, final StatementSetter statementSetter, final Object... parameters)
            throws UncheckedSQLException {
        return update(conn, sql, statementSetter, null, parameters);
    }

    /**
     *
     * @param conn
     * @param sql
     * @param jdbcSettings
     * @param parameters
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SafeVarargs
    public final int update(final Connection conn, final String sql, final JdbcSettings jdbcSettings, final Object... parameters) throws UncheckedSQLException {
        return update(conn, sql, StatementSetter.DEFAULT, jdbcSettings, parameters);
    }

    /**
     *
     * @param conn
     * @param sql
     * @param statementSetter
     * @param jdbcSettings
     * @param parameters
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @see #batchUpdate(Connection, String, StatementSetter, JdbcSettings, Object[])
     */
    @SafeVarargs
    public final int update(final Connection conn, final String sql, StatementSetter statementSetter, JdbcSettings jdbcSettings, final Object... parameters)
            throws UncheckedSQLException {
        final ParsedSql parsedSql = getParsedSql(sql);
        statementSetter = checkStatementSetter(parsedSql, statementSetter);
        jdbcSettings = checkJdbcSettings(jdbcSettings, parsedSql, _sqlMapper.getAttrs(sql));

        DataSource ds = null;
        Connection localConn = null;
        PreparedStatement stmt = null;

        try {
            ds = getDataSource(parsedSql.getParameterizedSql(), parameters, jdbcSettings);

            localConn = getConnection(conn, ds, jdbcSettings, SQLOperation.UPDATE);

            stmt = prepareStatement(ds, localConn, parsedSql, statementSetter, jdbcSettings, false, false, parameters);

            final int result = executeUpdate(parsedSql, stmt);

            if (isEntityOrMapParameter(parsedSql, parameters)) {
                if (parameters[0] instanceof DirtyMarker) {
                    DirtyMarkerUtil.markDirty((DirtyMarker) parameters[0], parsedSql.getNamedParameters(), false);
                }
            }

            return result;
        } catch (SQLException e) {
            String msg = ExceptionUtil.getMessage(e) + ". [SQL] " + parsedSql.sql();
            throw new UncheckedSQLException(msg, e);
        } finally {
            close(stmt);
            close(localConn, conn, ds);
        }
    }

    /**
     *
     * @param parsedSql
     * @param stmt
     * @return
     * @throws SQLException the SQL exception
     */
    protected int executeUpdate(final ParsedSql parsedSql, final PreparedStatement stmt) throws SQLException {
        if (_isReadOnly) {
            throw new RuntimeException("This SQL Executor is configured for read-only");
        }

        return JdbcUtil.executeUpdate(stmt);
    }

    /**
     *
     * @param sql
     * @param parametersList
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public int batchUpdate(final String sql, final List<?> parametersList) throws UncheckedSQLException {
        return batchUpdate(sql, StatementSetter.DEFAULT, parametersList);
    }

    /**
     *
     * @param sql
     * @param statementSetter
     * @param parametersList
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public int batchUpdate(final String sql, final StatementSetter statementSetter, final List<?> parametersList) throws UncheckedSQLException {
        return batchUpdate(sql, statementSetter, null, parametersList);
    }

    /**
     *
     * @param sql
     * @param jdbcSettings
     * @param parametersList
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public int batchUpdate(final String sql, final JdbcSettings jdbcSettings, final List<?> parametersList) throws UncheckedSQLException {
        return batchUpdate(sql, StatementSetter.DEFAULT, jdbcSettings, parametersList);
    }

    /**
     *
     * @param sql
     * @param statementSetter
     * @param jdbcSettings
     * @param parametersList
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public int batchUpdate(final String sql, final StatementSetter statementSetter, final JdbcSettings jdbcSettings, final List<?> parametersList)
            throws UncheckedSQLException {
        return batchUpdate(null, sql, statementSetter, jdbcSettings, parametersList);
    }

    /**
     *
     * @param conn
     * @param sql
     * @param parametersList
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public int batchUpdate(final Connection conn, final String sql, final List<?> parametersList) throws UncheckedSQLException {
        return batchUpdate(conn, sql, StatementSetter.DEFAULT, parametersList);
    }

    /**
     *
     * @param conn
     * @param sql
     * @param statementSetter
     * @param parametersList
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public int batchUpdate(final Connection conn, final String sql, final StatementSetter statementSetter, final List<?> parametersList)
            throws UncheckedSQLException {
        return batchUpdate(conn, sql, statementSetter, null, parametersList);
    }

    /**
     *
     * @param conn
     * @param sql
     * @param jdbcSettings
     * @param parametersList
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public int batchUpdate(final Connection conn, final String sql, final JdbcSettings jdbcSettings, final List<?> parametersList)
            throws UncheckedSQLException {
        return batchUpdate(conn, sql, StatementSetter.DEFAULT, jdbcSettings, parametersList);
    }

    /**
     *
     * @param conn
     * @param sql
     * @param statementSetter
     * @param jdbcSettings
     * @param parametersList
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @see #batchUpdate(Connection, String, StatementSetter, JdbcSettings, Object[])
     */
    public int batchUpdate(final Connection conn, final String sql, StatementSetter statementSetter, JdbcSettings jdbcSettings, final List<?> parametersList)
            throws UncheckedSQLException {
        final ParsedSql parsedSql = getParsedSql(sql);
        statementSetter = checkStatementSetter(parsedSql, statementSetter);
        jdbcSettings = checkJdbcSettings(jdbcSettings, parsedSql, _sqlMapper.getAttrs(sql));

        final int len = parametersList.size();
        final int batchSize = getBatchSize(jdbcSettings);

        DataSource ds = null;
        Connection localConn = null;
        PreparedStatement stmt = null;
        int originalIsolationLevel = 0;
        boolean autoCommit = true;

        try {
            ds = getDataSource(parsedSql.getParameterizedSql(), parametersList, jdbcSettings);

            localConn = getConnection(conn, ds, jdbcSettings, SQLOperation.UPDATE);

            try {
                originalIsolationLevel = localConn.getTransactionIsolation();
                autoCommit = localConn.getAutoCommit();
            } catch (SQLException e) {
                close(localConn, conn, ds);
                throw new UncheckedSQLException(e);
            }

            if ((conn == null) && (len > batchSize)) {
                localConn.setAutoCommit(false);

                setIsolationLevel(jdbcSettings, localConn);
            }

            stmt = prepareStatement(ds, localConn, parsedSql, statementSetter, jdbcSettings, false, true, parametersList);

            int result = 0;
            final Object[] parameters = new Object[1];

            if (len <= batchSize) {
                for (int i = 0; i < len; i++) {
                    parameters[0] = parametersList.get(i);

                    statementSetter.accept(parsedSql, stmt, parameters);
                    stmt.addBatch();
                }

                result += executeBatchUpdate(parsedSql, stmt);
            } else {
                int num = 0;

                for (int i = 0; i < len; i++) {
                    parameters[0] = parametersList.get(i);

                    statementSetter.accept(parsedSql, stmt, parameters);
                    stmt.addBatch();
                    num++;

                    if ((num % batchSize) == 0) {
                        result += executeBatchUpdate(parsedSql, stmt);
                    }
                }

                if ((num % batchSize) > 0) {
                    result += executeBatchUpdate(parsedSql, stmt);
                }
            }

            if ((conn == null) && (len > batchSize) && autoCommit == true) {
                localConn.commit();
            }

            if (N.firstOrNullIfEmpty(parametersList) instanceof DirtyMarker) {
                for (Object e : parametersList) {
                    DirtyMarkerUtil.markDirty((DirtyMarker) e, parsedSql.getNamedParameters(), false);
                }
            }

            return result;
        } catch (SQLException e) {
            if ((conn == null) && (len > batchSize) && autoCommit == true) {
                if (logger.isWarnEnabled()) {
                    logger.warn("Trying to roll back ...");
                }

                try {
                    localConn.rollback();

                    if (logger.isWarnEnabled()) {
                        logger.warn("succeeded to roll back");
                    }
                } catch (SQLException e1) {
                    logger.error("Failed to roll back", e1);
                }
            }

            String msg = ExceptionUtil.getMessage(e) + ". [SQL] " + parsedSql.sql();
            throw new UncheckedSQLException(msg, e);
        } finally {
            if ((conn == null) && (len > batchSize)) {
                try {
                    localConn.setAutoCommit(autoCommit);
                    localConn.setTransactionIsolation(originalIsolationLevel);
                } catch (SQLException e) {
                    logger.error("Failed to reset AutoCommit", e);
                }
            }

            close(stmt);
            close(localConn, conn, ds);
        }
    }

    /**
     * Execute batch update.
     *
     * @param parsedSql
     * @param stmt
     * @return
     * @throws SQLException the SQL exception
     */
    protected int executeBatchUpdate(final ParsedSql parsedSql, final PreparedStatement stmt) throws SQLException {
        if (_isReadOnly) {
            throw new RuntimeException("This SQL Executor is configured for read-only");
        }

        final int[] results = executeBatch(stmt);

        if ((results == null) || (results.length == 0)) {
            return 0;
        }

        int sum = 0;

        for (int i = 0; i < results.length; i++) {
            sum += results[i];
        }

        return sum;
    }

    //    // mess up. To uncomment this method, also need to modify getNamingPolicy/setNamingPolicy in JdbcSettings.
    //    int update(final EntityId entityId, final Map<String, Object> props) {
    //        return update(null, entityId, props);
    //    }
    //
    //    // mess up. To uncomment this method, also need to modify getNamingPolicy/setNamingPolicy in JdbcSettings.
    //    int update(final Connection conn, final EntityId entityId, final Map<String, Object> props) {
    //        final Pair2 pair = generateUpdateSQL(entityId, props);
    //
    //        return update(conn, sp.sql, sp.parameters);
    //    }
    //
    //    private Pair2 generateUpdateSQL(final EntityId entityId, final Map<String, Object> props) {
    //        final Condition cond = EntityManagerUtil.entityId2Condition(entityId);
    //        final NamingPolicy namingPolicy = _jdbcSettings.getNamingPolicy();
    //
    //        if (namingPolicy == null) {
    //            return NE.update(entityId.entityName()).set(props).where(cond).pair();
    //        }
    //
    //        switch (namingPolicy) {
    //            case LOWER_CASE_WITH_UNDERSCORE: {
    //                return NE.update(entityId.entityName()).set(props).where(cond).pair();
    //            }
    //
    //            case UPPER_CASE_WITH_UNDERSCORE: {
    //                return NE2.update(entityId.entityName()).set(props).where(cond).pair();
    //            }
    //
    //            case CAMEL_CASE: {
    //                return NE3.update(entityId.entityName()).set(props).where(cond).pair();
    //            }
    //
    //            default:
    //                throw new IllegalArgumentException("Unsupported naming policy");
    //        }
    //    }
    //
    //    // mess up. To uncomment this method, also need to modify getNamingPolicy/setNamingPolicy in JdbcSettings.
    //    int delete(final EntityId entityId) {
    //        return delete(null, entityId);
    //    }
    //
    //    // mess up. To uncomment this method, also need to modify getNamingPolicy/setNamingPolicy in JdbcSettings.
    //    int delete(final Connection conn, final EntityId entityId) {
    //        final Pair2 pair = generateDeleteSQL(entityId);
    //
    //        return update(conn, sp.sql, sp.parameters);
    //    }
    //
    //    private Pair2 generateDeleteSQL(final EntityId entityId) {
    //        final Condition cond = EntityManagerUtil.entityId2Condition(entityId);
    //        final NamingPolicy namingPolicy = _jdbcSettings.getNamingPolicy();
    //
    //        if (namingPolicy == null) {
    //            return NE.deleteFrom(entityId.entityName()).where(cond).pair();
    //        }
    //
    //        switch (namingPolicy) {
    //            case LOWER_CASE_WITH_UNDERSCORE: {
    //                return NE.deleteFrom(entityId.entityName()).where(cond).pair();
    //            }
    //
    //            case UPPER_CASE_WITH_UNDERSCORE: {
    //                return NE2.deleteFrom(entityId.entityName()).where(cond).pair();
    //            }
    //
    //            case CAMEL_CASE: {
    //                return NE3.deleteFrom(entityId.entityName()).where(cond).pair();
    //            }
    //
    //            default:
    //                throw new IllegalArgumentException("Unsupported naming policy");
    //        }
    //    }
    //
    //    // mess up. To uncomment this method, also need to modify getNamingPolicy/setNamingPolicy in JdbcSettings.
    //    boolean exists(final EntityId entityId) {
    //        return exists(null, entityId);
    //    }
    //
    //    // mess up. To uncomment this method, also need to modify getNamingPolicy/setNamingPolicy in JdbcSettings.
    //    boolean exists(final Connection conn, final EntityId entityId) {
    //        final Pair2 pair = generateQuerySQL(entityId, NE._1_list);
    //
    //        return query(conn, sp.sql, StatementSetter.DEFAULT, EXISTS_RESULT_SET_EXTRACTOR, null, sp.parameters);
    //    }

    /**
     *
     * @param sql
     * @param parameters
     * @return true, if successful
     */
    @SafeVarargs
    public final boolean exists(final String sql, final Object... parameters) {
        return exists(null, sql, parameters);
    }

    /**
     *
     * @param conn
     * @param sql
     * @param parameters
     * @return true, if successful
     */
    @SafeVarargs
    public final boolean exists(final Connection conn, final String sql, final Object... parameters) {
        return query(conn, sql, StatementSetter.DEFAULT, EXISTS_RESULT_SET_EXTRACTOR, null, parameters);
    }

    /**
     *
     * @param sql
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @deprecated may be misused and it's inefficient.
     */
    @Deprecated
    @SafeVarargs
    final int count(final String sql, final Object... parameters) {
        return count(null, sql, parameters);
    }

    /**
     *
     * @param conn
     * @param sql
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @deprecated may be misused and it's inefficient.
     */
    @Deprecated
    @SafeVarargs
    final int count(final Connection conn, final String sql, final Object... parameters) {
        return query(conn, sql, StatementSetter.DEFAULT, COUNT_RESULT_SET_EXTRACTOR, null, parameters);
    }

    /**
     *
     * @param <T>
     * @param targetClass
     * @param sql
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    @SafeVarargs
    public final <T> Optional<T> get(final Class<T> targetClass, final String sql, final Object... parameters) throws DuplicatedResultException {
        return Optional.ofNullable(gett(targetClass, sql, parameters));
    }

    /**
     *
     * @param <T>
     * @param targetClass
     * @param sql
     * @param statementSetter
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    @SafeVarargs
    public final <T> Optional<T> get(final Class<T> targetClass, final String sql, final StatementSetter statementSetter, final Object... parameters)
            throws DuplicatedResultException {
        return Optional.ofNullable(gett(targetClass, sql, statementSetter, parameters));
    }

    /**
     *
     * @param <T>
     * @param targetClass
     * @param sql
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    @SafeVarargs
    public final <T> Optional<T> get(final Class<T> targetClass, final String sql, final JdbcSettings jdbcSettings, final Object... parameters)
            throws DuplicatedResultException {
        return Optional.ofNullable(gett(targetClass, sql, jdbcSettings, parameters));
    }

    /**
     *
     * @param <T>
     * @param targetClass
     * @param sql
     * @param statementSetter
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    @SafeVarargs
    public final <T> Optional<T> get(final Class<T> targetClass, final String sql, final StatementSetter statementSetter, final JdbcSettings jdbcSettings,
            final Object... parameters) throws DuplicatedResultException {
        return Optional.ofNullable(gett(targetClass, sql, statementSetter, jdbcSettings, parameters));
    }

    /**
     *
     * @param <T>
     * @param targetClass
     * @param conn
     * @param sql
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    @SafeVarargs
    public final <T> Optional<T> get(final Class<T> targetClass, final Connection conn, final String sql, final Object... parameters)
            throws DuplicatedResultException {
        return Optional.ofNullable(gett(targetClass, conn, sql, parameters));
    }

    /**
     *
     * @param <T>
     * @param targetClass
     * @param conn
     * @param sql
     * @param statementSetter
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    @SafeVarargs
    public final <T> Optional<T> get(final Class<T> targetClass, final Connection conn, final String sql, final StatementSetter statementSetter,
            final Object... parameters) throws DuplicatedResultException {
        return Optional.ofNullable(gett(targetClass, conn, sql, statementSetter, parameters));
    }

    /**
     *
     * @param <T>
     * @param targetClass
     * @param conn
     * @param sql
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    @SafeVarargs
    public final <T> Optional<T> get(final Class<T> targetClass, final Connection conn, final String sql, final JdbcSettings jdbcSettings,
            final Object... parameters) throws DuplicatedResultException {
        return Optional.ofNullable(gett(targetClass, conn, sql, jdbcSettings, parameters));
    }

    /**
     *
     * @param <T>
     * @param targetClass
     * @param conn
     * @param sql
     * @param statementSetter
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    @SafeVarargs
    public final <T> Optional<T> get(final Class<T> targetClass, final Connection conn, final String sql, final StatementSetter statementSetter,
            JdbcSettings jdbcSettings, final Object... parameters) throws DuplicatedResultException {
        return Optional.ofNullable(gett(targetClass, conn, sql, statementSetter, jdbcSettings, parameters));
    }

    /**
     *
     * @param <T>
     * @param sql
     * @param rowMapper
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    @SafeVarargs
    public final <T> Optional<T> get(final String sql, final JdbcUtil.RowMapper<T> rowMapper, final Object... parameters) throws DuplicatedResultException {
        return Optional.ofNullable(gett(sql, rowMapper, parameters));
    }

    /**
     *
     * @param <T>
     * @param sql
     * @param statementSetter
     * @param rowMapper
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    public final <T> Optional<T> get(final String sql, final StatementSetter statementSetter, final JdbcUtil.RowMapper<T> rowMapper, final Object... parameters)
            throws DuplicatedResultException {
        return Optional.ofNullable(gett(sql, statementSetter, rowMapper, parameters));
    }

    /**
     *
     * @param <T>
     * @param sql
     * @param rowMapper
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    public final <T> Optional<T> get(final String sql, final JdbcUtil.RowMapper<T> rowMapper, final JdbcSettings jdbcSettings, final Object... parameters)
            throws DuplicatedResultException {
        return Optional.ofNullable(gett(sql, rowMapper, jdbcSettings, parameters));
    }

    /**
     *
     *
     * @param <T>
     * @param sql
     * @param statementSetter
     * @param rowMapper
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    @SafeVarargs
    public final <T> Optional<T> get(final String sql, final StatementSetter statementSetter, final JdbcUtil.RowMapper<T> rowMapper,
            final JdbcSettings jdbcSettings, final Object... parameters) throws DuplicatedResultException {
        return Optional.ofNullable(gett(sql, statementSetter, rowMapper, jdbcSettings, parameters));
    }

    /**
     *
     *
     * @param <T>
     * @param conn
     * @param sql
     * @param rowMapper
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    @SafeVarargs
    public final <T> Optional<T> get(final Connection conn, final String sql, final JdbcUtil.RowMapper<T> rowMapper, final Object... parameters)
            throws DuplicatedResultException {
        return Optional.ofNullable(gett(conn, sql, rowMapper, parameters));
    }

    /**
     *
     * @param <T>
     * @param conn
     * @param sql
     * @param statementSetter
     * @param rowMapper
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    @SafeVarargs
    public final <T> Optional<T> get(final Connection conn, final String sql, final StatementSetter statementSetter, final JdbcUtil.RowMapper<T> rowMapper,
            final Object... parameters) {
        return Optional.ofNullable(gett(conn, sql, statementSetter, rowMapper, parameters));
    }

    /**
     *
     * @param <T>
     * @param conn
     * @param sql
     * @param rowMapper
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    @SafeVarargs
    public final <T> Optional<T> get(final Connection conn, final String sql, final JdbcUtil.RowMapper<T> rowMapper, JdbcSettings jdbcSettings,
            final Object... parameters) throws DuplicatedResultException {
        return Optional.ofNullable(gett(conn, sql, rowMapper, jdbcSettings, parameters));
    }

    /**
     *
     *
     * @param <T>
     * @param conn
     * @param sql
     * @param statementSetter
     * @param rowMapper
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    @SafeVarargs
    public final <T> Optional<T> get(final Connection conn, final String sql, final StatementSetter statementSetter, final JdbcUtil.RowMapper<T> rowMapper,
            final JdbcSettings jdbcSettings, final Object... parameters) throws DuplicatedResultException {
        return Optional.ofNullable(gett(conn, sql, statementSetter, rowMapper, jdbcSettings, parameters));
    }

    /**
     * Gets the t.
     *
     * @param <T>
     * @param targetClass
     * @param sql
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    @SafeVarargs
    public final <T> T gett(final Class<T> targetClass, final String sql, final Object... parameters) throws DuplicatedResultException {
        return gett(targetClass, sql, StatementSetter.DEFAULT, parameters);
    }

    /**
     * Gets the t.
     *
     * @param <T>
     * @param targetClass
     * @param sql
     * @param statementSetter
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    @SafeVarargs
    public final <T> T gett(final Class<T> targetClass, final String sql, final StatementSetter statementSetter, final Object... parameters)
            throws DuplicatedResultException {
        return gett(targetClass, sql, statementSetter, null, parameters);
    }

    /**
     * Gets the t.
     *
     * @param <T>
     * @param targetClass
     * @param sql
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    @SafeVarargs
    public final <T> T gett(final Class<T> targetClass, final String sql, final JdbcSettings jdbcSettings, final Object... parameters)
            throws DuplicatedResultException {
        return gett(targetClass, sql, StatementSetter.DEFAULT, jdbcSettings, parameters);
    }

    /**
     * Gets the t.
     *
     * @param <T>
     * @param targetClass
     * @param sql
     * @param statementSetter
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    @SafeVarargs
    public final <T> T gett(final Class<T> targetClass, final String sql, final StatementSetter statementSetter, final JdbcSettings jdbcSettings,
            final Object... parameters) throws DuplicatedResultException {
        return gett(targetClass, null, sql, statementSetter, jdbcSettings, parameters);
    }

    /**
     * Gets the t.
     *
     * @param <T>
     * @param targetClass
     * @param conn
     * @param sql
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    @SafeVarargs
    public final <T> T gett(final Class<T> targetClass, final Connection conn, final String sql, final Object... parameters) throws DuplicatedResultException {
        return gett(targetClass, conn, sql, StatementSetter.DEFAULT, parameters);
    }

    /**
     * Gets the t.
     *
     * @param <T>
     * @param targetClass
     * @param conn
     * @param sql
     * @param statementSetter
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    @SafeVarargs
    public final <T> T gett(final Class<T> targetClass, final Connection conn, final String sql, final StatementSetter statementSetter,
            final Object... parameters) throws DuplicatedResultException {
        return gett(targetClass, conn, sql, statementSetter, null, parameters);
    }

    /**
     * Gets the t.
     *
     * @param <T>
     * @param targetClass
     * @param conn
     * @param sql
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    @SafeVarargs
    public final <T> T gett(final Class<T> targetClass, final Connection conn, final String sql, final JdbcSettings jdbcSettings, final Object... parameters)
            throws DuplicatedResultException {
        return gett(targetClass, conn, sql, StatementSetter.DEFAULT, jdbcSettings, parameters);
    }

    /**
     * Gets the t.
     *
     * @param <T>
     * @param targetClass
     * @param conn
     * @param sql
     * @param statementSetter
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    @SuppressWarnings("unchecked")
    @SafeVarargs
    public final <T> T gett(final Class<T> targetClass, final Connection conn, final String sql, final StatementSetter statementSetter,
            JdbcSettings jdbcSettings, final Object... parameters) throws DuplicatedResultException {
        N.checkArgNotNull(targetClass, "targetClass");

        final JdbcUtil.RowMapper<T> rowMapper = new JdbcUtil.RowMapper<T>() {
            private final BiRowMapper<T> biRowMapper = BiRowMapper.to(targetClass);

            @Override
            public T apply(ResultSet rs) throws SQLException {
                return biRowMapper.apply(rs, JdbcUtil.getColumnLabelList(rs));
            }
        };

        return gett(conn, sql, statementSetter, rowMapper, jdbcSettings, parameters);
    }

    /**
     * Gets the t.
     *
     * @param <T>
     * @param sql
     * @param rowMapper
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    @SafeVarargs
    public final <T> T gett(final String sql, final JdbcUtil.RowMapper<T> rowMapper, final Object... parameters) throws DuplicatedResultException {
        return gett(sql, StatementSetter.DEFAULT, rowMapper, parameters);
    }

    /**
     * Gets the t.
     *
     * @param <T>
     * @param sql
     * @param statementSetter
     * @param rowMapper
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    @SafeVarargs
    public final <T> T gett(final String sql, final StatementSetter statementSetter, final JdbcUtil.RowMapper<T> rowMapper, final Object... parameters)
            throws DuplicatedResultException {
        return gett(sql, statementSetter, rowMapper, null, parameters);
    }

    /**
     * Gets the t.
     *
     * @param <T>
     * @param sql
     * @param rowMapper
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    @SafeVarargs
    public final <T> T gett(final String sql, final JdbcUtil.RowMapper<T> rowMapper, final JdbcSettings jdbcSettings, final Object... parameters)
            throws DuplicatedResultException {
        return gett(sql, StatementSetter.DEFAULT, rowMapper, jdbcSettings, parameters);
    }

    /**
     * Gets the t.
     *
     * @param <T>
     * @param sql
     * @param statementSetter
     * @param rowMapper
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    @SafeVarargs
    public final <T> T gett(final String sql, final StatementSetter statementSetter, final JdbcUtil.RowMapper<T> rowMapper, final JdbcSettings jdbcSettings,
            final Object... parameters) throws DuplicatedResultException {
        return gett(null, sql, statementSetter, rowMapper, jdbcSettings, parameters);
    }

    /**
     * Gets the t.
     *
     * @param <T>
     * @param conn
     * @param sql
     * @param rowMapper
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    @SafeVarargs
    public final <T> T gett(final Connection conn, final String sql, final JdbcUtil.RowMapper<T> rowMapper, final Object... parameters)
            throws DuplicatedResultException {
        return gett(conn, sql, StatementSetter.DEFAULT, rowMapper, parameters);
    }

    /**
     * Gets the t.
     *
     * @param <T>
     * @param conn
     * @param sql
     * @param statementSetter
     * @param rowMapper
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    public final <T> T gett(final Connection conn, final String sql, final StatementSetter statementSetter, final JdbcUtil.RowMapper<T> rowMapper,
            final Object... parameters) throws DuplicatedResultException {
        return gett(conn, sql, statementSetter, rowMapper, null, parameters);
    }

    /**
     * Gets the t.
     *
     * @param <T>
     * @param conn
     * @param sql
     * @param rowMapper
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    public final <T> T gett(final Connection conn, final String sql, final JdbcUtil.RowMapper<T> rowMapper, JdbcSettings jdbcSettings,
            final Object... parameters) throws DuplicatedResultException {
        return gett(conn, sql, StatementSetter.DEFAULT, rowMapper, jdbcSettings, parameters);
    }

    /**
     *
     * @param <T>
     * @param conn
     * @param sql
     * @param statementSetter
     * @param rowMapper
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    @SuppressWarnings("unchecked")
    @SafeVarargs
    public final <T> T gett(final Connection conn, final String sql, final StatementSetter statementSetter, final JdbcUtil.RowMapper<T> rowMapper,
            JdbcSettings jdbcSettings, final Object... parameters) throws DuplicatedResultException {
        N.checkArgNotNull(rowMapper, "rowMapper");

        final ResultExtractor<T> resultExtractor = new ResultExtractor<T>() {
            @Override
            public T apply(ResultSet rs, final JdbcSettings jdbcSettings) throws SQLException {
                int offset = jdbcSettings.getOffset();

                if (offset > 0) {
                    JdbcUtil.skip(rs, offset);
                }

                T result = null;

                if (rs.next()) {
                    result = Objects.requireNonNull(rowMapper.apply(rs));

                    if (rs.next()) {
                        throw new DuplicatedResultException("More than one records found by sql: " + sql);
                    }
                }

                return result;
            }
        };

        return query(conn, sql, statementSetter, resultExtractor, jdbcSettings, parameters);
    }

    /**
     *
     * @param <T>
     * @param targetClass
     * @param sql
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final <T> Optional<T> findFirst(final Class<T> targetClass, final String sql, final Object... parameters) {
        return findFirst(targetClass, sql, StatementSetter.DEFAULT, parameters);
    }

    /**
     *
     * @param <T>
     * @param targetClass
     * @param sql
     * @param statementSetter
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final <T> Optional<T> findFirst(final Class<T> targetClass, final String sql, final StatementSetter statementSetter, final Object... parameters) {
        return findFirst(targetClass, sql, statementSetter, null, parameters);
    }

    /**
     *
     * @param <T>
     * @param targetClass
     * @param sql
     * @param jdbcSettings
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final <T> Optional<T> findFirst(final Class<T> targetClass, final String sql, final JdbcSettings jdbcSettings, final Object... parameters) {
        return findFirst(targetClass, sql, StatementSetter.DEFAULT, jdbcSettings, parameters);
    }

    /**
     *
     * @param <T>
     * @param targetClass
     * @param sql
     * @param statementSetter
     * @param jdbcSettings
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final <T> Optional<T> findFirst(final Class<T> targetClass, final String sql, final StatementSetter statementSetter, final JdbcSettings jdbcSettings,
            final Object... parameters) {
        return findFirst(targetClass, null, sql, statementSetter, jdbcSettings, parameters);
    }

    /**
     *
     * @param <T>
     * @param targetClass
     * @param conn
     * @param sql
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final <T> Optional<T> findFirst(final Class<T> targetClass, final Connection conn, final String sql, final Object... parameters) {
        return findFirst(targetClass, conn, sql, StatementSetter.DEFAULT, parameters);
    }

    /**
     *
     * @param <T>
     * @param targetClass
     * @param conn
     * @param sql
     * @param statementSetter
     * @param parameters
     * @return
     */
    public final <T> Optional<T> findFirst(final Class<T> targetClass, final Connection conn, final String sql, final StatementSetter statementSetter,
            final Object... parameters) {
        return findFirst(targetClass, conn, sql, statementSetter, null, parameters);
    }

    /**
     *
     * @param <T>
     * @param targetClass
     * @param conn
     * @param sql
     * @param jdbcSettings
     * @param parameters
     * @return
     */
    public final <T> Optional<T> findFirst(final Class<T> targetClass, final Connection conn, final String sql, final JdbcSettings jdbcSettings,
            final Object... parameters) {
        return findFirst(targetClass, conn, sql, StatementSetter.DEFAULT, jdbcSettings, parameters);
    }

    /**
     * Just fetch the result in the 1st row. {@code null} is returned if no result is found. This method will try to
     * convert the column value to the type of mapping entity property if the mapping entity property is not assignable
     * from column value.
     *
     * Remember to add {@code limit} condition if big result will be returned by the query.
     *
     * @param <T>
     * @param targetClass
     * @param conn
     * @param sql
     * @param statementSetter
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     */
    @SuppressWarnings("unchecked")
    @SafeVarargs
    public final <T> Optional<T> findFirst(final Class<T> targetClass, final Connection conn, final String sql, final StatementSetter statementSetter,
            final JdbcSettings jdbcSettings, final Object... parameters) {
        N.checkArgNotNull(targetClass, "targetClass");

        final JdbcUtil.RowMapper<T> rowMapper = new JdbcUtil.RowMapper<T>() {
            private final BiRowMapper<T> biRowMapper = BiRowMapper.to(targetClass);

            @Override
            public T apply(ResultSet rs) throws SQLException {
                return biRowMapper.apply(rs, JdbcUtil.getColumnLabelList(rs));
            }
        };

        return findFirst(conn, sql, statementSetter, rowMapper, jdbcSettings, parameters);
    }

    /**
     *
     * @param <T>
     * @param sql
     * @param rowMapper
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final <T> Optional<T> findFirst(final String sql, final JdbcUtil.RowMapper<T> rowMapper, final Object... parameters) {
        return findFirst(sql, StatementSetter.DEFAULT, rowMapper, parameters);
    }

    /**
     *
     * @param <T>
     * @param sql
     * @param statementSetter
     * @param rowMapper
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final <T> Optional<T> findFirst(final String sql, final StatementSetter statementSetter, final JdbcUtil.RowMapper<T> rowMapper,
            final Object... parameters) {
        return findFirst(sql, statementSetter, rowMapper, null, parameters);
    }

    /**
     *
     * @param <T>
     * @param sql
     * @param rowMapper
     * @param jdbcSettings
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final <T> Optional<T> findFirst(final String sql, final JdbcUtil.RowMapper<T> rowMapper, final JdbcSettings jdbcSettings,
            final Object... parameters) {
        return findFirst(sql, StatementSetter.DEFAULT, rowMapper, jdbcSettings, parameters);
    }

    /**
     *
     * @param <T>
     * @param sql
     * @param statementSetter
     * @param rowMapper
     * @param jdbcSettings
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final <T> Optional<T> findFirst(final String sql, final StatementSetter statementSetter, final JdbcUtil.RowMapper<T> rowMapper,
            final JdbcSettings jdbcSettings, final Object... parameters) {
        return findFirst(null, sql, statementSetter, rowMapper, jdbcSettings, parameters);
    }

    /**
     *
     * @param <T>
     * @param conn
     * @param sql
     * @param rowMapper
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final <T> Optional<T> findFirst(final Connection conn, final String sql, final JdbcUtil.RowMapper<T> rowMapper, final Object... parameters) {
        return findFirst(conn, sql, StatementSetter.DEFAULT, rowMapper, parameters);
    }

    /**
     *
     * @param <T>
     * @param conn
     * @param sql
     * @param statementSetter
     * @param rowMapper
     * @param parameters
     * @return
     */
    public final <T> Optional<T> findFirst(final Connection conn, final String sql, final StatementSetter statementSetter,
            final JdbcUtil.RowMapper<T> rowMapper, final Object... parameters) {
        return findFirst(conn, sql, statementSetter, rowMapper, null, parameters);
    }

    /**
     *
     * @param <T>
     * @param conn
     * @param sql
     * @param rowMapper
     * @param jdbcSettings
     * @param parameters
     * @return
     */
    public final <T> Optional<T> findFirst(final Connection conn, final String sql, final JdbcUtil.RowMapper<T> rowMapper, final JdbcSettings jdbcSettings,
            final Object... parameters) {
        return findFirst(conn, sql, StatementSetter.DEFAULT, rowMapper, jdbcSettings, parameters);
    }

    /**
     * Remember to add {@code limit} condition if big result will be returned by the query.
     *
     * @param <T>
     * @param conn
     * @param sql
     * @param statementSetter
     * @param rowMapper
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     */
    @SuppressWarnings("unchecked")
    @SafeVarargs
    public final <T> Optional<T> findFirst(final Connection conn, final String sql, final StatementSetter statementSetter,
            final JdbcUtil.RowMapper<T> rowMapper, final JdbcSettings jdbcSettings, final Object... parameters) {
        N.checkArgNotNull(rowMapper, "rowMapper");

        final ResultExtractor<T> resultExtractor = new ResultExtractor<T>() {
            @Override
            public T apply(ResultSet rs, final JdbcSettings jdbcSettings) throws SQLException {
                int offset = jdbcSettings.getOffset();

                if (offset > 0) {
                    JdbcUtil.skip(rs, offset);
                }

                return rs.next() ? Objects.requireNonNull(rowMapper.apply(rs)) : null;
            }
        };

        return Optional.ofNullable(query(conn, sql, statementSetter, resultExtractor, jdbcSettings, parameters));
    }

    /**
     *
     * @param <T>
     * @param targetClass
     * @param sql
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final <T> List<T> list(final Class<T> targetClass, final String sql, final Object... parameters) {
        return list(targetClass, sql, StatementSetter.DEFAULT, parameters);
    }

    /**
     *
     * @param <T>
     * @param targetClass
     * @param sql
     * @param statementSetter
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final <T> List<T> list(final Class<T> targetClass, final String sql, final StatementSetter statementSetter, final Object... parameters) {
        return list(targetClass, sql, statementSetter, null, parameters);
    }

    /**
     *
     * @param <T>
     * @param targetClass
     * @param sql
     * @param jdbcSettings
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final <T> List<T> list(final Class<T> targetClass, final String sql, final JdbcSettings jdbcSettings, final Object... parameters) {
        return list(targetClass, sql, StatementSetter.DEFAULT, jdbcSettings, parameters);
    }

    /**
     *
     * @param <T>
     * @param targetClass
     * @param sql
     * @param statementSetter
     * @param jdbcSettings
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final <T> List<T> list(final Class<T> targetClass, final String sql, final StatementSetter statementSetter, final JdbcSettings jdbcSettings,
            final Object... parameters) {
        return list(targetClass, null, sql, statementSetter, jdbcSettings, parameters);
    }

    /**
     *
     * @param <T>
     * @param targetClass
     * @param conn
     * @param sql
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final <T> List<T> list(final Class<T> targetClass, final Connection conn, final String sql, final Object... parameters) {
        return list(targetClass, conn, sql, StatementSetter.DEFAULT, parameters);
    }

    /**
     *
     * @param <T>
     * @param targetClass
     * @param conn
     * @param sql
     * @param statementSetter
     * @param parameters
     * @return
     */
    public final <T> List<T> list(final Class<T> targetClass, final Connection conn, final String sql, final StatementSetter statementSetter,
            final Object... parameters) {
        return list(targetClass, conn, sql, statementSetter, null, parameters);
    }

    /**
     *
     * @param <T>
     * @param targetClass
     * @param conn
     * @param sql
     * @param jdbcSettings
     * @param parameters
     * @return
     */
    public final <T> List<T> list(final Class<T> targetClass, final Connection conn, final String sql, final JdbcSettings jdbcSettings,
            final Object... parameters) {
        return list(targetClass, conn, sql, StatementSetter.DEFAULT, jdbcSettings, parameters);
    }

    /**
     *
     * @param <T>
     * @param targetClass
     * @param conn
     * @param sql
     * @param statementSetter
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     */
    @SuppressWarnings("unchecked")
    @SafeVarargs
    public final <T> List<T> list(final Class<T> targetClass, final Connection conn, final String sql, final StatementSetter statementSetter,
            final JdbcSettings jdbcSettings, final Object... parameters) {
        return list(conn, sql, statementSetter, BiRowMapper.to(targetClass), jdbcSettings, parameters);
    }

    /**
     *
     * @param <T>
     * @param sql
     * @param rowMapper
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final <T> List<T> list(final String sql, final JdbcUtil.BiRowMapper<T> rowMapper, final Object... parameters) {
        return list(sql, StatementSetter.DEFAULT, rowMapper, parameters);
    }

    /**
     *
     * @param <T>
     * @param sql
     * @param statementSetter
     * @param rowMapper
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final <T> List<T> list(final String sql, final StatementSetter statementSetter, final JdbcUtil.BiRowMapper<T> rowMapper,
            final Object... parameters) {
        return list(sql, statementSetter, rowMapper, null, parameters);
    }

    /**
     *
     * @param <T>
     * @param sql
     * @param rowMapper
     * @param jdbcSettings
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final <T> List<T> list(final String sql, final JdbcUtil.BiRowMapper<T> rowMapper, final JdbcSettings jdbcSettings, final Object... parameters) {
        return list(sql, StatementSetter.DEFAULT, rowMapper, jdbcSettings, parameters);
    }

    /**
     *
     * @param <T>
     * @param sql
     * @param statementSetter
     * @param rowMapper
     * @param jdbcSettings
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final <T> List<T> list(final String sql, final StatementSetter statementSetter, final JdbcUtil.BiRowMapper<T> rowMapper,
            final JdbcSettings jdbcSettings, final Object... parameters) {
        return list(null, sql, statementSetter, rowMapper, jdbcSettings, parameters);
    }

    /**
     *
     * @param <T>
     * @param conn
     * @param sql
     * @param rowMapper
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final <T> List<T> list(final Connection conn, final String sql, final JdbcUtil.BiRowMapper<T> rowMapper, final Object... parameters) {
        return list(conn, sql, StatementSetter.DEFAULT, rowMapper, parameters);
    }

    /**
     *
     * @param <T>
     * @param conn
     * @param sql
     * @param statementSetter
     * @param rowMapper
     * @param parameters
     * @return
     */
    public final <T> List<T> list(final Connection conn, final String sql, final StatementSetter statementSetter, final JdbcUtil.BiRowMapper<T> rowMapper,
            final Object... parameters) {
        return list(conn, sql, statementSetter, rowMapper, null, parameters);
    }

    /**
     *
     * @param <T>
     * @param conn
     * @param sql
     * @param rowMapper
     * @param jdbcSettings
     * @param parameters
     * @return
     */
    public final <T> List<T> list(final Connection conn, final String sql, final JdbcUtil.BiRowMapper<T> rowMapper, final JdbcSettings jdbcSettings,
            final Object... parameters) {
        return list(conn, sql, StatementSetter.DEFAULT, rowMapper, jdbcSettings, parameters);
    }

    /**
     *
     * @param <T>
     * @param conn
     * @param sql
     * @param statementSetter
     * @param rowMapper
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     */
    @SuppressWarnings("unchecked")
    @SafeVarargs
    public final <T> List<T> list(final Connection conn, final String sql, final StatementSetter statementSetter, final JdbcUtil.BiRowMapper<T> rowMapper,
            final JdbcSettings jdbcSettings, final Object... parameters) {
        N.checkArgNotNull(rowMapper);

        final ResultExtractor<List<T>> resultExtractor = new ResultExtractor<List<T>>() {
            @Override
            public List<T> apply(ResultSet rs, final JdbcSettings jdbcSettings) throws SQLException {
                int offset = jdbcSettings.getOffset();
                int count = jdbcSettings.getCount();

                if (offset > 0) {
                    JdbcUtil.skip(rs, offset);
                }

                final List<T> result = new ArrayList<>(N.min(count, 16));
                final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

                while (count-- > 0 && rs.next()) {
                    result.add(rowMapper.apply(rs, columnLabels));
                }

                return result;
            }
        };

        return query(conn, sql, statementSetter, resultExtractor, jdbcSettings, parameters);
    }

    /**
     * Execute the query in one or more data sources specified by {@code jdbcSettings} and merge the results.
     * It's designed for partition.
     *
     * @param <T>
     * @param targetClass
     * @param sql
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     */
    @SafeVarargs
    public final <T> List<T> listAll(final Class<T> targetClass, final String sql, final JdbcSettings jdbcSettings, final Object... parameters) {
        return listAll(targetClass, sql, StatementSetter.DEFAULT, jdbcSettings, parameters);
    }

    /**
     * Execute the query in one or more data sources specified by {@code jdbcSettings} and merge the results.
     * It's designed for partition.
     *
     * @param <T>
     * @param targetClass
     * @param sql
     * @param statementSetter
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     */
    @SafeVarargs
    public final <T> List<T> listAll(final Class<T> targetClass, final String sql, final StatementSetter statementSetter, final JdbcSettings jdbcSettings,
            final Object... parameters) {
        return listAll(sql, statementSetter, BiRowMapper.to(targetClass), jdbcSettings, parameters);
    }

    /**
     * Execute the query in one or more data sources specified by {@code jdbcSettings} and merge the results.
     * It's designed for partition.
     *
     * @param <T>
     * @param targetClass
     * @param sqls
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     */
    @SafeVarargs
    public final <T> List<T> listAll(final Class<T> targetClass, final List<String> sqls, final JdbcSettings jdbcSettings, final Object... parameters) {
        return listAll(targetClass, sqls, null, jdbcSettings, parameters);
    }

    /**
     * Execute the query in one or more data sources specified by {@code jdbcSettings} and merge the results.
     * It's designed for partition.
     *
     * @param <T>
     * @param targetClass
     * @param sqls
     * @param statementSetter
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     */
    @SafeVarargs
    public final <T> List<T> listAll(final Class<T> targetClass, final List<String> sqls, final StatementSetter statementSetter,
            final JdbcSettings jdbcSettings, final Object... parameters) {
        return listAll(sqls, statementSetter, BiRowMapper.to(targetClass), jdbcSettings, parameters);
    }

    /**
     * Execute the query in one or more data sources specified by {@code jdbcSettings} and merge the results.
     * It's designed for partition.
     *
     * @param <T>
     * @param sql
     * @param rowMapper
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     */
    @SafeVarargs
    public final <T> List<T> listAll(final String sql, final JdbcUtil.BiRowMapper<T> rowMapper, final JdbcSettings jdbcSettings, final Object... parameters) {
        return listAll(sql, StatementSetter.DEFAULT, rowMapper, jdbcSettings, parameters);
    }

    /**
     * Execute the query in one or more data sources specified by {@code jdbcSettings} and merge the results.
     * It's designed for partition.
     *
     * @param <T>
     * @param sql
     * @param statementSetter
     * @param rowMapper
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     */
    @SafeVarargs
    public final <T> List<T> listAll(final String sql, final StatementSetter statementSetter, final JdbcUtil.BiRowMapper<T> rowMapper,
            final JdbcSettings jdbcSettings, final Object... parameters) {
        checkJdbcSettingsForAllQuery(jdbcSettings);

        if (jdbcSettings == null || N.isNullOrEmpty(jdbcSettings.getQueryWithDataSources())) {
            return list(sql, statementSetter, rowMapper, jdbcSettings, parameters);
        }

        final Collection<String> dss = jdbcSettings.getQueryWithDataSources();
        List<List<T>> resultList = null;

        if (jdbcSettings.isQueryInParallel()) {
            resultList = Stream.of(dss).map(new Function<String, JdbcSettings>() {
                @Override
                public JdbcSettings apply(String ds) {
                    final JdbcSettings newJdbcSettings = jdbcSettings.copy();
                    newJdbcSettings.setQueryWithDataSources(null);
                    newJdbcSettings.setQueryWithDataSource(ds);
                    return newJdbcSettings;
                }
            }).parallel(dss.size()).map(new Function<JdbcSettings, List<T>>() {
                @Override
                public List<T> apply(JdbcSettings newJdbcSettings) {
                    return list(sql, statementSetter, rowMapper, newJdbcSettings, parameters);
                }
            }).toList();
        } else {
            final JdbcSettings newJdbcSettings = jdbcSettings.copy();
            newJdbcSettings.setQueryWithDataSources(null);
            resultList = new ArrayList<>(dss.size());

            for (String ds : dss) {
                newJdbcSettings.setQueryWithDataSource(ds);
                resultList.add(list(sql, statementSetter, rowMapper, newJdbcSettings, parameters));
            }
        }

        return N.concat(resultList);
    }

    /**
     * Check jdbc settings for all query.
     *
     * @param jdbcSettings
     */
    private void checkJdbcSettingsForAllQuery(JdbcSettings jdbcSettings) {
        if (jdbcSettings != null && (jdbcSettings.getOffset() != 0 || jdbcSettings.getCount() != Integer.MAX_VALUE)) {
            throw new IllegalArgumentException("Can't set 'offset' or 'count' for findAll/queryAll/streamAll methods");
        }
    }

    /**
     * Execute one or more queries in one or more data sources specified by {@code jdbcSettings} and merge the results.
     * It's designed for partition.
     *
     * @param <T>
     * @param sqls
     * @param rowMapper
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     */
    @SafeVarargs
    public final <T> List<T> listAll(final List<String> sqls, final JdbcUtil.BiRowMapper<T> rowMapper, final JdbcSettings jdbcSettings,
            final Object... parameters) {
        return listAll(sqls, null, rowMapper, jdbcSettings, parameters);
    }

    /**
     * Execute one or more queries in one or more data sources specified by {@code jdbcSettings} and merge the results.
     * It's designed for partition.
     *
     * @param <T>
     * @param sqls
     * @param statementSetter
     * @param rowMapper
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     */
    @SafeVarargs
    public final <T> List<T> listAll(final List<String> sqls, final StatementSetter statementSetter, final JdbcUtil.BiRowMapper<T> rowMapper,
            final JdbcSettings jdbcSettings, final Object... parameters) {
        if (sqls.size() == 1) {
            return listAll(sqls.get(0), statementSetter, rowMapper, jdbcSettings, parameters);
        }

        List<List<T>> resultList = null;

        if (jdbcSettings != null && jdbcSettings.isQueryInParallel()) {
            resultList = Stream.of(sqls).parallel(sqls.size()).map(new Function<String, List<T>>() {
                @Override
                public List<T> apply(String sql) {
                    return listAll(sql, statementSetter, rowMapper, jdbcSettings, parameters);
                }
            }).toList();
        } else {
            resultList = new ArrayList<>(sqls.size());

            for (String sql : sqls) {
                resultList.add(listAll(sql, statementSetter, rowMapper, jdbcSettings, parameters));
            }
        }

        return N.concat(resultList);
    }

    /**
     * Query for boolean.
     *
     * @param sql
     * @param parameters
     * @return
     * @see SQLExecutor#queryForSingleResult(Class, Connection, String, Object...).
     */
    @SafeVarargs
    public final OptionalBoolean queryForBoolean(final String sql, final Object... parameters) {
        return query(sql, StatementSetter.DEFAULT, SINGLE_BOOLEAN_EXTRACTOR, null, parameters);
    }

    /**
     * Query for char.
     *
     * @param sql
     * @param parameters
     * @return
     * @see SQLExecutor#queryForSingleResult(Class, Connection, String, Object...).
     */
    @SafeVarargs
    public final OptionalChar queryForChar(final String sql, final Object... parameters) {
        return query(sql, StatementSetter.DEFAULT, SINGLE_CHAR_EXTRACTOR, null, parameters);
    }

    /**
     * Query for byte.
     *
     * @param sql
     * @param parameters
     * @return
     * @see SQLExecutor#queryForSingleResult(Class, Connection, String, Object...).
     */
    @SafeVarargs
    public final OptionalByte queryForByte(final String sql, final Object... parameters) {
        return query(sql, StatementSetter.DEFAULT, SINGLE_BYTE_EXTRACTOR, null, parameters);
    }

    /**
     * Query for short.
     *
     * @param sql
     * @param parameters
     * @return
     * @see SQLExecutor#queryForSingleResult(Class, Connection, String, Object...).
     */
    @SafeVarargs
    public final OptionalShort queryForShort(final String sql, final Object... parameters) {
        return query(sql, StatementSetter.DEFAULT, SINGLE_SHORT_EXTRACTOR, null, parameters);
    }

    /**
     * Query for int.
     *
     * @param sql
     * @param parameters
     * @return
     * @see SQLExecutor#queryForSingleResult(Class, Connection, String, Object...).
     */
    @SafeVarargs
    public final OptionalInt queryForInt(final String sql, final Object... parameters) {
        return query(sql, StatementSetter.DEFAULT, SINGLE_INT_EXTRACTOR, null, parameters);
    }

    /**
     * Query for long.
     *
     * @param sql
     * @param parameters
     * @return
     * @see SQLExecutor#queryForSingleResult(Class, Connection, String, Object...).
     */
    @SafeVarargs
    public final OptionalLong queryForLong(final String sql, final Object... parameters) {
        return query(sql, StatementSetter.DEFAULT, SINGLE_LONG_EXTRACTOR, null, parameters);
    }

    /**
     * Query for float.
     *
     * @param sql
     * @param parameters
     * @return
     * @see SQLExecutor#queryForSingleResult(Class, Connection, String, Object...).
     */
    @SafeVarargs
    public final OptionalFloat queryForFloat(final String sql, final Object... parameters) {
        return query(sql, StatementSetter.DEFAULT, SINGLE_FLOAT_EXTRACTOR, null, parameters);
    }

    /**
     * Query for double.
     *
     * @param sql
     * @param parameters
     * @return
     * @see SQLExecutor#queryForSingleResult(Class, Connection, String, Object...).
     */
    @SafeVarargs
    public final OptionalDouble queryForDouble(final String sql, final Object... parameters) {
        return query(sql, StatementSetter.DEFAULT, SINGLE_DOUBLE_EXTRACTOR, null, parameters);
    }

    /**
     * Query for big decimal.
     *
     * @param sql
     * @param parameters
     * @return
     * @see SQLExecutor#queryForSingleResult(Class, Connection, String, Object...).
     */
    @SafeVarargs
    public final Nullable<BigDecimal> queryForBigDecimal(final String sql, final Object... parameters) {
        return query(sql, StatementSetter.DEFAULT, SINGLE_BIG_DECIMAL_EXTRACTOR, null, parameters);
    }

    /**
     * Query for string.
     *
     * @param sql
     * @param parameters
     * @return
     * @see SQLExecutor#queryForSingleResult(Class, Connection, String, Object...).
     */
    @SafeVarargs
    public final Nullable<String> queryForString(final String sql, final Object... parameters) {
        return query(sql, StatementSetter.DEFAULT, SINGLE_STRING_EXTRACTOR, null, parameters);
    }

    /**
     * Query for date.
     *
     * @param sql
     * @param parameters
     * @return
     * @see SQLExecutor#queryForSingleResult(Class, String, Object...).
     */
    @SafeVarargs
    public final Nullable<Date> queryForDate(final String sql, final Object... parameters) {
        return query(sql, StatementSetter.DEFAULT, SINGLE_DATE_EXTRACTOR, null, parameters);
    }

    /**
     * Query for time.
     *
     * @param sql
     * @param parameters
     * @return
     * @see SQLExecutor#queryForSingleResult(Class, String, Object...).
     */
    @SafeVarargs
    public final Nullable<Time> queryForTime(final String sql, final Object... parameters) {
        return query(sql, StatementSetter.DEFAULT, SINGLE_TIME_EXTRACTOR, null, parameters);
    }

    /**
     * Query for timestamp.
     *
     * @param sql
     * @param parameters
     * @return
     * @see SQLExecutor#queryForSingleResult(Class, String, Object...).
     */
    @SafeVarargs
    public final Nullable<Timestamp> queryForTimestamp(final String sql, final Object... parameters) {
        return query(sql, StatementSetter.DEFAULT, SINGLE_TIMESTAMP_EXTRACTOR, null, parameters);
    }

    /**
     * Query for single result.
     *
     * @param <V> the value type
     * @param targetClass
     * @param sql
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final <V> Nullable<V> queryForSingleResult(final Class<V> targetClass, final String sql, final Object... parameters) {
        return queryForSingleResult(targetClass, sql, StatementSetter.DEFAULT, parameters);
    }

    /**
     * Query for single result.
     *
     * @param <V> the value type
     * @param targetClass
     * @param sql
     * @param statementSetter
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final <V> Nullable<V> queryForSingleResult(final Class<V> targetClass, final String sql, final StatementSetter statementSetter,
            final Object... parameters) {
        return queryForSingleResult(targetClass, sql, statementSetter, null, parameters);
    }

    /**
     * Query for single result.
     *
     * @param <V> the value type
     * @param targetClass
     * @param sql
     * @param jdbcSettings
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final <V> Nullable<V> queryForSingleResult(final Class<V> targetClass, final String sql, final JdbcSettings jdbcSettings,
            final Object... parameters) {
        return queryForSingleResult(targetClass, sql, StatementSetter.DEFAULT, jdbcSettings, parameters);
    }

    /**
     * Query for single result.
     *
     * @param <V> the value type
     * @param targetClass
     * @param sql
     * @param statementSetter
     * @param jdbcSettings
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final <V> Nullable<V> queryForSingleResult(final Class<V> targetClass, final String sql, final StatementSetter statementSetter,
            final JdbcSettings jdbcSettings, final Object... parameters) {
        return queryForSingleResult(targetClass, null, sql, statementSetter, jdbcSettings, parameters);
    }

    /**
     * Query for single result.
     *
     * @param <V> the value type
     * @param targetClass
     * @param conn
     * @param sql
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final <V> Nullable<V> queryForSingleResult(final Class<V> targetClass, final Connection conn, final String sql, final Object... parameters) {
        return queryForSingleResult(targetClass, conn, sql, StatementSetter.DEFAULT, parameters);
    }

    /**
     * Query for single result.
     *
     * @param <V> the value type
     * @param targetClass
     * @param conn
     * @param sql
     * @param statementSetter
     * @param parameters
     * @return
     */
    public final <V> Nullable<V> queryForSingleResult(final Class<V> targetClass, final Connection conn, final String sql,
            final StatementSetter statementSetter, final Object... parameters) {
        return queryForSingleResult(targetClass, conn, sql, statementSetter, null, parameters);
    }

    /**
     * Query for single result.
     *
     * @param <V> the value type
     * @param targetClass
     * @param conn
     * @param sql
     * @param jdbcSettings
     * @param parameters
     * @return
     */
    public final <V> Nullable<V> queryForSingleResult(final Class<V> targetClass, final Connection conn, final String sql, final JdbcSettings jdbcSettings,
            final Object... parameters) {
        return queryForSingleResult(targetClass, conn, sql, StatementSetter.DEFAULT, jdbcSettings, parameters);
    }

    /**
     * Returns a {@code Nullable} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
     * <br />
     *
     * Special note for type conversion for {@code boolean} or {@code Boolean} type: {@code true} is returned if the
     * {@code String} value of the target column is {@code "true"}, case insensitive. or it's an integer with value > 0.
     * Otherwise, {@code false} is returned.
     *
     * Remember to add {@code limit} condition if big result will be returned by the query.
     *
     * @param <V> the value type
     * @param targetClass set result type to avoid the NullPointerException if result is null and T is primitive type
     *            "int, long. short ... char, boolean..".
     * @param conn
     * @param sql
     * @param statementSetter
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     */
    @SuppressWarnings("unchecked")
    @SafeVarargs
    public final <V> Nullable<V> queryForSingleResult(final Class<V> targetClass, final Connection conn, final String sql,
            final StatementSetter statementSetter, final JdbcSettings jdbcSettings, final Object... parameters) {
        return query(conn, sql, statementSetter, createSingleResultExtractor(targetClass), jdbcSettings, parameters);
    }

    /** The single result extractor pool. */
    private final ObjectPool<Class<?>, ResultExtractor<Nullable<?>>> singleResultExtractorPool = new ObjectPool<>(64);

    /**
     * Creates the single result extractor.
     *
     * @param <V> the value type
     * @param targetClass
     * @return
     */
    private <V> ResultExtractor<Nullable<V>> createSingleResultExtractor(final Class<V> targetClass) {
        @SuppressWarnings("rawtypes")
        ResultExtractor result = singleResultExtractorPool.get(targetClass);

        if (result == null) {
            result = new ResultExtractor<Nullable<V>>() {
                @Override
                public Nullable<V> apply(final ResultSet rs, final JdbcSettings jdbcSettings) throws SQLException {
                    JdbcUtil.skip(rs, jdbcSettings.getOffset());

                    if (rs.next()) {
                        return Nullable.of(N.convert(JdbcUtil.getColumnValue(rs, 1), targetClass));
                    }

                    return Nullable.empty();
                }
            };

            singleResultExtractorPool.put(targetClass, result);
        }

        return result;
    }

    /**
     * Query for unique result.
     *
     * @param <V> the value type
     * @param targetClass
     * @param sql
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    @SafeVarargs
    public final <V> Nullable<V> queryForUniqueResult(final Class<V> targetClass, final String sql, final Object... parameters)
            throws DuplicatedResultException {
        return queryForUniqueResult(targetClass, sql, StatementSetter.DEFAULT, parameters);
    }

    /**
     * Query for unique result.
     *
     * @param <V> the value type
     * @param targetClass
     * @param sql
     * @param statementSetter
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    @SafeVarargs
    public final <V> Nullable<V> queryForUniqueResult(final Class<V> targetClass, final String sql, final StatementSetter statementSetter,
            final Object... parameters) throws DuplicatedResultException {
        return queryForUniqueResult(targetClass, sql, statementSetter, null, parameters);
    }

    /**
     * Query for unique result.
     *
     * @param <V> the value type
     * @param targetClass
     * @param sql
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    @SafeVarargs
    public final <V> Nullable<V> queryForUniqueResult(final Class<V> targetClass, final String sql, final JdbcSettings jdbcSettings, final Object... parameters)
            throws DuplicatedResultException {
        return queryForUniqueResult(targetClass, sql, StatementSetter.DEFAULT, jdbcSettings, parameters);
    }

    /**
     * Query for unique result.
     *
     * @param <V> the value type
     * @param targetClass
     * @param sql
     * @param statementSetter
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    @SafeVarargs
    public final <V> Nullable<V> queryForUniqueResult(final Class<V> targetClass, final String sql, final StatementSetter statementSetter,
            final JdbcSettings jdbcSettings, final Object... parameters) throws DuplicatedResultException {
        return queryForUniqueResult(targetClass, null, sql, statementSetter, jdbcSettings, parameters);
    }

    /**
     * Query for unique result.
     *
     * @param <V> the value type
     * @param targetClass
     * @param conn
     * @param sql
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    @SafeVarargs
    public final <V> Nullable<V> queryForUniqueResult(final Class<V> targetClass, final Connection conn, final String sql, final Object... parameters)
            throws DuplicatedResultException {
        return queryForUniqueResult(targetClass, conn, sql, StatementSetter.DEFAULT, parameters);
    }

    /**
     * Query for unique result.
     *
     * @param <V> the value type
     * @param targetClass
     * @param conn
     * @param sql
     * @param statementSetter
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    public final <V> Nullable<V> queryForUniqueResult(final Class<V> targetClass, final Connection conn, final String sql,
            final StatementSetter statementSetter, final Object... parameters) throws DuplicatedResultException {
        return queryForUniqueResult(targetClass, conn, sql, statementSetter, null, parameters);
    }

    /**
     * Query for unique result.
     *
     * @param <V> the value type
     * @param targetClass
     * @param conn
     * @param sql
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if two or more records are found.
     */
    public final <V> Nullable<V> queryForUniqueResult(final Class<V> targetClass, final Connection conn, final String sql, final JdbcSettings jdbcSettings,
            final Object... parameters) throws DuplicatedResultException {
        return queryForUniqueResult(targetClass, conn, sql, StatementSetter.DEFAULT, jdbcSettings, parameters);
    }

    /**
     * Returns a {@code Nullable} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
     * And throws {@code DuplicatedResultException} if more than one record found.
     * <br />
     *
     * Special note for type conversion for {@code boolean} or {@code Boolean} type: {@code true} is returned if the
     * {@code String} value of the target column is {@code "true"}, case insensitive. or it's an integer with value > 0.
     * Otherwise, {@code false} is returned.
     *
     * Remember to add {@code limit} condition if big result will be returned by the query.
     *
     * @param <V> the value type
     * @param targetClass set result type to avoid the NullPointerException if result is null and T is primitive type
     *            "int, long. short ... char, boolean..".
     * @param conn
     * @param sql
     * @param statementSetter
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     * @throws DuplicatedResultException if more than one record found.
     */
    @SuppressWarnings("unchecked")
    @SafeVarargs
    public final <V> Nullable<V> queryForUniqueResult(final Class<V> targetClass, final Connection conn, final String sql,
            final StatementSetter statementSetter, final JdbcSettings jdbcSettings, final Object... parameters) throws DuplicatedResultException {
        return query(conn, sql, statementSetter, createUniqueResultExtractor(targetClass), jdbcSettings, parameters);
    }

    /** The unique result extractor pool. */
    private final ObjectPool<Class<?>, ResultExtractor<Nullable<?>>> uniqueResultExtractorPool = new ObjectPool<>(64);

    /**
     * Creates the unique result extractor.
     *
     * @param <V> the value type
     * @param targetClass
     * @return
     */
    private <V> ResultExtractor<Nullable<V>> createUniqueResultExtractor(final Class<V> targetClass) {
        @SuppressWarnings("rawtypes")
        ResultExtractor result = uniqueResultExtractorPool.get(targetClass);

        if (result == null) {
            result = new ResultExtractor<Nullable<V>>() {
                @Override
                public Nullable<V> apply(final ResultSet rs, final JdbcSettings jdbcSettings) throws SQLException {
                    JdbcUtil.skip(rs, jdbcSettings.getOffset());

                    if (rs.next()) {
                        final Nullable<V> result = Nullable.of(N.convert(JdbcUtil.getColumnValue(rs, 1), targetClass));

                        if (result.isPresent() && rs.next()) {
                            throw new DuplicatedResultException("At least two results found: "
                                    + Strings.concat(result.get(), ", ", N.convert(JdbcUtil.getColumnValue(rs, 1), targetClass)));
                        }

                        return result;
                    }

                    return Nullable.empty();
                }
            };

            uniqueResultExtractorPool.put(targetClass, result);
        }

        return result;
    }

    /**
     *
     * @param sql
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final DataSet query(final String sql, final Object... parameters) {
        return query(sql, StatementSetter.DEFAULT, parameters);
    }

    /**
     *
     * @param sql
     * @param statementSetter
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final DataSet query(final String sql, final StatementSetter statementSetter, final Object... parameters) {
        return query(sql, statementSetter, (JdbcSettings) null, parameters);
    }

    /**
     *
     * @param sql
     * @param jdbcSettings
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final DataSet query(final String sql, final JdbcSettings jdbcSettings, final Object... parameters) {
        return query(sql, StatementSetter.DEFAULT, jdbcSettings, parameters);
    }

    /**
     *
     * @param sql
     * @param statementSetter
     * @param jdbcSettings
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final DataSet query(final String sql, final StatementSetter statementSetter, final JdbcSettings jdbcSettings, final Object... parameters) {
        return query(sql, statementSetter, ResultExtractor.TO_DATA_SET, jdbcSettings, parameters);
    }

    /**
     *
     * @param <T>
     * @param sql
     * @param resultExtractor
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final <T> T query(final String sql, final ResultExtractor<T> resultExtractor, final Object... parameters) {
        return query(sql, StatementSetter.DEFAULT, resultExtractor, parameters);
    }

    /**
     *
     * @param <T>
     * @param sql
     * @param statementSetter
     * @param resultExtractor
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final <T> T query(final String sql, final StatementSetter statementSetter, final ResultExtractor<T> resultExtractor, final Object... parameters) {
        return query(sql, statementSetter, resultExtractor, null, parameters);
    }

    /**
     *
     * @param <T>
     * @param sql
     * @param resultExtractor
     * @param jdbcSettings
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final <T> T query(final String sql, final ResultExtractor<T> resultExtractor, final JdbcSettings jdbcSettings, final Object... parameters) {
        return query(sql, StatementSetter.DEFAULT, resultExtractor, jdbcSettings, parameters);
    }

    /**
     *
     * Remember to close the <code>ResultSet</code>, <code>Statement</code> and <code>Connection</code> if the return type <code>T</code> is <code>ResultSet</code> or <code>RowIterator</code>.
     *
     * If <code>T</code> is <code>RowIterator</code>, call <code>rowIterator.close()</code> to close <code>ResultSet</code>, <code>Statement</code> and <code>Connection</code>.
     * <br></br>
     * If <code>T</code> is <code>ResultSet</code>, call below codes to close <code>ResultSet</code>, <code>Statement</code> and <code>Connection</code>.
     *
     * <pre>
     * <code>
     * Connection conn = null;
     * Statement stmt = null;
     *
     * try {
     *     stmt = rs.getStatement();
     *     conn = stmt.getConnection();
     * } catch (SQLException e) {
     *     // TODO.
     * } finally {
     *     JdbcUtil.closeQuietly(rs, stmt, conn);
     * }
     * </code>
     * </pre>
     *
     * @param <T>
     * @param sql
     * @param statementSetter
     * @param resultExtractor
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     */
    @SafeVarargs
    public final <T> T query(final String sql, final StatementSetter statementSetter, final ResultExtractor<T> resultExtractor, final JdbcSettings jdbcSettings,
            final Object... parameters) {
        return query(null, sql, statementSetter, resultExtractor, jdbcSettings, parameters);
    }

    /**
     *
     * @param conn
     * @param sql
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final DataSet query(final Connection conn, final String sql, final Object... parameters) {
        return query(conn, sql, StatementSetter.DEFAULT, parameters);
    }

    /**
     *
     * @param conn
     * @param sql
     * @param statementSetter
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final DataSet query(final Connection conn, final String sql, final StatementSetter statementSetter, final Object... parameters) {
        return query(conn, sql, statementSetter, (JdbcSettings) null, parameters);
    }

    /**
     *
     * @param conn
     * @param sql
     * @param jdbcSettings
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final DataSet query(final Connection conn, final String sql, final JdbcSettings jdbcSettings, final Object... parameters) {
        return query(conn, sql, StatementSetter.DEFAULT, jdbcSettings, parameters);
    }

    /**
     *
     * @param conn
     * @param sql
     * @param statementSetter
     * @param jdbcSettings
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final DataSet query(final Connection conn, final String sql, final StatementSetter statementSetter, final JdbcSettings jdbcSettings,
            final Object... parameters) {
        return query(conn, sql, statementSetter, ResultExtractor.TO_DATA_SET, jdbcSettings, parameters);
    }

    /**
     *
     * @param <T>
     * @param conn
     * @param sql
     * @param resultExtractor
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final <T> T query(final Connection conn, final String sql, final ResultExtractor<T> resultExtractor, final Object... parameters) {
        return query(conn, sql, StatementSetter.DEFAULT, resultExtractor, parameters);
    }

    /**
     *
     * @param <T>
     * @param conn
     * @param sql
     * @param statementSetter
     * @param resultExtractor
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final <T> T query(final Connection conn, final String sql, final StatementSetter statementSetter, final ResultExtractor<T> resultExtractor,
            final Object... parameters) {
        return query(conn, sql, statementSetter, resultExtractor, null, parameters);
    }

    /**
     *
     * @param <T>
     * @param conn
     * @param sql
     * @param resultExtractor
     * @param jdbcSettings
     * @param parameters
     * @return
     */
    @SafeVarargs
    public final <T> T query(final Connection conn, final String sql, final ResultExtractor<T> resultExtractor, final JdbcSettings jdbcSettings,
            final Object... parameters) {
        return query(conn, sql, StatementSetter.DEFAULT, resultExtractor, jdbcSettings, parameters);
    }

    /**
     * Remember to close the <code>ResultSet</code>, <code>Statement</code> and <code>Connection</code> if the return type <code>T</code> is <code>ResultSet</code> or <code>RowIterator</code>.
     *
     * If <code>T</code> is <code>RowIterator</code>, call <code>rowIterator.close()</code> to close <code>ResultSet</code>, <code>Statement</code> and <code>Connection</code>.
     * <br></br>
     * If <code>T</code> is <code>ResultSet</code>, call below codes to close <code>ResultSet</code>, <code>Statement</code> and <code>Connection</code>.
     *
     * <pre>
     * <code>
     * Connection conn = null;
     * Statement stmt = null;
     *
     * try {
     *     stmt = rs.getStatement();
     *     conn = stmt.getConnection();
     * } catch (SQLException e) {
     *     // TODO.
     * } finally {
     *     JdbcUtil.closeQuietly(rs, stmt, conn);
     * }
     * </code>
     * </pre>
     *
     *
     * @param <T>
     * @param conn
     * @param sql
     * @param statementSetter
     * @param resultExtractor
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     */
    @SafeVarargs
    public final <T> T query(final Connection conn, final String sql, final StatementSetter statementSetter, final ResultExtractor<T> resultExtractor,
            final JdbcSettings jdbcSettings, final Object... parameters) {
        return query(null, conn, sql, statementSetter, new ResultSetExtractor<T>() {
            @Override
            public T extractData(Class<?> targetClass, ParsedSql parsedSql, ResultSet rs, final JdbcSettings jdbcSettings) throws SQLException {
                return resultExtractor.apply(rs, jdbcSettings);
            }

        }, jdbcSettings, parameters);
    }

    /**
     *
     * @param <T>
     * @param targetClass
     * @param inputConn
     * @param sql
     * @param statementSetter
     * @param resultExtractor
     * @param jdbcSettings
     * @param parameters
     * @return
     */
    protected <T> T query(final Class<T> targetClass, final Connection inputConn, final String sql, StatementSetter statementSetter,
            ResultSetExtractor<T> resultExtractor, JdbcSettings jdbcSettings, final Object... parameters) {
        final ParsedSql parsedSql = getParsedSql(sql);
        statementSetter = checkStatementSetter(parsedSql, statementSetter);
        resultExtractor = checkResultSetExtractor(parsedSql, resultExtractor);
        jdbcSettings = checkJdbcSettings(jdbcSettings, parsedSql, _sqlMapper.getAttrs(sql));

        final boolean isFromStreamQuery = resultExtractor == RESULT_SET_EXTRACTOR_ONLY_FOR_STREAM;
        boolean noException = false;

        T result = null;

        DataSource ds = null;
        Connection localConn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            ds = getDataSource(parsedSql.getParameterizedSql(), parameters, jdbcSettings);

            localConn = getConnection(inputConn, ds, jdbcSettings, SQLOperation.SELECT);

            stmt = prepareStatement(ds, localConn, parsedSql, statementSetter, jdbcSettings, false, false, parameters);

            if (jdbcSettings == null || jdbcSettings.getFetchDirection() == -1) {
                stmt.setFetchDirection(ResultSet.FETCH_FORWARD);
            }

            rs = JdbcUtil.executeQuery(stmt);

            result = resultExtractor.extractData(targetClass, parsedSql, rs, jdbcSettings);

            noException = true;
        } catch (SQLException e) {
            String msg = ExceptionUtil.getMessage(e) + ". [SQL] " + parsedSql.sql();
            throw new UncheckedSQLException(msg, e);
        } finally {
            if (noException && result instanceof ResultSet) {
                if (isFromStreamQuery) {
                    // will be closed in stream.
                } else {
                    try {
                        close(rs, stmt);
                    } finally {
                        close(localConn, inputConn, ds);
                    }

                    throw new UnsupportedOperationException("The return type of 'ResultSetExtractor' can't be 'ResultSet'.");
                }
            } else {
                try {
                    close(rs, stmt);
                } finally {
                    close(localConn, inputConn, ds);
                }
            }
        }

        return result;
    }

    /**
     * Execute the query in one or more data sources specified by {@code jdbcSettings} and merge the results.
     * It's designed for partition.
     *
     * @param sql
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     */
    @SafeVarargs
    public final DataSet queryAll(final String sql, final JdbcSettings jdbcSettings, final Object... parameters) {
        return queryAll(sql, StatementSetter.DEFAULT, jdbcSettings, parameters);
    }

    /**
     * Execute the query in one or more data sources specified by {@code jdbcSettings} and merge the results.
     * It's designed for partition.
     *
     * @param sql
     * @param statementSetter
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     */
    @SafeVarargs
    public final DataSet queryAll(final String sql, final StatementSetter statementSetter, final JdbcSettings jdbcSettings, final Object... parameters) {
        checkJdbcSettingsForAllQuery(jdbcSettings);

        if (jdbcSettings == null || N.isNullOrEmpty(jdbcSettings.getQueryWithDataSources())) {
            return query(sql, statementSetter, jdbcSettings, parameters);
        }

        final Collection<String> dss = jdbcSettings.getQueryWithDataSources();

        if (jdbcSettings.isQueryInParallel()) {
            final List<DataSet> resultList = Stream.of(dss).map(new Function<String, JdbcSettings>() {
                @Override
                public JdbcSettings apply(String ds) {
                    final JdbcSettings newJdbcSettings = jdbcSettings.copy();
                    newJdbcSettings.setQueryWithDataSources(null);
                    newJdbcSettings.setQueryWithDataSource(ds);
                    return newJdbcSettings;
                }
            }).parallel(dss.size()).map(new Function<JdbcSettings, DataSet>() {
                @Override
                public DataSet apply(JdbcSettings newJdbcSettings) {
                    return query(sql, statementSetter, newJdbcSettings, parameters);
                }
            }).toList();

            return DataSetUtil.merge(resultList);
        } else {
            final JdbcSettings newJdbcSettings = jdbcSettings.copy();
            newJdbcSettings.setQueryWithDataSources(null);
            final List<DataSet> resultList = new ArrayList<>(dss.size());

            for (String ds : dss) {
                newJdbcSettings.setQueryWithDataSource(ds);

                resultList.add(query(sql, statementSetter, newJdbcSettings, parameters));
            }

            return DataSetUtil.merge(resultList);
        }
    }

    /**
     * Execute one or more queries in one or more data sources specified by {@code jdbcSettings} and merge the results.
     * It's designed for partition.
     *
     * @param sqls
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     */
    @SafeVarargs
    public final DataSet queryAll(final List<String> sqls, final JdbcSettings jdbcSettings, final Object... parameters) {
        return queryAll(sqls, null, jdbcSettings, parameters);
    }

    /**
     * Execute one or more queries in one or more data sources specified by {@code jdbcSettings} and merge the results.
     * It's designed for partition.
     *
     * @param sqls
     * @param statementSetter
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     */
    @SafeVarargs
    public final DataSet queryAll(final List<String> sqls, final StatementSetter statementSetter, final JdbcSettings jdbcSettings, final Object... parameters) {
        if (sqls.size() == 1) {
            return queryAll(sqls.get(0), statementSetter, jdbcSettings, parameters);
        }

        if (jdbcSettings != null && jdbcSettings.isQueryInParallel()) {
            final List<DataSet> resultList = Stream.of(sqls).parallel(sqls.size()).map(new Function<String, DataSet>() {
                @Override
                public DataSet apply(String sql) {
                    return queryAll(sql, statementSetter, jdbcSettings, parameters);
                }
            }).toList();

            return DataSetUtil.merge(resultList);
        } else {
            final List<DataSet> resultList = new ArrayList<>(sqls.size());

            for (String sql : sqls) {
                resultList.add(queryAll(sql, statementSetter, jdbcSettings, parameters));
            }

            return DataSetUtil.merge(resultList);
        }
    }

    /**
     *
     * Lazy execution, lazy fetch. The query execution and record fetching only happen when a terminal operation of the stream is called.
     *
     * @param <T>
     * @param targetClass
     * @param sql
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     */
    @SafeVarargs
    public final <T> Stream<T> stream(final Class<T> targetClass, final String sql, final Object... parameters) {
        return stream(targetClass, sql, StatementSetter.DEFAULT, parameters);
    }

    /**
     * Lazy execution, lazy fetch. The query execution and record fetching only happen when a terminal operation of the stream is called.
     *
     * @param <T>
     * @param targetClass
     * @param sql
     * @param statementSetter
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     */
    @SafeVarargs
    public final <T> Stream<T> stream(final Class<T> targetClass, final String sql, final StatementSetter statementSetter, final Object... parameters) {
        return stream(targetClass, sql, statementSetter, null, parameters);
    }

    /**
     * Lazy execution, lazy fetch. The query execution and record fetching only happen when a terminal operation of the stream is called.
     *
     * @param <T>
     * @param targetClass
     * @param sql
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     */
    public final <T> Stream<T> stream(final Class<T> targetClass, final String sql, final JdbcSettings jdbcSettings, final Object... parameters) {
        return stream(targetClass, sql, StatementSetter.DEFAULT, jdbcSettings, parameters);
    }

    /**
     * Lazy execution, lazy fetch. The query execution and record fetching only happen when a terminal operation of the stream is called.
     *
     * @param <T>
     * @param targetClass
     * @param sql
     * @param statementSetter
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     */
    @SafeVarargs
    public final <T> Stream<T> stream(final Class<T> targetClass, final String sql, final StatementSetter statementSetter, final JdbcSettings jdbcSettings,
            final Object... parameters) {
        return stream(sql, statementSetter, BiRowMapper.to(targetClass), jdbcSettings, parameters);
    }

    /**
     * Lazy execution, lazy fetch. The query execution and record fetching only happen when a terminal operation of the stream is called.
     *
     * @param <T>
     * @param sql
     * @param rowMapper
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     */
    @SafeVarargs
    public final <T> Stream<T> stream(final String sql, final JdbcUtil.BiRowMapper<T> rowMapper, final Object... parameters) {
        return stream(sql, StatementSetter.DEFAULT, rowMapper, parameters);
    }

    /**
     * Lazy execution, lazy fetch. The query execution and record fetching only happen when a terminal operation of the stream is called.
     *
     * @param <T>
     * @param sql
     * @param statementSetter
     * @param rowMapper
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     */
    @SafeVarargs
    public final <T> Stream<T> stream(final String sql, final StatementSetter statementSetter, final JdbcUtil.BiRowMapper<T> rowMapper,
            final Object... parameters) {
        return stream(sql, statementSetter, rowMapper, null, parameters);
    }

    /**
     * Lazy execution, lazy fetch. The query execution and record fetching only happen when a terminal operation of the stream is called.
     *
     * @param <T>
     * @param sql
     * @param rowMapper
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     */
    @SafeVarargs
    public final <T> Stream<T> stream(final String sql, final JdbcUtil.BiRowMapper<T> rowMapper, final JdbcSettings jdbcSettings, final Object... parameters) {
        return stream(sql, StatementSetter.DEFAULT, rowMapper, jdbcSettings, parameters);
    }

    /** The Constant RESULT_SET_EXTRACTOR. */
    private static final ResultSetExtractor<ResultSet> RESULT_SET_EXTRACTOR_ONLY_FOR_STREAM = new ResultSetExtractor<ResultSet>() {
        @Override
        public ResultSet extractData(final Class<?> targetClass, final ParsedSql parsedSql, final ResultSet rs, final JdbcSettings jdbcSettings)
                throws SQLException {
            return rs;
        }
    };

    /**
     *
     * Lazy execution, lazy fetch. The query execution and record fetching only happen when a terminal operation of the stream is called.
     *
     * @param <T>
     * @param sql
     * @param statementSetter
     * @param rowMapper
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     */
    @SafeVarargs
    public final <T> Stream<T> stream(final String sql, final StatementSetter statementSetter, final JdbcUtil.BiRowMapper<T> rowMapper,
            final JdbcSettings jdbcSettings, final Object... parameters) {

        final ObjIteratorEx<T> lazyIter = ObjIteratorEx.of(new Supplier<ObjIteratorEx<T>>() {
            private ObjIteratorEx<T> internalIter = null;

            @SuppressWarnings("deprecation")
            @Override
            public ObjIteratorEx<T> get() {
                if (internalIter == null) {
                    final Connection inputConn = null;

                    final JdbcSettings newJdbcSettings = jdbcSettings == null ? _jdbcSettings.copy() : jdbcSettings.copy();
                    final int offset = newJdbcSettings.getOffset();
                    final int count = newJdbcSettings.getCount();
                    newJdbcSettings.setOffset(0);
                    newJdbcSettings.setCount(Integer.MAX_VALUE);

                    final boolean noTransactionForStream = newJdbcSettings.noTransactionForStream();
                    final ParsedSql parsedSql = ParsedSql.parse(sql);
                    final DataSource ds = getDataSource(parsedSql.getParameterizedSql(), parameters, newJdbcSettings);
                    final Connection localConn = noTransactionForStream ? directGetConnectionFromPool(ds)
                            : getConnection(inputConn, ds, newJdbcSettings, SQLOperation.SELECT);
                    ResultSet resultSet = null;

                    try {
                        resultSet = query(null, localConn, sql, statementSetter, RESULT_SET_EXTRACTOR_ONLY_FOR_STREAM, newJdbcSettings, parameters);
                        final ResultSet rs = resultSet;

                        internalIter = new ObjIteratorEx<T>() {
                            private boolean skipped = false;
                            private boolean hasNext = false;
                            private int cnt = 0;
                            private List<String> columnLabels = null;

                            @Override
                            public boolean hasNext() {
                                if (skipped == false) {
                                    skip();
                                }

                                if (hasNext == false) {
                                    try {
                                        if (cnt++ < count && rs.next()) {
                                            hasNext = true;
                                        }
                                    } catch (SQLException e) {
                                        throw new UncheckedSQLException(e);
                                    }
                                }

                                return hasNext;
                            }

                            @Override
                            public T next() {
                                if (hasNext() == false) {
                                    throw new NoSuchElementException();
                                }

                                try {
                                    final T result = rowMapper.apply(rs, columnLabels);
                                    hasNext = false;
                                    return result;
                                } catch (SQLException e) {
                                    throw new UncheckedSQLException(e);
                                }
                            }

                            @Override
                            public void skip(long n) {
                                N.checkArgNotNegative(n, "n");

                                if (skipped == false) {
                                    skip();
                                }

                                final long m = hasNext ? n - 1 : n;
                                hasNext = false;

                                try {
                                    JdbcUtil.skip(rs, Math.min(m, count - cnt));
                                } catch (SQLException e) {
                                    throw new UncheckedSQLException(e);
                                }
                            }

                            @Override
                            public long count() {
                                if (skipped == false) {
                                    skip();
                                }

                                long result = hasNext ? 1 : 0;
                                hasNext = false;

                                try {
                                    while (cnt++ < count && rs.next()) {
                                        result++;
                                    }
                                } catch (SQLException e) {
                                    throw new UncheckedSQLException(e);
                                }

                                return result;
                            }

                            @Override
                            public void close() {
                                try {
                                    JdbcUtil.closeQuietly(rs, true, false);
                                } finally {
                                    if (noTransactionForStream) {
                                        JdbcUtil.closeQuietly(localConn);
                                    } else {
                                        SQLExecutor.this.close(localConn, inputConn, ds);
                                    }
                                }
                            }

                            private void skip() {
                                if (skipped == false) {
                                    skipped = true;

                                    try {
                                        columnLabels = JdbcUtil.getColumnLabelList(rs);

                                        if (offset > 0) {
                                            JdbcUtil.skip(rs, offset);
                                        }
                                    } catch (SQLException e) {
                                        throw new UncheckedSQLException(e);
                                    }
                                }
                            }
                        };
                    } finally {
                        if (internalIter == null) {
                            try {
                                JdbcUtil.closeQuietly(resultSet, true, false);
                            } finally {
                                if (noTransactionForStream) {
                                    JdbcUtil.closeQuietly(localConn);
                                } else {
                                    SQLExecutor.this.close(localConn, inputConn, ds);
                                }
                            }
                        }
                    }
                }

                return internalIter;
            }
        });

        return Stream.of(lazyIter).onClose(new Runnable() {
            @Override
            public void run() {
                lazyIter.close();
            }
        });
    }

    private Connection directGetConnectionFromPool(final DataSource ds) throws UncheckedSQLException {
        try {
            return ds.getConnection();
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     * Execute the query in one or more data sources specified by {@code jdbcSettings} and merge the results.
     * It's designed for partition.
     *
     * @param <T>
     * @param targetClass
     * @param sql
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     */
    @SafeVarargs
    public final <T> Stream<T> streamAll(final Class<T> targetClass, final String sql, final JdbcSettings jdbcSettings, final Object... parameters) {
        return streamAll(targetClass, sql, StatementSetter.DEFAULT, jdbcSettings, parameters);
    }

    /**
     * Execute the query in one or more data sources specified by {@code jdbcSettings} and merge the results.
     * It's designed for partition.
     *
     * @param <T>
     * @param targetClass
     * @param sql
     * @param statementSetter
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     */
    @SafeVarargs
    public final <T> Stream<T> streamAll(final Class<T> targetClass, final String sql, final StatementSetter statementSetter, final JdbcSettings jdbcSettings,
            final Object... parameters) {
        return streamAll(sql, statementSetter, BiRowMapper.to(targetClass), jdbcSettings, parameters);
    }

    /**
     * Execute the query in one or more data sources specified by {@code jdbcSettings} and merge the results.
     * It's designed for partition.
     *
     * @param <T>
     * @param sql
     * @param rowMapper
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     */
    @SafeVarargs
    public final <T> Stream<T> streamAll(final String sql, final JdbcUtil.BiRowMapper<T> rowMapper, final JdbcSettings jdbcSettings,
            final Object... parameters) {
        return streamAll(sql, StatementSetter.DEFAULT, rowMapper, jdbcSettings, parameters);
    }

    /**
     * Execute the query in one or more data sources specified by {@code jdbcSettings} and merge the results.
     * It's designed for partition.
     *
     * @param <T>
     * @param sql
     * @param statementSetter
     * @param rowMapper
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     */
    @SafeVarargs
    public final <T> Stream<T> streamAll(final String sql, final StatementSetter statementSetter, final JdbcUtil.BiRowMapper<T> rowMapper,
            final JdbcSettings jdbcSettings, final Object... parameters) {
        checkJdbcSettingsForAllQuery(jdbcSettings);

        if (jdbcSettings == null || N.isNullOrEmpty(jdbcSettings.getQueryWithDataSources())) {
            return stream(sql, statementSetter, rowMapper, jdbcSettings, parameters);
        }

        final Collection<String> dss = jdbcSettings.getQueryWithDataSources();

        return Stream.of(dss).map(new Function<String, JdbcSettings>() {
            @Override
            public JdbcSettings apply(String ds) {
                return jdbcSettings.copy().setQueryWithDataSources(null).setQueryWithDataSource(ds);
            }
        }).__(new Function<Stream<JdbcSettings>, Stream<JdbcSettings>>() {
            @Override
            public Stream<JdbcSettings> apply(Stream<JdbcSettings> s) {
                return jdbcSettings.isQueryInParallel() ? s.parallel(dss.size()) : s;
            }
        }).flatMap(new Function<JdbcSettings, Stream<T>>() {
            @Override
            public Stream<T> apply(JdbcSettings newJdbcSettings) {
                return stream(sql, statementSetter, rowMapper, newJdbcSettings, parameters);
            }
        });
    }

    /**
     * Execute one or more queries in one or more data sources specified by {@code jdbcSettings} and merge the results.
     * It's designed for partition.
     *
     * @param <T>
     * @param targetClass
     * @param sqls
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     */
    @SafeVarargs
    public final <T> Stream<T> streamAll(final Class<T> targetClass, final List<String> sqls, final JdbcSettings jdbcSettings, final Object... parameters) {
        return streamAll(targetClass, sqls, null, jdbcSettings, parameters);
    }

    /**
     * Execute one or more queries in one or more data sources specified by {@code jdbcSettings} and merge the results.
     * It's designed for partition.
     *
     * @param <T>
     * @param targetClass
     * @param sqls
     * @param statementSetter
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     */
    @SafeVarargs
    public final <T> Stream<T> streamAll(final Class<T> targetClass, final List<String> sqls, final StatementSetter statementSetter,
            final JdbcSettings jdbcSettings, final Object... parameters) {
        return streamAll(sqls, statementSetter, BiRowMapper.to(targetClass), jdbcSettings, parameters);
    }

    /**
     * Execute one or more queries in one or more data sources specified by {@code jdbcSettings} and merge the results.
     * It's designed for partition.
     *
     * @param <T>
     * @param sqls
     * @param rowMapper
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     */
    @SafeVarargs
    public final <T> Stream<T> streamAll(final List<String> sqls, final JdbcUtil.BiRowMapper<T> rowMapper, final JdbcSettings jdbcSettings,
            final Object... parameters) {
        return streamAll(sqls, null, rowMapper, jdbcSettings, parameters);
    }

    /**
     * Execute one or more queries in one or more data sources specified by {@code jdbcSettings} and merge the results.
     * It's designed for partition.
     *
     * @param <T>
     * @param sqls
     * @param statementSetter
     * @param rowMapper
     * @param jdbcSettings
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @return
     */
    @SafeVarargs
    public final <T> Stream<T> streamAll(final List<String> sqls, final StatementSetter statementSetter, final JdbcUtil.BiRowMapper<T> rowMapper,
            final JdbcSettings jdbcSettings, final Object... parameters) {
        if (sqls.size() == 1) {
            return streamAll(sqls.get(0), statementSetter, rowMapper, jdbcSettings, parameters);
        }

        final boolean isQueryInParallel = jdbcSettings != null && jdbcSettings.isQueryInParallel();

        return Stream.of(sqls).__(new Function<Stream<String>, Stream<String>>() {
            @Override
            public Stream<String> apply(Stream<String> s) {
                return isQueryInParallel ? s.parallel(sqls.size()) : s;
            }
        }).flatMap(new Function<String, Stream<T>>() {
            @Override
            public Stream<T> apply(String sql) {
                return streamAll(sql, statementSetter, rowMapper, jdbcSettings, parameters);
            }
        });
    }

    /**
     *
     * @param sql
     * @param parameters
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public final boolean execute(final String sql, final Object... parameters) throws UncheckedSQLException {
        return execute(null, sql, parameters);
    }

    /**
     * Execute the sql with the specified parameters.
     *
     * @param conn
     * @param sql
     * @param parameters it can be {@code Object[]/List} for (named) parameterized query, or {@code Map<String, Object>/Entity} for named parameterized query.
     * DO NOT use primitive array {@code boolean[]/char[]/byte[]/short[]/int[]/long[]/float[]/double[]} for passing multiple parameters.
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SafeVarargs
    public final boolean execute(final Connection conn, final String sql, final Object... parameters) throws UncheckedSQLException {
        final ParsedSql parsedSql = getParsedSql(sql);
        final StatementSetter statementSetter = checkStatementSetter(parsedSql, null);
        final JdbcSettings jdbcSettings = checkJdbcSettings(null, parsedSql, _sqlMapper.getAttrs(sql));

        final SQLOperation sqlOperation = JdbcUtil.getSQLOperation(parsedSql.getParameterizedSql());
        DataSource ds = null;
        Connection localConn = null;
        PreparedStatement stmt = null;

        try {
            ds = getDataSource(parsedSql.getParameterizedSql(), parameters, jdbcSettings);

            localConn = getConnection(conn, ds, jdbcSettings, sqlOperation);

            stmt = prepareStatement(ds, localConn, parsedSql, statementSetter, jdbcSettings, false, false, parameters);

            return JdbcUtil.execute(stmt);
        } catch (SQLException e) {
            String msg = ExceptionUtil.getMessage(e) + ". [SQL] " + parsedSql.sql();
            throw new UncheckedSQLException(msg, e);
        } finally {
            close(stmt);
            close(localConn, conn, ds);
        }
    }

    /**
     * Refer to {@code beginTransaction(IsolationLevel, boolean, JdbcSettings)}.
     *
     * @return
     * @see #beginTransaction(IsolationLevel, boolean, JdbcSettings)
     */
    public SQLTransaction beginTransaction() {
        return beginTransaction(IsolationLevel.DEFAULT);
    }

    /**
     *
     * Refer to {@code beginTransaction(IsolationLevel, boolean, JdbcSettings)}.
     *
     * @param isolationLevel
     * @return
     * @see #beginTransaction(IsolationLevel, boolean, JdbcSettings)
     */
    public SQLTransaction beginTransaction(final IsolationLevel isolationLevel) {
        return beginTransaction(isolationLevel, false);
    }

    /**
     *
     * Refer to {@code beginTransaction(IsolationLevel, boolean, JdbcSettings)}.
     *
     * @param forUpdateOnly
     * @return
     * @see #beginTransaction(IsolationLevel, boolean, JdbcSettings)
     */
    public SQLTransaction beginTransaction(final boolean forUpdateOnly) {
        return beginTransaction(IsolationLevel.DEFAULT, forUpdateOnly);
    }

    /**
     *
     * Refer to {@code beginTransaction(IsolationLevel, boolean, JdbcSettings)}.
     *
     * @param isolationLevel
     * @param forUpdateOnly
     * @return
     * @see #beginTransaction(IsolationLevel, boolean, JdbcSettings)
     */
    public SQLTransaction beginTransaction(IsolationLevel isolationLevel, boolean forUpdateOnly) {
        return beginTransaction(isolationLevel, forUpdateOnly, null);
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * That's to say the transaction started by {@code JdbcUtil.beginTransaction} or in {@code Spring} will have the final control on commit/roll back over the {@code Connection}.
     * <br />
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     * <br />
     * Transactions started by {@code SQLExecutor.beginTransaction} won't be shared by {@code JdbcUtil.beginTransaction} or Spring.
     *
     * <br />
     * <br />
     *
     * The connection opened in the transaction will be automatically closed after the transaction is committed or rolled back.
     * DON'T close it again by calling the close method.
     *
     * Transaction can be started:
     *
     * <pre>
     * <code>
     *   final SQLTransaction tran = sqlExecutor.beginTransaction(IsolationLevel.READ_COMMITTED);
     *   try {
     *       // sqlExecutor.insert(...);
     *       // sqlExecutor.update(...);
     *       // sqlExecutor.query(...);
     *
     *       tran.commit();
     *   } finally {
     *       // The connection will be automatically closed after the transaction is committed or rolled back.
     *       tran.rollbackIfNotCommitted();
     *   }
     * </code>
     * </pre>
     *
     * @param isolationLevel
     * @param forUpdateOnly
     * @param jdbcSettings
     * @return
     */
    public SQLTransaction beginTransaction(final IsolationLevel isolationLevel, final boolean forUpdateOnly, final JdbcSettings jdbcSettings) {
        N.checkArgNotNull(isolationLevel, "isolationLevel");

        final DataSource ds = jdbcSettings != null && jdbcSettings.getQueryWithDataSource() != null
                ? getDataSource(N.EMPTY_STRING, N.EMPTY_OBJECT_ARRAY, jdbcSettings)
                : _ds;

        SQLTransaction tran = SQLTransaction.getTransaction(ds, CreatedBy.JDBC_UTIL);

        if (tran == null) {
            Connection conn = null;
            boolean noException = false;

            try {
                conn = getConnection(ds);
                tran = new SQLTransaction(ds, conn, isolationLevel == IsolationLevel.DEFAULT ? _defaultIsolationLevel : isolationLevel, CreatedBy.JDBC_UTIL,
                        true);
                tran.incrementAndGetRef(isolationLevel, forUpdateOnly);

                noException = true;
            } catch (SQLException e) {
                throw new UncheckedSQLException(e);
            } finally {
                if (noException == false) {
                    close(conn, ds);
                }
            }

            logger.info("Create a new SQLTransaction(id={})", tran.id());
            SQLTransaction.putTransaction(tran);
        } else {
            logger.info("Reusing the existing SQLTransaction(id={})", tran.id());
            tran.incrementAndGetRef(isolationLevel, forUpdateOnly);
        }

        return tran;
    }

    /**
     * Gets the DB sequence.
     *
     * @param tableName
     * @param seqName
     * @return
     */
    public DBSequence getDBSequence(final String tableName, final String seqName) {
        return new DBSequence(this, tableName, seqName, 0, 1000);
    }

    /**
     * Supports global sequence by db table.
     *
     * @param tableName
     * @param seqName
     * @param startVal
     * @param seqBufferSize the numbers to allocate/reserve from database table when cached numbers are used up.
     * @return
     */
    public DBSequence getDBSequence(final String tableName, final String seqName, final long startVal, final int seqBufferSize) {
        return new DBSequence(this, tableName, seqName, startVal, seqBufferSize);
    }

    /**
     * Supports global lock by db table.
     *
     * @param tableName
     * @return
     */
    public DBLock getDBLock(final String tableName) {
        return new DBLock(this, tableName);
    }

    /**
     * Does table exist.
     *
     * @param tableName
     * @return true, if successful
     */
    public boolean doesTableExist(final String tableName) {
        Connection conn = getConnection();

        try {
            return JdbcUtil.doesTableExist(conn, tableName);
        } finally {
            closeConnection(conn);
        }
    }

    /**
     * Returns {@code true} if succeed to create table, otherwise {@code false} is returned.
     *
     * @param tableName
     * @param schema
     * @return true, if successful
     */
    public boolean createTableIfNotExists(final String tableName, final String schema) {
        Connection conn = getConnection();

        try {
            return JdbcUtil.createTableIfNotExists(conn, tableName, schema);
        } finally {
            closeConnection(conn);
        }
    }

    /**
     * Returns {@code true} if succeed to drop table, otherwise {@code false} is returned.
     *
     * @param tableName
     * @return true, if successful
     */
    public boolean dropTableIfExists(final String tableName) {
        Connection conn = getConnection();

        try {
            return JdbcUtil.dropTableIfExists(conn, tableName);
        } finally {
            closeConnection(conn);
        }
    }

    /**
     * Gets the column name list.
     *
     * @param tableName
     * @return
     */
    public ImmutableList<String> getColumnNameList(final String tableName) {
        ImmutableList<String> columnNameList = _tableColumnNamePool.get(tableName);

        if (columnNameList == null) {
            Connection conn = getConnection();

            try {
                columnNameList = ImmutableList.of(JdbcUtil.getColumnNameList(conn, tableName));
                _tableColumnNamePool.put(tableName, columnNameList);
            } catch (SQLException e) {
                throw new UncheckedSQLException(e);
            } finally {
                closeConnection(conn);
            }
        }

        return columnNameList;
    }

    /**
     * Gets the connection.
     *
     * @return
     */
    public Connection getConnection() {
        return getConnection(_ds);
    }

    /**
     *
     * @param conn
     */
    public void closeConnection(final Connection conn) {
        close(conn, _ds);
    }

    /**
     * Gets the data source.
     *
     * @param sql
     * @param parameters
     * @param jdbcSettings
     * @return
     */
    protected DataSource getDataSource(final String sql, final Object[] parameters, final JdbcSettings jdbcSettings) {
        if (_dsm == null || _dss == null) {
            if ((jdbcSettings != null) && (jdbcSettings.getQueryWithDataSource() != null || N.notNullOrEmpty(jdbcSettings.getQueryWithDataSources()))) {
                throw new IllegalArgumentException(
                        "No data source is available with name: " + (jdbcSettings.getQueryWithDataSource() != null ? jdbcSettings.getQueryWithDataSource()
                                : N.toString(jdbcSettings.getQueryWithDataSources())));
            }

            return _ds;
        } else {
            if ((jdbcSettings == null) || (jdbcSettings.getQueryWithDataSource() == null)) {
                return _dss.select(_dsm, null, sql, parameters, null);
            } else {
                return _dss.select(_dsm, null, sql, parameters, N.asProps(QUERY_WITH_DATA_SOURCE, jdbcSettings.getQueryWithDataSource()));
            }
        }
    }

    /**
     * Gets the data source.
     *
     * @param sql
     * @param parametersList
     * @param jdbcSettings
     * @return
     */
    protected DataSource getDataSource(final String sql, final List<?> parametersList, final JdbcSettings jdbcSettings) {
        if (_dsm == null || _dss == null) {
            if ((jdbcSettings != null) && (jdbcSettings.getQueryWithDataSource() != null || N.notNullOrEmpty(jdbcSettings.getQueryWithDataSources()))) {
                throw new IllegalArgumentException(
                        "No data source is available with name: " + (jdbcSettings.getQueryWithDataSource() != null ? jdbcSettings.getQueryWithDataSource()
                                : N.toString(jdbcSettings.getQueryWithDataSources())));
            }

            return _ds;
        } else {
            if ((jdbcSettings == null) || (jdbcSettings.getQueryWithDataSource() == null)) {
                return _dss.select(_dsm, null, sql, parametersList, null);
            } else {
                return _dss.select(_dsm, null, sql, parametersList, N.asProps(QUERY_WITH_DATA_SOURCE, jdbcSettings.getQueryWithDataSource()));
            }
        }
    }

    /**
     * Gets the connection.
     *
     * @param inputConn
     * @param ds
     * @param jdbcSettings
     * @param op
     * @return
     */
    protected Connection getConnection(final Connection inputConn, final DataSource ds, final JdbcSettings jdbcSettings, final SQLOperation op) {
        if (inputConn != null) {
            return inputConn;
        }

        final SQLTransaction tran = SQLTransaction.getTransaction(ds, CreatedBy.JDBC_UTIL);

        if (tran == null || (tran.isForUpdateOnly() && op == SQLOperation.SELECT)) {
            return getConnection(ds);
        }

        return tran.connection();
    }

    /**
     * Gets the connection.
     *
     * @param ds
     * @return
     */
    protected Connection getConnection(final DataSource ds) {
        return JdbcUtil.getConnection(ds);
    }

    /**
     *
     * @param ds
     * @param localConn
     * @param parsedSql
     * @param statementSetter
     * @param jdbcSettings
     * @param autoGeneratedKeys
     * @param isBatch
     * @param parameters
     * @return
     * @throws SQLException the SQL exception
     */
    protected PreparedStatement prepareStatement(final DataSource ds, final Connection localConn, final ParsedSql parsedSql,
            final StatementSetter statementSetter, final JdbcSettings jdbcSettings, final boolean autoGeneratedKeys, final boolean isBatch,
            final Object... parameters) throws SQLException {
        String sql = parsedSql.getParameterizedSql();

        if (ds instanceof com.landawn.abacus.DataSource) {
            final com.landawn.abacus.DataSource ds2 = (com.landawn.abacus.DataSource) ds;

            if (isBatch) {
                sql = ds2.getSliceSelector().select(null, sql, (List<?>) parameters[0], null);
            } else {
                sql = ds2.getSliceSelector().select(null, sql, parameters, null);
            }
        }

        logSQL(parsedSql, jdbcSettings, parameters);

        final PreparedStatement stmt = prepareStatement(localConn, sql, autoGeneratedKeys, jdbcSettings);

        setParameters(parsedSql, stmt, statementSetter, isBatch, parameters);

        return stmt;
    }

    /**
     *
     * @param conn
     * @param sql
     * @param autoGeneratedKeys
     * @param jdbcSettings
     * @return
     * @throws SQLException the SQL exception
     */
    protected PreparedStatement prepareStatement(final Connection conn, String sql, final boolean autoGeneratedKeys, final JdbcSettings jdbcSettings)
            throws SQLException {
        PreparedStatement stmt = null;

        if (jdbcSettings == null) {
            stmt = conn.prepareStatement(sql, autoGeneratedKeys ? Statement.RETURN_GENERATED_KEYS : Statement.NO_GENERATED_KEYS);
        } else {
            if (N.notNullOrEmpty(jdbcSettings.getReturnedColumnIndexes())) {
                //    if (jdbcSettings.getReturnedColumnIndexes().length != 1) {
                //        throw new IllegalArgumentException("only 1 generated key is supported At present");
                //    }

                stmt = conn.prepareStatement(sql, jdbcSettings.getReturnedColumnIndexes());
            } else if (N.notNullOrEmpty(jdbcSettings.getReturnedColumnNames())) {
                //    if (jdbcSettings.getReturnedColumnNames().length != 1) {
                //        throw new IllegalArgumentException("only 1 generated key is supported At present");
                //    }

                stmt = conn.prepareStatement(sql, jdbcSettings.getReturnedColumnNames());
            } else if (jdbcSettings.isAutoGeneratedKeys() || autoGeneratedKeys) {
                stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            } else if ((jdbcSettings.getResultSetType() != -1) || (jdbcSettings.getResultSetConcurrency() != -1)
                    || (jdbcSettings.getResultSetHoldability() != -1)) {
                int resultSetType = (jdbcSettings.getResultSetType() == -1) ? JdbcSettings.DEFAULT_RESULT_SET_TYPE : jdbcSettings.getResultSetType();

                int resultSetConcurrency = (jdbcSettings.getResultSetConcurrency() == -1) ? JdbcSettings.DEFAULT_RESULT_SET_CONCURRENCY
                        : jdbcSettings.getResultSetConcurrency();

                if (jdbcSettings.getResultSetHoldability() != -1) {
                    stmt = conn.prepareStatement(sql, resultSetType, resultSetConcurrency, jdbcSettings.getResultSetHoldability());
                } else {
                    stmt = conn.prepareStatement(sql, resultSetType, resultSetConcurrency);
                }
            } else {
                stmt = conn.prepareStatement(sql);
            }

            if (jdbcSettings.getFetchSize() != -1) {
                stmt.setFetchSize(jdbcSettings.getFetchSize());
            }

            if (jdbcSettings.getMaxRows() != -1) {
                stmt.setMaxRows(jdbcSettings.getMaxRows());
            }

            if (jdbcSettings.getMaxFieldSize() != -1) {
                stmt.setMaxFieldSize(jdbcSettings.getMaxFieldSize());
            }

            if (jdbcSettings.getFetchDirection() != -1) {
                stmt.setFetchDirection(jdbcSettings.getFetchDirection());
            }

            if (jdbcSettings.getQueryTimeout() != -1) {
                stmt.setQueryTimeout(jdbcSettings.getQueryTimeout());
            }
        }

        return stmt;
    }

    //    protected CallableStatement prepareCallableStatement(final DataSource ds, final Connection localConn, final ParsedSql parsedSql,
    //            final StatementSetter statementSetter, final JdbcSettings jdbcSettings, final boolean autoGeneratedKeys, final boolean isBatch,
    //            final Object... parameters) throws SQLException {
    //        String sql = parsedSql.getPureSQL();
    //
    //        if (isBatch) {
    //            sql = ds.getSliceSelector().select(null, sql, (List<?>) parameters[0], null);
    //        } else {
    //            sql = ds.getSliceSelector().select(null, sql, parameters, null);
    //        }
    //
    //        logSQL(sql, jdbcSettings, parameters);
    //
    //        final CallableStatement stmt = prepareCallableStatement(localConn, sql, jdbcSettings);
    //
    //        setParameters(parsedSql, stmt, statementSetter, isBatch, parameters);
    //
    //        return stmt;
    //    }
    //
    //    protected CallableStatement prepareCallableStatement(final Connection conn, String sql, final JdbcSettings jdbcSettings) throws SQLException {
    //        CallableStatement stmt = null;
    //
    //        if (jdbcSettings == null) {
    //            stmt = conn.prepareCall(sql);
    //        } else {
    //            if ((jdbcSettings.getResultSetType() != -1) || (jdbcSettings.getResultSetConcurrency() != -1) || (jdbcSettings.getResultSetHoldability() != -1)) {
    //                int resultSetType = (jdbcSettings.getResultSetType() == -1) ? JdbcSettings.DEFAULT_RESULT_SET_TYPE : jdbcSettings.getResultSetType();
    //
    //                int resultSetConcurrency = (jdbcSettings.getResultSetConcurrency() == -1) ? JdbcSettings.DEFAULT_RESULT_SET_CONCURRENCY
    //                        : jdbcSettings.getResultSetConcurrency();
    //
    //                if (jdbcSettings.getResultSetHoldability() != -1) {
    //                    stmt = conn.prepareCall(sql, resultSetType, resultSetConcurrency, jdbcSettings.getResultSetHoldability());
    //                } else {
    //                    stmt = conn.prepareCall(sql, resultSetType, resultSetConcurrency);
    //                }
    //            } else {
    //                stmt = conn.prepareCall(sql);
    //            }
    //
    //            if (jdbcSettings.getFetchSize() != -1) {
    //                stmt.setFetchSize(jdbcSettings.getFetchSize());
    //            }
    //
    //            if (jdbcSettings.getMaxRows() != -1) {
    //                stmt.setMaxRows(jdbcSettings.getMaxRows());
    //            }
    //
    //            if (jdbcSettings.getMaxFieldSize() != -1) {
    //                stmt.setMaxFieldSize(jdbcSettings.getMaxFieldSize());
    //            }
    //
    //            if (jdbcSettings.getFetchDirection() != -1) {
    //                stmt.setFetchDirection(jdbcSettings.getFetchDirection());
    //            }
    //
    //            if (jdbcSettings.getQueryTimeout() != -1) {
    //                stmt.setQueryTimeout(jdbcSettings.getQueryTimeout());
    //            }
    //        }
    //        return stmt;
    //    }

    /**
     * Sets the parameters.
     *
     * @param parsedSql
     * @param stmt
     * @param statementSetter
     * @param isBatch
     * @param parameters
     * @throws SQLException the SQL exception
     */
    protected void setParameters(final ParsedSql parsedSql, final PreparedStatement stmt, final StatementSetter statementSetter, final boolean isBatch,
            final Object... parameters) throws SQLException {
        if (isBatch || (N.isNullOrEmpty(parameters) && statementSetter == StatementSetter.DEFAULT)) {
            // ignore
        } else {
            statementSetter.accept(parsedSql, stmt, parameters);
        }
    }

    protected void logSQL(ParsedSql parsedSql, final JdbcSettings jdbcSettings, final Object... parameters) {
        if (logger.isInfoEnabled()) {
            if ((jdbcSettings != null) && (jdbcSettings.isLogSQL() || jdbcSettings.isLogSQLWithParameters())) {
                if (jdbcSettings.isLogSQLWithParameters()) {
                    logger.info("[SQL]: " + parsedSql.sql() + " {" + StringUtil.join(parameters, ", ") + "}");
                } else {
                    logger.info("[SQL]: " + parsedSql.sql());
                }
            } else if (JdbcUtil.isSQLLogEnabled_TL.get()) {
                logger.info("[SQL]: " + parsedSql.sql());
            }
        }
    }

    /**
     *
     * @param rs
     */
    protected void close(final ResultSet rs) {
        JdbcUtil.closeQuietly(rs);
    }

    /**
     *
     * @param stmt
     */
    protected void close(final PreparedStatement stmt) {
        JdbcUtil.closeQuietly(stmt);
    }

    /**
     *
     * @param rs
     * @param stmt
     */
    protected void close(final ResultSet rs, final PreparedStatement stmt) {
        JdbcUtil.closeQuietly(rs, stmt);
    }

    /**
     *
     * @param localConn
     * @param inputConn
     * @param ds
     */
    protected void close(final Connection localConn, final Connection inputConn, final DataSource ds) {
        if (inputConn == null) {
            final SQLTransaction tran = SQLTransaction.getTransaction(ds, CreatedBy.JDBC_UTIL);

            if (tran != null && tran.connection() == localConn) {
                // ignore.
            } else {
                close(localConn, ds);
            }
        }
    }

    /**
     *
     * @param conn
     * @param ds
     */
    protected void close(final Connection conn, final DataSource ds) {
        JdbcUtil.releaseConnection(conn, ds);
    }

    /**
     * Close the underline data source.
     */
    public void close() {
        try {
            if (_ds != null && _ds instanceof com.landawn.abacus.DataSource) {
                final com.landawn.abacus.DataSource ds = (com.landawn.abacus.DataSource) _ds;

                if (ds.isClosed() == false) {
                    ds.close();
                }
            }
        } finally {
            if (_dsm != null && _dsm.isClosed() == false) {
                _dsm.close();
            }
        }
    }

    /**
     * Gets the batch size.
     *
     * @param jdbcSettings
     * @return
     */
    protected int getBatchSize(final JdbcSettings jdbcSettings) {
        return ((jdbcSettings == null) || (jdbcSettings.getBatchSize() < 0)) ? JdbcSettings.DEFAULT_BATCH_SIZE : jdbcSettings.getBatchSize();
    }

    /**
     * Check statement setter.
     *
     * @param parsedSql
     * @param statementSetter
     * @return
     */
    protected StatementSetter checkStatementSetter(final ParsedSql parsedSql, StatementSetter statementSetter) {
        if (statementSetter == null) {
            statementSetter = StatementSetter.DEFAULT;
        }

        return statementSetter;
    }

    /**
     * Check result set extractor.
     *
     * @param <T>
     * @param parsedSql
     * @param resultExtractor
     * @return
     */
    @SuppressWarnings("unchecked")
    protected <T> ResultSetExtractor<T> checkResultSetExtractor(final ParsedSql parsedSql, ResultSetExtractor<T> resultExtractor) {
        if (resultExtractor == null) {
            resultExtractor = (ResultSetExtractor<T>) ResultExtractor.TO_DATA_SET;
        }

        return resultExtractor;
    }

    /**
     * Check jdbc settings.
     *
     * @param jdbcSettings
     * @param parsedSql
     * @param attrs
     * @return
     */
    protected JdbcSettings checkJdbcSettings(final JdbcSettings jdbcSettings, final ParsedSql parsedSql, final Map<String, String> attrs) {
        JdbcSettings newJdbcSettings = null;

        if (jdbcSettings == null) {
            newJdbcSettings = setJdbcSettingsForParsedSql(_jdbcSettings, parsedSql, attrs);
        } else {
            newJdbcSettings = setJdbcSettingsForParsedSql(jdbcSettings, parsedSql, attrs);
        }

        if ((newJdbcSettings.getOffset() < 0) || (newJdbcSettings.getCount() < 0)) {
            throw new IllegalArgumentException("offset or count can't be less than 0: " + newJdbcSettings.getOffset() + ", " + newJdbcSettings.getCount());
        }

        return newJdbcSettings;
    }

    /**
     * Sets the jdbc settings for named SQL.
     *
     * @param jdbcSettings
     * @param parsedSql
     * @param attrs
     * @return
     */
    protected JdbcSettings setJdbcSettingsForParsedSql(JdbcSettings jdbcSettings, final ParsedSql parsedSql, final Map<String, String> attrs) {
        if ((parsedSql == null) || N.isNullOrEmpty(attrs)) {
            return jdbcSettings;
        } else {
            jdbcSettings = jdbcSettings.copy();

            String attr = attrs.get(SQLMapper.BATCH_SIZE);
            if (attr != null) {
                jdbcSettings.setBatchSize(N.parseInt(attr));
            }

            attr = attrs.get(SQLMapper.FETCH_SIZE);
            if (attr != null) {
                jdbcSettings.setFetchSize(N.parseInt(attr));
            }

            attr = attrs.get(SQLMapper.RESULT_SET_TYPE);
            if (attr != null) {
                Integer resultSetType = SQLMapper.RESULT_SET_TYPE_MAP.get(attr);

                if (resultSetType == null) {
                    throw new IllegalArgumentException("Result set type: '" + attr + "' is not supported");
                }

                jdbcSettings.setResultSetType(resultSetType);
            }

            attr = attrs.get(SQLMapper.TIMEOUT);
            if (attr != null) {
                jdbcSettings.setQueryTimeout(N.parseInt(attr));
            }

            return jdbcSettings;
        }
    }

    /**
     * Gets the named SQL.
     *
     * @param sql
     * @return
     */
    protected ParsedSql getParsedSql(final String sql) {
        N.checkArgNotNull(sql, "sql");

        ParsedSql parsedSql = null;

        if (_sqlMapper != null) {
            parsedSql = _sqlMapper.get(sql);
        }

        if (parsedSql == null) {
            parsedSql = ParsedSql.parse(sql);
        }

        return parsedSql;
    }

    /**
     * Gets the column label list.
     *
     * @param sql should be prepared sql because it will be cached.
     * @param rs
     * @return
     * @throws SQLException the SQL exception
     */
    protected static ImmutableList<String> getColumnLabelList(final String sql, final ResultSet rs) throws SQLException {
        ImmutableList<String> labelList = N.notNullOrEmpty(sql) ? _sqlColumnLabelPool.get(sql) : null;

        if (labelList == null) {
            labelList = ImmutableList.of(JdbcUtil.getColumnLabelList(rs));

            if (N.notNullOrEmpty(sql) && sql.length() <= CACHED_SQL_LENGTH) {
                if (_sqlColumnLabelPool.size() >= SQL_CACHE_SIZE) {
                    final List<String> tmp = new ArrayList<>(_sqlColumnLabelPool.keySet());
                    Maps.removeKeys(_sqlColumnLabelPool, tmp.subList(0, (int) (tmp.size() * 0.25)));
                }

                _sqlColumnLabelPool.put(sql, labelList);
            }
        }

        return labelList;
    }

    /**
     * Checks if is entity or map parameter.
     *
     * @param parsedSql
     * @param parameters
     * @return true, if is entity or map parameter
     */
    protected static boolean isEntityOrMapParameter(final ParsedSql parsedSql, final Object... parameters) {
        if (N.isNullOrEmpty(parsedSql.getNamedParameters())) {
            return false;
        }

        if (N.isNullOrEmpty(parameters) || (parameters.length != 1) || (parameters[0] == null)) {
            return false;
        }

        if (ClassUtil.isEntity(parameters[0].getClass()) || parameters[0] instanceof Map || parameters[0] instanceof EntityId) {
            return true;
        }

        return false;
    }

    /**
     *
     *
     * @param <T>
     * @param <ID>
     * @see com.landawn.abacus.annotation.ReadOnly
     * @see com.landawn.abacus.annotation.ReadOnlyId
     * @see com.landawn.abacus.annotation.NonUpdatable
     * @see com.landawn.abacus.annotation.Transient
     * @see com.landawn.abacus.annotation.Table
     * @see com.landawn.abacus.annotation.Column
     * @see com.landawn.abacus.annotation.AccessFieldByMethod
     * @see com.landawn.abacus.annotation.JoinedBy
     * @see com.landawn.abacus.condition.ConditionFactory
     * @see com.landawn.abacus.condition.ConditionFactory.CF
     * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/Connection.html">http://docs.oracle.com/javase/8/docs/api/java/sql/Connection.html</a>
     * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/Statement.html">http://docs.oracle.com/javase/8/docs/api/java/sql/Statement.html</a>
     * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/PreparedStatement.html">http://docs.oracle.com/javase/8/docs/api/java/sql/PreparedStatement.html</a>
     * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/ResultSet.html">http://docs.oracle.com/javase/8/docs/api/java/sql/ResultSet.html</a>
     */
    public static class Mapper<T, ID> {

        /** The Constant EXISTS_SELECT_PROP_NAMES. */
        static final ImmutableList<String> EXISTS_SELECT_PROP_NAMES = ImmutableList.of(NSC._1);

        /** The Constant COUNT_SELECT_PROP_NAMES. */
        static final ImmutableList<String> COUNT_SELECT_PROP_NAMES = ImmutableList.of(NSC.COUNT_ALL);

        final SQLExecutor sqlExecutor;
        final NamingPolicy namingPolicy;
        final Class<? extends SQLBuilder> sbc;

        final Class<T> targetEntityClass;
        final boolean isDirtyMarker;
        final EntityInfo entityInfo;
        final Class<ID> idClass;

        private final boolean isEntityId;
        private final boolean isNoId;

        /** The default select prop name list. */
        private final ImmutableList<String> defaultSelectPropNameList;

        /** The default update prop name list. */
        private final ImmutableList<String> defaultUpdatePropNameList;

        /** The id prop name. */
        private final String oneIdPropName;

        /** The id prop name list. */
        private final ImmutableList<String> idPropNameList;

        /** The id prop name set. */
        private final ImmutableSet<String> idPropNameSet;

        /** The id cond. */
        private final Condition idCond;

        /** The sql exists by id. */
        private final String sql_exists_by_id;

        /** The sql get by id. */
        private final String sql_get_by_id;

        /** The sql insert with id. */
        private final String sql_insert_with_id;

        /** The sql insert without id. */
        private final String sql_insert_without_id;

        /** The sql update by id. */
        private final String sql_update_by_id;

        /** The sql delete by id. */
        private final String sql_delete_by_id;

        private final BiRowMapper<ID> keyExtractor;
        private final Function<Object, ID> idGetter;
        private final Function<Map<String, Object>, ID> idGetter2;
        private final Predicate<ID> isDefaultIdTester;
        private final String[] returnColumnNames;
        private final JdbcSettings jdbcSettingsForInsert;

        // TODO cache more sqls to improve performance.

        /**
         * Instantiates a new mapper.
         *
         * @param entityClass the target class
         * @param idClass the target id class.
         * @param sqlExecutor
         * @param namingPolicy
         */
        @SuppressWarnings("deprecation")
        Mapper(final Class<T> entityClass, final Class<ID> idClass, final SQLExecutor sqlExecutor, final NamingPolicy namingPolicy) {
            this.sqlExecutor = sqlExecutor;
            this.namingPolicy = namingPolicy;

            final List<String> idPropNames = ClassUtil.getIdFieldNames(entityClass, true);
            final boolean isFakeId = ClassUtil.isFakeId(idPropNames);

            // Not a good idea to define Mapper<SomeEntity, Void>.
            if (isFakeId) {
                N.checkArgNotNullOrEmpty(idPropNames, "Target class: " + ClassUtil.getCanonicalClassName(entityClass)
                        + " must have at least one id property annotated by @Id or @ReadOnlyId on field or class");
            }

            //    N.checkArgument(idPropNames.size() == 1, "Only one id is supported at present. But Entity class {} has {} ids: {}", targetClass, idPropNames.size(),
            //            idPropNames);

            final Class<?> idReturnType = isFakeId == false && idPropNames.size() == 1
                    ? ClassUtil.getPropGetMethod(entityClass, idPropNames.get(0)).getReturnType()
                    : Object.class;

            if (isFakeId) {
                if (!idClass.equals(Void.class)) {
                    throw new IllegalArgumentException("'ID' type only can be Void for entity with no id property");
                }
            } else if (idPropNames.size() == 1) {
                if (!(Primitives.wrap(idClass).isAssignableFrom(Primitives.wrap(idReturnType)))) {
                    throw new IllegalArgumentException("The 'ID' type declared in Dao type parameters: " + idClass
                            + " is not assignable from the id property type in the entity class: " + idReturnType);
                }
            } else if (idPropNames.size() > 1) {
                if (!idClass.equals(EntityId.class)) {
                    throw new IllegalArgumentException("'ID' type only can be EntityId for entity with two or more id properties");
                }
            }

            this.targetEntityClass = entityClass;
            this.isDirtyMarker = ClassUtil.isDirtyMarker(targetEntityClass);
            this.entityInfo = ParserUtil.getEntityInfo(targetEntityClass);
            this.idClass = Primitives.wrap(idClass).isAssignableFrom(Primitives.wrap(idReturnType)) ? (Class<ID>) idReturnType : idClass;
            this.isEntityId = EntityId.class.isAssignableFrom(idClass);
            this.isNoId = isFakeId;

            this.oneIdPropName = idPropNames.get(0);
            this.idPropNameList = ImmutableList.copyOf(idPropNames);
            this.idPropNameSet = ImmutableSet.copyOf(idPropNames);
            this.defaultSelectPropNameList = ImmutableList.copyOf(SQLBuilder.getSelectPropNamesByClass(entityClass, false, null));
            this.defaultUpdatePropNameList = ImmutableList.copyOf(SQLBuilder.getUpdatePropNamesByClass(entityClass, idPropNameSet));
            this.idCond = idPropNames.size() == 1 ? CF.eq(oneIdPropName) : CF.and(StreamEx.of(idPropNames).map(CF::eq).toList());

            this.sql_exists_by_id = this.prepareQuery(SQLBuilder._1_list, idCond).sql;
            this.sql_get_by_id = this.prepareQuery(defaultSelectPropNameList, idCond).sql;
            this.sql_insert_with_id = this.prepareInsertSql(SQLBuilder.getInsertPropNamesByClass(entityClass, null));
            this.sql_insert_without_id = this.prepareInsertSql(SQLBuilder.getInsertPropNamesByClass(entityClass, idPropNameSet));
            this.sql_update_by_id = this.prepareUpdateSql(defaultUpdatePropNameList);
            this.sql_delete_by_id = this.prepareDelete(idCond).sql;

            this.sbc = namingPolicy.equals(NamingPolicy.LOWER_CASE_WITH_UNDERSCORE) ? PSC.class
                    : (namingPolicy.equals(NamingPolicy.UPPER_CASE_WITH_UNDERSCORE) ? PAC.class : PLC.class);

            final boolean isOneId = isNoId == false && idPropNameList.size() == 1;

            final ImmutableMap<String, String> propColumnNameMap = SQLBuilder.getPropColumnNameMap(entityClass, namingPolicy);

            returnColumnNames = isNoId ? N.EMPTY_STRING_ARRAY
                    : (isOneId ? Array.of(propColumnNameMap.get(oneIdPropName))
                            : Stream.of(idPropNameList).map(idName -> propColumnNameMap.get(idName)).toArray(IntFunctions.ofStringArray()));

            final Tuple3<BiRowMapper<ID>, Function<Object, ID>, BiConsumer<ID, Object>> tp3 = JdbcUtil.getIdGeneratorGetterSetter(entityClass, namingPolicy);

            this.keyExtractor = tp3._1;

            this.idGetter = tp3._2;

            this.idGetter2 = isNoId ? props -> null : (isOneId ? props -> N.convert(props.get(oneIdPropName), this.idClass) : props -> {
                final Seid entityId = Seid.of(ClassUtil.getSimpleClassName(entityClass));

                for (String idName : idPropNameList) {
                    entityId.set(idName, props.get(idName));
                }

                return (ID) entityId;
            });

            this.isDefaultIdTester = isNoId ? id -> true
                    : (isOneId ? id -> JdbcUtil.isDefaultIdPropValue(id)
                            : id -> Stream.of(((EntityId) id).entrySet()).allMatch(e -> JdbcUtil.isDefaultIdPropValue(e.getValue())));

            jdbcSettingsForInsert = JdbcSettings.create().setReturnedColumnNames(returnColumnNames);
        }

        /**
         *
         * @param id which could be {@code Number}/{@code String}... or {@code Entity}/{@code Map} for composed id.
         * @return true, if successful
         */
        public boolean exists(final ID id) {
            checkIdRequired();

            return sqlExecutor.queryForInt(sql_exists_by_id, id).orElse(0) > 0;
        }

        /**
         *
         * @param whereCause
         * @return true, if successful
         */
        public boolean exists(final Condition whereCause) {
            return exists(null, whereCause);
        }

        /**
         *
         * @param conn
         * @param id
         * @return true, if successful
         */
        public boolean exists(final Connection conn, final ID id) {
            checkIdRequired();

            return sqlExecutor.queryForSingleResult(int.class, conn, sql_exists_by_id, id).orElse(0) > 0;
        }

        /**
         *
         * @param conn
         * @param whereCause
         * @return true, if successful
         */
        public boolean exists(final Connection conn, final Condition whereCause) {
            final SP sp = prepareQuery(EXISTS_SELECT_PROP_NAMES, whereCause, 1);

            return sqlExecutor.exists(conn, sp.sql, JdbcUtil.getParameterArray(sp));
        }

        /**
         *
         * @param whereCause
         * @return
         */
        public int count(final Condition whereCause) {
            return count(null, whereCause);
        }

        /**
         *
         * @param conn
         * @param whereCause
         * @return
         */
        public int count(final Connection conn, final Condition whereCause) {
            final SP sp = prepareQuery(COUNT_SELECT_PROP_NAMES, whereCause);

            return sqlExecutor.queryForSingleResult(int.class, conn, sp.sql, JdbcUtil.getParameterArray(sp)).orElse(0);
        }

        /**
         *
         * @param id which could be {@code Number}/{@code String}... or {@code Entity}/{@code Map} for composed id.
         * @return
         * @throws DuplicatedResultException if two or more records are found.
         */
        public Optional<T> get(final ID id) throws DuplicatedResultException {
            return Optional.ofNullable(gett(id));
        }

        //    /**
        //     *
        //     * @param id which could be {@code Number}/{@code String}... or {@code Entity}/{@code Map} for composed id.
        //     * @param selectPropNames
        //     * @return
        //     * @deprecated replaced by {@code get(id, Arrays.asList(selectPropNames)}
        //     */
        //    @Deprecated
        //    @SafeVarargs
        //    public final Optional<T> get(final ID id, final String... selectPropNames) {
        //        return Optional.ofNullable(gett(id, selectPropNames));
        //    }

        /**
         *
         * @param id which could be {@code Number}/{@code String}... or {@code Entity}/{@code Map} for composed id.
         * @param selectPropNames
         * @return
         * @throws DuplicatedResultException if two or more records are found.
         */
        public Optional<T> get(final ID id, final Collection<String> selectPropNames) throws DuplicatedResultException {
            return Optional.ofNullable(gett(id, selectPropNames));
        }

        /**
         *
         * @param conn
         * @param id which could be {@code Number}/{@code String}... or {@code Entity}/{@code Map} for composed id.
         * @param selectPropNames
         * @return
         * @throws DuplicatedResultException if two or more records are found.
         */
        public Optional<T> get(final Connection conn, final ID id, final Collection<String> selectPropNames) throws DuplicatedResultException {
            return Optional.ofNullable(gett(conn, id, selectPropNames));
        }

        /**
         * Gets the t.
         *
         * @param id which could be {@code Number}/{@code String}... or {@code Entity}/{@code Map} for composed id.
         * @return
         * @throws DuplicatedResultException if two or more records are found.
         */
        public T gett(final ID id) throws DuplicatedResultException {
            return gett(id, (Collection<String>) null);
        }

        //    /**
        //     *
        //     * @param id which could be {@code Number}/{@code String}... or {@code Entity}/{@code Map} for composed id.
        //     * @param selectPropNames
        //     * @return
        //     * @deprecated replaced by {@code gett(id, Arrays.asList(selectPropNames)}
        //     */
        //    @Deprecated
        //    @SafeVarargs
        //    public final T gett(final ID id, final String... selectPropNames) {
        //        return gett(id, Arrays.asList(selectPropNames));
        //    }

        /**
         * Gets the t.
         *
         * @param id which could be {@code Number}/{@code String}... or {@code Entity}/{@code Map} for composed id.
         * @param selectPropNames
         * @return
         * @throws DuplicatedResultException if two or more records are found.
         */
        public T gett(final ID id, final Collection<String> selectPropNames) throws DuplicatedResultException {
            return gett(null, id, selectPropNames);
        }

        /**
         * Gets the t.
         *
         * @param conn
         * @param id which could be {@code Number}/{@code String}... or {@code Entity}/{@code Map} for composed id.
         * @param selectPropNames
         * @return
         * @throws DuplicatedResultException if two or more records are found.
         */
        public T gett(final Connection conn, final ID id, final Collection<String> selectPropNames) throws DuplicatedResultException {
            checkIdRequired();

            if (N.isNullOrEmpty(selectPropNames)) {
                return sqlExecutor.gett(targetEntityClass, conn, sql_get_by_id, id);
            } else {
                final SP sp = prepareQuery(selectPropNames, idCond);
                return sqlExecutor.gett(targetEntityClass, conn, sp.sql, id);
            }
        }

        /**
         *
         *
         * @param ids
         * @return
         * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}.
         */
        public List<T> batchGet(final Collection<? extends ID> ids) throws DuplicatedResultException {
            return batchGet(ids, (Collection<String>) null);
        }

        /**
         *
         *
         * @param ids
         * @param selectPropNames
         * @return
         * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}.
         */
        public List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames) throws DuplicatedResultException {
            return batchGet(ids, selectPropNames, JdbcSettings.DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param ids
         * @param selectPropNames
         * @param batchSize
         * @return
         * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}.
         */
        public List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final int batchSize)
                throws DuplicatedResultException {
            return batchGet(null, ids, selectPropNames, batchSize);
        }

        /**
         *
         *
         * @param conn
         * @param ids
         * @param selectPropNames
         * @param batchSize
         * @return
         * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}.
         */
        public List<T> batchGet(final Connection conn, final Collection<? extends ID> ids, final Collection<String> selectPropNames, final int batchSize)
                throws DuplicatedResultException {
            checkIdRequired();

            N.checkArgPositive(batchSize, "batchSize");

            if (N.isNullOrEmpty(ids)) {
                return new ArrayList<>();
            }

            final ID firstId = N.first(ids).get();
            final boolean isMap = firstId instanceof Map;
            final boolean isEntity = firstId != null && ClassUtil.isEntity(firstId.getClass());
            final boolean isEntityId = firstId instanceof EntityId;

            N.checkArgument(idPropNameList.size() > 1 || !(isEntity || isMap || isEntityId),
                    "Input 'ids' can not be EntityIds/Maps or entities for single id ");

            final List<ID> idList = ids instanceof List ? (List<ID>) ids : new ArrayList<>(ids);
            final List<T> resultList = new ArrayList<>(idList.size());

            if (idPropNameList.size() == 1) {
                String sql = prepareQuery(selectPropNames, idCond).sql;
                sql = sql.substring(0, sql.lastIndexOf('=')) + "IN ";

                if (ids.size() >= batchSize) {
                    final Joiner joiner = Joiner.with(", ", "(", ")").reuseCachedBuffer(true);

                    for (int i = 0; i < batchSize; i++) {
                        joiner.append('?');
                    }

                    String inSQL = sql + joiner.toString();

                    for (int i = 0, to = ids.size() - batchSize; i <= to; i += batchSize) {
                        resultList.addAll(sqlExecutor.list(targetEntityClass, conn, inSQL, null, null, idList.subList(i, i + batchSize).toArray()));
                    }
                }

                if (ids.size() % batchSize != 0) {
                    final int remaining = ids.size() % batchSize;
                    final Joiner joiner = Joiner.with(", ", "(", ")").reuseCachedBuffer(true);

                    for (int i = 0; i < remaining; i++) {
                        joiner.append('?');
                    }

                    String inSQL = sql + joiner.toString();
                    resultList
                            .addAll(sqlExecutor.list(targetEntityClass, conn, inSQL, null, null, idList.subList(ids.size() - remaining, ids.size()).toArray()));
                }
            } else {
                if (ids.size() >= batchSize) {
                    for (int i = 0, to = ids.size() - batchSize; i <= to; i += batchSize) {
                        if (isMap) {
                            resultList.addAll(list(CF.eqAndOr((List<Map<String, ?>>) idList.subList(i, i + batchSize))));
                        } else if (isEntityId) {
                            resultList.addAll(list(CF.id2Cond((List<EntityId>) idList.subList(i, i + batchSize))));
                        } else {
                            resultList.addAll(list(CF.eqAndOr(idList.subList(i, i + batchSize), idPropNameList)));
                        }
                    }
                }

                if (ids.size() % batchSize != 0) {
                    final int remaining = ids.size() % batchSize;

                    if (isMap) {
                        resultList.addAll(list(CF.eqAndOr((List<Map<String, ?>>) idList.subList(ids.size() - remaining, ids.size()))));
                    } else if (isEntityId) {
                        resultList.addAll(list(CF.id2Cond((List<EntityId>) idList.subList(idList.size() - remaining, ids.size()))));
                    } else {
                        resultList.addAll(list(CF.eqAndOr(idList.subList(ids.size() - remaining, ids.size()), idPropNameList)));
                    }
                }
            }

            if (resultList.size() > ids.size()) {
                throw new DuplicatedResultException("The size of result: " + resultList.size() + " is bigger than the size of input ids: " + ids.size());
            }

            return resultList;
        }

        /**
         *
         * @param whereCause
         * @return
         */
        public Optional<T> findFirst(final Condition whereCause) {
            return findFirst((Collection<String>) null, whereCause);
        }

        /**
         *
         * @param selectPropNames
         * @param whereCause
         * @return
         */
        public Optional<T> findFirst(final Collection<String> selectPropNames, final Condition whereCause) {
            return findFirst(selectPropNames, whereCause, null);
        }

        /**
         *
         * @param selectPropNames
         * @param whereCause
         * @param jdbcSettings
         * @return
         */
        public Optional<T> findFirst(final Collection<String> selectPropNames, final Condition whereCause, final JdbcSettings jdbcSettings) {
            return findFirst(null, selectPropNames, whereCause, jdbcSettings);
        }

        /**
         *
         * @param conn
         * @param whereCause
         * @return
         */
        public Optional<T> findFirst(final Connection conn, final Condition whereCause) {
            return findFirst(conn, null, whereCause);
        }

        /**
         *
         * @param conn
         * @param selectPropNames
         * @param whereCause
         * @return
         */
        public Optional<T> findFirst(final Connection conn, final Collection<String> selectPropNames, final Condition whereCause) {
            return findFirst(conn, selectPropNames, whereCause, null);
        }

        /**
         *
         * @param conn
         * @param selectPropNames
         * @param whereCause
         * @param jdbcSettings
         * @return
         */
        public Optional<T> findFirst(final Connection conn, final Collection<String> selectPropNames, final Condition whereCause,
                final JdbcSettings jdbcSettings) {
            final SP sp = prepareQuery(selectPropNames, whereCause);

            return sqlExecutor.findFirst(targetEntityClass, conn, sp.sql, StatementSetter.DEFAULT, jdbcSettings, JdbcUtil.getParameterArray(sp));
        }

        /**
         *
         * @param <R>
         * @param selectPropNames
         * @param rowMapper
         * @param whereCause
         * @return
         */
        public <R> Optional<R> findFirst(final Collection<String> selectPropNames, final JdbcUtil.RowMapper<R> rowMapper, final Condition whereCause) {
            return findFirst(selectPropNames, rowMapper, whereCause, null);
        }

        /**
         *
         * @param <R>
         * @param selectPropNames
         * @param rowMapper
         * @param whereCause
         * @param jdbcSettings
         * @return
         */
        public <R> Optional<R> findFirst(final Collection<String> selectPropNames, final JdbcUtil.RowMapper<R> rowMapper, final Condition whereCause,
                final JdbcSettings jdbcSettings) {
            return findFirst(null, selectPropNames, rowMapper, whereCause, jdbcSettings);
        }

        /**
         *
         * @param <R>
         * @param conn
         * @param selectPropNames
         * @param rowMapper
         * @param whereCause
         * @return
         */
        public <R> Optional<R> findFirst(final Connection conn, final Collection<String> selectPropNames, final JdbcUtil.RowMapper<R> rowMapper,
                final Condition whereCause) {
            return findFirst(conn, selectPropNames, rowMapper, whereCause, null);
        }

        /**
         *
         * @param <R>
         * @param conn
         * @param selectPropNames
         * @param rowMapper
         * @param whereCause
         * @param jdbcSettings
         * @return
         */
        public <R> Optional<R> findFirst(final Connection conn, final Collection<String> selectPropNames, final JdbcUtil.RowMapper<R> rowMapper,
                final Condition whereCause, final JdbcSettings jdbcSettings) {
            final SP sp = prepareQuery(selectPropNames, whereCause);

            return sqlExecutor.findFirst(conn, sp.sql, StatementSetter.DEFAULT, rowMapper, jdbcSettings, JdbcUtil.getParameterArray(sp));
        }

        /**
         *
         * @param <R>
         * @param selectPropNames
         * @param rowMapper
         * @param whereCause
         * @return
         */
        public <R> Optional<R> findFirst(final Collection<String> selectPropNames, final JdbcUtil.BiRowMapper<R> rowMapper, final Condition whereCause) {
            return findFirst(selectPropNames, rowMapper, whereCause, null);
        }

        /**
         *
         * @param <R>
         * @param selectPropNames
         * @param rowMapper
         * @param whereCause
         * @param jdbcSettings
         * @return
         */
        public <R> Optional<R> findFirst(final Collection<String> selectPropNames, final JdbcUtil.BiRowMapper<R> rowMapper, final Condition whereCause,
                final JdbcSettings jdbcSettings) {
            return findFirst(null, selectPropNames, rowMapper, whereCause, jdbcSettings);
        }

        /**
         *
         * @param <R>
         * @param conn
         * @param selectPropNames
         * @param rowMapper
         * @param whereCause
         * @return
         */
        public <R> Optional<R> findFirst(final Connection conn, final Collection<String> selectPropNames, final JdbcUtil.BiRowMapper<R> rowMapper,
                final Condition whereCause) {
            return findFirst(conn, selectPropNames, rowMapper, whereCause, null);
        }

        /**
         *
         * @param <R>
         * @param conn
         * @param selectPropNames
         * @param rowMapper
         * @param whereCause
         * @param jdbcSettings
         * @return
         */
        public <R> Optional<R> findFirst(final Connection conn, final Collection<String> selectPropNames, final JdbcUtil.BiRowMapper<R> rowMapper,
                final Condition whereCause, final JdbcSettings jdbcSettings) {
            final SP sp = prepareQuery(selectPropNames, whereCause);

            final ResultExtractor<R> resultExtractor = new ResultExtractor<R>() {
                @Override
                public R apply(ResultSet rs, final JdbcSettings jdbcSettings) throws SQLException {
                    int offset = jdbcSettings.getOffset();

                    if (offset > 0) {
                        JdbcUtil.skip(rs, offset);
                    }

                    return rs.next() ? Objects.requireNonNull(rowMapper.apply(rs, JdbcUtil.getColumnLabelList(rs))) : null;
                }
            };

            return Optional.ofNullable(sqlExecutor.query(conn, sp.sql, StatementSetter.DEFAULT, resultExtractor, jdbcSettings, JdbcUtil.getParameterArray(sp)));
        }

        /**
         *
         * @param whereCause
         * @return
         */
        public List<T> list(final Condition whereCause) {
            return list((Collection<String>) null, whereCause);
        }

        /**
         *
         * @param selectPropNames
         * @param whereCause
         * @return
         */
        public List<T> list(final Collection<String> selectPropNames, final Condition whereCause) {
            return list(selectPropNames, whereCause, null);
        }

        /**
         *
         * @param selectPropNames
         * @param whereCause
         * @param jdbcSettings
         * @return
         */
        public List<T> list(final Collection<String> selectPropNames, final Condition whereCause, final JdbcSettings jdbcSettings) {
            return list(null, selectPropNames, whereCause, jdbcSettings);
        }

        /**
         *
         * @param conn
         * @param whereCause
         * @return
         */
        public List<T> list(final Connection conn, final Condition whereCause) {
            return list(conn, (Collection<String>) null, whereCause);
        }

        /**
         *
         * @param conn
         * @param selectPropNames
         * @param whereCause
         * @return
         */
        public List<T> list(final Connection conn, final Collection<String> selectPropNames, final Condition whereCause) {
            return list(conn, selectPropNames, whereCause, null);
        }

        /**
         *
         * @param conn
         * @param selectPropNames
         * @param whereCause
         * @param jdbcSettings
         * @return
         */
        public List<T> list(final Connection conn, final Collection<String> selectPropNames, final Condition whereCause, final JdbcSettings jdbcSettings) {
            final SP sp = prepareQuery(selectPropNames, whereCause);

            return sqlExecutor.list(targetEntityClass, conn, sp.sql, StatementSetter.DEFAULT, jdbcSettings, JdbcUtil.getParameterArray(sp));
        }

        /**
         *
         * @param <R>
         * @param singleSelectPropName
         * @param whereCause
         * @return
         */
        public <R> List<R> list(final String singleSelectPropName, final Condition whereCause) {
            return list(singleSelectPropName, whereCause, null);
        }

        /**
         *
         * @param <R>
         * @param singleSelectPropName
         * @param whereCause
         * @param jdbcSettings
         * @return
         */
        public <R> List<R> list(final String singleSelectPropName, final Condition whereCause, final JdbcSettings jdbcSettings) {
            return list(null, singleSelectPropName, whereCause, jdbcSettings);
        }

        /**
         *
         * @param <R>
         * @param conn
         * @param singleSelectPropName
         * @param whereCause
         * @return
         */
        public <R> List<R> list(final Connection conn, final String singleSelectPropName, final Condition whereCause) {
            return list(conn, singleSelectPropName, whereCause, null);
        }

        /**
         *
         * @param <R>
         * @param conn
         * @param singleSelectPropName
         * @param whereCause
         * @param jdbcSettings
         * @return
         */
        public <R> List<R> list(final Connection conn, final String singleSelectPropName, final Condition whereCause, final JdbcSettings jdbcSettings) {
            final PropInfo propInfo = entityInfo.getPropInfo(singleSelectPropName);
            final RowMapper<R> rowMapper = propInfo == null ? RowMapper.GET_OBJECT : RowMapper.get((Type<R>) propInfo.dbType);

            return list(conn, singleSelectPropName, rowMapper, whereCause, jdbcSettings);
        }

        /**
         *
         * @param <R>
         * @param singleSelectPropName
         * @param rowMapper
         * @param whereCause
         * @return
         */
        public <R> List<R> list(final String singleSelectPropName, final JdbcUtil.RowMapper<R> rowMapper, final Condition whereCause) {
            return list(singleSelectPropName, rowMapper, whereCause, null);
        }

        /**
         *
         * @param <R>
         * @param singleSelectPropName
         * @param rowMapper
         * @param whereCause
         * @param jdbcSettings
         * @return
         */
        public <R> List<R> list(final String singleSelectPropName, final JdbcUtil.RowMapper<R> rowMapper, final Condition whereCause,
                final JdbcSettings jdbcSettings) {
            return list(null, singleSelectPropName, rowMapper, whereCause, jdbcSettings);
        }

        /**
         *
         * @param <R>
         * @param conn
         * @param singleSelectPropName
         * @param rowMapper
         * @param whereCause
         * @return
         */
        public <R> List<R> list(final Connection conn, final String singleSelectPropName, final JdbcUtil.RowMapper<R> rowMapper, final Condition whereCause) {
            return list(conn, singleSelectPropName, rowMapper, whereCause, null);
        }

        /**
         *
         * @param <R>
         * @param conn
         * @param singleSelectPropName
         * @param rowMapper
         * @param whereCause
         * @param jdbcSettings
         * @return
         */
        public <R> List<R> list(final Connection conn, final String singleSelectPropName, final JdbcUtil.RowMapper<R> rowMapper, final Condition whereCause,
                final JdbcSettings jdbcSettings) {
            return list(conn, Arrays.asList(singleSelectPropName), rowMapper, whereCause, jdbcSettings);
        }

        /**
         *
         * @param <R>
         * @param selectPropNames
         * @param rowMapper
         * @param whereCause
         * @return
         */
        public <R> List<R> list(final Collection<String> selectPropNames, final JdbcUtil.RowMapper<R> rowMapper, final Condition whereCause) {
            return list(selectPropNames, rowMapper, whereCause, null);
        }

        /**
         *
         * @param <R>
         * @param selectPropNames
         * @param rowMapper
         * @param whereCause
         * @param jdbcSettings
         * @return
         */
        public <R> List<R> list(final Collection<String> selectPropNames, final JdbcUtil.RowMapper<R> rowMapper, final Condition whereCause,
                final JdbcSettings jdbcSettings) {
            return list(null, selectPropNames, rowMapper, whereCause, jdbcSettings);
        }

        /**
         *
         * @param <R>
         * @param conn
         * @param selectPropNames
         * @param rowMapper
         * @param whereCause
         * @return
         */
        public <R> List<R> list(final Connection conn, final Collection<String> selectPropNames, final JdbcUtil.RowMapper<R> rowMapper,
                final Condition whereCause) {
            return list(conn, selectPropNames, rowMapper, whereCause, null);
        }

        /**
         *
         * @param <R>
         * @param conn
         * @param selectPropNames
         * @param rowMapper
         * @param whereCause
         * @param jdbcSettings
         * @return
         */
        public <R> List<R> list(final Connection conn, final Collection<String> selectPropNames, final JdbcUtil.RowMapper<R> rowMapper,
                final Condition whereCause, final JdbcSettings jdbcSettings) {
            N.checkArgNotNull(rowMapper);

            final JdbcUtil.BiRowMapper<R> biRowMapper = JdbcUtil.toBiRowMapper(rowMapper);

            return list(conn, selectPropNames, biRowMapper, whereCause, jdbcSettings);
        }

        /**
         *
         * @param <R>
         * @param selectPropNames
         * @param rowMapper
         * @param whereCause
         * @return
         */
        public <R> List<R> list(final Collection<String> selectPropNames, final JdbcUtil.BiRowMapper<R> rowMapper, final Condition whereCause) {
            return list(selectPropNames, rowMapper, whereCause, null);
        }

        /**
         *
         * @param <R>
         * @param selectPropNames
         * @param rowMapper
         * @param whereCause
         * @param jdbcSettings
         * @return
         */
        public <R> List<R> list(final Collection<String> selectPropNames, final JdbcUtil.BiRowMapper<R> rowMapper, final Condition whereCause,
                final JdbcSettings jdbcSettings) {
            return list(null, selectPropNames, rowMapper, whereCause, jdbcSettings);
        }

        /**
         *
         * @param <R>
         * @param conn
         * @param selectPropNames
         * @param rowMapper
         * @param whereCause
         * @return
         */
        public <R> List<R> list(final Connection conn, final Collection<String> selectPropNames, final JdbcUtil.BiRowMapper<R> rowMapper,
                final Condition whereCause) {
            return list(conn, selectPropNames, rowMapper, whereCause, null);
        }

        /**
         *
         * @param <R>
         * @param conn
         * @param selectPropNames
         * @param rowMapper
         * @param whereCause
         * @param jdbcSettings
         * @return
         */
        public <R> List<R> list(final Connection conn, final Collection<String> selectPropNames, final JdbcUtil.BiRowMapper<R> rowMapper,
                final Condition whereCause, final JdbcSettings jdbcSettings) {
            final SP sp = prepareQuery(selectPropNames, whereCause);

            return sqlExecutor.list(conn, sp.sql, StatementSetter.DEFAULT, rowMapper, jdbcSettings, JdbcUtil.getParameterArray(sp));
        }

        /**
         * Execute the query in one or more data sources specified by {@code jdbcSettings} and merge the results.
         * It's designed for partition.
         *
         * @param whereCause
         * @param jdbcSettings
         * @return
         * @see SQLExecutor#listAll(Class, String, StatementSetter, JdbcSettings, Object...)
         */
        public List<T> listAll(final Condition whereCause, final JdbcSettings jdbcSettings) {
            return listAll((Collection<String>) null, whereCause, jdbcSettings);
        }

        /**
         * Execute the query in one or more data sources specified by {@code jdbcSettings} and merge the results.
         * It's designed for partition.
         *
         * @param <R>
         * @param singleSelectPropName
         * @param whereCause
         * @param jdbcSettings
         * @return
         */
        public <R> List<R> listAll(final String singleSelectPropName, final Condition whereCause, final JdbcSettings jdbcSettings) {
            final PropInfo propInfo = entityInfo.getPropInfo(singleSelectPropName);
            final RowMapper<R> rowMapper = propInfo == null ? RowMapper.GET_OBJECT : RowMapper.get((Type<R>) propInfo.dbType);

            return listAll(singleSelectPropName, rowMapper, whereCause, jdbcSettings);
        }

        /**
         * Execute the query in one or more data sources specified by {@code jdbcSettings} and merge the results.
         * It's designed for partition.
         *
         * @param <R>
         * @param singleSelectPropName
         * @param rowMapper
         * @param whereCause
         * @param jdbcSettings
         * @return
         */
        public <R> List<R> listAll(final String singleSelectPropName, final JdbcUtil.RowMapper<R> rowMapper, final Condition whereCause,
                final JdbcSettings jdbcSettings) {
            return listAll(Arrays.asList(singleSelectPropName), rowMapper, whereCause, jdbcSettings);
        }

        /**
         * Execute the query in one or more data sources specified by {@code jdbcSettings} and merge the results.
         * It's designed for partition.
         *
         * @param selectPropNames
         * @param whereCause
         * @param jdbcSettings
         * @return
         * @see SQLExecutor#listAll(Class, String, StatementSetter, JdbcSettings, Object...)
         */
        public List<T> listAll(final Collection<String> selectPropNames, final Condition whereCause, final JdbcSettings jdbcSettings) {
            final SP sp = prepareQuery(selectPropNames, whereCause);

            return sqlExecutor.listAll(targetEntityClass, sp.sql, StatementSetter.DEFAULT, jdbcSettings, JdbcUtil.getParameterArray(sp));
        }

        /**
         * Execute the query in one or more data sources specified by {@code jdbcSettings} and merge the results.
         * It's designed for partition.
         *
         * @param <R>
         * @param selectPropNames
         * @param rowMapper
         * @param whereCause
         * @param jdbcSettings
         * @return
         */
        public <R> List<R> listAll(final Collection<String> selectPropNames, final JdbcUtil.RowMapper<R> rowMapper, final Condition whereCause,
                final JdbcSettings jdbcSettings) {
            N.checkArgNotNull(rowMapper);

            final JdbcUtil.BiRowMapper<R> biRowMapper = JdbcUtil.toBiRowMapper(rowMapper);

            return listAll(selectPropNames, biRowMapper, whereCause, jdbcSettings);
        }

        /**
         * Execute the query in one or more data sources specified by {@code jdbcSettings} and merge the results.
         * It's designed for partition.
         *
         * @param <R>
         * @param selectPropNames
         * @param rowMapper
         * @param whereCause
         * @param jdbcSettings
         * @return
         */
        public <R> List<R> listAll(final Collection<String> selectPropNames, final JdbcUtil.BiRowMapper<R> rowMapper, final Condition whereCause,
                final JdbcSettings jdbcSettings) {
            final SP sp = prepareQuery(selectPropNames, whereCause);

            return sqlExecutor.listAll(sp.sql, StatementSetter.DEFAULT, rowMapper, jdbcSettings, JdbcUtil.getParameterArray(sp));
        }

        /**
         * Lazy execution, lazy fetch. The query execution and record fetching only happen when a terminal operation of the stream is called.
         *
         * @param whereCause
         * @return
         */
        public Stream<T> stream(final Condition whereCause) {
            return stream((Collection<String>) null, whereCause);
        }

        /**
         * Lazy execution, lazy fetch. The query execution and record fetching only happen when a terminal operation of the stream is called.
         *
         * @param selectPropNames
         * @param whereCause
         * @return
         */
        public Stream<T> stream(final Collection<String> selectPropNames, final Condition whereCause) {
            return stream(selectPropNames, whereCause, null);
        }

        /**
         * Lazy execution, lazy fetch. The query execution and record fetching only happen when a terminal operation of the stream is called.
         *
         * @param selectPropNames
         * @param whereCause
         * @param jdbcSettings
         * @return
         */
        public Stream<T> stream(final Collection<String> selectPropNames, final Condition whereCause, final JdbcSettings jdbcSettings) {
            final SP sp = prepareQuery(selectPropNames, whereCause);

            return sqlExecutor.stream(targetEntityClass, sp.sql, StatementSetter.DEFAULT, jdbcSettings, JdbcUtil.getParameterArray(sp));
        }

        /**
         *
         * @param <R>
         * @param singleSelectPropName
         * @param whereCause
         * @return
         */
        public <R> Stream<R> stream(final String singleSelectPropName, final Condition whereCause) {
            return stream(singleSelectPropName, whereCause, null);
        }

        /**
         *
         * @param <R>
         * @param singleSelectPropName
         * @param whereCause
         * @param jdbcSettings
         * @return
         */
        public <R> Stream<R> stream(final String singleSelectPropName, final Condition whereCause, final JdbcSettings jdbcSettings) {
            final PropInfo propInfo = entityInfo.getPropInfo(singleSelectPropName);
            final RowMapper<R> rowMapper = propInfo == null ? RowMapper.GET_OBJECT : RowMapper.get((Type<R>) propInfo.dbType);

            return stream(singleSelectPropName, rowMapper, whereCause, jdbcSettings);
        }

        /**
         * Lazy execution, lazy fetch. The query execution and record fetching only happen when a terminal operation of the stream is called.
         *
         * @param <R>
         * @param singleSelectPropName
         * @param rowMapper
         * @param whereCause
         * @return
         */
        public <R> Stream<R> stream(final String singleSelectPropName, final JdbcUtil.RowMapper<R> rowMapper, final Condition whereCause) {
            return stream(singleSelectPropName, rowMapper, whereCause, null);
        }

        /**
         * Lazy execution, lazy fetch. The query execution and record fetching only happen when a terminal operation of the stream is called.
         *
         * @param <R>
         * @param singleSelectPropName
         * @param rowMapper
         * @param whereCause
         * @param jdbcSettings
         * @return
         */
        public <R> Stream<R> stream(final String singleSelectPropName, final JdbcUtil.RowMapper<R> rowMapper, final Condition whereCause,
                final JdbcSettings jdbcSettings) {
            return stream(Arrays.asList(singleSelectPropName), rowMapper, whereCause, jdbcSettings);
        }

        /**
         * Lazy execution, lazy fetch. The query execution and record fetching only happen when a terminal operation of the stream is called.
         *
         * @param <R>
         * @param selectPropNames
         * @param rowMapper
         * @param whereCause
         * @return
         */
        public <R> Stream<R> stream(final Collection<String> selectPropNames, final JdbcUtil.RowMapper<R> rowMapper, final Condition whereCause) {
            return stream(selectPropNames, rowMapper, whereCause, null);
        }

        /**
         * Lazy execution, lazy fetch. The query execution and record fetching only happen when a terminal operation of the stream is called.
         *
         * @param <R>
         * @param selectPropNames
         * @param rowMapper
         * @param whereCause
         * @param jdbcSettings
         * @return
         */
        public <R> Stream<R> stream(final Collection<String> selectPropNames, final JdbcUtil.RowMapper<R> rowMapper, final Condition whereCause,
                final JdbcSettings jdbcSettings) {
            N.checkArgNotNull(rowMapper);

            final JdbcUtil.BiRowMapper<R> biRowMapper = JdbcUtil.toBiRowMapper(rowMapper);

            return stream(selectPropNames, biRowMapper, whereCause, jdbcSettings);
        }

        /**
         * Lazy execution, lazy fetch. The query execution and record fetching only happen when a terminal operation of the stream is called.
         *
         * @param <R>
         * @param selectPropNames
         * @param rowMapper
         * @param whereCause
         * @return
         */
        public <R> Stream<R> stream(final Collection<String> selectPropNames, final JdbcUtil.BiRowMapper<R> rowMapper, final Condition whereCause) {
            return stream(selectPropNames, rowMapper, whereCause, null);
        }

        /**
         * Lazy execution, lazy fetch. The query execution and record fetching only happen when a terminal operation of the stream is called.
         *
         * @param <R>
         * @param selectPropNames
         * @param rowMapper
         * @param whereCause
         * @param jdbcSettings
         * @return
         */
        public <R> Stream<R> stream(final Collection<String> selectPropNames, final JdbcUtil.BiRowMapper<R> rowMapper, final Condition whereCause,
                final JdbcSettings jdbcSettings) {
            final SP sp = prepareQuery(selectPropNames, whereCause);

            return sqlExecutor.stream(sp.sql, StatementSetter.DEFAULT, rowMapper, jdbcSettings, JdbcUtil.getParameterArray(sp));
        }

        /**
         * Execute the query in one or more data sources specified by {@code jdbcSettings} and merge the results.
         * It's designed for partition.
         *
         * @param whereCause
         * @param jdbcSettings
         * @return
         * @see SQLExecutor#streamAll(Class, String, StatementSetter, JdbcSettings, Object...)
         */
        public Stream<T> streamAll(final Condition whereCause, final JdbcSettings jdbcSettings) {
            return streamAll((Collection<String>) null, whereCause, jdbcSettings);
        }

        /**
         * Execute the query in one or more data sources specified by {@code jdbcSettings} and merge the results.
         * It's designed for partition.
         *
         * @param <R>
         * @param singleSelectPropName
         * @param whereCause
         * @param jdbcSettings
         * @return
         */
        public <R> Stream<R> streamAll(final String singleSelectPropName, final Condition whereCause, final JdbcSettings jdbcSettings) {
            final PropInfo propInfo = entityInfo.getPropInfo(singleSelectPropName);
            final RowMapper<R> rowMapper = propInfo == null ? RowMapper.GET_OBJECT : RowMapper.get((Type<R>) propInfo.dbType);

            return streamAll(singleSelectPropName, rowMapper, whereCause, jdbcSettings);
        }

        /**
         * Execute the query in one or more data sources specified by {@code jdbcSettings} and merge the results.
         * It's designed for partition.
         *
         * @param <R>
         * @param singleSelectPropName
         * @param rowMapper
         * @param whereCause
         * @param jdbcSettings
         * @return
         */
        public <R> Stream<R> streamAll(final String singleSelectPropName, final JdbcUtil.RowMapper<R> rowMapper, final Condition whereCause,
                final JdbcSettings jdbcSettings) {
            return streamAll(Arrays.asList(singleSelectPropName), rowMapper, whereCause, jdbcSettings);
        }

        /**
         * Execute the query in one or more data sources specified by {@code jdbcSettings} and merge the results.
         * It's designed for partition.
         *
         * @param selectPropNames
         * @param whereCause
         * @param jdbcSettings
         * @return
         * @see SQLExecutor#streamAll(Class, String, StatementSetter, JdbcSettings, Object...)
         */
        public Stream<T> streamAll(final Collection<String> selectPropNames, final Condition whereCause, final JdbcSettings jdbcSettings) {
            final SP sp = prepareQuery(selectPropNames, whereCause);

            return sqlExecutor.streamAll(targetEntityClass, sp.sql, StatementSetter.DEFAULT, jdbcSettings, JdbcUtil.getParameterArray(sp));
        }

        /**
         * Execute the query in one or more data sources specified by {@code jdbcSettings} and merge the results.
         * It's designed for partition.
         *
         * @param <R>
         * @param selectPropNames
         * @param rowMapper
         * @param whereCause
         * @param jdbcSettings
         * @return
         */
        public <R> Stream<R> streamAll(final Collection<String> selectPropNames, final JdbcUtil.RowMapper<R> rowMapper, final Condition whereCause,
                final JdbcSettings jdbcSettings) {
            N.checkArgNotNull(rowMapper);

            final JdbcUtil.BiRowMapper<R> biRowMapper = JdbcUtil.toBiRowMapper(rowMapper);

            return streamAll(selectPropNames, biRowMapper, whereCause, jdbcSettings);
        }

        /**
         * Execute the query in one or more data sources specified by {@code jdbcSettings} and merge the results.
         * It's designed for partition.
         *
         *
         * @param <R>
         * @param selectPropNames
         * @param rowMapper
         * @param whereCause
         * @param jdbcSettings
         * @return
         */
        public <R> Stream<R> streamAll(final Collection<String> selectPropNames, final JdbcUtil.BiRowMapper<R> rowMapper, final Condition whereCause,
                final JdbcSettings jdbcSettings) {
            final SP sp = prepareQuery(selectPropNames, whereCause);

            return sqlExecutor.streamAll(sp.sql, StatementSetter.DEFAULT, rowMapper, jdbcSettings, JdbcUtil.getParameterArray(sp));
        }

        /**
         *
         * @param whereCause
         * @return
         */
        public DataSet query(final Condition whereCause) {
            return query((Collection<String>) null, whereCause);
        }

        /**
         *
         * @param selectPropNames
         * @param whereCause
         * @return
         */
        public DataSet query(final Collection<String> selectPropNames, final Condition whereCause) {
            return query(selectPropNames, whereCause, null);
        }

        /**
         *
         * @param selectPropNames
         * @param whereCause
         * @param jdbcSettings
         * @return
         */
        public DataSet query(final Collection<String> selectPropNames, final Condition whereCause, final JdbcSettings jdbcSettings) {
            return query(null, selectPropNames, whereCause, jdbcSettings);
        }

        /**
         *
         * @param conn
         * @param whereCause
         * @return
         */
        public DataSet query(final Connection conn, final Condition whereCause) {
            return query(conn, null, whereCause);
        }

        /**
         *
         * @param conn
         * @param selectPropNames
         * @param whereCause
         * @return
         */
        public DataSet query(final Connection conn, final Collection<String> selectPropNames, final Condition whereCause) {
            return query(conn, selectPropNames, whereCause, null);
        }

        /**
         *
         * @param conn
         * @param selectPropNames
         * @param whereCause
         * @param jdbcSettings
         * @return
         */
        public DataSet query(final Connection conn, final Collection<String> selectPropNames, final Condition whereCause, final JdbcSettings jdbcSettings) {
            final SP sp = prepareQuery(selectPropNames, whereCause);

            return sqlExecutor.query(conn, sp.sql, StatementSetter.DEFAULT, null, jdbcSettings, JdbcUtil.getParameterArray(sp));
        }

        /**
         * Execute the query in one or more data sources specified by {@code jdbcSettings} and merge the results.
         * It's designed for partition.
         *
         * @param whereCause
         * @param jdbcSettings
         * @return
         * @see SQLExecutor#queryAll(String, StatementSetter, JdbcSettings, Object...)
         */
        public DataSet queryAll(final Condition whereCause, final JdbcSettings jdbcSettings) {
            return queryAll(null, whereCause, jdbcSettings);
        }

        /**
         * Execute the query in one or more data sources specified by {@code jdbcSettings} and merge the results.
         * It's designed for partition.
         *
         * @param selectPropNames
         * @param whereCause
         * @param jdbcSettings
         * @return
         * @see SQLExecutor#queryAll(String, StatementSetter, JdbcSettings, Object...)
         */
        public DataSet queryAll(final Collection<String> selectPropNames, final Condition whereCause, final JdbcSettings jdbcSettings) {
            final SP sp = prepareQuery(selectPropNames, whereCause);

            return sqlExecutor.queryAll(sp.sql, StatementSetter.DEFAULT, jdbcSettings, JdbcUtil.getParameterArray(sp));
        }

        /**
         * Query for boolean.
         *
         * @param singleSelectPropName
         * @param whereCause
         * @return
         * @see Mapper#queryForSingleResult(Class, Connection, String, Condition, JdbcSettings)
         */
        public OptionalBoolean queryForBoolean(final String singleSelectPropName, final Condition whereCause) {
            final SP sp = prepareQuery(Arrays.asList(singleSelectPropName), whereCause, 1);

            return sqlExecutor.queryForBoolean(sp.sql, JdbcUtil.getParameterArray(sp));
        }

        /**
         * Query for char.
         *
         * @param singleSelectPropName
         * @param whereCause
         * @return
         * @see Mapper#queryForSingleResult(Class, Connection, String, Condition, JdbcSettings)
         */
        public OptionalChar queryForChar(final String singleSelectPropName, final Condition whereCause) {
            final SP sp = prepareQuery(Arrays.asList(singleSelectPropName), whereCause, 1);

            return sqlExecutor.queryForChar(sp.sql, JdbcUtil.getParameterArray(sp));
        }

        /**
         * Query for byte.
         *
         * @param singleSelectPropName
         * @param whereCause
         * @return
         * @see Mapper#queryForSingleResult(Class, Connection, String, Condition, JdbcSettings)
         */
        public OptionalByte queryForByte(final String singleSelectPropName, final Condition whereCause) {
            final SP sp = prepareQuery(Arrays.asList(singleSelectPropName), whereCause, 1);

            return sqlExecutor.queryForByte(sp.sql, JdbcUtil.getParameterArray(sp));
        }

        /**
         * Query for short.
         *
         * @param singleSelectPropName
         * @param whereCause
         * @return
         * @see Mapper#queryForSingleResult(Class, Connection, String, Condition, JdbcSettings)
         */
        public OptionalShort queryForShort(final String singleSelectPropName, final Condition whereCause) {
            final SP sp = prepareQuery(Arrays.asList(singleSelectPropName), whereCause, 1);

            return sqlExecutor.queryForShort(sp.sql, JdbcUtil.getParameterArray(sp));
        }

        /**
         * Query for int.
         *
         * @param singleSelectPropName
         * @param whereCause
         * @return
         * @see Mapper#queryForSingleResult(Class, Connection, String, Condition, JdbcSettings)
         */
        public OptionalInt queryForInt(final String singleSelectPropName, final Condition whereCause) {
            final SP sp = prepareQuery(Arrays.asList(singleSelectPropName), whereCause, 1);

            return sqlExecutor.queryForInt(sp.sql, JdbcUtil.getParameterArray(sp));
        }

        /**
         * Query for long.
         *
         * @param singleSelectPropName
         * @param whereCause
         * @return
         * @see Mapper#queryForSingleResult(Class, Connection, String, Condition, JdbcSettings)
         */
        public OptionalLong queryForLong(final String singleSelectPropName, final Condition whereCause) {
            final SP sp = prepareQuery(Arrays.asList(singleSelectPropName), whereCause, 1);

            return sqlExecutor.queryForLong(sp.sql, JdbcUtil.getParameterArray(sp));
        }

        /**
         * Query for float.
         *
         * @param singleSelectPropName
         * @param whereCause
         * @return
         * @see Mapper#queryForSingleResult(Class, Connection, String, Condition, JdbcSettings)
         */
        public OptionalFloat queryForFloat(final String singleSelectPropName, final Condition whereCause) {
            final SP sp = prepareQuery(Arrays.asList(singleSelectPropName), whereCause, 1);

            return sqlExecutor.queryForFloat(sp.sql, JdbcUtil.getParameterArray(sp));
        }

        /**
         * Query for double.
         *
         * @param singleSelectPropName
         * @param whereCause
         * @return
         * @see Mapper#queryForSingleResult(Class, Connection, String, Condition, JdbcSettings)
         */
        public OptionalDouble queryForDouble(final String singleSelectPropName, final Condition whereCause) {
            final SP sp = prepareQuery(Arrays.asList(singleSelectPropName), whereCause, 1);

            return sqlExecutor.queryForDouble(sp.sql, JdbcUtil.getParameterArray(sp));
        }

        /**
         * Query for big decimal.
         *
         * @param singleSelectPropName
         * @param whereCause
         * @return
         * @see Mapper#queryForSingleResult(Class, Connection, String, Condition, JdbcSettings)
         */
        public Nullable<BigDecimal> queryForBigDecimal(final String singleSelectPropName, final Condition whereCause) {
            final SP sp = prepareQuery(Arrays.asList(singleSelectPropName), whereCause, 1);

            return sqlExecutor.queryForBigDecimal(sp.sql, JdbcUtil.getParameterArray(sp));
        }

        /**
         * Query for string.
         *
         * @param singleSelectPropName
         * @param whereCause
         * @return
         * @see Mapper#queryForSingleResult(Class, Connection, String, Condition, JdbcSettings)
         */
        public Nullable<String> queryForString(final String singleSelectPropName, final Condition whereCause) {
            final SP sp = prepareQuery(Arrays.asList(singleSelectPropName), whereCause, 1);

            return sqlExecutor.queryForString(sp.sql, JdbcUtil.getParameterArray(sp));
        }

        /**
         * Query for date.
         *
         * @param singleSelectPropName
         * @param whereCause
         * @return
         * @see Mapper#queryForSingleResult(Class, Connection, String, Condition, JdbcSettings)
         */
        public Nullable<java.sql.Date> queryForDate(final String singleSelectPropName, final Condition whereCause) {
            final SP sp = prepareQuery(Arrays.asList(singleSelectPropName), whereCause, 1);

            return sqlExecutor.queryForDate(sp.sql, JdbcUtil.getParameterArray(sp));
        }

        /**
         * Query for time.
         *
         * @param singleSelectPropName
         * @param whereCause
         * @return
         * @see Mapper#queryForSingleResult(Class, Connection, String, Condition, JdbcSettings)
         */
        public Nullable<java.sql.Time> queryForTime(final String singleSelectPropName, final Condition whereCause) {
            final SP sp = prepareQuery(Arrays.asList(singleSelectPropName), whereCause, 1);

            return sqlExecutor.queryForTime(sp.sql, JdbcUtil.getParameterArray(sp));
        }

        /**
         * Query for timestamp.
         *
         * @param singleSelectPropName
         * @param whereCause
         * @return
         * @see Mapper#queryForSingleResult(Class, Connection, String, Condition, JdbcSettings)
         */
        public Nullable<java.sql.Timestamp> queryForTimestamp(final String singleSelectPropName, final Condition whereCause) {
            final SP sp = prepareQuery(Arrays.asList(singleSelectPropName), whereCause, 1);

            return sqlExecutor.queryForTimestamp(sp.sql, JdbcUtil.getParameterArray(sp));
        }

        /**
         * Query for single result.
         *
         * @param <V> the value type
         * @param targetValueClass
         * @param singleSelectPropName
         * @param id
         * @return
         * @see Mapper#queryForSingleResult(Class, Connection, String, Condition, JdbcSettings)
         */
        public <V> Nullable<V> queryForSingleResult(final Class<V> targetValueClass, final String singleSelectPropName, final ID id) {
            return queryForSingleResult(targetValueClass, singleSelectPropName, id2Cond(id));
        }

        /**
         * Query for single result.
         *
         * @param <V> the value type
         * @param targetValueClass
         * @param singleSelectPropName
         * @param whereCause
         * @return
         * @see Mapper#queryForSingleResult(Class, Connection, String, Condition, JdbcSettings)
         */
        public <V> Nullable<V> queryForSingleResult(final Class<V> targetValueClass, final String singleSelectPropName, final Condition whereCause) {
            return queryForSingleResult(targetValueClass, singleSelectPropName, whereCause, null);
        }

        /**
         * Query for single result.
         *
         * @param <V> the value type
         * @param targetValueClass
         * @param singleSelectPropName
         * @param whereCause
         * @param jdbcSettings
         * @return
         * @see Mapper#queryForSingleResult(Class, Connection, String, Condition, JdbcSettings)
         */
        public <V> Nullable<V> queryForSingleResult(final Class<V> targetValueClass, final String singleSelectPropName, final Condition whereCause,
                final JdbcSettings jdbcSettings) {
            return queryForSingleResult(targetValueClass, null, singleSelectPropName, whereCause, jdbcSettings);
        }

        /**
         * Query for single result.
         *
         * @param <V> the value type
         * @param targetValueClass
         * @param conn
         * @param singleSelectPropName
         * @param id
         * @return
         * @see Mapper#queryForSingleResult(Class, Connection, String, Condition, JdbcSettings)
         */
        public <V> Nullable<V> queryForSingleResult(final Class<V> targetValueClass, final Connection conn, final String singleSelectPropName, final ID id) {
            return queryForSingleResult(targetValueClass, conn, singleSelectPropName, id2Cond(id));
        }

        /**
         * Query for single result.
         *
         * @param <V> the value type
         * @param targetValueClass
         * @param conn
         * @param singleSelectPropName
         * @param whereCause
         * @return
         * @see Mapper#queryForSingleResult(Class, Connection, String, Condition, JdbcSettings)
         */
        public <V> Nullable<V> queryForSingleResult(final Class<V> targetValueClass, final Connection conn, final String singleSelectPropName,
                final Condition whereCause) {
            return queryForSingleResult(targetValueClass, conn, singleSelectPropName, whereCause, null);
        }

        /**
         * Returns a {@code Nullable} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
         *
         * @param <V> the value type
         * @param targetValueClass
         * @param conn
         * @param singleSelectPropName
         * @param whereCause
         * @param jdbcSettings
         * @return
         * @see Mapper#queryForSingleResult(Class, Connection, String, Condition, JdbcSettings)
         */
        public <V> Nullable<V> queryForSingleResult(final Class<V> targetValueClass, final Connection conn, final String singleSelectPropName,
                final Condition whereCause, final JdbcSettings jdbcSettings) {
            final SP sp = prepareQuery(Arrays.asList(singleSelectPropName), whereCause, 1);

            return sqlExecutor.queryForSingleResult(targetValueClass, conn, sp.sql, StatementSetter.DEFAULT, jdbcSettings, JdbcUtil.getParameterArray(sp));
        }

        /**
         * Query for unique result.
         *
         * @param <V> the value type
         * @param targetValueClass
         * @param singleSelectPropName
         * @param id
         * @return
         * @throws DuplicatedResultException if two or more records are found.
         */
        public <V> Nullable<V> queryForUniqueResult(final Class<V> targetValueClass, final String singleSelectPropName, final ID id)
                throws DuplicatedResultException {
            return queryForUniqueResult(targetValueClass, singleSelectPropName, id2Cond(id));
        }

        /**
         * Query for unique result.
         *
         * @param <V> the value type
         * @param targetValueClass
         * @param singleSelectPropName
         * @param whereCause
         * @return
         * @throws DuplicatedResultException the duplicated result exception
         */
        public <V> Nullable<V> queryForUniqueResult(final Class<V> targetValueClass, final String singleSelectPropName, final Condition whereCause)
                throws DuplicatedResultException {
            return queryForUniqueResult(targetValueClass, singleSelectPropName, whereCause, null);
        }

        /**
         * Query for unique result.
         *
         * @param <V> the value type
         * @param targetValueClass
         * @param singleSelectPropName
         * @param whereCause
         * @param jdbcSettings
         * @return
         * @throws DuplicatedResultException if two or more records are found.
         */
        public <V> Nullable<V> queryForUniqueResult(final Class<V> targetValueClass, final String singleSelectPropName, final Condition whereCause,
                final JdbcSettings jdbcSettings) throws DuplicatedResultException {
            return queryForUniqueResult(targetValueClass, null, singleSelectPropName, whereCause, jdbcSettings);
        }

        /**
         * Query for unique result.
         *
         * @param <V> the value type
         * @param targetValueClass
         * @param conn
         * @param singleSelectPropName
         * @param id
         * @return
         * @throws DuplicatedResultException if two or more records are found.
         */
        public <V> Nullable<V> queryForUniqueResult(final Class<V> targetValueClass, final Connection conn, final String singleSelectPropName, final ID id)
                throws DuplicatedResultException {
            return queryForUniqueResult(targetValueClass, conn, singleSelectPropName, id2Cond(id));
        }

        /**
         * Query for unique result.
         *
         * @param <V> the value type
         * @param targetValueClass
         * @param conn
         * @param singleSelectPropName
         * @param whereCause
         * @return
         * @throws DuplicatedResultException if two or more records are found.
         */
        public <V> Nullable<V> queryForUniqueResult(final Class<V> targetValueClass, final Connection conn, final String singleSelectPropName,
                final Condition whereCause) throws DuplicatedResultException {
            return queryForUniqueResult(targetValueClass, conn, singleSelectPropName, whereCause, null);
        }

        /**
         * Query for unique result.
         *
         * @param <V> the value type
         * @param targetValueClass
         * @param conn
         * @param singleSelectPropName
         * @param whereCause
         * @param jdbcSettings
         * @return
         * @throws DuplicatedResultException if two or more records are found.
         */
        public <V> Nullable<V> queryForUniqueResult(final Class<V> targetValueClass, final Connection conn, final String singleSelectPropName,
                final Condition whereCause, final JdbcSettings jdbcSettings) throws DuplicatedResultException {
            final SP sp = prepareQuery(Arrays.asList(singleSelectPropName), whereCause, 1);

            return sqlExecutor.queryForUniqueResult(targetValueClass, conn, sp.sql, StatementSetter.DEFAULT, jdbcSettings, JdbcUtil.getParameterArray(sp));
        }

        /**
         *
         * @param selectPropNames
         * @param whereCause
         * @return
         */
        private SP prepareQuery(final Collection<String> selectPropNames, final Condition whereCause) {
            return prepareQuery(selectPropNames, whereCause, 0);
        }

        /**
         *
         * @param selectPropNames
         * @param whereCause
         * @param count
         * @return
         */
        private SP prepareQuery(Collection<String> selectPropNames, final Condition whereCause, final int count) {
            if (N.isNullOrEmpty(selectPropNames)) {
                selectPropNames = defaultSelectPropNameList;
            }

            SQLBuilder sqlBuilder = null;

            switch (namingPolicy) {
                case LOWER_CASE_WITH_UNDERSCORE:
                    if (N.isNullOrEmpty(selectPropNames)) {
                        sqlBuilder = NSC.selectFrom(targetEntityClass).append(whereCause);
                    } else {
                        sqlBuilder = NSC.select(selectPropNames).from(targetEntityClass).append(whereCause);
                    }

                    break;

                case UPPER_CASE_WITH_UNDERSCORE:
                    if (N.isNullOrEmpty(selectPropNames)) {
                        sqlBuilder = NAC.selectFrom(targetEntityClass).append(whereCause);
                    } else {
                        sqlBuilder = NAC.select(selectPropNames).from(targetEntityClass).append(whereCause);
                    }

                    break;

                case LOWER_CAMEL_CASE:
                    if (N.isNullOrEmpty(selectPropNames)) {
                        sqlBuilder = NLC.selectFrom(targetEntityClass).append(whereCause);
                    } else {
                        sqlBuilder = NLC.select(selectPropNames).from(targetEntityClass).append(whereCause);
                    }

                    break;

                default:
                    throw new RuntimeException("Unsupported naming policy: " + namingPolicy);
            }

            if (count > 0 && count < Integer.MAX_VALUE) {
                switch (sqlExecutor.dbVersion()) {
                    case ORACLE:
                    case SQL_SERVER:
                        // Do nothing because limit is not supported.

                        break;

                    default:
                        sqlBuilder.limit(count);
                }
            }

            return sqlBuilder.pair();
        }

        /**
         * Insert the specified entity into data store, and set back the auto-generated id to the specified entity if there is the auto-generated id.
         *
         * @param entity
         * @return
         */
        public ID insert(final T entity) {
            return insert(null, entity);
        }

        /**
         *
         * @param entity
         * @param propNamesToInsert
         * @return
         */
        public ID insert(final T entity, final Collection<String> propNamesToInsert) {
            return insert(null, entity, propNamesToInsert);
        }

        /**
         *
         * @param props
         * @return
         */
        public ID insert(final Map<String, Object> props) {
            return insert(null, props);
        }

        /**
         *
         * @param conn
         * @param entity
         * @return
         */
        public ID insert(final Connection conn, final T entity) {
            return insert(conn, entity, null);
        }

        /**
         *
         * @param conn
         * @param entity
         * @param propNamesToInsert
         * @return
         */
        public ID insert(final Connection conn, final T entity, final Collection<String> propNamesToInsert) {
            N.checkArgNotNull(entity);

            final String sql = prepareInsertSql(entity, propNamesToInsert, false);

            if (isNoId) {
                sqlExecutor.update(conn, sql, entity);

                return null;
            } else {
                final ID id = sqlExecutor.insert(conn, sql, jdbcSettingsForInsert, entity);

                return id == null ? idGetter.apply(entity) : id;
            }
        }

        /**
         *
         * @param conn
         * @param props
         * @return
         */
        public ID insert(final Connection conn, final Map<String, Object> props) {
            N.checkArgNotNull(props);

            final String sql = prepareInsertSql(props);

            if (isNoId) {
                sqlExecutor.update(conn, sql, props);

                return null;
            } else {
                final ID id = sqlExecutor.insert(conn, sql, jdbcSettingsForInsert, props);

                return id == null ? idGetter2.apply(props) : id;
            }
        }

        /**
         *
         * @param entities
         * @return
         */
        public List<ID> batchInsert(final Collection<? extends T> entities) {
            return batchInsert(entities, JdbcSettings.DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param entities
         * @param batchSize
         * @return
         */
        public List<ID> batchInsert(final Collection<? extends T> entities, final int batchSize) {
            return batchInsert(entities, batchSize, IsolationLevel.DEFAULT);
        }

        /**
         *
         * @param entities
         * @param batchSize
         * @param isolationLevel
         * @return
         */
        public List<ID> batchInsert(final Collection<? extends T> entities, final int batchSize, final IsolationLevel isolationLevel) {
            return batchInsert(entities, null, batchSize, isolationLevel);
        }

        /**
         *
         * @param entities
         * @param propNamesToInsert
         * @param batchSize
         * @param isolationLevel
         * @return
         */
        public List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert, final int batchSize,
                final IsolationLevel isolationLevel) {
            return batchInsert(null, entities, propNamesToInsert, batchSize, isolationLevel);
        }

        /**
         *
         * @param conn
         * @param entities
         * @return
         */
        public List<ID> batchInsert(final Connection conn, final Collection<? extends T> entities) {
            return batchInsert(conn, entities, JdbcSettings.DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param conn
         * @param entities
         * @param batchSize
         * @return
         */
        public List<ID> batchInsert(final Connection conn, final Collection<? extends T> entities, final int batchSize) {
            return batchInsert(conn, entities, null, batchSize);
        }

        /**
         * Insert All the records by batch operation. And set back auto-generated ids to the specified entities if there are the auto-generated ids.
         *
         * @param conn
         * @param entities
         * @param propNamesToInsert
         * @param batchSize
         * @return
         */
        public List<ID> batchInsert(final Connection conn, final Collection<? extends T> entities, final Collection<String> propNamesToInsert,
                final int batchSize) {
            return batchInsert(conn, entities, propNamesToInsert, batchSize, IsolationLevel.DEFAULT);
        }

        /**
         *
         * @param conn
         * @param entities
         * @param propNamesToInsert
         * @param batchSize
         * @param isolationLevel
         * @return
         */
        private List<ID> batchInsert(final Connection conn, final Collection<? extends T> entities, final Collection<String> propNamesToInsert,
                final int batchSize, final IsolationLevel isolationLevel) {
            N.checkArgPositive(batchSize, "batchSize");

            if (N.isNullOrEmpty(entities)) {
                return new ArrayList<>();
            }

            final List<T> entityList = entities instanceof List ? (List<T>) entities : new ArrayList<>(entities);
            final T entity = N.firstOrNullIfEmpty(entityList);
            final String sql = prepareInsertSql(entity, propNamesToInsert, true);
            final JdbcSettings jdbcSettings = JdbcSettings.create()
                    .setBatchSize(batchSize)
                    .setIsolationLevel(isolationLevel)
                    .setReturnedColumnNames(returnColumnNames);

            List<ID> ids = sqlExecutor.batchInsert(conn, sql, StatementSetter.DEFAULT, keyExtractor, jdbcSettings, entityList);

            if (N.isNullOrEmpty(ids)) {
                ids = Stream.of(entityList).map(idGetter).toList();
            }

            return ids;
        }

        /**
         * Prepare insert sql.
         *
         * @param entity
         * @param propNamesToInsert
         * @param isForBatchInsert TODO
         * @return
         */
        private String prepareInsertSql(final T entity, final Collection<String> propNamesToInsert, boolean isForBatchInsert) {
            Collection<String> insertingPropNames = propNamesToInsert;

            if (N.isNullOrEmpty(insertingPropNames)) {
                if (isDirtyMarker && isForBatchInsert == false) {
                    insertingPropNames = SQLBuilder.getInsertPropNames(entity, null);
                } else {
                    return isDefaultIdTester.test(idGetter.apply(entity)) ? sql_insert_without_id : sql_insert_with_id;
                }
            }

            if (N.isNullOrEmpty(insertingPropNames)) {
                return isDefaultIdTester.test(idGetter.apply(entity)) ? sql_insert_without_id : sql_insert_with_id;
            }

            return prepareInsertSql(insertingPropNames);
        }

        /**
         * Prepare insert sql.
         *
         * @param props
         * @return
         */
        private String prepareInsertSql(final Map<String, Object> props) {
            N.checkArgument(N.notNullOrEmpty(props), "props");

            return prepareInsertSql(props.keySet());
        }

        /**
         * Prepare insert sql.
         *
         * @param insertingPropNames
         * @return
         */
        private String prepareInsertSql(final Collection<String> insertingPropNames) {
            N.checkArgument(N.notNullOrEmpty(insertingPropNames), "insertingPropNames");

            switch (namingPolicy) {
                case LOWER_CASE_WITH_UNDERSCORE:
                    return NSC.insert(insertingPropNames).into(targetEntityClass).sql();

                case UPPER_CASE_WITH_UNDERSCORE:
                    return NAC.insert(insertingPropNames).into(targetEntityClass).sql();

                case LOWER_CAMEL_CASE:
                    return NLC.insert(insertingPropNames).into(targetEntityClass).sql();

                default:
                    throw new RuntimeException("Unsupported naming policy: " + namingPolicy);
            }
        }

        /**
         * Execute {@code add} and return the added entity if the record doesn't, otherwise, {@code update} is executed and updated db record is returned.
         *
         * @param entity
         * @return
         */
        public T upsert(final T entity) {
            checkIdRequired();

            final T dbEntity = gett(idGetter.apply(entity));

            if (dbEntity == null) {
                insert(entity);
                return entity;
            } else {
                N.merge(entity, dbEntity, false, idPropNameSet);
                update(dbEntity);
                return dbEntity;
            }
        }

        /**
         * Execute {@code add} and return the added entity if the record doesn't, otherwise, {@code update} is executed and updated db record is returned.
         *
         * @param entity
         * @param whereCause to verify if the record exists or not.
         * @return
         */
        public T upsert(final T entity, final Condition whereCause) {
            N.checkArgNotNull(whereCause, "whereCause");

            final T dbEntity = findFirst(whereCause).orNull();

            if (dbEntity == null) {
                insert(entity);
                return entity;
            } else {
                N.merge(entity, dbEntity, false, idPropNameSet);
                update(dbEntity);
                return dbEntity;
            }
        }

        /**
         *
         * @param entity
         * @return true, if successful
         */
        public boolean refresh(final T entity) {
            final Collection<String> propNamesToRefresh = isDirtyMarker ? DirtyMarkerUtil.signedPropNames((DirtyMarker) entity) : defaultSelectPropNameList;

            return refresh(entity, propNamesToRefresh);
        }

        /**
         *
         * @param entity
         * @param propNamesToRefresh
         * @return {@code false} if no record found by the ids in the specified {@code entity}.
         */
        public boolean refresh(final T entity, Collection<String> propNamesToRefresh) {
            checkIdRequired();

            if (N.isNullOrEmpty(propNamesToRefresh)) {
                return exists(idGetter.apply(entity));
            }

            final T dbEntity = gett(idGetter.apply(entity));

            if (dbEntity == null) {
                return false;
            } else {
                N.merge(dbEntity, entity, propNamesToRefresh);

                if (isDirtyMarker) {
                    DirtyMarkerUtil.markDirty((DirtyMarker) entity, propNamesToRefresh, false);
                }

                return true;
            }
        }

        /**
         *
         * @param entity
         * @return
         */
        public int update(final T entity) {
            return update(entity, (Collection<String>) null);
        }

        /**
         *
         * @param entity
         * @param propNamesToUpdate
         * @return
         */
        public int update(final T entity, final Collection<String> propNamesToUpdate) {
            return update((Connection) null, entity, propNamesToUpdate);
        }

        /**
         *
         * @param propName
         * @param propValue
         * @param id
         * @return
         */
        public int update(final String propName, final Object propValue, final ID id) {
            final Map<String, Object> updateProps = new HashMap<>();
            updateProps.put(propName, propValue);

            return update(updateProps, id);
        }

        /**
         *
         * @param propName
         * @param propValue
         * @param cond
         * @return
         */
        public int update(final String propName, final Object propValue, final Condition cond) {
            final Map<String, Object> updateProps = new HashMap<>();
            updateProps.put(propName, propValue);

            return update(updateProps, cond);
        }

        /**
         *
         * @param props
         * @param id
         * @return
         */
        public int update(final Map<String, Object> props, final ID id) {
            return update((Connection) null, props, id);
        }

        /**
         *
         * @param props
         * @param whereCause
         * @return
         */
        public int update(final Map<String, Object> props, final Condition whereCause) {
            return update((Connection) null, props, whereCause);
        }

        /**
         *
         * @param conn
         * @param entity
         * @return
         */
        public int update(final Connection conn, final T entity) {
            return update(conn, entity, (Collection<String>) null);
        }

        /**
         *
         * @param conn
         * @param entity
         * @param propNamesToUpdate
         * @return
         */
        public int update(final Connection conn, final T entity, final Collection<String> propNamesToUpdate) {
            checkIdRequired();

            N.checkArgNotNull(entity);

            final String sql = prepareUpdateSql(entity, propNamesToUpdate, false);

            final int updateCount = sqlExecutor.update(conn, sql, entity);

            // postUpdate(entity, propNamesToUpdate);

            return updateCount;
        }

        /**
         *
         * @param conn
         * @param propName
         * @param propValue
         * @param id
         * @return
         */
        public int update(final Connection conn, final String propName, final Object propValue, final ID id) {
            final Map<String, Object> updateProps = new HashMap<>();
            updateProps.put(propName, propValue);

            return update(conn, updateProps, id);
        }

        /**
         *
         * @param conn
         * @param propName
         * @param propValue
         * @param cond
         * @return
         */
        public int update(final Connection conn, final String propName, final Object propValue, final Condition cond) {
            final Map<String, Object> updateProps = new HashMap<>();
            updateProps.put(propName, propValue);

            return update(conn, updateProps, cond);
        }

        /**
         *
         * @param conn
         * @param props
         * @param id
         * @return
         */
        public int update(final Connection conn, final Map<String, Object> props, final ID id) {
            checkIdRequired();

            N.checkArgNotNull(id);

            return update(conn, props, id2Cond(id));
        }

        /**
         *
         * @param conn
         * @param props
         * @param whereCause
         * @return
         */
        public int update(final Connection conn, final Map<String, Object> props, final Condition whereCause) {
            N.checkArgNotNull(props);
            N.checkArgNotNull(whereCause);

            if (N.isNullOrEmpty(props)) {
                return 0;
            }

            final SP sp = prepareUpdate(props, whereCause);

            return sqlExecutor.update(conn, sp.sql, JdbcUtil.getParameterArray(sp));
        }

        /**
         * Update All the records by batch operation.
         *
         * @param entities which must have the same properties set for update.
         * @return
         */
        public int batchUpdate(final Collection<? extends T> entities) {
            return batchUpdate(entities, (Collection<String>) null);
        }

        /**
         *
         * @param entities
         * @param propNamesToUpdate
         * @return
         */
        public int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate) {
            return batchUpdate(entities, propNamesToUpdate, JdbcSettings.DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param entities
         * @param batchSize
         * @return
         */
        public int batchUpdate(final Collection<? extends T> entities, final int batchSize) {
            return batchUpdate(entities, (Collection<String>) null, batchSize);
        }

        /**
         * Update All the records by batch operation.
         *
         * @param entities which must have the same properties set for update.
         * @param propNamesToUpdate
         * @param batchSize Default value is 200.
         * @return
         */
        public int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate, final int batchSize) {
            return batchUpdate(entities, propNamesToUpdate, batchSize, IsolationLevel.DEFAULT);
        }

        /**
         *
         * @param entities
         * @param batchSize
         * @param isolationLevel
         * @return
         */
        public int batchUpdate(final Collection<? extends T> entities, final int batchSize, final IsolationLevel isolationLevel) {
            return batchUpdate(entities, (Collection<String>) null, batchSize, isolationLevel);
        }

        /**
         *
         * @param entities
         * @param propNamesToUpdate
         * @param batchSize
         * @param isolationLevel
         * @return
         */
        public int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate, final int batchSize,
                final IsolationLevel isolationLevel) {
            return batchUpdate((Connection) null, entities, propNamesToUpdate, batchSize, isolationLevel);
        }

        /**
         *
         * @param conn
         * @param entities
         * @return
         */
        public int batchUpdate(final Connection conn, final Collection<? extends T> entities) {
            return batchUpdate(conn, entities, (Collection<String>) null);
        }

        /**
         *
         * @param conn
         * @param entities
         * @param propNamesToUpdate
         * @return
         */
        public int batchUpdate(final Connection conn, final Collection<? extends T> entities, final Collection<String> propNamesToUpdate) {
            return batchUpdate(conn, entities, propNamesToUpdate, JdbcSettings.DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param conn
         * @param entities
         * @param batchSize
         * @return
         */
        public int batchUpdate(final Connection conn, final Collection<? extends T> entities, final int batchSize) {
            return batchUpdate(conn, entities, (Collection<String>) null, batchSize);
        }

        /**
         *
         * @param conn
         * @param entities
         * @param propNamesToUpdate
         * @param batchSize
         * @return
         */
        public int batchUpdate(final Connection conn, final Collection<? extends T> entities, final Collection<String> propNamesToUpdate, final int batchSize) {
            return batchUpdate(conn, entities, propNamesToUpdate, batchSize, IsolationLevel.DEFAULT);
        }

        /**
         *
         * @param conn
         * @param entities
         * @param propNamesToUpdate
         * @param batchSize
         * @param isolationLevel
         * @return
         */
        private int batchUpdate(final Connection conn, final Collection<? extends T> entities, final Collection<String> propNamesToUpdate, final int batchSize,
                final IsolationLevel isolationLevel) {
            checkIdRequired();

            N.checkArgPositive(batchSize, "batchSize");

            if (N.isNullOrEmpty(entities)) {
                return 0;
            }

            final List<T> entityList = entities instanceof List ? (List<T>) entities : new ArrayList<>(entities);
            final T entity = N.firstOrNullIfEmpty(entityList);
            final String sql = prepareUpdateSql(entity, propNamesToUpdate, true);
            final JdbcSettings jdbcSettings = JdbcSettings.create().setBatchSize(batchSize).setIsolationLevel(isolationLevel);

            final int updateCount = sqlExecutor.batchUpdate(conn, sql, StatementSetter.DEFAULT, jdbcSettings, entityList);

            //    if (isDirtyMarker) {
            //        for (Object entity : entities) {
            //            postUpdate(entity, propNamesToUpdate);
            //        }
            //    }

            return updateCount;
        }

        /**
         * Prepare update sql.
         *
         * @param entity
         * @param propNamesToUpdate
         * @param isForBatchUpdate TODO
         * @return
         */
        private String prepareUpdateSql(final T entity, final Collection<String> propNamesToUpdate, boolean isForBatchUpdate) {
            Collection<String> updatingPropNames = propNamesToUpdate;

            if (N.isNullOrEmpty(propNamesToUpdate)) {
                if (isDirtyMarker && isForBatchUpdate == false) {
                    updatingPropNames = DirtyMarkerUtil.dirtyPropNames((DirtyMarker) entity);
                } else {
                    return sql_update_by_id;
                }
            }

            if (N.isNullOrEmpty(propNamesToUpdate)) {
                return sql_update_by_id;
            }

            return prepareUpdateSql(updatingPropNames);
        }

        /**
         *
         * @param props
         * @param whereCause
         * @return
         */
        private SP prepareUpdate(final Map<String, Object> props, final Condition whereCause) {
            N.checkArgument(N.notNullOrEmpty(props), "props");

            switch (namingPolicy) {
                case LOWER_CASE_WITH_UNDERSCORE:
                    return NSC.update(targetEntityClass).set(props).append(whereCause).pair();

                case UPPER_CASE_WITH_UNDERSCORE:
                    return NAC.update(targetEntityClass).set(props).append(whereCause).pair();

                case LOWER_CAMEL_CASE:
                    return NLC.update(targetEntityClass).set(props).append(whereCause).pair();

                default:
                    throw new RuntimeException("Unsupported naming policy: " + namingPolicy);
            }
        }

        /**
         * Prepare update sql.
         *
         * @param propNamesToUpdate
         * @return
         */
        private String prepareUpdateSql(final Collection<String> propNamesToUpdate) {
            N.checkArgument(N.notNullOrEmpty(propNamesToUpdate), "propNamesToUpdate");

            switch (namingPolicy) {
                case LOWER_CASE_WITH_UNDERSCORE:
                    return NSC.update(targetEntityClass).set(propNamesToUpdate).where(idCond).sql();

                case UPPER_CASE_WITH_UNDERSCORE:
                    return NAC.update(targetEntityClass).set(propNamesToUpdate).where(idCond).sql();

                case LOWER_CAMEL_CASE:
                    return NLC.update(targetEntityClass).set(propNamesToUpdate).where(idCond).sql();

                default:
                    throw new RuntimeException("Unsupported naming policy: " + namingPolicy);
            }
        }

        //    @SuppressWarnings("deprecation")
        //    private void postUpdate(final Object entity, final Collection<String> propNamesToUpdate) {
        //        if (isDirtyMarker) {
        //            if (propNamesToUpdate == null) {
        //                ((DirtyMarker) entity).markDirty(false);
        //            } else {
        //                ((DirtyMarker) entity).markDirty(propNamesToUpdate, false);
        //            }
        //        }
        //    }

        /**
         *
         * @param whereCause
         * @return
         */
        public int delete(final Condition whereCause) {
            return delete(null, whereCause);
        }

        /**
         *
         * @param conn
         * @param whereCause
         * @return
         */
        public int delete(final Connection conn, final Condition whereCause) {
            N.checkArgNotNull(whereCause);

            final SP sp = prepareDelete(whereCause);

            return sqlExecutor.update(conn, sp.sql, JdbcUtil.getParameterArray(sp));
        }

        /**
         *
         * @param entity
         * @return
         */
        public int delete(final T entity) {
            return delete(null, entity);
        }

        /**
         *
         * @param conn
         * @param entity
         * @return
         */
        public int delete(final Connection conn, final T entity) {
            checkIdRequired();

            N.checkArgNotNull(entity);

            return sqlExecutor.update(conn, sql_delete_by_id, entity);
        }

        /**
         * Delete all the records by batch operation.
         *
         * @param entities
         * @return
         */
        public int batchDelete(final Collection<? extends T> entities) {
            return batchDelete(entities, JdbcSettings.DEFAULT_BATCH_SIZE);
        }

        /**
         * Delete all the records by batch operation.
         *
         * @param entities
         * @param batchSize Default value is 200.
         * @return
         */
        public int batchDelete(final Collection<? extends T> entities, final int batchSize) {
            return batchDelete(entities, batchSize, IsolationLevel.DEFAULT);
        }

        /**
         * Delete all the records by batch operation.
         *
         * @param entities
         * @param batchSize Default value is 200.
         * @param isolationLevel
         * @return
         */
        public int batchDelete(final Collection<? extends T> entities, final int batchSize, final IsolationLevel isolationLevel) {
            return batchDelete(null, entities, batchSize, isolationLevel);
        }

        /**
         * Delete all the records by batch operation.
         *
         * @param conn
         * @param entities
         * @return
         */
        public int batchDelete(final Connection conn, final Collection<? extends T> entities) {
            return batchDelete(conn, entities, JdbcSettings.DEFAULT_BATCH_SIZE);
        }

        /**
         * Delete all the records by batch operation.
         *
         * @param conn
         * @param entities
         * @param batchSize
         * @return
         */
        public int batchDelete(final Connection conn, final Collection<? extends T> entities, final int batchSize) {
            return batchDelete(conn, entities, batchSize, IsolationLevel.DEFAULT);
        }

        /**
         *
         * @param conn
         * @param entities
         * @param batchSize
         * @param isolationLevel
         * @return
         */
        private int batchDelete(final Connection conn, final Collection<? extends T> entities, final int batchSize, final IsolationLevel isolationLevel) {
            checkIdRequired();

            N.checkArgPositive(batchSize, "batchSize");

            if (N.isNullOrEmpty(entities)) {
                return 0;
            }

            final List<T> entityList = entities instanceof List ? ((List<T>) entities) : N.newArrayList(entities);
            final JdbcSettings jdbcSettings = JdbcSettings.create().setBatchSize(batchSize).setIsolationLevel(isolationLevel);

            return sqlExecutor.batchUpdate(conn, sql_delete_by_id, jdbcSettings, entityList);
        }

        /**
         *
         * @param whereCause
         * @return
         */
        private SP prepareDelete(final Condition whereCause) {
            SP sp = null;

            switch (namingPolicy) {
                case LOWER_CASE_WITH_UNDERSCORE:
                    sp = NSC.deleteFrom(targetEntityClass).append(whereCause).pair();

                    break;

                case UPPER_CASE_WITH_UNDERSCORE:
                    sp = NAC.deleteFrom(targetEntityClass).append(whereCause).pair();

                    break;

                case LOWER_CAMEL_CASE:
                    sp = NLC.deleteFrom(targetEntityClass).append(whereCause).pair();

                    break;

                default:
                    throw new RuntimeException("Unsupported naming policy: " + namingPolicy);
            }

            return sp;
        }

        /**
         * Delete by id.
         *
         * @param id
         * @return
         */
        public int deleteById(final ID id) {
            return deleteById(null, id);
        }

        /**
         * Delete by id.
         *
         * @param conn
         * @param id
         * @return
         */
        public int deleteById(final Connection conn, final ID id) {
            checkIdRequired();

            N.checkArgNotNull(id);

            return sqlExecutor.update(conn, sql_delete_by_id, id);
        }

        /**
         * Delete all the records by batch operation.
         *
         * @param ids
         * @return
         */
        public int batchDeleteByIds(final Collection<? extends ID> ids) {
            return batchDeleteByIds(ids, JdbcSettings.DEFAULT_BATCH_SIZE);
        }

        /**
         * Delete all the records by batch operation.
         *
         * @param ids
         * @param batchSize Default value is 200.
         * @return
         */
        public int batchDeleteByIds(final Collection<? extends ID> ids, final int batchSize) {
            return batchDeleteByIds(ids, batchSize, IsolationLevel.DEFAULT);
        }

        /**
         * Delete all the records by batch operation.
         *
         * @param ids
         * @param batchSize Default value is 200.
         * @param isolationLevel
         * @return
         */
        public int batchDeleteByIds(final Collection<? extends ID> ids, final int batchSize, final IsolationLevel isolationLevel) {
            return batchDeleteByIds(null, ids, batchSize, isolationLevel);
        }

        /**
         * Delete all the records by batch operation.
         *
         * @param conn
         * @param ids
         * @return
         */
        public int batchDeleteByIds(final Connection conn, final Collection<? extends ID> ids) {
            return batchDeleteByIds(conn, ids, JdbcSettings.DEFAULT_BATCH_SIZE);
        }

        /**
         * Delete all the records by batch operation.
         *
         * @param conn
         * @param ids
         * @param batchSize
         * @return
         */
        public int batchDeleteByIds(final Connection conn, final Collection<? extends ID> ids, final int batchSize) {
            return batchDeleteByIds(conn, ids, batchSize, IsolationLevel.DEFAULT);
        }

        /**
         * Batch delete by ids.
         *
         * @param conn
         * @param ids
         * @param batchSize
         * @param isolationLevel
         * @return
         */
        private int batchDeleteByIds(final Connection conn, final Collection<? extends ID> ids, final int batchSize, final IsolationLevel isolationLevel) {
            checkIdRequired();

            N.checkArgPositive(batchSize, "batchSize");

            if (N.isNullOrEmpty(ids)) {
                return 0;
            }

            final List<ID> idList = ids instanceof List ? ((List<ID>) ids) : N.newArrayList(ids);

            final JdbcSettings jdbcSettings = JdbcSettings.create().setBatchSize(batchSize).setIsolationLevel(isolationLevel);

            return sqlExecutor.batchUpdate(conn, sql_delete_by_id, jdbcSettings, idList);
        }

        /**
         * Id 2 cond.
         *
         * @param id
         * @return
         */
        private Condition id2Cond(final Object id) {
            checkIdRequired();

            if (isEntityId) {
                return CF.id2Cond((EntityId) id);
            } else {
                return CF.eq(oneIdPropName, id);
            }
        }

        private void checkIdRequired() {
            if (isNoId) {
                throw new UnsupportedOperationException("Id is not defined for operations with ID parameter");
            }
        }

        /**
         *
         * @param <R>
         * @param func
         * @return
         */
        @Beta
        public <R> ContinuableFuture<R> asyncCall(final Function<Mapper<T, ID>, R> func) {
            N.checkArgNotNull(func, "func");

            return sqlExecutor._asyncExecutor.execute(() -> func.apply(this));
        }

        /**
         *
         * @param <R>
         * @param func
         * @param executor
         * @return
         */
        @Beta
        public <R> ContinuableFuture<R> asyncCall(final Function<Mapper<T, ID>, R> func, final Executor executor) {
            N.checkArgNotNull(func, "func");
            N.checkArgNotNull(executor, "executor");

            return ContinuableFuture.call(() -> func.apply(this), executor);
        }

        /**
         *
         * @param action
         * @return
         */
        @Beta
        public ContinuableFuture<Void> asyncRun(final Consumer<Mapper<T, ID>> action) {
            N.checkArgNotNull(action, "action");

            return sqlExecutor._asyncExecutor.execute(() -> action.accept(this));
        }

        /**
         *
         * @param action
         * @param executor
         * @return
         */
        @Beta
        public ContinuableFuture<Void> asyncRun(final Consumer<Mapper<T, ID>> action, final Executor executor) {
            N.checkArgNotNull(action, "action");
            N.checkArgNotNull(executor, "executor");

            return ContinuableFuture.run(() -> action.accept(this), executor);
        }

        /**
         *
         * @return
         */
        public String toStirng() {
            return "Mapper[" + ClassUtil.getCanonicalClassName(targetEntityClass) + "]";
        }
    }

    public static class MapperL<T> extends Mapper<T, Long> {
        MapperL(final Class<T> entityClass, final SQLExecutor sqlExecutor, final NamingPolicy namingPolicy) {
            super(entityClass, Long.class, sqlExecutor, namingPolicy);
        }

        public boolean exists(final long id) {
            return exists(Long.valueOf(id));
        }

        public boolean exists(final Connection conn, final long id) {
            return exists(conn, Long.valueOf(id));
        }

        public Optional<T> get(final long id) throws DuplicatedResultException {
            return get(Long.valueOf(id));
        }

        public Optional<T> get(final long id, final Collection<String> selectPropNames) throws DuplicatedResultException {
            return get(Long.valueOf(id), selectPropNames);
        }

        public Optional<T> get(final Connection conn, final long id, final Collection<String> selectPropNames) throws DuplicatedResultException {
            return get(conn, Long.valueOf(id), selectPropNames);
        }

        public T gett(final long id) throws DuplicatedResultException {
            return gett(Long.valueOf(id));
        }

        public T gett(final long id, final Collection<String> selectPropNames) throws DuplicatedResultException {
            return gett(Long.valueOf(id), selectPropNames);
        }

        public T gett(final Connection conn, final long id, final Collection<String> selectPropNames) throws DuplicatedResultException {
            return gett(conn, Long.valueOf(id), selectPropNames);
        }

        public <V> Nullable<V> queryForSingleResult(final Class<V> targetValueClass, final String singleSelectPropName, final long id)
                throws DuplicatedResultException {
            return queryForSingleResult(targetValueClass, singleSelectPropName, Long.valueOf(id));
        }

        public <V> Nullable<V> queryForSingleResult(final Class<V> targetValueClass, final Connection conn, final String singleSelectPropName, final long id)
                throws DuplicatedResultException {
            return queryForSingleResult(targetValueClass, conn, singleSelectPropName, Long.valueOf(id));
        }

        public <V> Nullable<V> queryForUniqueResult(final Class<V> targetValueClass, final String singleSelectPropName, final long id)
                throws DuplicatedResultException {
            return queryForUniqueResult(targetValueClass, singleSelectPropName, Long.valueOf(id));
        }

        public <V> Nullable<V> queryForUniqueResult(final Class<V> targetValueClass, final Connection conn, final String singleSelectPropName, final long id)
                throws DuplicatedResultException {
            return queryForUniqueResult(targetValueClass, conn, singleSelectPropName, Long.valueOf(id));
        }

        public int update(final String propName, final Object propValue, final long id) {
            return update(propName, propValue, Long.valueOf(id));
        }

        public int update(final Connection conn, final String propName, final Object propValue, final long id) {
            return update(conn, propName, propValue, Long.valueOf(id));
        }

        public int update(final Map<String, Object> props, final long id) {
            return update(props, Long.valueOf(id));
        }

        public int update(final Connection conn, final Map<String, Object> props, final long id) {
            return update(conn, props, Long.valueOf(id));
        }

        public int deleteById(final long id) {
            return deleteById(Long.valueOf(id));
        }

        public int deleteById(final Connection conn, final long id) {
            return deleteById(conn, Long.valueOf(id));
        }
    }

    public static class MapperEx<T, ID> extends Mapper<T, ID> {

        MapperEx(final Class<T> entityClass, final Class<ID> idClass, final SQLExecutor sqlExecutor, final NamingPolicy namingPolicy) {
            super(entityClass, idClass, sqlExecutor, namingPolicy);
        }

        /**
         *
         * @param entity
         * @param joinEntityClass
         */
        public void loadJoinEntities(final T entity, final Class<?> joinEntityClass) {
            loadJoinEntities(entity, joinEntityClass, null);
        }

        /**
         *
         * @param entity
         * @param joinEntityClass
         * @param selectPropNames
         */
        public void loadJoinEntities(final T entity, final Class<?> joinEntityClass, final Collection<String> selectPropNames) {
            final List<String> joinEntityPropNames = JoinInfo.getJoinEntityPropNamesByType(Mapper.class, targetEntityClass, joinEntityClass);

            N.checkArgument(N.notNullOrEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

            for (String joinEntityPropName : joinEntityPropNames) {
                loadJoinEntities(entity, joinEntityPropName, selectPropNames);
            }
        }

        /**
         *
         * @param entities
         * @param joinEntityClass
         */
        public void loadJoinEntities(final Collection<? extends T> entities, final Class<?> joinEntityClass) {
            loadJoinEntities(entities, joinEntityClass, null);
        }

        /**
         *
         * @param entities
         * @param joinEntityClass
         * @param selectPropNames
         */
        public void loadJoinEntities(final Collection<? extends T> entities, final Class<?> joinEntityClass, final Collection<String> selectPropNames) {
            if (N.isNullOrEmpty(entities)) {
                return;
            }

            final List<String> joinEntityPropNames = JoinInfo.getJoinEntityPropNamesByType(Mapper.class, targetEntityClass, joinEntityClass);
            N.checkArgument(N.notNullOrEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

            for (String joinEntityPropName : joinEntityPropNames) {
                loadJoinEntities(entities, joinEntityPropName, selectPropNames);
            }
        }

        /**
         *
         * @param entity
         * @param joinEntityPropName
         */
        public void loadJoinEntities(final T entity, final String joinEntityPropName) {
            loadJoinEntities(entity, joinEntityPropName, null);
        }

        /**
         *
         * @param entity
         * @param joinEntityPropName
         * @param selectPropNames
         */
        public void loadJoinEntities(final T entity, final String joinEntityPropName, final Collection<String> selectPropNames) {
            final JoinInfo propJoinInfo = JoinInfo.getPropJoinInfo(Mapper.class, targetEntityClass, joinEntityPropName);
            final Tuple2<Function<Collection<String>, String>, BiParametersSetter<PreparedStatement, Object>> tp = propJoinInfo
                    .getSelectSQLBuilderAndParamSetter(sbc);

            final String sql = tp._1.apply(selectPropNames);
            final StatementSetter statementSetter = (parsedSql, stmt, parameters) -> tp._2.accept(stmt, entity);
            Object propValue = null;

            if (propJoinInfo.joinPropInfo.type.isCollection()) {
                final List<?> propEntities = sqlExecutor.list(propJoinInfo.referencedEntityClass, sql, statementSetter);

                if (propJoinInfo.joinPropInfo.clazz.isAssignableFrom(propEntities.getClass())) {
                    propValue = propEntities;
                } else {
                    final Collection<Object> c = (Collection<Object>) N.newInstance(propJoinInfo.joinPropInfo.clazz);
                    c.addAll(propEntities);
                    propValue = c;
                }
            } else {
                propValue = sqlExecutor.findFirst(propJoinInfo.referencedEntityClass, sql, statementSetter).orNull();
            }

            propJoinInfo.joinPropInfo.setPropValue(entity, propValue);

            if (isDirtyMarker) {
                DirtyMarkerUtil.markDirty((DirtyMarker) entity, propJoinInfo.joinPropInfo.name, false);
            }
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         */
        public void loadJoinEntities(final Collection<? extends T> entities, final String joinEntityPropName) {
            loadJoinEntities(entities, joinEntityPropName, null);
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param selectPropNames
         */
        public void loadJoinEntities(final Collection<? extends T> entities, final String joinEntityPropName, final Collection<String> selectPropNames) {
            if (N.isNullOrEmpty(entities)) {
                return;
            } else if (entities.size() == 1) {
                loadJoinEntities(N.firstOrNullIfEmpty(entities), joinEntityPropName, selectPropNames);
            } else {
                final JoinInfo propJoinInfo = JoinInfo.getPropJoinInfo(Mapper.class, targetEntityClass, joinEntityPropName);
                final Tuple2<BiFunction<Collection<String>, Integer, String>, BiParametersSetter<PreparedStatement, Collection<?>>> tp = propJoinInfo
                        .getSelectSQLBuilderAndParamSetterForBatch(sbc);

                final String sql = tp._1.apply(selectPropNames, entities.size());
                final StatementSetter statementSetter = (parsedSql, stmt, parameters) -> tp._2.accept(stmt, entities);

                if (propJoinInfo.isManyToManyJoin()) {
                    final BiRowMapper<Object> biRowMapper = BiRowMapper.to(propJoinInfo.referencedEntityClass, true);
                    final BiRowMapper<Pair<Object, Object>> pairBiRowMapper = (rs, cls) -> Pair.of(rs.getObject(1), biRowMapper.apply(rs, cls));

                    final List<Pair<Object, Object>> joinPropEntities = sqlExecutor.list(sql, statementSetter, pairBiRowMapper);

                    propJoinInfo.setJoinPropEntities(entities, Stream.of(joinPropEntities).groupTo(it -> it.left, it -> it.right));
                } else {
                    final List<?> joinPropEntities = sqlExecutor.list(propJoinInfo.referencedEntityClass, sql, statementSetter);

                    propJoinInfo.setJoinPropEntities(entities, joinPropEntities);
                }
            }
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         */
        public void loadJoinEntities(final T entity, final Collection<String> joinEntityPropNames) {
            if (N.isNullOrEmpty(joinEntityPropNames)) {
                return;
            }

            for (String joinEntityPropName : joinEntityPropNames) {
                loadJoinEntities(entity, joinEntityPropName);
            }
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @param inParallel
         */
        public void loadJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final boolean inParallel) {
            if (inParallel) {
                loadJoinEntities(entity, joinEntityPropNames, sqlExecutor._asyncExecutor.getExecutor());
            } else {
                loadJoinEntities(entity, joinEntityPropNames);
            }
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @param executor
         */
        public void loadJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final Executor executor) {
            if (N.isNullOrEmpty(joinEntityPropNames)) {
                return;
            }

            final List<ContinuableFuture<Void>> futures = Stream.of(joinEntityPropNames)
                    .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntities(entity, joinEntityPropName), executor))
                    .toList();

            complete(futures);
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         */
        public void loadJoinEntities(final Collection<? extends T> entities, final Collection<String> joinEntityPropNames) {
            if (N.isNullOrEmpty(entities) || N.isNullOrEmpty(joinEntityPropNames)) {
                return;
            }

            for (String joinEntityPropName : joinEntityPropNames) {
                loadJoinEntities(entities, joinEntityPropName);
            }
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param inParallel
         */
        public void loadJoinEntities(final Collection<? extends T> entities, final Collection<String> joinEntityPropNames, final boolean inParallel) {
            if (inParallel) {
                loadJoinEntities(entities, joinEntityPropNames, sqlExecutor._asyncExecutor.getExecutor());
            } else {
                loadJoinEntities(entities, joinEntityPropNames);
            }
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param executor
         */
        public void loadJoinEntities(final Collection<? extends T> entities, final Collection<String> joinEntityPropNames, final Executor executor) {
            if (N.isNullOrEmpty(entities) || N.isNullOrEmpty(joinEntityPropNames)) {
                return;
            }

            final List<ContinuableFuture<Void>> futures = Stream.of(joinEntityPropNames)
                    .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntities(entities, joinEntityPropName), executor))
                    .toList();

            complete(futures);
        }

        /**
         *
         * @param entity
         */
        public void loadAllJoinEntities(T entity) {
            loadJoinEntities(entity, JoinInfo.getEntityJoinInfo(Mapper.class, targetEntityClass).keySet());
        }

        /**
         *
         * @param entity
         * @param inParallel
         */
        public void loadAllJoinEntities(final T entity, final boolean inParallel) {
            if (inParallel) {
                loadAllJoinEntities(entity, sqlExecutor._asyncExecutor.getExecutor());
            } else {
                loadAllJoinEntities(entity);
            }
        }

        /**
         *
         * @param entity
         * @param executor
         */
        public void loadAllJoinEntities(final T entity, final Executor executor) {
            loadJoinEntities(entity, JoinInfo.getEntityJoinInfo(Mapper.class, targetEntityClass).keySet(), executor);
        }

        /**
         *
         * @param entities
         */
        public void loadAllJoinEntities(final Collection<? extends T> entities) {
            if (N.isNullOrEmpty(entities)) {
                return;
            }

            loadJoinEntities(entities, JoinInfo.getEntityJoinInfo(Mapper.class, targetEntityClass).keySet());
        }

        /**
         *
         * @param entities
         * @param inParallel
         */
        public void loadAllJoinEntities(final Collection<? extends T> entities, final boolean inParallel) {
            if (inParallel) {
                loadAllJoinEntities(entities, sqlExecutor._asyncExecutor.getExecutor());
            } else {
                loadAllJoinEntities(entities);
            }
        }

        /**
         *
         * @param entities
         * @param executor
         */
        public void loadAllJoinEntities(final Collection<? extends T> entities, final Executor executor) {
            if (N.isNullOrEmpty(entities)) {
                return;
            }

            loadJoinEntities(entities, JoinInfo.getEntityJoinInfo(Mapper.class, targetEntityClass).keySet(), executor);
        }

        /**
         *
         * @param entity
         * @param joinEntityClass
         */
        public void loadJoinEntitiesIfNull(final T entity, final Class<?> joinEntityClass) {
            loadJoinEntitiesIfNull(entity, joinEntityClass, null);
        }

        /**
         *
         * @param entity
         * @param joinEntityClass
         * @param selectPropNames
         */
        public void loadJoinEntitiesIfNull(final T entity, final Class<?> joinEntityClass, final Collection<String> selectPropNames) {
            final List<String> joinEntityPropNames = JoinInfo.getJoinEntityPropNamesByType(Mapper.class, entity.getClass(), joinEntityClass);
            N.checkArgument(N.notNullOrEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, entity.getClass());

            for (String joinEntityPropName : joinEntityPropNames) {
                loadJoinEntitiesIfNull(entity, joinEntityPropName, selectPropNames);
            }
        }

        /**
         *
         * @param entities
         * @param joinEntityClass
         */
        public void loadJoinEntitiesIfNull(final Collection<? extends T> entities, final Class<?> joinEntityClass) {
            loadJoinEntitiesIfNull(entities, joinEntityClass, null);
        }

        /**
         *
         * @param entities
         * @param joinEntityClass
         * @param selectPropNames
         */
        public void loadJoinEntitiesIfNull(final Collection<? extends T> entities, final Class<?> joinEntityClass, final Collection<String> selectPropNames) {
            if (N.isNullOrEmpty(entities)) {
                return;
            }

            final Class<?> entityClass = N.firstOrNullIfEmpty(entities).getClass();
            final List<String> joinEntityPropNames = JoinInfo.getJoinEntityPropNamesByType(Mapper.class, entityClass, joinEntityClass);
            N.checkArgument(N.notNullOrEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, entityClass);

            for (String joinEntityPropName : joinEntityPropNames) {
                loadJoinEntitiesIfNull(entities, joinEntityPropName, selectPropNames);
            }
        }

        /**
         *
         * @param entity
         * @param joinEntityPropName
         */
        public void loadJoinEntitiesIfNull(final T entity, final String joinEntityPropName) {
            loadJoinEntitiesIfNull(entity, joinEntityPropName, null);
        }

        /**
         *
         * @param entity
         * ?
         * @param joinEntityPropName
         * @param selectPropNames
         */
        public void loadJoinEntitiesIfNull(final T entity, final String joinEntityPropName, final Collection<String> selectPropNames) {
            final Class<?> cls = entity.getClass();
            final PropInfo propInfo = ParserUtil.getEntityInfo(cls).getPropInfo(joinEntityPropName);

            if (propInfo.getPropValue(entity) == null) {
                loadJoinEntities(entity, joinEntityPropName, selectPropNames);
            }
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         */
        public void loadJoinEntitiesIfNull(final Collection<? extends T> entities, final String joinEntityPropName) {
            loadJoinEntitiesIfNull(entities, joinEntityPropName, null);
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param selectPropNames
         */
        public void loadJoinEntitiesIfNull(final Collection<? extends T> entities, final String joinEntityPropName, final Collection<String> selectPropNames) {
            if (N.isNullOrEmpty(entities)) {
                return;
            }

            final Class<?> cls = N.firstOrNullIfEmpty(entities).getClass();
            final PropInfo propInfo = ParserUtil.getEntityInfo(cls).getPropInfo(joinEntityPropName);
            final List<T> newEntities = N.filter(entities, entity -> propInfo.getPropValue(entity) == null);

            loadJoinEntities(newEntities, joinEntityPropName, selectPropNames);
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         */
        public void loadJoinEntitiesIfNull(final T entity, final Collection<String> joinEntityPropNames) {
            if (N.isNullOrEmpty(joinEntityPropNames)) {
                return;
            }

            for (String joinEntityPropName : joinEntityPropNames) {
                loadJoinEntitiesIfNull(entity, joinEntityPropName);
            }
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @param inParallel
         */
        public void loadJoinEntitiesIfNull(final T entity, final Collection<String> joinEntityPropNames, final boolean inParallel) {
            if (inParallel) {
                loadJoinEntitiesIfNull(entity, joinEntityPropNames, sqlExecutor._asyncExecutor.getExecutor());
            } else {
                loadJoinEntitiesIfNull(entity, joinEntityPropNames);
            }
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @param executor
         */
        public void loadJoinEntitiesIfNull(final T entity, final Collection<String> joinEntityPropNames, final Executor executor) {
            if (N.isNullOrEmpty(joinEntityPropNames)) {
                return;
            }

            final List<ContinuableFuture<Void>> futures = Stream.of(joinEntityPropNames)
                    .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntitiesIfNull(entity, joinEntityPropName), executor))
                    .toList();

            complete(futures);
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         */
        public void loadJoinEntitiesIfNull(final Collection<? extends T> entities, final Collection<String> joinEntityPropNames) {
            if (N.isNullOrEmpty(entities) || N.isNullOrEmpty(joinEntityPropNames)) {
                return;
            }

            for (String joinEntityPropName : joinEntityPropNames) {
                loadJoinEntitiesIfNull(entities, joinEntityPropName);
            }
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param inParallel
         */
        public void loadJoinEntitiesIfNull(final Collection<? extends T> entities, final Collection<String> joinEntityPropNames, final boolean inParallel) {
            if (inParallel) {
                loadJoinEntitiesIfNull(entities, joinEntityPropNames, sqlExecutor._asyncExecutor.getExecutor());
            } else {
                loadJoinEntitiesIfNull(entities, joinEntityPropNames);
            }
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param executor
         */
        public void loadJoinEntitiesIfNull(final Collection<? extends T> entities, final Collection<String> joinEntityPropNames, final Executor executor) {
            if (N.isNullOrEmpty(entities) || N.isNullOrEmpty(joinEntityPropNames)) {
                return;
            }

            final List<ContinuableFuture<Void>> futures = Stream.of(joinEntityPropNames)
                    .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntitiesIfNull(entities, joinEntityPropName), executor))
                    .toList();

            complete(futures);
        }

        /**
         *
         * @param entity
         */
        public void loadJoinEntitiesIfNull(T entity) {
            loadJoinEntitiesIfNull(entity, JoinInfo.getEntityJoinInfo(Mapper.class, targetEntityClass).keySet());
        }

        /**
         *
         * @param entity
         * @param inParallel
         */
        public void loadJoinEntitiesIfNull(final T entity, final boolean inParallel) {
            if (inParallel) {
                loadJoinEntitiesIfNull(entity, sqlExecutor._asyncExecutor.getExecutor());
            } else {
                loadJoinEntitiesIfNull(entity);
            }
        }

        /**
         *
         * @param entity
         * @param executor
         */
        public void loadJoinEntitiesIfNull(final T entity, final Executor executor) {
            loadJoinEntitiesIfNull(entity, JoinInfo.getEntityJoinInfo(Mapper.class, targetEntityClass).keySet(), executor);
        }

        /**
         *
         * @param entities
         */
        public void loadJoinEntitiesIfNull(final Collection<? extends T> entities) {
            if (N.isNullOrEmpty(entities)) {
                return;
            }

            loadJoinEntitiesIfNull(entities, JoinInfo.getEntityJoinInfo(Mapper.class, targetEntityClass).keySet());
        }

        /**
         *
         * @param entities
         * @param inParallel
         */
        public void loadJoinEntitiesIfNull(final Collection<? extends T> entities, final boolean inParallel) {
            if (inParallel) {
                loadJoinEntitiesIfNull(entities, sqlExecutor._asyncExecutor.getExecutor());
            } else {
                loadJoinEntitiesIfNull(entities);
            }
        }

        /**
         *
         * @param entities
         * @param executor
         */
        public void loadJoinEntitiesIfNull(final Collection<? extends T> entities, final Executor executor) {
            if (N.isNullOrEmpty(entities)) {
                return;
            }

            loadJoinEntitiesIfNull(entities, JoinInfo.getEntityJoinInfo(Mapper.class, targetEntityClass).keySet(), executor);
        }

        /**
         *
         * @param entity
         * @param joinEntityClass
         * @return the total count of updated/deleted records.
         * @throws UncheckedSQLException the SQL exception
         */
        public int deleteJoinEntities(final T entity, final Class<?> joinEntityClass) throws UncheckedSQLException {
            final List<String> joinEntityPropNames = JoinInfo.getJoinEntityPropNamesByType(Mapper.class, targetEntityClass, joinEntityClass);
            N.checkArgument(N.notNullOrEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

            int result = 0;

            for (String joinEntityPropName : joinEntityPropNames) {
                result += deleteJoinEntities(entity, joinEntityPropName);
            }

            return result;
        }

        /**
         *
         * @param entities
         * @param joinEntityClass
         * @return the total count of updated/deleted records.
         * @throws UncheckedSQLException the SQL exception
         */
        public int deleteJoinEntities(final Collection<T> entities, final Class<?> joinEntityClass) throws UncheckedSQLException {
            if (N.isNullOrEmpty(entities)) {
                return 0;
            }

            final List<String> joinEntityPropNames = JoinInfo.getJoinEntityPropNamesByType(Mapper.class, targetEntityClass, joinEntityClass);
            N.checkArgument(N.notNullOrEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

            int result = 0;

            for (String joinEntityPropName : joinEntityPropNames) {
                result += deleteJoinEntities(entities, joinEntityPropName);
            }

            return result;
        }

        /**
         *
         * @param entity
         * @param joinEntityPropName
         * @param selectPropNames
         * @return the total count of updated/deleted records.
         * @throws UncheckedSQLException the SQL exception
         */
        public int deleteJoinEntities(final T entity, final String joinEntityPropName) throws UncheckedSQLException {
            final JoinInfo propJoinInfo = JoinInfo.getPropJoinInfo(Mapper.class, targetEntityClass, joinEntityPropName);
            final Tuple2<String, BiParametersSetter<PreparedStatement, Object>> tp = propJoinInfo.getDeleteSqlAndParamSetter(sbc);

            final StatementSetter statementSetter = (parsedSql, stmt, parameters) -> tp._2.accept(stmt, entity);

            return sqlExecutor.update(tp._1, statementSetter);
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param selectPropNames
         * @return the total count of updated/deleted records.
         * @throws UncheckedSQLException the SQL exception
         */
        public int deleteJoinEntities(final Collection<T> entities, final String joinEntityPropName) throws UncheckedSQLException {
            final JoinInfo propJoinInfo = JoinInfo.getPropJoinInfo(Mapper.class, targetEntityClass, joinEntityPropName);
            final Tuple2<String, BiParametersSetter<PreparedStatement, Object>> tp = propJoinInfo.getDeleteSqlAndParamSetter(sbc);

            final StatementSetter statementSetter = (parsedSql, stmt, parameters) -> tp._2.accept(stmt, parameters[0]);
            final List<T> parametersList = entities instanceof List ? (List<T>) entities : new ArrayList<>(entities);

            return sqlExecutor.batchUpdate(tp._1, statementSetter, parametersList);
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @return the total count of updated/deleted records.
         * @throws UncheckedSQLException the SQL exception
         */
        public int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames) throws UncheckedSQLException {
            if (N.isNullOrEmpty(joinEntityPropNames)) {
                return 0;
            }

            int result = 0;

            for (String joinEntityPropName : joinEntityPropNames) {
                result += deleteJoinEntities(entity, joinEntityPropName);
            }

            return result;
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @param inParallel
         * @return the total count of updated/deleted records.
         * @throws UncheckedSQLException the SQL exception
         */
        public int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final boolean inParallel) throws UncheckedSQLException {
            if (inParallel) {
                return deleteJoinEntities(entity, joinEntityPropNames, sqlExecutor._asyncExecutor.getExecutor());
            } else {
                return deleteJoinEntities(entity, joinEntityPropNames);
            }
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @param executor
         * @return the total count of updated/deleted records.
         * @throws UncheckedSQLException the SQL exception
         */
        public int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final Executor executor) throws UncheckedSQLException {
            if (N.isNullOrEmpty(joinEntityPropNames)) {
                return 0;
            }

            final List<ContinuableFuture<Integer>> futures = StreamE.of(joinEntityPropNames, UncheckedSQLException.class)
                    .map(joinEntityPropName -> ContinuableFuture.call(() -> deleteJoinEntities(entity, joinEntityPropName), executor))
                    .toList();

            return completeSum(futures);
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @return the total count of updated/deleted records.
         * @throws UncheckedSQLException the SQL exception
         */
        public int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames) throws UncheckedSQLException {
            if (N.isNullOrEmpty(entities) || N.isNullOrEmpty(joinEntityPropNames)) {
                return 0;
            }

            int result = 0;

            for (String joinEntityPropName : joinEntityPropNames) {
                result += deleteJoinEntities(entities, joinEntityPropName);
            }

            return result;
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param inParallel
         * @return the total count of updated/deleted records.
         * @throws UncheckedSQLException the SQL exception
         */
        public int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final boolean inParallel)
                throws UncheckedSQLException {
            if (inParallel) {
                return deleteJoinEntities(entities, joinEntityPropNames, sqlExecutor._asyncExecutor.getExecutor());
            } else {
                return deleteJoinEntities(entities, joinEntityPropNames);
            }
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param executor
         * @return the total count of updated/deleted records.
         * @throws UncheckedSQLException the SQL exception
         */
        public int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final Executor executor)
                throws UncheckedSQLException {
            if (N.isNullOrEmpty(entities) || N.isNullOrEmpty(joinEntityPropNames)) {
                return 0;
            }

            final List<ContinuableFuture<Integer>> futures = StreamE.of(joinEntityPropNames, UncheckedSQLException.class)
                    .map(joinEntityPropName -> ContinuableFuture.call(() -> deleteJoinEntities(entities, joinEntityPropName), executor))
                    .toList();

            return completeSum(futures);
        }

        /**
         *
         * @param entity
         * @return the total count of updated/deleted records.
         * @throws UncheckedSQLException the SQL exception
         */
        public int deleteAllJoinEntities(T entity) throws UncheckedSQLException {
            return deleteJoinEntities(entity, JoinInfo.getEntityJoinInfo(Mapper.class, targetEntityClass).keySet());
        }

        /**
         *
         * @param entity
         * @param inParallel
         * @return the total count of updated/deleted records.
         * @throws UncheckedSQLException the SQL exception
         */
        public int deleteAllJoinEntities(final T entity, final boolean inParallel) throws UncheckedSQLException {
            if (inParallel) {
                return deleteAllJoinEntities(entity, sqlExecutor._asyncExecutor.getExecutor());
            } else {
                return deleteAllJoinEntities(entity);
            }
        }

        /**
         *
         * @param entity
         * @param executor
         * @return the total count of updated/deleted records.
         * @throws UncheckedSQLException the SQL exception
         */
        public int deleteAllJoinEntities(final T entity, final Executor executor) throws UncheckedSQLException {
            return deleteJoinEntities(entity, JoinInfo.getEntityJoinInfo(Mapper.class, targetEntityClass).keySet(), executor);
        }

        /**
         *
         * @param entities
         * @return the total count of updated/deleted records.
         * @throws UncheckedSQLException the SQL exception
         */
        public int deleteAllJoinEntities(final Collection<T> entities) throws UncheckedSQLException {
            if (N.isNullOrEmpty(entities)) {
                return 0;
            }

            return deleteJoinEntities(entities, JoinInfo.getEntityJoinInfo(Mapper.class, targetEntityClass).keySet());
        }

        /**
         *
         * @param entities
         * @param inParallel
         * @return the total count of updated/deleted records.
         * @throws UncheckedSQLException the SQL exception
         */
        public int deleteAllJoinEntities(final Collection<T> entities, final boolean inParallel) throws UncheckedSQLException {
            if (inParallel) {
                return deleteAllJoinEntities(entities, sqlExecutor._asyncExecutor.getExecutor());
            } else {
                return deleteAllJoinEntities(entities);
            }
        }

        /**
         *
         * @param entities
         * @param executor
         * @return the total count of updated/deleted records.
         * @throws UncheckedSQLException the SQL exception
         */
        public int deleteAllJoinEntities(final Collection<T> entities, final Executor executor) throws UncheckedSQLException {
            if (N.isNullOrEmpty(entities)) {
                return 0;
            }

            return deleteJoinEntities(entities, JoinInfo.getEntityJoinInfo(Mapper.class, targetEntityClass).keySet(), executor);
        }

        private static final Throwables.Consumer<? super Exception, RuntimeException> throwRuntimeExceptionAction = e -> {
            throw N.toRuntimeException(e);
        };

        static void complete(final List<ContinuableFuture<Void>> futures) {
            for (ContinuableFuture<Void> f : futures) {
                f.gett().ifFailure(throwRuntimeExceptionAction);
            }
        }

        static int completeSum(final List<ContinuableFuture<Integer>> futures) {
            int result = 0;
            Result<Integer, Exception> ret = null;

            for (ContinuableFuture<Integer> f : futures) {
                ret = f.gett();

                if (ret.isFailure()) {
                    throwRuntimeExceptionAction.accept(ret.getExceptionIfPresent());
                }

                result += ret.orElse(0);
            }

            return result;
        }
    }

    public static class MapperLEx<T> extends MapperEx<T, Long> {
        MapperLEx(final Class<T> entityClass, final SQLExecutor sqlExecutor, final NamingPolicy namingPolicy) {
            super(entityClass, Long.class, sqlExecutor, namingPolicy);
        }

        public boolean exists(final long id) {
            return exists(Long.valueOf(id));
        }

        public boolean exists(final Connection conn, final long id) {
            return exists(conn, Long.valueOf(id));
        }

        public Optional<T> get(final long id) throws DuplicatedResultException {
            return get(Long.valueOf(id));
        }

        public Optional<T> get(final long id, final Collection<String> selectPropNames) throws DuplicatedResultException {
            return get(Long.valueOf(id), selectPropNames);
        }

        public Optional<T> get(final Connection conn, final long id, final Collection<String> selectPropNames) throws DuplicatedResultException {
            return get(conn, Long.valueOf(id), selectPropNames);
        }

        public T gett(final long id) throws DuplicatedResultException {
            return gett(Long.valueOf(id));
        }

        public T gett(final long id, final Collection<String> selectPropNames) throws DuplicatedResultException {
            return gett(Long.valueOf(id), selectPropNames);
        }

        public T gett(final Connection conn, final long id, final Collection<String> selectPropNames) throws DuplicatedResultException {
            return gett(conn, Long.valueOf(id), selectPropNames);
        }

        public <V> Nullable<V> queryForSingleResult(final Class<V> targetValueClass, final String singleSelectPropName, final long id)
                throws DuplicatedResultException {
            return queryForSingleResult(targetValueClass, singleSelectPropName, Long.valueOf(id));
        }

        public <V> Nullable<V> queryForSingleResult(final Class<V> targetValueClass, final Connection conn, final String singleSelectPropName, final long id)
                throws DuplicatedResultException {
            return queryForSingleResult(targetValueClass, conn, singleSelectPropName, Long.valueOf(id));
        }

        public <V> Nullable<V> queryForUniqueResult(final Class<V> targetValueClass, final String singleSelectPropName, final long id)
                throws DuplicatedResultException {
            return queryForUniqueResult(targetValueClass, singleSelectPropName, Long.valueOf(id));
        }

        public <V> Nullable<V> queryForUniqueResult(final Class<V> targetValueClass, final Connection conn, final String singleSelectPropName, final long id)
                throws DuplicatedResultException {
            return queryForUniqueResult(targetValueClass, conn, singleSelectPropName, Long.valueOf(id));
        }

        public int update(final String propName, final Object propValue, final long id) {
            return update(propName, propValue, Long.valueOf(id));
        }

        public int update(final Connection conn, final String propName, final Object propValue, final long id) {
            return update(conn, propName, propValue, Long.valueOf(id));
        }

        public int update(final Map<String, Object> props, final long id) {
            return update(props, Long.valueOf(id));
        }

        public int update(final Connection conn, final Map<String, Object> props, final long id) {
            return update(conn, props, Long.valueOf(id));
        }

        public int deleteById(final long id) {
            return deleteById(Long.valueOf(id));
        }

        public int deleteById(final Connection conn, final long id) {
            return deleteById(conn, Long.valueOf(id));
        }
    }

    /**
     * Refer to http://landawn.com/introduction-to-jdbc.html about how to set parameters in <code>java.sql.PreparedStatement</code>
     *
     * @author Haiyang Li
     *
     */
    public interface StatementSetter extends Throwables.TriConsumer<ParsedSql, PreparedStatement, Object[], SQLException> {


        /** The Constant DEFAULT. */
        StatementSetter DEFAULT = new AbstractStatementSetter() {
            @SuppressWarnings("rawtypes")
            @Override
            protected void setParameters(final PreparedStatement stmt, final int parameterCount, final Object[] parameters, final Type[] parameterTypes)
                    throws SQLException {
                if (N.notNullOrEmpty(parameterTypes) && parameterTypes.length >= parameterCount) {
                    for (int i = 0; i < parameterCount; i++) {
                        parameterTypes[i].set(stmt, i + 1, parameters[i]);
                    }
                } else if (N.notNullOrEmpty(parameters) && parameters.length >= parameterCount) {
                    for (int i = 0; i < parameterCount; i++) {
                        if (parameters[i] == null) {
                            stmt.setObject(i + 1, parameters[i]);
                        } else {
                            N.typeOf(parameters[i].getClass()).set(stmt, i + 1, parameters[i]);
                        }
                    }
                }
            }
        };

        /**
         * Sets the parameters.
         *
         * @param parsedSql
         * @param stmt
         * @param parameters
         * @throws SQLException the SQL exception
         */
        @Override
        void accept(final ParsedSql parsedSql, final PreparedStatement stmt, final Object[] parameters) throws SQLException;

        static StatementSetter create(Throwables.Consumer<PreparedStatement, SQLException> stmtSetter) {
            return (parsedSql, stmt, parameters) -> stmtSetter.accept(stmt);
        }

        static StatementSetter create(Throwables.BiConsumer<NamedQuery, Object[], SQLException> stmtSetter) {
            return (parsedSql, stmt, parameters) -> stmtSetter.accept(new NamedQuery(stmt, parsedSql), parameters);
        }
    }

    /**
     * Refer to http://landawn.com/introduction-to-jdbc.html about how to read columns/rows from <code>java.sql.ResultSet</code>
     *
     * @author Haiyang Li
     * @param <T>
     */
    public interface ResultExtractor<T> extends Throwables.BiFunction<ResultSet, JdbcSettings, T, SQLException> {

        ResultExtractor<DataSet> TO_DATA_SET = (rs, jdbcSettings) -> JdbcUtil.extractData(rs, jdbcSettings.getOffset(), jdbcSettings.getCount(), false);

        /**
         * @deprecated please use {@code TO_DATA_SET}
         */
        @Deprecated
        ResultExtractor<DataSet> DATA_SET = TO_DATA_SET;

        /**
         *
         * @param rs
         * @param jdbcSettings
         * @return
         * @throws SQLException the SQL exception
         */
        @Override
        T apply(final ResultSet rs, final JdbcSettings jdbcSettings) throws SQLException;

        default <R> ResultExtractor<R> andThen(final Throwables.Function<? super T, ? extends R, SQLException> after) {
            N.checkArgNotNull(after);

            return (rs, jdbcSettings) -> after.apply(apply(rs, jdbcSettings));
        }

        static <T> ResultExtractor<T> create(Throwables.Function<ResultSet, T, SQLException> resultExtractor) {
            return (rs, jdbcSettings) -> resultExtractor.apply(rs);
        }

        static <T> ResultExtractor<T> create(Throwables.BiFunction<ResultSet, List<String>, T, SQLException> resultExtractor) {
            return (rs, jdbcSettings) -> resultExtractor.apply(rs, JdbcUtil.getColumnLabelList(rs));
        }

        static <T> ResultExtractor<T> create(Throwables.TriFunction<ResultSet, List<String>, JdbcSettings, T, SQLException> resultExtractor) {
            return (rs, jdbcSettings) -> resultExtractor.apply(rs, JdbcUtil.getColumnLabelList(rs), jdbcSettings);
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor
         * @param valueExtractor
         * @return
         */
        static <K, V> ResultExtractor<Map<K, V>> toMap(final RowMapper<K> keyExtractor, final RowMapper<V> valueExtractor) {
            return toMap(keyExtractor, valueExtractor, Suppliers.<K, V> ofMap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param supplier
         * @return
         */
        static <K, V, M extends Map<K, V>> ResultExtractor<M> toMap(final RowMapper<K> keyExtractor, final RowMapper<V> valueExtractor,
                final Supplier<? extends M> supplier) {
            return toMap(keyExtractor, valueExtractor, Fn.<V> throwingMerger(), supplier);
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor
         * @param valueExtractor
         * @param mergeFunction
         * @return
         * @see {@link Fn.throwingMerger()}
         * @see {@link Fn.replacingMerger()}
         * @see {@link Fn.ignoringMerger()}
         */
        static <K, V> ResultExtractor<Map<K, V>> toMap(final RowMapper<K> keyExtractor, final RowMapper<V> valueExtractor,
                final BinaryOperator<V> mergeFunction) {
            return toMap(keyExtractor, valueExtractor, mergeFunction, Suppliers.<K, V> ofMap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param mergeFunction
         * @param supplier
         * @return
         * @see {@link Fn.throwingMerger()}
         * @see {@link Fn.replacingMerger()}
         * @see {@link Fn.ignoringMerger()}
         */
        static <K, V, M extends Map<K, V>> ResultExtractor<M> toMap(final RowMapper<K> keyExtractor, final RowMapper<V> valueExtractor,
                final BinaryOperator<V> mergeFunction, final Supplier<? extends M> supplier) {
            N.checkArgNotNull(keyExtractor, "keyExtractor");
            N.checkArgNotNull(valueExtractor, "valueExtractor");
            N.checkArgNotNull(mergeFunction, "mergeFunction");
            N.checkArgNotNull(supplier, "supplier");

            return new ResultExtractor<M>() {
                @Override
                public M apply(final ResultSet rs, final JdbcSettings jdbcSettings) throws SQLException {
                    final int offset = jdbcSettings.getOffset();
                    int count = jdbcSettings.getCount();

                    JdbcUtil.skip(rs, offset);

                    final M result = supplier.get();

                    while (count-- > 0 && rs.next()) {
                        Maps.merge(result, keyExtractor.apply(rs), valueExtractor.apply(rs), mergeFunction);
                    }

                    return result;
                }
            };
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <A>
         * @param <D>
         * @param keyExtractor
         * @param valueExtractor
         * @param downstream
         * @return
         */
        static <K, V, A, D> ResultExtractor<Map<K, D>> toMap(final RowMapper<K> keyExtractor, final RowMapper<V> valueExtractor,
                final Collector<? super V, A, D> downstream) {
            return toMap(keyExtractor, valueExtractor, downstream, Suppliers.<K, D> ofMap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <A>
         * @param <D>
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param downstream
         * @param supplier
         * @return
         */
        static <K, V, A, D, M extends Map<K, D>> ResultExtractor<M> toMap(final RowMapper<K> keyExtractor, final RowMapper<V> valueExtractor,
                final Collector<? super V, A, D> downstream, final Supplier<? extends M> supplier) {
            N.checkArgNotNull(keyExtractor, "keyExtractor");
            N.checkArgNotNull(valueExtractor, "valueExtractor");
            N.checkArgNotNull(downstream, "downstream");
            N.checkArgNotNull(supplier, "supplier");

            return new ResultExtractor<M>() {
                @Override
                public M apply(final ResultSet rs, final JdbcSettings jdbcSettings) throws SQLException {
                    final int offset = jdbcSettings.getOffset();
                    int count = jdbcSettings.getCount();

                    JdbcUtil.skip(rs, offset);

                    final Supplier<A> downstreamSupplier = downstream.supplier();
                    final BiConsumer<A, ? super V> downstreamAccumulator = downstream.accumulator();
                    final Function<A, D> downstreamFinisher = downstream.finisher();

                    final M result = supplier.get();
                    final Map<K, A> tmp = (Map<K, A>) result;
                    K key = null;
                    A container = null;

                    while (count-- > 0 && rs.next()) {
                        key = keyExtractor.apply(rs);
                        container = tmp.get(key);

                        if (container == null) {
                            container = downstreamSupplier.get();
                            tmp.put(key, container);
                        }

                        downstreamAccumulator.accept(container, valueExtractor.apply(rs));
                    }

                    for (Map.Entry<K, D> entry : result.entrySet()) {
                        entry.setValue(downstreamFinisher.apply((A) entry.getValue()));
                    }

                    return result;
                }
            };
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor
         * @param valueExtractor
         * @return
         */
        static <K, V> ResultExtractor<Map<K, V>> toMap(final BiRowMapper<K> keyExtractor, final BiRowMapper<V> valueExtractor) {
            return toMap(keyExtractor, valueExtractor, Suppliers.<K, V> ofMap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param supplier
         * @return
         */
        static <K, V, M extends Map<K, V>> ResultExtractor<M> toMap(final BiRowMapper<K> keyExtractor, final BiRowMapper<V> valueExtractor,
                final Supplier<? extends M> supplier) {
            return toMap(keyExtractor, valueExtractor, Fn.<V> throwingMerger(), supplier);
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor
         * @param valueExtractor
         * @param mergeFunction
         * @return
         * @see {@link Fn.throwingMerger()}
         * @see {@link Fn.replacingMerger()}
         * @see {@link Fn.ignoringMerger()}
         */
        static <K, V> ResultExtractor<Map<K, V>> toMap(final BiRowMapper<K> keyExtractor, final BiRowMapper<V> valueExtractor,
                final BinaryOperator<V> mergeFunction) {
            return toMap(keyExtractor, valueExtractor, mergeFunction, Suppliers.<K, V> ofMap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param mergeFunction
         * @param supplier
         * @return
         * @see {@link Fn.throwingMerger()}
         * @see {@link Fn.replacingMerger()}
         * @see {@link Fn.ignoringMerger()}
         */
        static <K, V, M extends Map<K, V>> ResultExtractor<M> toMap(final BiRowMapper<K> keyExtractor, final BiRowMapper<V> valueExtractor,
                final BinaryOperator<V> mergeFunction, final Supplier<? extends M> supplier) {
            N.checkArgNotNull(keyExtractor, "keyExtractor");
            N.checkArgNotNull(valueExtractor, "valueExtractor");
            N.checkArgNotNull(mergeFunction, "mergeFunction");
            N.checkArgNotNull(supplier, "supplier");

            return new ResultExtractor<M>() {
                @Override
                public M apply(final ResultSet rs, final JdbcSettings jdbcSettings) throws SQLException {
                    final int offset = jdbcSettings.getOffset();
                    int count = jdbcSettings.getCount();

                    JdbcUtil.skip(rs, offset);

                    final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);
                    final M result = supplier.get();

                    while (count-- > 0 && rs.next()) {
                        Maps.merge(result, keyExtractor.apply(rs, columnLabels), valueExtractor.apply(rs, columnLabels), mergeFunction);
                    }

                    return result;
                }
            };
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <A>
         * @param <D>
         * @param keyExtractor
         * @param valueExtractor
         * @param downstream
         * @return
         */
        static <K, V, A, D> ResultExtractor<Map<K, D>> toMap(final BiRowMapper<K> keyExtractor, final BiRowMapper<V> valueExtractor,
                final Collector<? super V, A, D> downstream) {
            return toMap(keyExtractor, valueExtractor, downstream, Suppliers.<K, D> ofMap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <A>
         * @param <D>
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param downstream
         * @param supplier
         * @return
         */
        static <K, V, A, D, M extends Map<K, D>> ResultExtractor<M> toMap(final BiRowMapper<K> keyExtractor, final BiRowMapper<V> valueExtractor,
                final Collector<? super V, A, D> downstream, final Supplier<? extends M> supplier) {
            N.checkArgNotNull(keyExtractor, "keyExtractor");
            N.checkArgNotNull(valueExtractor, "valueExtractor");
            N.checkArgNotNull(downstream, "downstream");
            N.checkArgNotNull(supplier, "supplier");

            return new ResultExtractor<M>() {
                @Override
                public M apply(final ResultSet rs, final JdbcSettings jdbcSettings) throws SQLException {
                    final int offset = jdbcSettings.getOffset();
                    int count = jdbcSettings.getCount();

                    JdbcUtil.skip(rs, offset);

                    final Supplier<A> downstreamSupplier = downstream.supplier();
                    final BiConsumer<A, ? super V> downstreamAccumulator = downstream.accumulator();
                    final Function<A, D> downstreamFinisher = downstream.finisher();

                    final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);
                    final M result = supplier.get();
                    final Map<K, A> tmp = (Map<K, A>) result;
                    K key = null;
                    A container = null;

                    while (count-- > 0 && rs.next()) {
                        key = keyExtractor.apply(rs, columnLabels);
                        container = tmp.get(key);

                        if (container == null) {
                            container = downstreamSupplier.get();
                            tmp.put(key, container);
                        }

                        downstreamAccumulator.accept(container, valueExtractor.apply(rs, columnLabels));
                    }

                    for (Map.Entry<K, D> entry : result.entrySet()) {
                        entry.setValue(downstreamFinisher.apply((A) entry.getValue()));
                    }

                    return result;
                }
            };
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor
         * @param valueExtractor
         * @return
         */
        static <K, V> ResultExtractor<ListMultimap<K, V>> toMultimap(final RowMapper<K> keyExtractor, final RowMapper<V> valueExtractor) {
            return toMultimap(keyExtractor, valueExtractor, Suppliers.<K, V> ofListMultimap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <C>
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param multimapSupplier
         * @return
         */
        static <K, V, C extends Collection<V>, M extends Multimap<K, V, C>> ResultExtractor<M> toMultimap(final RowMapper<K> keyExtractor,
                final RowMapper<V> valueExtractor, final Supplier<? extends M> multimapSupplier) {
            N.checkArgNotNull(keyExtractor, "keyExtractor");
            N.checkArgNotNull(valueExtractor, "valueExtractor");
            N.checkArgNotNull(multimapSupplier, "multimapSupplier");

            return new ResultExtractor<M>() {
                @Override
                public M apply(final ResultSet rs, final JdbcSettings jdbcSettings) throws SQLException {
                    final int offset = jdbcSettings.getOffset();
                    int count = jdbcSettings.getCount();

                    JdbcUtil.skip(rs, offset);

                    final M result = multimapSupplier.get();

                    while (count-- > 0 && rs.next()) {
                        result.put(keyExtractor.apply(rs), valueExtractor.apply(rs));
                    }

                    return result;
                }
            };
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor
         * @param valueExtractor
         * @return
         */
        static <K, V> ResultExtractor<ListMultimap<K, V>> toMultimap(final BiRowMapper<K> keyExtractor, final BiRowMapper<V> valueExtractor) {
            return toMultimap(keyExtractor, valueExtractor, Suppliers.<K, V> ofListMultimap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <C>
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param multimapSupplier
         * @return
         */
        static <K, V, C extends Collection<V>, M extends Multimap<K, V, C>> ResultExtractor<M> toMultimap(final BiRowMapper<K> keyExtractor,
                final BiRowMapper<V> valueExtractor, final Supplier<? extends M> multimapSupplier) {
            N.checkArgNotNull(keyExtractor, "keyExtractor");
            N.checkArgNotNull(valueExtractor, "valueExtractor");
            N.checkArgNotNull(multimapSupplier, "multimapSupplier");

            return new ResultExtractor<M>() {
                @Override
                public M apply(final ResultSet rs, final JdbcSettings jdbcSettings) throws SQLException {
                    final int offset = jdbcSettings.getOffset();
                    int count = jdbcSettings.getCount();

                    JdbcUtil.skip(rs, offset);

                    final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);
                    final M result = multimapSupplier.get();

                    while (count-- > 0 && rs.next()) {
                        result.put(keyExtractor.apply(rs, columnLabels), valueExtractor.apply(rs, columnLabels));
                    }

                    return result;
                }
            };
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor
         * @param valueExtractor
         * @return
         */
        static <K, V> ResultExtractor<Map<K, List<V>>> groupTo(final RowMapper<K> keyExtractor, final RowMapper<V> valueExtractor) {
            return groupTo(keyExtractor, valueExtractor, Suppliers.<K, List<V>> ofMap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param supplier
         * @return
         */
        static <K, V, M extends Map<K, List<V>>> ResultExtractor<M> groupTo(final RowMapper<K> keyExtractor, final RowMapper<V> valueExtractor,
                final Supplier<? extends M> supplier) {
            N.checkArgNotNull(keyExtractor, "keyExtractor");
            N.checkArgNotNull(valueExtractor, "valueExtractor");
            N.checkArgNotNull(supplier, "supplier");

            return new ResultExtractor<M>() {
                @Override
                public M apply(final ResultSet rs, final JdbcSettings jdbcSettings) throws SQLException {
                    final int offset = jdbcSettings.getOffset();
                    int count = jdbcSettings.getCount();

                    JdbcUtil.skip(rs, offset);

                    final M result = supplier.get();
                    K key = null;
                    List<V> value = null;

                    while (count-- > 0 && rs.next()) {
                        key = keyExtractor.apply(rs);
                        value = result.get(key);

                        if (value == null) {
                            value = new ArrayList<>();
                            result.put(key, value);
                        }

                        value.add(valueExtractor.apply(rs));
                    }

                    return result;
                }
            };
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor
         * @param valueExtractor
         * @return
         */
        static <K, V> ResultExtractor<Map<K, List<V>>> groupTo(final BiRowMapper<K> keyExtractor, final BiRowMapper<V> valueExtractor) {
            return groupTo(keyExtractor, valueExtractor, Suppliers.<K, List<V>> ofMap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param supplier
         * @return
         */
        static <K, V, M extends Map<K, List<V>>> ResultExtractor<M> groupTo(final BiRowMapper<K> keyExtractor, final BiRowMapper<V> valueExtractor,
                final Supplier<? extends M> supplier) {
            N.checkArgNotNull(keyExtractor, "keyExtractor");
            N.checkArgNotNull(valueExtractor, "valueExtractor");
            N.checkArgNotNull(supplier, "supplier");

            return new ResultExtractor<M>() {
                @Override
                public M apply(final ResultSet rs, final JdbcSettings jdbcSettings) throws SQLException {
                    final int offset = jdbcSettings.getOffset();
                    int count = jdbcSettings.getCount();

                    JdbcUtil.skip(rs, offset);

                    final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);
                    final M result = supplier.get();
                    K key = null;
                    List<V> value = null;

                    while (count-- > 0 && rs.next()) {
                        key = keyExtractor.apply(rs, columnLabels);
                        value = result.get(key);

                        if (value == null) {
                            value = new ArrayList<>();
                            result.put(key, value);
                        }

                        value.add(valueExtractor.apply(rs, columnLabels));
                    }

                    return result;
                }
            };
        }

        static ResultExtractor<DataSet> toDataSet(final RowFilter rowFilter) {
            return new ResultExtractor<DataSet>() {
                @Override
                public DataSet apply(final ResultSet rs, final JdbcSettings jdbcSettings) throws SQLException {
                    return JdbcUtil.extractData(rs, jdbcSettings.getOffset(), jdbcSettings.getCount(), rowFilter, false);
                }
            };
        }

        static ResultExtractor<DataSet> toDataSet(final RowExtractor rowExtractor) {
            return new ResultExtractor<DataSet>() {
                @Override
                public DataSet apply(final ResultSet rs, final JdbcSettings jdbcSettings) throws SQLException {
                    return JdbcUtil.extractData(rs, jdbcSettings.getOffset(), jdbcSettings.getCount(), rowExtractor, false);
                }
            };
        }

        static ResultExtractor<DataSet> toDataSet(final RowFilter rowFilter, final RowExtractor rowExtractor) {
            return new ResultExtractor<DataSet>() {
                @Override
                public DataSet apply(final ResultSet rs, final JdbcSettings jdbcSettings) throws SQLException {
                    return JdbcUtil.extractData(rs, jdbcSettings.getOffset(), jdbcSettings.getCount(), rowFilter, rowExtractor, false);
                }
            };
        }

        static <R> ResultExtractor<R> to(final Throwables.Function<DataSet, R, SQLException> after) {
            return (rs, jdbcSettings) -> after.apply(TO_DATA_SET.apply(rs, jdbcSettings));
        }
    }

    /**
     * Refer to http://landawn.com/introduction-to-jdbc.html about how to read columns/rows from <code>java.sql.ResultSet</code>
     *
     * @author Haiyang Li
     * @param <T>
     */
    interface ResultSetExtractor<T> {

        /**
         *
         * @param targetClass
         * @param parsedSql
         * @param rs
         * @param jdbcSettings
         * @return
         * @throws SQLException the SQL exception
         */
        T extractData(final Class<?> targetClass, final ParsedSql parsedSql, final ResultSet rs, final JdbcSettings jdbcSettings) throws SQLException;
    }

    /**
     * The Class AbstractStatementSetter.
     */
    public static abstract class AbstractStatementSetter implements StatementSetter {

        /**
         * Sets the parameters.
         *
         * @param parsedSql
         * @param stmt
         * @param parameters
         * @throws SQLException the SQL exception
         */
        @SuppressWarnings("rawtypes")
        @Override
        public void accept(final ParsedSql parsedSql, final PreparedStatement stmt, final Object[] parameters) throws SQLException {
            final int parameterCount = parsedSql.getParameterCount();

            if (parameterCount == 0) {
                return;
            } else if (N.isNullOrEmpty(parameters)) {
                throw new IllegalArgumentException(
                        "The count of parameter in sql is: " + parsedSql.getParameterCount() + ". But the specified parameters is null or empty");
            }

            Object[] parameterValues = null;
            Type[] parameterTypes = null;

            if (isEntityOrMapParameter(parsedSql, parameters)) {
                final List<String> namedParameters = parsedSql.getNamedParameters();
                final Object parameter_0 = parameters[0];

                parameterValues = new Object[parameterCount];

                if (ClassUtil.isEntity(parameter_0.getClass())) {
                    final Object entity = parameter_0;
                    final Class<?> cls = entity.getClass();
                    final EntityInfo entityInfo = ParserUtil.getEntityInfo(cls);
                    parameterTypes = new Type[parameterCount];
                    PropInfo propInfo = null;

                    for (int i = 0; i < parameterCount; i++) {
                        propInfo = entityInfo.getPropInfo(namedParameters.get(i));

                        if (propInfo == null) {
                            throw new IllegalArgumentException("Parameter for property '" + namedParameters.get(i) + "' is missed");
                        }

                        parameterValues[i] = propInfo.getPropValue(entity);
                        parameterTypes[i] = propInfo.dbType;
                    }
                } else if (parameter_0 instanceof Map) {
                    @SuppressWarnings("unchecked")
                    final Map<String, Object> m = (Map<String, Object>) parameter_0;

                    for (int i = 0; i < parameterCount; i++) {
                        parameterValues[i] = m.get(namedParameters.get(i));

                        if ((parameterValues[i] == null) && !m.containsKey(namedParameters.get(i))) {
                            throw new IllegalArgumentException("Parameter for property '" + namedParameters.get(i) + "' is missed");
                        }
                    }
                } else {
                    final EntityId entityId = (EntityId) parameter_0;

                    for (int i = 0; i < parameterCount; i++) {
                        parameterValues[i] = entityId.get(namedParameters.get(i));

                        if ((parameterValues[i] == null) && !entityId.containsKey(namedParameters.get(i))) {
                            throw new IllegalArgumentException("Parameter for property '" + namedParameters.get(i) + "' is missed");
                        }
                    }
                }
            } else {
                parameterValues = getParameterValues(parsedSql, parameters);
            }

            setParameters(stmt, parameterCount, parameterValues, parameterTypes);
        }

        /**
         * Sets the parameters.
         *
         * @param stmt
         * @param parameterCount
         * @param parameters
         * @param parameterTypes
         * @throws SQLException the SQL exception
         */
        @SuppressWarnings("rawtypes")
        protected abstract void setParameters(PreparedStatement stmt, int parameterCount, Object[] parameters, Type[] parameterTypes) throws SQLException;

        /**
         * Gets the parameter values.
         *
         * @param parsedSql
         * @param parameters
         * @return
         */
        protected Object[] getParameterValues(final ParsedSql parsedSql, final Object... parameters) {
            if ((parameters.length == 1) && (parameters[0] != null)) {
                if (parameters[0] instanceof Object[] && ((((Object[]) parameters[0]).length) >= parsedSql.getParameterCount())) {
                    return (Object[]) parameters[0];
                } else if (parameters[0] instanceof List && (((List<?>) parameters[0]).size() >= parsedSql.getParameterCount())) {
                    final Collection<?> c = (Collection<?>) parameters[0];
                    return c.toArray(new Object[c.size()]);
                }
            }

            return parameters;
        }
    }
}
