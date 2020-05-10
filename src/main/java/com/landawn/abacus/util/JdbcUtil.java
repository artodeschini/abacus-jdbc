/*
 * Copyright (c) 2015, Haiyang Li.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.landawn.abacus.util;

import static com.landawn.abacus.dataSource.DataSourceConfiguration.DRIVER;
import static com.landawn.abacus.dataSource.DataSourceConfiguration.PASSWORD;
import static com.landawn.abacus.dataSource.DataSourceConfiguration.URL;
import static com.landawn.abacus.dataSource.DataSourceConfiguration.USER;
import static com.landawn.abacus.util.IOUtil.DEFAULT_QUEUE_SIZE_FOR_ROW_PARSER;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Date;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.xml.parsers.DocumentBuilder;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import com.landawn.abacus.DataSet;
import com.landawn.abacus.DataSource;
import com.landawn.abacus.DataSourceManager;
import com.landawn.abacus.DirtyMarker;
import com.landawn.abacus.EntityId;
import com.landawn.abacus.IsolationLevel;
import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.annotation.Internal;
import com.landawn.abacus.annotation.SequentialOnly;
import com.landawn.abacus.annotation.Stateful;
import com.landawn.abacus.cache.Cache;
import com.landawn.abacus.condition.Condition;
import com.landawn.abacus.core.DirtyMarkerUtil;
import com.landawn.abacus.core.RowDataSet;
import com.landawn.abacus.core.Seid;
import com.landawn.abacus.dataSource.DataSourceConfiguration;
import com.landawn.abacus.dataSource.DataSourceManagerConfiguration;
import com.landawn.abacus.dataSource.SQLDataSource;
import com.landawn.abacus.dataSource.SQLDataSourceManager;
import com.landawn.abacus.exception.DuplicatedResultException;
import com.landawn.abacus.exception.ParseException;
import com.landawn.abacus.exception.UncheckedIOException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.logging.Logger;
import com.landawn.abacus.logging.LoggerFactory;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.EntityInfo;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.DaoUtil.NonDBOperation;
import com.landawn.abacus.util.ExceptionalStream.StreamE;
import com.landawn.abacus.util.Fn.BiConsumers;
import com.landawn.abacus.util.Fn.Suppliers;
import com.landawn.abacus.util.NoCachingNoUpdating.DisposableObjArray;
import com.landawn.abacus.util.SQLBuilder.SP;
import com.landawn.abacus.util.SQLExecutor.StatementSetter;
import com.landawn.abacus.util.SQLTransaction.CreatedBy;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.Tuple.Tuple3;
import com.landawn.abacus.util.Tuple.Tuple4;
import com.landawn.abacus.util.Tuple.Tuple5;
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
import com.landawn.abacus.util.function.BinaryOperator;
import com.landawn.abacus.util.function.Function;
import com.landawn.abacus.util.function.Supplier;
import com.landawn.abacus.util.stream.Collector;
import com.landawn.abacus.util.stream.EntryStream;
import com.landawn.abacus.util.stream.Stream;

/**
 * The Class JdbcUtil.
 *
 * @author Haiyang Li
 * @see {@link com.landawn.abacus.condition.ConditionFactory}
 * @see {@link com.landawn.abacus.condition.ConditionFactory.CF}
 * @see {@link com.landawn.abacus.annotation.ReadOnly}
 * @see {@link com.landawn.abacus.annotation.ReadOnlyId}
 * @see {@link com.landawn.abacus.annotation.NonUpdatable}
 * @see {@link com.landawn.abacus.annotation.Transient}
 * @see {@link com.landawn.abacus.annotation.Table}
 * @see {@link com.landawn.abacus.annotation.Column}
 * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/Connection.html">http://docs.oracle.com/javase/8/docs/api/java/sql/Connection.html</a>
 * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/Statement.html">http://docs.oracle.com/javase/8/docs/api/java/sql/Statement.html</a>
 * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/PreparedStatement.html">http://docs.oracle.com/javase/8/docs/api/java/sql/PreparedStatement.html</a>
 * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/ResultSet.html">http://docs.oracle.com/javase/8/docs/api/java/sql/ResultSet.html</a>
 * @since 0.8
 */
public final class JdbcUtil {

    /** The Constant logger. */
    static final Logger logger = LoggerFactory.getLogger(JdbcUtil.class);

    /** The Constant DEFAULT_BATCH_SIZE. */
    public static final int DEFAULT_BATCH_SIZE = 200;

    /** The Constant CURRENT_DIR_PATH. */
    // ...
    static final String CURRENT_DIR_PATH = "./";

    /** The async executor. */
    static final AsyncExecutor asyncExecutor = new AsyncExecutor(Math.max(8, IOUtil.CPU_CORES), Math.max(64, IOUtil.CPU_CORES), 180L, TimeUnit.SECONDS);

    /** The Constant DEFAULT_STMT_SETTER. */
    static final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> DEFAULT_STMT_SETTER = new JdbcUtil.BiParametersSetter<PreparedStatement, Object[]>() {
        @Override
        public void accept(PreparedStatement stmt, Object[] parameters) throws SQLException {
            for (int i = 0, len = parameters.length; i < len; i++) {
                stmt.setObject(i + 1, parameters[i]);
            }
        }
    };

    /** The Constant sqlStateForTableNotExists. */
    private static final Set<String> sqlStateForTableNotExists = N.newHashSet();

    static {
        sqlStateForTableNotExists.add("42S02"); // for MySQCF.
        sqlStateForTableNotExists.add("42P01"); // for PostgreSQCF.
        sqlStateForTableNotExists.add("42501"); // for HSQLDB.
    }

    /**
     * Instantiates a new jdbc util.
     */
    private JdbcUtil() {
        // singleton
    }

    /**
     * Gets the DB version.
     *
     * @param conn
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static DBVersion getDBVersion(final Connection conn) throws UncheckedSQLException {
        try {
            String dbProudctName = conn.getMetaData().getDatabaseProductName();
            String dbProudctVersion = conn.getMetaData().getDatabaseProductVersion();

            DBVersion dbVersion = DBVersion.OTHERS;

            String upperCaseProductName = dbProudctName.toUpperCase();
            if (upperCaseProductName.contains("H2")) {
                dbVersion = DBVersion.H2;
            } else if (upperCaseProductName.contains("HSQL")) {
                dbVersion = DBVersion.HSQLDB;
            } else if (upperCaseProductName.contains("MYSQL")) {
                if (dbProudctVersion.startsWith("5.5")) {
                    dbVersion = DBVersion.MYSQL_5_5;
                } else if (dbProudctVersion.startsWith("5.6")) {
                    dbVersion = DBVersion.MYSQL_5_6;
                } else if (dbProudctVersion.startsWith("5.7")) {
                    dbVersion = DBVersion.MYSQL_5_7;
                } else if (dbProudctVersion.startsWith("5.8")) {
                    dbVersion = DBVersion.MYSQL_5_8;
                } else if (dbProudctVersion.startsWith("5.9")) {
                    dbVersion = DBVersion.MYSQL_5_9;
                } else if (dbProudctVersion.startsWith("6")) {
                    dbVersion = DBVersion.MYSQL_6;
                } else if (dbProudctVersion.startsWith("7")) {
                    dbVersion = DBVersion.MYSQL_7;
                } else if (dbProudctVersion.startsWith("8")) {
                    dbVersion = DBVersion.MYSQL_8;
                } else if (dbProudctVersion.startsWith("9")) {
                    dbVersion = DBVersion.MYSQL_9;
                } else if (dbProudctVersion.startsWith("10")) {
                    dbVersion = DBVersion.MYSQL_10;
                } else {
                    dbVersion = DBVersion.MYSQL_OTHERS;
                }
            } else if (upperCaseProductName.contains("POSTGRESQL")) {
                if (dbProudctVersion.startsWith("9.2")) {
                    dbVersion = DBVersion.POSTGRESQL_9_2;
                } else if (dbProudctVersion.startsWith("9.3")) {
                    dbVersion = DBVersion.POSTGRESQL_9_3;
                } else if (dbProudctVersion.startsWith("9.4")) {
                    dbVersion = DBVersion.POSTGRESQL_9_4;
                } else if (dbProudctVersion.startsWith("9.5")) {
                    dbVersion = DBVersion.POSTGRESQL_9_5;
                } else if (dbProudctVersion.startsWith("10")) {
                    dbVersion = DBVersion.POSTGRESQL_10;
                } else if (dbProudctVersion.startsWith("11")) {
                    dbVersion = DBVersion.POSTGRESQL_11;
                } else if (dbProudctVersion.startsWith("12")) {
                    dbVersion = DBVersion.POSTGRESQL_12;
                } else {
                    dbVersion = DBVersion.POSTGRESQL_OTHERS;
                }
            } else if (upperCaseProductName.contains("ORACLE")) {
                dbVersion = DBVersion.ORACLE;
            } else if (upperCaseProductName.contains("DB2")) {
                dbVersion = DBVersion.DB2;
            } else if (upperCaseProductName.contains("SQL SERVER")) {
                dbVersion = DBVersion.SQL_SERVER;
            }

            return dbVersion;
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     * Creates the data source manager.
     *
     * @param dataSourceXmlFile
     * @return DataSourceManager
     * @throws UncheckedIOException the unchecked IO exception
     * @throws UncheckedSQLException the unchecked SQL exception
     * @see DataSource.xsd
     */
    public static DataSourceManager createDataSourceManager(final String dataSourceXmlFile) throws UncheckedIOException, UncheckedSQLException {
        InputStream is = null;
        try {
            is = new FileInputStream(Configuration.findFile(dataSourceXmlFile));
            return createDataSourceManager(is, dataSourceXmlFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            IOUtil.close(is);
        }

    }

    /**
     * Creates the data source manager.
     *
     * @param dataSourceXmlInputStream
     * @return DataSourceManager
     * @throws UncheckedIOException the unchecked IO exception
     * @throws UncheckedSQLException the unchecked SQL exception
     * @see DataSource.xsd
     */
    public static DataSourceManager createDataSourceManager(final InputStream dataSourceXmlInputStream) throws UncheckedIOException, UncheckedSQLException {
        return createDataSourceManager(dataSourceXmlInputStream, CURRENT_DIR_PATH);
    }

    /** The Constant PROPERTIES. */
    private static final String PROPERTIES = "properties";

    /** The Constant RESOURCE. */
    private static final String RESOURCE = "resource";

    /**
     * Creates the data source manager.
     *
     * @param dataSourceXmlInputStream
     * @param dataSourceXmlFile
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    private static DataSourceManager createDataSourceManager(final InputStream dataSourceXmlInputStream, final String dataSourceXmlFile)
            throws UncheckedIOException, UncheckedSQLException {
        DocumentBuilder domParser = XMLUtil.createDOMParser();
        Document doc = null;

        try {
            doc = domParser.parse(dataSourceXmlInputStream);

            Element rootElement = doc.getDocumentElement();

            final Map<String, String> props = new HashMap<>();
            List<Element> propertiesElementList = XMLUtil.getElementsByTagName(rootElement, PROPERTIES);

            if (N.notNullOrEmpty(propertiesElementList)) {
                for (Element propertiesElement : propertiesElementList) {
                    File resourcePropertiesFile = Configuration.findFileByFile(new File(dataSourceXmlFile), propertiesElement.getAttribute(RESOURCE));
                    java.util.Properties properties = new java.util.Properties();
                    InputStream is = null;

                    try {
                        is = new FileInputStream(resourcePropertiesFile);

                        if (resourcePropertiesFile.getName().endsWith(".xml")) {
                            properties.loadFromXML(is);
                        } else {
                            properties.load(is);
                        }
                    } finally {
                        IOUtil.close(is);
                    }

                    for (Object key : properties.keySet()) {
                        props.put((String) key, (String) properties.get(key));
                    }
                }
            }

            String nodeName = rootElement.getNodeName();
            if (nodeName.equals(DataSourceManagerConfiguration.DATA_SOURCE_MANAGER)) {
                DataSourceManagerConfiguration config = new DataSourceManagerConfiguration(rootElement, props);
                return new SQLDataSourceManager(config);
            } else if (nodeName.equals(DataSourceConfiguration.DATA_SOURCE)) {
                DataSourceConfiguration config = new DataSourceConfiguration(rootElement, props);
                return new SimpleDataSourceManager(new SQLDataSource(config));
            } else {
                throw new RuntimeException("Unknown xml format with root element: " + nodeName);
            }
        } catch (SAXException e) {
            throw new ParseException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Creates the data source.
     *
     * @param dataSourceFile
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     * @throws UncheckedSQLException the unchecked SQL exception
     * @see DataSource.xsd
     */
    public static javax.sql.DataSource createDataSource(final String dataSourceFile) throws UncheckedIOException, UncheckedSQLException {
        InputStream is = null;
        try {
            is = new FileInputStream(Configuration.findFile(dataSourceFile));
            return createDataSource(is, dataSourceFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            IOUtil.close(is);
        }
    }

    /**
     * Creates the data source.
     *
     * @param dataSourceInputStream
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     * @throws UncheckedSQLException the unchecked SQL exception
     * @see DataSource.xsd
     */
    public static javax.sql.DataSource createDataSource(final InputStream dataSourceInputStream) throws UncheckedIOException, UncheckedSQLException {
        return createDataSource(dataSourceInputStream, CURRENT_DIR_PATH);
    }

    /**
     * Creates the data source.
     *
     * @param dataSourceInputStream
     * @param dataSourceFile
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    private static javax.sql.DataSource createDataSource(final InputStream dataSourceInputStream, final String dataSourceFile)
            throws UncheckedIOException, UncheckedSQLException {
        final String dataSourceString = IOUtil.readString(dataSourceInputStream);

        if (CURRENT_DIR_PATH.equals(dataSourceFile) || dataSourceFile.endsWith(".xml")) {
            try {
                return createDataSourceManager(new ByteArrayInputStream(dataSourceString.getBytes())).getPrimaryDataSource();
            } catch (ParseException e) {
                // ignore.
            } catch (UncheckedIOException e) {
                // ignore.
            }
        }

        final Map<String, String> newProps = new HashMap<>();
        final java.util.Properties properties = new java.util.Properties();

        try {
            properties.load(new ByteArrayInputStream(dataSourceString.getBytes()));

            Object value = null;

            for (Object key : properties.keySet()) {
                value = properties.get(key);
                newProps.put(key.toString().trim(), value.toString().trim());
            }

        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return new SQLDataSource(newProps);
    }

    /**
     * Creates the data source.
     *
     * @param url
     * @param user
     * @param password
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static javax.sql.DataSource createDataSource(final String url, final String user, final String password) throws UncheckedSQLException {
        return createDataSource(getDriverClasssByUrl(url), url, user, password);
    }

    /**
     * Creates the data source.
     *
     * @param driver
     * @param url
     * @param user
     * @param password
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static javax.sql.DataSource createDataSource(final String driver, final String url, final String user, final String password)
            throws UncheckedSQLException {
        final Class<? extends Driver> driverClass = ClassUtil.forClass(driver);

        return createDataSource(driverClass, url, user, password);
    }

    /**
     * Creates the data source.
     *
     * @param driverClass
     * @param url
     * @param user
     * @param password
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static javax.sql.DataSource createDataSource(final Class<? extends Driver> driverClass, final String url, final String user, final String password)
            throws UncheckedSQLException {
        N.checkArgNotNullOrEmpty(url, "url");

        final Map<String, Object> props = new HashMap<>();

        props.put(DRIVER, driverClass.getCanonicalName());
        props.put(URL, url);
        props.put(USER, user);
        props.put(PASSWORD, password);

        return createDataSource(props);
    }

    /**
     * Creates the data source.
     *
     * @param props refer to Connection.xsd for the supported properties.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static javax.sql.DataSource createDataSource(final Map<String, ?> props) throws UncheckedSQLException {
        final String driver = (String) props.get(DRIVER);

        if (N.isNullOrEmpty(driver)) {
            final String url = (String) props.get(URL);

            if (N.isNullOrEmpty(url)) {
                throw new IllegalArgumentException("Url is not specified");
            }

            final Map<String, Object> tmp = new HashMap<>(props);

            tmp.put(DRIVER, getDriverClasssByUrl(url).getCanonicalName());

            return new SQLDataSource(tmp);
        } else {
            return new SQLDataSource(props);
        }
    }

    //    /**
    //     *
    //     * @param sqlDataSource
    //     * @return
    //     * @deprecated
    //     */
    //    @Deprecated
    //    public static DataSource wrap(final javax.sql.DataSource sqlDataSource) {
    //        return sqlDataSource instanceof DataSource ? ((DataSource) sqlDataSource) : new SimpleDataSource(sqlDataSource);
    //    }

    /**
     * Creates the connection.
     *
     * @param url
     * @param user
     * @param password
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static Connection createConnection(final String url, final String user, final String password) throws UncheckedSQLException {
        return createConnection(getDriverClasssByUrl(url), url, user, password);
    }

    /**
     * Gets the driver classs by url.
     *
     * @param url
     * @return
     */
    private static Class<? extends Driver> getDriverClasssByUrl(final String url) {
        N.checkArgNotNullOrEmpty(url, "url");

        Class<? extends Driver> driverClass = null;
        // jdbc:mysql://localhost:3306/abacustest
        if (url.indexOf("mysql") > 0 || StringUtil.indexOfIgnoreCase(url, "mysql") > 0) {
            driverClass = ClassUtil.forClass("com.mysql.jdbc.Driver");
            // jdbc:postgresql://localhost:5432/abacustest
        } else if (url.indexOf("postgresql") > 0 || StringUtil.indexOfIgnoreCase(url, "postgresql") > 0) {
            driverClass = ClassUtil.forClass("org.postgresql.Driver");
            // jdbc:h2:hsql://<host>:<port>/<database>
        } else if (url.indexOf("h2") > 0 || StringUtil.indexOfIgnoreCase(url, "h2") > 0) {
            driverClass = ClassUtil.forClass("org.h2.Driver");
            // jdbc:hsqldb:hsql://localhost/abacustest
        } else if (url.indexOf("hsqldb") > 0 || StringUtil.indexOfIgnoreCase(url, "hsqldb") > 0) {
            driverClass = ClassUtil.forClass("org.hsqldb.jdbc.JDBCDriver");
            // jdbc.url=jdbc:oracle:thin:@localhost:1521:abacustest
        } else if (url.indexOf("oracle") > 0 || StringUtil.indexOfIgnoreCase(url, "oracle") > 0) {
            driverClass = ClassUtil.forClass("oracle.jdbc.driver.OracleDriver");
            // jdbc.url=jdbc:sqlserver://localhost:1433;Database=abacustest
        } else if (url.indexOf("sqlserver") > 0 || StringUtil.indexOfIgnoreCase(url, "sqlserver") > 0) {
            driverClass = ClassUtil.forClass("com.microsoft.sqlserver.jdbc.SQLServerDriver");
            // jdbc:db2://localhost:50000/abacustest
        } else if (url.indexOf("db2") > 0 || StringUtil.indexOfIgnoreCase(url, "db2") > 0) {
            driverClass = ClassUtil.forClass("com.ibm.db2.jcc.DB2Driver");
        } else {
            throw new IllegalArgumentException(
                    "Can not identity the driver class by url: " + url + ". Only mysql, postgresql, hsqldb, sqlserver, oracle and db2 are supported currently");
        }
        return driverClass;
    }

    /**
     * Creates the connection.
     *
     * @param driverClass
     * @param url
     * @param user
     * @param password
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static Connection createConnection(final String driverClass, final String url, final String user, final String password)
            throws UncheckedSQLException {
        Class<? extends Driver> cls = ClassUtil.forClass(driverClass);
        return createConnection(cls, url, user, password);
    }

    /**
     * Creates the connection.
     *
     * @param driverClass
     * @param url
     * @param user
     * @param password
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static Connection createConnection(final Class<? extends Driver> driverClass, final String url, final String user, final String password)
            throws UncheckedSQLException {
        try {
            DriverManager.registerDriver(N.newInstance(driverClass));

            return DriverManager.getConnection(url, user, password);
        } catch (SQLException e) {
            throw new UncheckedSQLException("Failed to close create connection", e);
        }
    }

    /** The is in spring. */
    private static boolean isInSpring = true;

    static {
        try {
            isInSpring = ClassUtil.forClass("org.springframework.jdbc.datasource.DataSourceUtils") != null;
        } catch (Throwable e) {
            isInSpring = false;
        }
    }

    /**
     * Spring Transaction is supported and Integrated.
     * If this method is called where a Spring transaction is started with the specified {@code DataSource},
     * the {@code Connection} started the Spring Transaction will be returned. Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be returned.
     *
     * @param ds
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static Connection getConnection(final javax.sql.DataSource ds) throws UncheckedSQLException {
        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) {
            try {
                return org.springframework.jdbc.datasource.DataSourceUtils.getConnection(ds);
            } catch (NoClassDefFoundError e) {
                isInSpring = false;

                try {
                    return ds.getConnection();
                } catch (SQLException e1) {
                    throw new UncheckedSQLException(e1);
                }
            }
        } else {
            try {
                return ds.getConnection();
            } catch (SQLException e) {
                throw new UncheckedSQLException(e);
            }
        }
    }

    /**
     * Spring Transaction is supported and Integrated.
     * If this method is called where a Spring transaction is started with the specified {@code DataSource},
     * the specified {@code Connection} won't be returned to {@code DataSource}(Connection pool) until the transaction is committed or rolled back. Otherwise the specified {@code Connection} will be directly returned back to {@code DataSource}(Connection pool).
     *
     * @param conn
     * @param ds
     */
    public static void releaseConnection(final Connection conn, final javax.sql.DataSource ds) {
        if (conn == null) {
            return;
        }

        if (isInSpring && ds != null && !isSpringTransactionalDisabled_TL.get()) {
            try {
                org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(conn, ds);
            } catch (NoClassDefFoundError e) {
                isInSpring = false;
                JdbcUtil.closeQuietly(conn);
            }
        } else {
            JdbcUtil.closeQuietly(conn);
        }
    }

    /**
     * Creates the close handler.
     *
     * @param conn
     * @param ds
     * @return
     */
    static Runnable createCloseHandler(final Connection conn, final javax.sql.DataSource ds) {
        return () -> releaseConnection(conn, ds);
    }

    /**
     *
     * @param rs
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static void close(final ResultSet rs) throws UncheckedSQLException {
        if (rs != null) {
            try {
                rs.close();
            } catch (SQLException e) {
                throw new UncheckedSQLException(e);
            }
        }
    }

    /**
     *
     * @param rs
     * @param closeStatement
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static void close(final ResultSet rs, final boolean closeStatement) throws UncheckedSQLException {
        close(rs, closeStatement, false);
    }

    /**
     *
     * @param rs
     * @param closeStatement
     * @param closeConnection
     * @throws IllegalArgumentException if {@code closeStatement = false} while {@code closeConnection = true}.
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static void close(final ResultSet rs, final boolean closeStatement, final boolean closeConnection)
            throws IllegalArgumentException, UncheckedSQLException {
        if (closeConnection && closeStatement == false) {
            throw new IllegalArgumentException("'closeStatement' can't be false while 'closeConnection' is true");
        }

        if (rs == null) {
            return;
        }

        Connection conn = null;
        Statement stmt = null;

        try {
            if (closeStatement || closeConnection) {
                stmt = rs.getStatement();
            }

            if (closeConnection && stmt != null) {
                conn = stmt.getConnection();
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            close(rs, stmt, conn);
        }
    }

    /**
     *
     * @param stmt
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static void close(final Statement stmt) throws UncheckedSQLException {
        if (stmt != null) {
            try {
                stmt.close();
            } catch (SQLException e) {
                throw new UncheckedSQLException(e);
            }
        }
    }

    /**
     *
     * @param conn
     * @throws UncheckedSQLException the unchecked SQL exception
     * @deprecated consider using {@link #releaseConnection(Connection, javax.sql.DataSource)}
     */
    @Deprecated
    public static void close(final Connection conn) throws UncheckedSQLException {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException e) {
                throw new UncheckedSQLException(e);
            }
        }
    }

    /**
     *
     * @param rs
     * @param stmt
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static void close(final ResultSet rs, final Statement stmt) throws UncheckedSQLException {
        try {
            if (rs != null) {
                rs.close();
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            try {
                if (stmt != null) {
                    stmt.close();
                }
            } catch (SQLException e) {
                throw new UncheckedSQLException(e);
            }
        }
    }

    /**
     *
     * @param stmt
     * @param conn
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static void close(final Statement stmt, final Connection conn) throws UncheckedSQLException {
        try {
            if (stmt != null) {
                stmt.close();
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            try {
                if (conn != null) {
                    conn.close();
                }
            } catch (SQLException e) {
                throw new UncheckedSQLException(e);
            }
        }
    }

    /**
     *
     * @param rs
     * @param stmt
     * @param conn
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static void close(final ResultSet rs, final Statement stmt, final Connection conn) throws UncheckedSQLException {
        try {
            if (rs != null) {
                rs.close();
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            try {
                if (stmt != null) {
                    stmt.close();
                }
            } catch (SQLException e) {
                throw new UncheckedSQLException(e);
            } finally {
                try {
                    if (conn != null) {
                        conn.close();
                    }
                } catch (SQLException e) {
                    throw new UncheckedSQLException(e);
                }
            }
        }
    }

    /**
     * Unconditionally close an <code>ResultSet</code>.
     * <p>
     * Equivalent to {@link ResultSet#close()}, except any exceptions will be ignored.
     * This is typically used in finally blocks.
     *
     * @param rs
     */
    public static void closeQuietly(final ResultSet rs) {
        closeQuietly(rs, null, null);
    }

    /**
     *
     * @param rs
     * @param closeStatement
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static void closeQuietly(final ResultSet rs, final boolean closeStatement) throws UncheckedSQLException {
        closeQuietly(rs, closeStatement, false);
    }

    /**
     *
     * @param rs
     * @param closeStatement
     * @param closeConnection
     * @throws IllegalArgumentException if {@code closeStatement = false} while {@code closeConnection = true}.
     */
    public static void closeQuietly(final ResultSet rs, final boolean closeStatement, final boolean closeConnection) throws IllegalArgumentException {
        if (closeConnection && closeStatement == false) {
            throw new IllegalArgumentException("'closeStatement' can't be false while 'closeConnection' is true");
        }

        if (rs == null) {
            return;
        }

        Connection conn = null;
        Statement stmt = null;

        try {
            if (closeStatement || closeConnection) {
                stmt = rs.getStatement();
            }

            if (closeConnection && stmt != null) {
                conn = stmt.getConnection();
            }
        } catch (SQLException e) {
            logger.error("Failed to get Statement or Connection by ResultSet", e);
        } finally {
            closeQuietly(rs, stmt, conn);
        }
    }

    /**
     * Unconditionally close an <code>Statement</code>.
     * <p>
     * Equivalent to {@link Statement#close()}, except any exceptions will be ignored.
     * This is typically used in finally blocks.
     *
     * @param stmt
     */
    public static void closeQuietly(final Statement stmt) {
        closeQuietly(null, stmt, null);
    }

    /**
     * Unconditionally close an <code>Connection</code>.
     * <p>
     * Equivalent to {@link Connection#close()}, except any exceptions will be ignored.
     * This is typically used in finally blocks.
     *
     * @param conn
     * @deprecated consider using {@link #releaseConnection(Connection, javax.sql.DataSource)}
     */
    @Deprecated
    public static void closeQuietly(final Connection conn) {
        closeQuietly(null, null, conn);
    }

    /**
     * Unconditionally close the <code>ResultSet, Statement</code>.
     * <p>
     * Equivalent to {@link ResultSet#close()}, {@link Statement#close()}, except any exceptions will be ignored.
     * This is typically used in finally blocks.
     *
     * @param rs
     * @param stmt
     */
    public static void closeQuietly(final ResultSet rs, final Statement stmt) {
        closeQuietly(rs, stmt, null);
    }

    /**
     * Unconditionally close the <code>Statement, Connection</code>.
     * <p>
     * Equivalent to {@link Statement#close()}, {@link Connection#close()}, except any exceptions will be ignored.
     * This is typically used in finally blocks.
     *
     * @param stmt
     * @param conn
     */
    public static void closeQuietly(final Statement stmt, final Connection conn) {
        closeQuietly(null, stmt, conn);
    }

    /**
     * Unconditionally close the <code>ResultSet, Statement, Connection</code>.
     * <p>
     * Equivalent to {@link ResultSet#close()}, {@link Statement#close()}, {@link Connection#close()}, except any exceptions will be ignored.
     * This is typically used in finally blocks.
     *
     * @param rs
     * @param stmt
     * @param conn
     */
    public static void closeQuietly(final ResultSet rs, final Statement stmt, final Connection conn) {
        if (rs != null) {
            try {
                rs.close();
            } catch (Exception e) {
                logger.error("Failed to close ResultSet", e);
            }
        }

        if (stmt != null) {
            try {
                stmt.close();
            } catch (Exception e) {
                logger.error("Failed to close Statement", e);
            }
        }

        if (conn != null) {
            try {
                conn.close();
            } catch (Exception e) {
                logger.error("Failed to close Connection", e);
            }
        }
    }

    /**
     *
     * @param rs
     * @param n the count of row to move ahead.
     * @return
     * @throws SQLException the SQL exception
     */
    public static int skip(final ResultSet rs, int n) throws SQLException {
        return skip(rs, (long) n);
    }

    /**
     *
     * @param rs
     * @param n the count of row to move ahead.
     * @return
     * @throws SQLException the SQL exception
     * @see {@link ResultSet#absolute(int)}
     */
    @SuppressWarnings("deprecation")
    public static int skip(final ResultSet rs, long n) throws SQLException {
        return InternalUtil.skip(rs, n);
    }

    /**
     * Gets the column count.
     *
     * @param rs
     * @return
     * @throws SQLException the SQL exception
     */
    public static int getColumnCount(ResultSet rs) throws SQLException {
        return rs.getMetaData().getColumnCount();
    }

    /**
     * Gets the column name list.
     *
     * @param conn
     * @param tableName
     * @return
     * @throws SQLException the SQL exception
     */
    public static List<String> getColumnNameList(final Connection conn, final String tableName) throws SQLException {
        final String query = "SELECT * FROM " + tableName + " WHERE 1 > 2";
        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            stmt = prepareStatement(conn, query);
            rs = executeQuery(stmt);

            final ResultSetMetaData metaData = rs.getMetaData();
            final int columnCount = metaData.getColumnCount();
            final List<String> columnNameList = new ArrayList<>(columnCount);

            for (int i = 1, n = columnCount + 1; i < n; i++) {
                columnNameList.add(metaData.getColumnName(i));
            }

            return columnNameList;
        } finally {
            closeQuietly(rs, stmt);
        }
    }

    /**
     * Gets the column label list.
     *
     * @param rs
     * @return
     * @throws SQLException the SQL exception
     */
    @SuppressWarnings("deprecation")
    public static List<String> getColumnLabelList(ResultSet rs) throws SQLException {
        return InternalUtil.getColumnLabelList(rs);
    }

    /**
     * Gets the column label.
     *
     * @param rsmd
     * @param columnIndex
     * @return
     * @throws SQLException the SQL exception
     */
    @SuppressWarnings("deprecation")
    public static String getColumnLabel(final ResultSetMetaData rsmd, final int columnIndex) throws SQLException {
        return InternalUtil.getColumnLabel(rsmd, columnIndex);
    }

    /**
     * Gets the column value.
     *
     * @param rs
     * @param columnIndex
     * @return
     * @throws SQLException the SQL exception
     */
    @SuppressWarnings("deprecation")
    public static Object getColumnValue(final ResultSet rs, final int columnIndex) throws SQLException {
        return InternalUtil.getColumnValue(rs, columnIndex);
    }

    /**
     * Gets the column value.
     *
     * @param rs
     * @param columnLabel
     * @return
     * @throws SQLException the SQL exception
     */
    @SuppressWarnings("deprecation")
    public static Object getColumnValue(final ResultSet rs, final String columnLabel) throws SQLException {
        return InternalUtil.getColumnValue(rs, columnLabel);
    }

    /**
     * Gets the column value.
     *
     * @param <T>
     * @param targetClass
     * @param rs
     * @param columnIndex
     * @return
     * @throws SQLException the SQL exception
     */
    public static <T> T getColumnValue(final Class<T> targetClass, final ResultSet rs, final int columnIndex) throws SQLException {
        return N.<T> typeOf(targetClass).get(rs, columnIndex);
    }

    /**
     * Gets the column value.
     *
     * @param <T>
     * @param targetClass
     * @param rs
     * @param columnLabel
     * @return
     * @throws SQLException the SQL exception
     */
    public static <T> T getColumnValue(final Class<T> targetClass, final ResultSet rs, final String columnLabel) throws SQLException {
        return N.<T> typeOf(targetClass).get(rs, columnLabel);
    }

    /**
     * 
     * @param ds
     * @return
     */
    public static boolean isInTransaction(final javax.sql.DataSource ds) {
        if (SQLTransaction.getTransaction(ds, CreatedBy.JDBC_UTIL) != null) {
            return true;
        }

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) {
            Connection conn = null;

            try {
                conn = getConnection(ds);

                return org.springframework.jdbc.datasource.DataSourceUtils.isConnectionTransactional(conn, ds);
            } catch (NoClassDefFoundError e) {
                isInSpring = false;
            } finally {
                releaseConnection(conn, ds);
            }
        }

        return false;
    }

    /**
     * Refer to: {@code beginTransaction(javax.sql.DataSource, IsolationLevel, boolean)}.
     *
     * @param dataSource
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @see {@link #beginTransaction(javax.sql.DataSource, IsolationLevel, boolean)}
     */
    public static SQLTransaction beginTransaction(final javax.sql.DataSource dataSource) throws UncheckedSQLException {
        return beginTransaction(dataSource, IsolationLevel.DEFAULT);
    }

    /**
     * Refer to: {@code beginTransaction(javax.sql.DataSource, IsolationLevel, boolean)}.
     *
     * @param dataSource
     * @param isolationLevel
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @see {@link #beginTransaction(javax.sql.DataSource, IsolationLevel, boolean)}
     */
    public static SQLTransaction beginTransaction(final javax.sql.DataSource dataSource, final IsolationLevel isolationLevel) throws UncheckedSQLException {
        return beginTransaction(dataSource, isolationLevel, false);
    }

    /**
     * Starts a global transaction which will be shared by all in-line database query with the same {@code DataSource} in the same thread,
     * including methods: {@code JdbcUtil.beginTransaction/prepareQuery/prepareNamedQuery/prepareCallableQuery, SQLExecutor(Mapper).beginTransaction/get/insert/batchInsert/update/batchUpdate/query/list/findFirst/...}
     *
     * <br />
     * Spring Transaction is supported and Integrated.
     * If this method is called at where a Spring transaction is started with the specified {@code DataSource},
     * the {@code Connection} started the Spring Transaction will be used here.
     * That's to say the Spring transaction will have the final control on commit/roll back over the {@code Connection}.
     *
     * <br />
     * <br />
     *
     * Here is the general code pattern to work with {@code SQLTransaction}.
     *
     * <pre>
     * <code>
     * public void doSomethingA() {
     *     ...
     *     final SQLTransaction tranA = JdbcUtil.beginTransaction(dataSource1, isolation);
     *
     *     try {
     *         ...
     *         doSomethingB(); // Share the same transaction 'tranA' because they're in the same thread and start transaction with same DataSource 'dataSource1'.
     *         ...
     *         doSomethingC(); // won't share the same transaction 'tranA' although they're in the same thread but start transaction with different DataSource 'dataSource2'.
     *         ...
     *         tranA.commit();
     *     } finally {
     *         tranA.rollbackIfNotCommitted();
     *     }
     * }
     *
     * public void doSomethingB() {
     *     ...
     *     final SQLTransaction tranB = JdbcUtil.beginTransaction(dataSource1, isolation);
     *     try {
     *         // do your work with the conn...
     *         ...
     *         tranB.commit();
     *     } finally {
     *         tranB.rollbackIfNotCommitted();
     *     }
     * }
     *
     * public void doSomethingC() {
     *     ...
     *     final SQLTransaction tranC = JdbcUtil.beginTransaction(dataSource2, isolation);
     *     try {
     *         // do your work with the conn...
     *         ...
     *         tranC.commit();
     *     } finally {
     *         tranC.rollbackIfNotCommitted();
     *     }
     * }
     * </pre>
     * </code>
     *
     * It's incorrect to use flag to identity the transaction should be committed or rolled back.
     * Don't write below code:
     * <pre>
     * <code>
     * public void doSomethingA() {
     *     ...
     *     final SQLTransaction tranA = JdbcUtil.beginTransaction(dataSource1, isolation);
     *     boolean flagToCommit = false;
     *     try {
     *         // do your work with the conn...
     *         ...
     *         flagToCommit = true;
     *     } finally {
     *         if (flagToCommit) {
     *             tranA.commit();
     *         } else {
     *             tranA.rollbackIfNotCommitted();
     *         }
     *     }
     * }
     * </code>
     * </pre>
     *
     * @param dataSource
     * @param isolationLevel
     * @param isForUpdateOnly
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @see {@link #getConnection(javax.sql.DataSource)}
     * @see {@link #releaseConnection(Connection, javax.sql.DataSource)}
     * @see SQLExecutor#beginTransaction(IsolationLevel, boolean, JdbcSettings)
     */
    public static SQLTransaction beginTransaction(final javax.sql.DataSource dataSource, final IsolationLevel isolationLevel, final boolean isForUpdateOnly)
            throws UncheckedSQLException {
        N.checkArgNotNull(dataSource, "dataSource");
        N.checkArgNotNull(isolationLevel, "isolationLevel");

        SQLTransaction tran = SQLTransaction.getTransaction(dataSource, CreatedBy.JDBC_UTIL);

        if (tran == null) {
            Connection conn = null;
            boolean noException = false;

            try {
                conn = getConnection(dataSource);
                tran = new SQLTransaction(dataSource, conn, isolationLevel, CreatedBy.JDBC_UTIL, true);
                tran.incrementAndGetRef(isolationLevel, isForUpdateOnly);

                noException = true;
            } catch (SQLException e) {
                throw new UncheckedSQLException(e);
            } finally {
                if (noException == false) {
                    releaseConnection(conn, dataSource);
                }
            }

            logger.info("Create a new SQLTransaction(id={})", tran.id());
            SQLTransaction.putTransaction(tran);
        } else {
            logger.info("Reusing the existing SQLTransaction(id={})", tran.id());
            tran.incrementAndGetRef(isolationLevel, isForUpdateOnly);
        }

        return tran;
    }

    /**
     *
     * @param <T>
     * @param <E>
     * @param ds
     * @param cmd
     * @return
     * @throws E
     */
    @Beta
    public static <T, E extends Throwable> T callInTransaction(final javax.sql.DataSource ds, final Throwables.Callable<T, E> cmd) throws E {
        final SQLTransaction tran = JdbcUtil.beginTransaction(ds);
        T result = null;

        try {
            result = cmd.call();
            tran.commit();
        } finally {
            tran.rollbackIfNotCommitted();
        }

        return result;
    }

    /**
     *
     * @param <T>
     * @param <E>
     * @param ds
     * @param cmd
     * @return
     * @throws E
     */
    @Beta
    public static <T, E extends Throwable> T callInTransaction(final javax.sql.DataSource ds, final Throwables.Function<javax.sql.DataSource, T, E> cmd)
            throws E {
        final SQLTransaction tran = JdbcUtil.beginTransaction(ds);
        T result = null;

        try {
            result = cmd.apply(ds);
            tran.commit();
        } finally {
            tran.rollbackIfNotCommitted();
        }

        return result;
    }

    /**
     *
     * @param <E>
     * @param ds
     * @param cmd
     * @return
     * @throws E
     */
    @Beta
    public static <E extends Throwable> void runInTransaction(final javax.sql.DataSource ds, final Throwables.Runnable<E> cmd) throws E {
        final SQLTransaction tran = JdbcUtil.beginTransaction(ds);

        try {
            cmd.run();
            tran.commit();
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    /**
     *
     * @param <E>
     * @param ds
     * @param cmd
     * @return
     * @throws E
     */
    @Beta
    public static <E extends Throwable> void runInTransaction(final javax.sql.DataSource ds, final Throwables.Consumer<javax.sql.DataSource, E> cmd) throws E {
        final SQLTransaction tran = JdbcUtil.beginTransaction(ds);

        try {
            cmd.accept(ds);
            tran.commit();
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    /**
     *
     * @param <T>
     * @param <E>
     * @param ds
     * @param cmd
     * @return
     * @throws E
     */
    @Beta
    public static <T, E extends Throwable> T callNotInStartedTransaction(final javax.sql.DataSource ds, final Throwables.Callable<T, E> cmd) throws E {
        final SQLTransaction tran = SQLTransaction.getTransaction(ds, CreatedBy.JDBC_UTIL);

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) {
            JdbcUtil.disableSpringTransactional(true);

            try {
                if (tran == null) {
                    return cmd.call();
                } else {
                    return tran.callNotInMe(cmd);
                }
            } finally {
                JdbcUtil.disableSpringTransactional(false);
            }
        } else {
            if (tran == null) {
                return cmd.call();
            } else {
                return tran.callNotInMe(cmd);
            }
        }
    }

    /**
     *
     * @param <T>
     * @param <E>
     * @param ds
     * @param cmd
     * @return
     * @throws E
     */
    @Beta
    public static <T, E extends Throwable> T callNotInStartedTransaction(final javax.sql.DataSource ds,
            final Throwables.Function<javax.sql.DataSource, T, E> cmd) throws E {
        final SQLTransaction tran = SQLTransaction.getTransaction(ds, CreatedBy.JDBC_UTIL);

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) {
            JdbcUtil.disableSpringTransactional(true);

            try {
                if (tran == null) {
                    return cmd.apply(ds);
                } else {
                    return tran.callNotInMe(() -> cmd.apply(ds));
                }
            } finally {
                JdbcUtil.disableSpringTransactional(false);
            }
        } else {
            if (tran == null) {
                return cmd.apply(ds);
            } else {
                return tran.callNotInMe(() -> cmd.apply(ds));
            }
        }
    }

    /**
     *
     * @param <E>
     * @param ds
     * @param cmd
     * @return
     * @throws E
     */
    @Beta
    public static <E extends Throwable> void runNotInStartedTransaction(final javax.sql.DataSource ds, final Throwables.Runnable<E> cmd) throws E {
        final SQLTransaction tran = SQLTransaction.getTransaction(ds, CreatedBy.JDBC_UTIL);

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) {
            JdbcUtil.disableSpringTransactional(true);

            try {
                if (tran == null) {
                    cmd.run();
                } else {
                    tran.runNotInMe(cmd);
                }
            } finally {
                JdbcUtil.disableSpringTransactional(false);
            }
        } else {
            if (tran == null) {
                cmd.run();
            } else {
                tran.runNotInMe(cmd);
            }
        }
    }

    /**
     *
     * @param <E>
     * @param ds
     * @param cmd
     * @return
     * @throws E
     */
    @Beta
    public static <E extends Throwable> void runNotInStartedTransaction(final javax.sql.DataSource ds, final Throwables.Consumer<javax.sql.DataSource, E> cmd)
            throws E {
        final SQLTransaction tran = SQLTransaction.getTransaction(ds, CreatedBy.JDBC_UTIL);

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) {
            JdbcUtil.disableSpringTransactional(true);

            try {
                if (tran == null) {
                    cmd.accept(ds);
                } else {
                    tran.runNotInMe(() -> cmd.accept(ds));
                }
            } finally {
                JdbcUtil.disableSpringTransactional(false);
            }
        } else {
            if (tran == null) {
                cmd.accept(ds);
            } else {
                tran.runNotInMe(() -> cmd.accept(ds));
            }
        }
    }

    /**
     * Gets the SQL operation.
     *
     * @param sql
     * @return
     */
    static SQLOperation getSQLOperation(String sql) {
        if (StringUtil.startsWithIgnoreCase(sql.trim(), "select ")) {
            return SQLOperation.SELECT;
        } else if (StringUtil.startsWithIgnoreCase(sql.trim(), "update ")) {
            return SQLOperation.UPDATE;
        } else if (StringUtil.startsWithIgnoreCase(sql.trim(), "insert ")) {
            return SQLOperation.INSERT;
        } else if (StringUtil.startsWithIgnoreCase(sql.trim(), "delete ")) {
            return SQLOperation.DELETE;
        } else {
            for (SQLOperation so : SQLOperation.values()) {
                if (StringUtil.startsWithIgnoreCase(sql.trim(), so.name())) {
                    return so;
                }
            }
        }

        return SQLOperation.UNKNOWN;
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param sql
     * @return
     * @throws SQLException the SQL exception
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql) throws SQLException {
        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareQuery(tran.connection(), sql);
        } else {
            PreparedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareQuery(conn, sql).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param sql
     * @param autoGeneratedKeys
     * @return
     * @throws SQLException the SQL exception
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql, final boolean autoGeneratedKeys) throws SQLException {
        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareQuery(tran.connection(), sql, autoGeneratedKeys);
        } else {
            PreparedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareQuery(conn, sql, autoGeneratedKeys).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     *
     * @param ds
     * @param sql
     * @param returnColumnIndexes
     * @return
     * @throws SQLException the SQL exception
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql, final int[] returnColumnIndexes) throws SQLException {
        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareQuery(tran.connection(), sql, returnColumnIndexes);
        } else {
            PreparedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareQuery(conn, sql, returnColumnIndexes).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     *
     * @param ds
     * @param sql
     * @param returnColumnNames
     * @return
     * @throws SQLException the SQL exception
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql, final String[] returnColumnNames) throws SQLException {
        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareQuery(tran.connection(), sql, returnColumnNames);
        } else {
            PreparedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareQuery(conn, sql, returnColumnNames).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     *
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param sql
     * @param stmtCreator the created {@code PreparedStatement} will be closed after any execution methods in {@code PreparedQuery/PreparedCallableQuery} is called.
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/list/execute/....
     * @return
     * @throws SQLException the SQL exception
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareQuery(tran.connection(), sql, stmtCreator);
        } else {
            PreparedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareQuery(conn, sql, stmtCreator).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareQuery(dataSource.getConnection(), sql);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param sql
     * @return
     * @throws SQLException the SQL exception
     */
    public static PreparedQuery prepareQuery(final Connection conn, final String sql) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");

        return new PreparedQuery(prepareStatement(conn, sql));
    }

    /**
     *
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareQuery(dataSource.getConnection(), sql, autoGeneratedKeys);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param sql
     * @param autoGeneratedKeys
     * @return
     * @throws SQLException the SQL exception
     */
    public static PreparedQuery prepareQuery(final Connection conn, final String sql, final boolean autoGeneratedKeys) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");

        return new PreparedQuery(prepareStatement(conn, sql, autoGeneratedKeys));
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareQuery(dataSource.getConnection(), sql, returnColumnIndexes);
     * </code>
     * </pre>
     *
     * @param conn
     * @param sql
     * @param returnColumnIndexes
     * @return
     * @throws SQLException the SQL exception
     */
    public static PreparedQuery prepareQuery(final Connection conn, final String sql, final int[] returnColumnIndexes) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");
        N.checkArgNotNullOrEmpty(returnColumnIndexes, "returnColumnIndexes");

        return new PreparedQuery(prepareStatement(conn, sql, returnColumnIndexes));
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareQuery(dataSource.getConnection(), sql, returnColumnNames);
     * </code>
     * </pre>
     *
     * @param conn
     * @param sql
     * @param returnColumnNames
     * @return
     * @throws SQLException the SQL exception
     */
    public static PreparedQuery prepareQuery(final Connection conn, final String sql, final String[] returnColumnNames) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");
        N.checkArgNotNullOrEmpty(returnColumnNames, "returnColumnNames");

        return new PreparedQuery(prepareStatement(conn, sql, returnColumnNames));
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareQuery(dataSource.getConnection(), sql, stmtCreator);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param sql
     * @param stmtCreator the created {@code PreparedStatement} will be closed after any execution methods in {@code PreparedQuery/PreparedCallableQuery} is called.
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/list/execute/....
     * @return
     * @throws SQLException the SQL exception
     */
    public static PreparedQuery prepareQuery(final Connection conn, final String sql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");
        N.checkArgNotNull(stmtCreator, "stmtCreator");

        return new PreparedQuery(prepareStatement(conn, sql, stmtCreator));
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @return
     * @throws SQLException the SQL exception
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql) throws SQLException {
        final SQLTransaction tran = getTransaction(ds, namedSql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareNamedQuery(conn, namedSql).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param autoGeneratedKeys
     * @return
     * @throws SQLException the SQL exception
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql, final boolean autoGeneratedKeys) throws SQLException {
        final SQLTransaction tran = getTransaction(ds, namedSql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql, autoGeneratedKeys);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareNamedQuery(conn, namedSql, autoGeneratedKeys).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param returnColumnIndexes
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql, final int[] returnColumnIndexes) throws SQLException {
        final SQLTransaction tran = getTransaction(ds, namedSql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql, returnColumnIndexes);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareNamedQuery(conn, namedSql, returnColumnIndexes).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param returnColumnNames
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql, final String[] returnColumnNames) throws SQLException {
        final SQLTransaction tran = getTransaction(ds, namedSql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql, returnColumnNames);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareNamedQuery(conn, namedSql, returnColumnNames).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     *
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param stmtCreator the created {@code PreparedStatement} will be closed after any execution methods in {@code NamedQuery/PreparedCallableQuery} is called.
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/list/execute/....
     * @return
     * @throws SQLException the SQL exception
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        final SQLTransaction tran = getTransaction(ds, namedSql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql, stmtCreator);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareNamedQuery(conn, namedSql, stmtCreator).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSql);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final String namedSql) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(namedSql, "namedSql");

        final ParsedSql parsedSql = parseNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, parsedSql), parsedSql);
    }

    /**
     *
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSql, autoGeneratedKeys);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param autoGeneratedKeys
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final String namedSql, final boolean autoGeneratedKeys) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(namedSql, "namedSql");

        final ParsedSql parsedSql = parseNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, parsedSql, autoGeneratedKeys), parsedSql);
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSql);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param returnColumnIndexes
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final String namedSql, final int[] returnColumnIndexes) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(namedSql, "namedSql");
        N.checkArgNotNullOrEmpty(returnColumnIndexes, "returnColumnIndexes");

        final ParsedSql parsedSql = parseNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, parsedSql, returnColumnIndexes), parsedSql);
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSql);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param returnColumnNames
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final String namedSql, final String[] returnColumnNames) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(namedSql, "namedSql");
        N.checkArgNotNullOrEmpty(returnColumnNames, "returnColumnNames");

        final ParsedSql parsedSql = parseNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, parsedSql, returnColumnNames), parsedSql);
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSql, stmtCreator);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param stmtCreator the created {@code PreparedStatement} will be closed after any execution methods in {@code NamedQuery/PreparedCallableQuery} is called.
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/list/execute/....
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final String namedSql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(namedSql, "namedSql");
        N.checkArgNotNull(stmtCreator, "stmtCreator");

        final ParsedSql parsedSql = parseNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, parsedSql, stmtCreator), parsedSql);
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @return
     * @throws SQLException the SQL exception
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final ParsedSql namedSql) throws SQLException {
        validateNamedSql(namedSql);

        final SQLTransaction tran = getTransaction(ds, namedSql.getParameterizedSql(), CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareNamedQuery(conn, namedSql).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param autoGeneratedKeys
     * @return
     * @throws SQLException the SQL exception
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final ParsedSql namedSql, final boolean autoGeneratedKeys) throws SQLException {
        validateNamedSql(namedSql);

        final SQLTransaction tran = getTransaction(ds, namedSql.getParameterizedSql(), CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql, autoGeneratedKeys);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareNamedQuery(conn, namedSql, autoGeneratedKeys).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param returnColumnIndexes
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final ParsedSql namedSql, final int[] returnColumnIndexes) throws SQLException {
        validateNamedSql(namedSql);

        final SQLTransaction tran = getTransaction(ds, namedSql.getParameterizedSql(), CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql, returnColumnIndexes);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareNamedQuery(conn, namedSql, returnColumnIndexes).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param returnColumnNames
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final ParsedSql namedSql, final String[] returnColumnNames) throws SQLException {
        validateNamedSql(namedSql);

        final SQLTransaction tran = getTransaction(ds, namedSql.getParameterizedSql(), CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql, returnColumnNames);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareNamedQuery(conn, namedSql, returnColumnNames).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     *
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param stmtCreator the created {@code PreparedStatement} will be closed after any execution methods in {@code NamedQuery/PreparedCallableQuery} is called.
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/list/execute/....
     * @return
     * @throws SQLException the SQL exception
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final ParsedSql namedSql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        validateNamedSql(namedSql);

        final SQLTransaction tran = getTransaction(ds, namedSql.getParameterizedSql(), CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql, stmtCreator);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareNamedQuery(conn, namedSql, stmtCreator).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSql);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final ParsedSql namedSql) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(namedSql, "namedSql");
        validateNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, namedSql), namedSql);
    }

    /**
     *
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSql, autoGeneratedKeys);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param autoGeneratedKeys
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final ParsedSql namedSql, final boolean autoGeneratedKeys) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(namedSql, "namedSql");
        validateNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, namedSql, autoGeneratedKeys), namedSql);
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSql);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param returnColumnIndexes
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final ParsedSql namedSql, final int[] returnColumnIndexes) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(namedSql, "namedSql");
        N.checkArgNotNullOrEmpty(returnColumnIndexes, "returnColumnIndexes");
        validateNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, namedSql, returnColumnIndexes), namedSql);
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSql);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param returnColumnNames
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final ParsedSql namedSql, final String[] returnColumnNames) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(namedSql, "namedSql");
        N.checkArgNotNullOrEmpty(returnColumnNames, "returnColumnNames");
        validateNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, namedSql, returnColumnNames), namedSql);
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSql, stmtCreator);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param stmtCreator the created {@code PreparedStatement} will be closed after any execution methods in {@code NamedQuery/PreparedCallableQuery} is called.
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/list/execute/....
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final ParsedSql namedSql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(namedSql, "namedSql");
        N.checkArgNotNull(stmtCreator, "stmtCreator");
        validateNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, namedSql, stmtCreator), namedSql);
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param sql
     * @return
     * @throws SQLException the SQL exception
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static PreparedCallableQuery prepareCallableQuery(final javax.sql.DataSource ds, final String sql) throws SQLException {
        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareCallableQuery(tran.connection(), sql);
        } else {
            PreparedCallableQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareCallableQuery(conn, sql).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param sql
     * @param stmtCreator the created {@code CallableStatement} will be closed after any execution methods in {@code PreparedQuery/PreparedCallableQuery} is called.
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/list/execute/....
     * @return
     * @throws SQLException the SQL exception
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static PreparedCallableQuery prepareCallableQuery(final javax.sql.DataSource ds, final String sql,
            final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator) throws SQLException {
        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareCallableQuery(tran.connection(), sql, stmtCreator);
        } else {
            PreparedCallableQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareCallableQuery(conn, sql, stmtCreator).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareCallableQuery(dataSource.getConnection(), sql);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param sql
     * @return
     * @throws SQLException the SQL exception
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static PreparedCallableQuery prepareCallableQuery(final Connection conn, final String sql) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");

        return new PreparedCallableQuery(prepareCallable(conn, sql));
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareCallableQuery(dataSource.getConnection(), sql, stmtCreator);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param sql
     * @param stmtCreator the created {@code CallableStatement} will be closed after any execution methods in {@code PreparedQuery/PreparedCallableQuery} is called.
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/list/execute/....
     * @return
     * @throws SQLException the SQL exception
     */
    public static PreparedCallableQuery prepareCallableQuery(final Connection conn, final String sql,
            final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");
        N.checkArgNotNull(stmtCreator, "stmtCreator");

        return new PreparedCallableQuery(prepareCallable(conn, sql, stmtCreator));
    }

    static PreparedStatement prepareStatement(final Connection conn, final String sql) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + sql);
        }

        return conn.prepareStatement(sql);
    }

    static PreparedStatement prepareStatement(final Connection conn, final String sql, final boolean autoGeneratedKeys) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + sql);
        }

        return conn.prepareStatement(sql, autoGeneratedKeys ? Statement.RETURN_GENERATED_KEYS : Statement.NO_GENERATED_KEYS);
    }

    static PreparedStatement prepareStatement(final Connection conn, final String sql, final int[] returnColumnIndexes) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + sql);
        }

        return conn.prepareStatement(sql, returnColumnIndexes);
    }

    static PreparedStatement prepareStatement(final Connection conn, final String sql, final String[] returnColumnNames) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + sql);
        }

        return conn.prepareStatement(sql, returnColumnNames);
    }

    static PreparedStatement prepareStatement(final Connection conn, final String sql, final int resultSetType, final int resultSetConcurrency)
            throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + sql);
        }

        return conn.prepareStatement(sql, resultSetType, resultSetConcurrency);
    }

    static PreparedStatement prepareStatement(final Connection conn, final String sql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + sql);
        }

        return stmtCreator.apply(conn, sql);
    }

    static PreparedStatement prepareStatement(final Connection conn, final ParsedSql parsedSql) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + parsedSql.sql());
        }

        return conn.prepareStatement(parsedSql.getParameterizedSql());
    }

    static PreparedStatement prepareStatement(final Connection conn, final ParsedSql parsedSql, final boolean autoGeneratedKeys) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + parsedSql.sql());
        }

        return conn.prepareStatement(parsedSql.getParameterizedSql(), autoGeneratedKeys ? Statement.RETURN_GENERATED_KEYS : Statement.NO_GENERATED_KEYS);
    }

    static PreparedStatement prepareStatement(final Connection conn, final ParsedSql parsedSql, final int[] returnColumnIndexes) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + parsedSql.sql());
        }

        return conn.prepareStatement(parsedSql.getParameterizedSql(), returnColumnIndexes);
    }

    static PreparedStatement prepareStatement(final Connection conn, final ParsedSql parsedSql, final String[] returnColumnNames) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + parsedSql.sql());
        }

        return conn.prepareStatement(parsedSql.getParameterizedSql(), returnColumnNames);
    }

    static PreparedStatement prepareStatement(final Connection conn, final ParsedSql parsedSql, final int resultSetType, final int resultSetConcurrency)
            throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + parsedSql.sql());
        }

        return conn.prepareStatement(parsedSql.getParameterizedSql(), resultSetType, resultSetConcurrency);
    }

    static PreparedStatement prepareStatement(final Connection conn, final ParsedSql parsedSql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + parsedSql.sql());
        }

        return stmtCreator.apply(conn, parsedSql.getParameterizedSql());
    }

    static CallableStatement prepareCallable(final Connection conn, final String sql) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + sql);
        }

        return conn.prepareCall(sql);
    }

    static CallableStatement prepareCallable(final Connection conn, final String sql,
            final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + sql);
        }

        return stmtCreator.apply(conn, sql);
    }

    static CallableStatement prepareCallable(final Connection conn, final ParsedSql parsedSql) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + parsedSql.sql());
        }

        return conn.prepareCall(parsedSql.getParameterizedSql());
    }

    static CallableStatement prepareCallable(final Connection conn, final ParsedSql parsedSql,
            final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + parsedSql.sql());
        }

        return stmtCreator.apply(conn, parsedSql.getParameterizedSql());
    }

    /**
     *
     * @param conn
     * @param sql
     * @param parameters
     * @return
     * @throws SQLException the SQL exception
     */
    @SafeVarargs
    static PreparedStatement prepareStmt(final Connection conn, final String sql, final Object... parameters) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");

        final ParsedSql parsedSql = ParsedSql.parse(sql);
        final PreparedStatement stmt = prepareStatement(conn, parsedSql);

        if (N.notNullOrEmpty(parameters)) {
            StatementSetter.DEFAULT.setParameters(parsedSql, stmt, parameters);
        }

        return stmt;
    }

    /**
     *
     * @param conn
     * @param sql
     * @param parameters
     * @return
     * @throws SQLException the SQL exception
     */
    @SafeVarargs
    static CallableStatement prepareCall(final Connection conn, final String sql, final Object... parameters) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");

        final ParsedSql parsedSql = ParsedSql.parse(sql);
        final CallableStatement stmt = prepareCallable(conn, parsedSql);

        if (N.notNullOrEmpty(parameters)) {
            StatementSetter.DEFAULT.setParameters(parsedSql, stmt, parameters);
        }

        return stmt;
    }

    /**
     * Batch prepare statement.
     *
     * @param conn
     * @param sql
     * @param parametersList
     * @return
     * @throws SQLException the SQL exception
     */
    static PreparedStatement prepareBatchStmt(final Connection conn, final String sql, final List<?> parametersList) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");

        final ParsedSql parsedSql = ParsedSql.parse(sql);
        final PreparedStatement stmt = prepareStatement(conn, parsedSql);

        for (Object parameters : parametersList) {
            StatementSetter.DEFAULT.setParameters(parsedSql, stmt, N.asArray(parameters));
            stmt.addBatch();
        }

        return stmt;
    }

    /**
     *
     * @param conn
     * @param sql
     * @param parametersList
     * @return
     * @throws SQLException the SQL exception
     */
    static CallableStatement prepareBatchCall(final Connection conn, final String sql, final List<?> parametersList) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");

        final ParsedSql parsedSql = ParsedSql.parse(sql);
        final CallableStatement stmt = prepareCallable(conn, parsedSql);

        for (Object parameters : parametersList) {
            StatementSetter.DEFAULT.setParameters(parsedSql, stmt, N.asArray(parameters));
            stmt.addBatch();
        }

        return stmt;
    }

    /**
     * Creates the named SQL.
     *
     * @param namedSql
     * @return
     */
    private static ParsedSql parseNamedSql(final String namedSql) {
        N.checkArgNotNullOrEmpty(namedSql, "namedSql");

        final ParsedSql parsedSql = ParsedSql.parse(namedSql);

        validateNamedSql(parsedSql);

        return parsedSql;
    }

    private static void validateNamedSql(final ParsedSql namedSql) {
        if (namedSql.getNamedParameters().size() != namedSql.getParameterCount()) {
            throw new IllegalArgumentException("\"" + namedSql.sql() + "\" is not a valid named sql:");
        }
    }

    private static SQLTransaction getTransaction(final javax.sql.DataSource ds, final String sql, final CreatedBy createdBy) {
        final SQLOperation sqlOperation = JdbcUtil.getSQLOperation(sql);
        final SQLTransaction tran = SQLTransaction.getTransaction(ds, createdBy);

        if (tran == null || (tran.isForUpdateOnly() && sqlOperation == SQLOperation.SELECT)) {
            return null;
        } else {
            return tran;
        }
    }

    /**
     *
     * @param ds
     * @param sql
     * @param parameters
     * @return
     * @throws SQLException the SQL exception
     */
    @SafeVarargs
    public static DataSet executeQuery(final javax.sql.DataSource ds, final String sql, final Object... parameters) throws SQLException {
        N.checkArgNotNull(ds, "ds");
        N.checkArgNotNull(sql, "sql");

        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return executeQuery(tran.connection(), sql, parameters);
        } else {
            final Connection conn = getConnection(ds);

            try {
                return executeQuery(conn, sql, parameters);
            } finally {
                releaseConnection(conn, ds);
            }
        }
    }

    /**
     *
     * @param conn
     * @param sql
     * @param parameters
     * @return
     * @throws SQLException the SQL exception
     */
    @SafeVarargs
    public static DataSet executeQuery(final Connection conn, final String sql, final Object... parameters) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");

        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            stmt = prepareStmt(conn, sql, parameters);

            stmt.setFetchDirection(ResultSet.FETCH_FORWARD);

            rs = executeQuery(stmt);

            return extractData(rs);
        } finally {
            closeQuietly(rs, stmt);
        }
    }

    //    /**
    //     *
    //     * @param stmt
    //     * @return
    //     * @throws SQLException the SQL exception
    //     */
    //    public static DataSet executeQuery(final PreparedStatement stmt) throws SQLException {
    //        ResultSet rs = null;
    //
    //        try {
    //            rs = executeQuerry(stmt);
    //
    //            return extractData(rs);
    //        } finally {
    //            closeQuietly(rs);
    //        }
    //    }

    /**
     *
     * @param ds
     * @param sql
     * @param parameters
     * @return
     * @throws SQLException the SQL exception
     */
    @SafeVarargs
    public static int executeUpdate(final javax.sql.DataSource ds, final String sql, final Object... parameters) throws SQLException {
        N.checkArgNotNull(ds, "ds");
        N.checkArgNotNull(sql, "sql");

        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return executeUpdate(tran.connection(), sql, parameters);
        } else {
            final Connection conn = getConnection(ds);

            try {
                return executeUpdate(conn, sql, parameters);
            } finally {
                releaseConnection(conn, ds);
            }
        }
    }

    /**
     *
     * @param conn
     * @param sql
     * @param parameters
     * @return
     * @throws SQLException the SQL exception
     */
    @SafeVarargs
    public static int executeUpdate(final Connection conn, final String sql, final Object... parameters) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");

        PreparedStatement stmt = null;

        try {
            stmt = prepareStmt(conn, sql, parameters);

            return executeUpdate(stmt);
        } finally {
            closeQuietly(stmt);
        }
    }

    /**
     *
     * @param ds
     * @param sql
     * @param listOfParameters
     * @return
     * @throws SQLException the SQL exception
     */
    public static int executeBatchUpdate(final javax.sql.DataSource ds, final String sql, final List<?> listOfParameters) throws SQLException {
        return executeBatchUpdate(ds, sql, listOfParameters, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     *
     * @param ds
     * @param sql
     * @param listOfParameters
     * @param batchSize
     * @return
     * @throws SQLException the SQL exception
     */
    public static int executeBatchUpdate(final javax.sql.DataSource ds, final String sql, final List<?> listOfParameters, final int batchSize)
            throws SQLException {
        N.checkArgNotNull(ds, "ds");
        N.checkArgNotNull(sql, "sql");
        N.checkArgPositive(batchSize, "batchSize");

        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return executeBatchUpdate(tran.connection(), sql, listOfParameters, batchSize);
        } else if (listOfParameters.size() <= batchSize) {
            final Connection conn = getConnection(ds);

            try {
                return executeBatchUpdate(conn, sql, listOfParameters, batchSize);
            } finally {
                releaseConnection(conn, ds);
            }
        } else {
            final SQLTransaction tran2 = JdbcUtil.beginTransaction(ds);
            int ret = 0;

            try {
                ret = executeBatchUpdate(tran2.connection(), sql, listOfParameters, batchSize);
                tran2.commit();
            } finally {
                tran2.rollbackIfNotCommitted();
            }

            return ret;
        }
    }

    /**
     * Execute batch update.
     *
     * @param conn
     * @param sql
     * @param listOfParameters
     * @return
     * @throws SQLException the SQL exception
     */
    public static int executeBatchUpdate(final Connection conn, final String sql, final List<?> listOfParameters) throws SQLException {
        return executeBatchUpdate(conn, sql, listOfParameters, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Execute batch update.
     *
     * @param conn
     * @param sql
     * @param listOfParameters
     * @param batchSize
     * @return
     * @throws SQLException the SQL exception
     */
    public static int executeBatchUpdate(final Connection conn, final String sql, final List<?> listOfParameters, final int batchSize) throws SQLException {
        N.checkArgNotNull(conn);
        N.checkArgNotNull(sql);
        N.checkArgPositive(batchSize, "batchSize");

        if (N.isNullOrEmpty(listOfParameters)) {
            return 0;
        }

        final ParsedSql parsedSql = ParsedSql.parse(sql);
        final boolean originalAutoCommit = conn.getAutoCommit();
        PreparedStatement stmt = null;
        boolean noException = false;

        try {
            if (originalAutoCommit && listOfParameters.size() > batchSize) {
                conn.setAutoCommit(false);
            }

            stmt = prepareStatement(conn, parsedSql);

            int res = 0;
            int idx = 0;

            for (Object parameters : listOfParameters) {
                StatementSetter.DEFAULT.setParameters(parsedSql, stmt, parameters);
                stmt.addBatch();

                if (++idx % batchSize == 0) {
                    res += N.sum(executeBatch(stmt));
                }
            }

            if (idx % batchSize != 0) {
                res += N.sum(executeBatch(stmt));
            }

            noException = true;

            return res;
        } finally {
            if (originalAutoCommit && listOfParameters.size() > batchSize) {
                try {
                    if (noException) {
                        conn.commit();
                    } else {
                        conn.rollback();
                    }
                } finally {
                    try {
                        conn.setAutoCommit(true);
                    } finally {
                        JdbcUtil.closeQuietly(stmt);
                    }
                }
            } else {
                JdbcUtil.closeQuietly(stmt);
            }
        }
    }

    /**
     *
     * @param ds
     * @param sql
     * @param listOfParameters
     * @return
     * @throws SQLException the SQL exception
     */
    public static long executeLargeBatchUpdate(final javax.sql.DataSource ds, final String sql, final List<?> listOfParameters) throws SQLException {
        return executeLargeBatchUpdate(ds, sql, listOfParameters, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     *
     * @param ds
     * @param sql
     * @param listOfParameters
     * @param batchSize
     * @return
     * @throws SQLException the SQL exception
     */
    public static long executeLargeBatchUpdate(final javax.sql.DataSource ds, final String sql, final List<?> listOfParameters, final int batchSize)
            throws SQLException {
        N.checkArgNotNull(ds, "ds");
        N.checkArgNotNull(sql, "sql");
        N.checkArgPositive(batchSize, "batchSize");

        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return executeLargeBatchUpdate(tran.connection(), sql, listOfParameters, batchSize);
        } else if (listOfParameters.size() <= batchSize) {
            final Connection conn = getConnection(ds);

            try {
                return executeLargeBatchUpdate(conn, sql, listOfParameters, batchSize);
            } finally {
                releaseConnection(conn, ds);
            }
        } else {
            final SQLTransaction tran2 = JdbcUtil.beginTransaction(ds);
            long ret = 0;

            try {
                ret = executeLargeBatchUpdate(tran2.connection(), sql, listOfParameters, batchSize);
                tran2.commit();
            } finally {
                tran2.rollbackIfNotCommitted();
            }

            return ret;
        }
    }

    /**
     * Execute batch update.
     *
     * @param conn
     * @param sql
     * @param listOfParameters
     * @return
     * @throws SQLException the SQL exception
     */
    public static long executeLargeBatchUpdate(final Connection conn, final String sql, final List<?> listOfParameters) throws SQLException {
        return executeLargeBatchUpdate(conn, sql, listOfParameters, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Execute batch update.
     *
     * @param conn
     * @param sql
     * @param listOfParameters
     * @param batchSize
     * @return
     * @throws SQLException the SQL exception
     */
    public static long executeLargeBatchUpdate(final Connection conn, final String sql, final List<?> listOfParameters, final int batchSize)
            throws SQLException {
        N.checkArgNotNull(conn);
        N.checkArgNotNull(sql);
        N.checkArgPositive(batchSize, "batchSize");

        if (N.isNullOrEmpty(listOfParameters)) {
            return 0;
        }

        final ParsedSql parsedSql = ParsedSql.parse(sql);
        final boolean originalAutoCommit = conn.getAutoCommit();
        PreparedStatement stmt = null;
        boolean noException = false;

        try {
            if (originalAutoCommit && listOfParameters.size() > batchSize) {
                conn.setAutoCommit(false);
            }

            stmt = prepareStatement(conn, parsedSql);

            long res = 0;
            int idx = 0;

            for (Object parameters : listOfParameters) {
                StatementSetter.DEFAULT.setParameters(parsedSql, stmt, parameters);
                stmt.addBatch();

                if (++idx % batchSize == 0) {
                    res += N.sum(executeLargeBatch(stmt));
                }
            }

            if (idx % batchSize != 0) {
                res += N.sum(executeLargeBatch(stmt));
            }

            noException = true;

            return res;
        } finally {
            if (originalAutoCommit && listOfParameters.size() > batchSize) {
                try {
                    if (noException) {
                        conn.commit();
                    } else {
                        conn.rollback();
                    }
                } finally {
                    try {
                        conn.setAutoCommit(true);
                    } finally {
                        JdbcUtil.closeQuietly(stmt);
                    }
                }
            } else {
                JdbcUtil.closeQuietly(stmt);
            }
        }
    }

    /**
     *
     * @param ds
     * @param sql
     * @param parameters
     * @return
     * @throws SQLException the SQL exception
     */
    @SafeVarargs
    public static boolean execute(final javax.sql.DataSource ds, final String sql, final Object... parameters) throws SQLException {
        N.checkArgNotNull(ds, "ds");
        N.checkArgNotNull(sql, "sql");

        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return execute(tran.connection(), sql, parameters);
        } else {
            final Connection conn = getConnection(ds);

            try {
                return execute(conn, sql, parameters);
            } finally {
                releaseConnection(conn, ds);
            }
        }
    }

    /**
     *
     * @param conn
     * @param sql
     * @param parameters
     * @return true, if successful
     * @throws SQLException the SQL exception
     */
    @SafeVarargs
    public static boolean execute(final Connection conn, final String sql, final Object... parameters) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");

        PreparedStatement stmt = null;

        try {
            stmt = prepareStmt(conn, sql, parameters);

            return JdbcUtil.execute(stmt);
        } finally {
            closeQuietly(stmt);
        }
    }

    static ResultSet executeQuery(PreparedStatement stmt) throws SQLException {
        if (logger.isInfoEnabled() && minExecutionTimeForSQLPerfLog_TL.get() >= 0) {
            final long startTime = System.currentTimeMillis();

            try {
                return stmt.executeQuery();
            } finally {
                final long elapsedTime = System.currentTimeMillis() - startTime;

                if (elapsedTime >= minExecutionTimeForSQLPerfLog_TL.get()) {
                    logger.info("[SQL-PERF]: " + elapsedTime + ", " + stmt.toString());
                }

                try {
                    stmt.clearParameters();
                } catch (SQLException e) {
                    logger.error("Failed to clear parameters after executeQuery", e);
                }
            }
        } else {
            try {
                return stmt.executeQuery();
            } finally {
                try {
                    stmt.clearParameters();
                } catch (SQLException e) {
                    logger.error("Failed to clear parameters after executeQuery", e);
                }
            }
        }
    }

    static int executeUpdate(PreparedStatement stmt) throws SQLException {
        if (logger.isInfoEnabled() && minExecutionTimeForSQLPerfLog_TL.get() >= 0) {
            final long startTime = System.currentTimeMillis();

            try {
                return stmt.executeUpdate();
            } finally {
                final long elapsedTime = System.currentTimeMillis() - startTime;

                if (elapsedTime >= minExecutionTimeForSQLPerfLog_TL.get()) {
                    logger.info("[SQL-PERF]: " + elapsedTime + ", " + stmt.toString());
                }

                try {
                    stmt.clearParameters();
                } catch (SQLException e) {
                    logger.error("Failed to clear parameters after executeUpdate", e);
                }
            }
        } else {
            try {
                return stmt.executeUpdate();
            } finally {
                try {
                    stmt.clearParameters();
                } catch (SQLException e) {
                    logger.error("Failed to clear parameters after executeUpdate", e);
                }
            }
        }
    }

    static int[] executeBatch(Statement stmt) throws SQLException {
        if (logger.isInfoEnabled() && minExecutionTimeForSQLPerfLog_TL.get() >= 0) {
            final long startTime = System.currentTimeMillis();

            try {
                return stmt.executeBatch();
            } finally {
                final long elapsedTime = System.currentTimeMillis() - startTime;

                if (elapsedTime >= minExecutionTimeForSQLPerfLog_TL.get()) {
                    logger.info("[SQL-PERF]: " + elapsedTime + ", " + stmt.toString());
                }

                try {
                    stmt.clearBatch();
                } catch (SQLException e) {
                    logger.error("Failed to clear batch parameters after executeBatch", e);
                }
            }
        } else {
            try {
                return stmt.executeBatch();
            } finally {
                try {
                    stmt.clearBatch();
                } catch (SQLException e) {
                    logger.error("Failed to clear batch parameters after executeBatch", e);
                }
            }
        }
    }

    static long[] executeLargeBatch(Statement stmt) throws SQLException {
        if (logger.isInfoEnabled() && minExecutionTimeForSQLPerfLog_TL.get() >= 0) {
            final long startTime = System.currentTimeMillis();

            try {
                return stmt.executeLargeBatch();
            } finally {
                final long elapsedTime = System.currentTimeMillis() - startTime;

                if (elapsedTime >= minExecutionTimeForSQLPerfLog_TL.get()) {
                    logger.info("[SQL-PERF]: " + elapsedTime + ", " + stmt.toString());
                }

                try {
                    stmt.clearBatch();
                } catch (SQLException e) {
                    logger.error("Failed to clear batch parameters after executeLargeBatch", e);
                }
            }
        } else {
            try {
                return stmt.executeLargeBatch();
            } finally {
                try {
                    stmt.clearBatch();
                } catch (SQLException e) {
                    logger.error("Failed to clear batch parameters after executeLargeBatch", e);
                }
            }
        }
    }

    static boolean execute(PreparedStatement stmt) throws SQLException {
        if (logger.isInfoEnabled() && minExecutionTimeForSQLPerfLog_TL.get() >= 0) {
            final long startTime = System.currentTimeMillis();

            try {
                return stmt.execute();
            } finally {
                final long elapsedTime = System.currentTimeMillis() - startTime;

                if (elapsedTime >= minExecutionTimeForSQLPerfLog_TL.get()) {
                    logger.info("[SQL-PERF]: " + elapsedTime + ", " + stmt.toString());
                }

                try {
                    stmt.clearParameters();
                } catch (SQLException e) {
                    logger.error("Failed to clear parameters after execute", e);
                }
            }
        } else {
            try {
                return stmt.execute();
            } finally {
                try {
                    stmt.clearParameters();
                } catch (SQLException e) {
                    logger.error("Failed to clear parameters after execute", e);
                }
            }
        }
    }

    static final RowFilter INTERNAL_DUMMY_ROW_FILTER = RowFilter.ALWAYS_TRUE;

    static final RowExtractor INTERNAL_DUMMY_ROW_EXTRACTOR = (rs, outputRow) -> {
        throw new UnsupportedOperationException("DO NOT CALL ME.");
    };

    /**
     *
     * @param rs
     * @return
     * @throws SQLException the SQL exception
     */
    public static DataSet extractData(final ResultSet rs) throws SQLException {
        return extractData(rs, false);
    }

    /**
     *
     * @param rs
     * @param closeResultSet
     * @return
     * @throws SQLException the SQL exception
     */
    public static DataSet extractData(final ResultSet rs, final boolean closeResultSet) throws SQLException {
        return extractData(rs, 0, Integer.MAX_VALUE, closeResultSet);
    }

    /**
     *
     * @param rs
     * @param offset
     * @param count
     * @return
     * @throws SQLException the SQL exception
     */
    public static DataSet extractData(final ResultSet rs, final int offset, final int count) throws SQLException {
        return extractData(rs, offset, count, false);
    }

    /**
     *
     * @param rs
     * @param offset
     * @param count
     * @param closeResultSet
     * @return
     * @throws SQLException the SQL exception
     */
    public static DataSet extractData(final ResultSet rs, final int offset, final int count, final boolean closeResultSet) throws SQLException {
        return extractData(rs, offset, count, INTERNAL_DUMMY_ROW_FILTER, INTERNAL_DUMMY_ROW_EXTRACTOR, closeResultSet);
    }

    /**
     *
     * @param rs
     * @param offset
     * @param count
     * @param filter
     * @param closeResultSet
     * @return
     * @throws SQLException the SQL exception
     */
    public static DataSet extractData(final ResultSet rs, int offset, int count, final RowFilter filter, final boolean closeResultSet) throws SQLException {
        return extractData(rs, offset, count, filter, INTERNAL_DUMMY_ROW_EXTRACTOR, closeResultSet);
    }

    /**
     *
     * @param rs
     * @param offset
     * @param count
     * @param rowExtractor
     * @param closeResultSet
     * @return
     * @throws SQLException the SQL exception
     */
    public static DataSet extractData(final ResultSet rs, int offset, int count, final RowExtractor rowExtractor, final boolean closeResultSet)
            throws SQLException {
        return extractData(rs, offset, count, INTERNAL_DUMMY_ROW_FILTER, rowExtractor, closeResultSet);
    }

    /**
     *
     * @param rs
     * @param offset
     * @param count
     * @param filter
     * @param rowExtractor
     * @param closeResultSet
     * @return
     * @throws SQLException the SQL exception
     */
    public static DataSet extractData(final ResultSet rs, int offset, int count, final RowFilter filter, final RowExtractor rowExtractor,
            final boolean closeResultSet) throws SQLException {
        N.checkArgNotNull(rs, "ResultSet");
        N.checkArgNotNegative(offset, "offset");
        N.checkArgNotNegative(count, "count");
        N.checkArgNotNull(filter, "filter");
        N.checkArgNotNull(rowExtractor, "rowExtractor");

        try {
            // TODO [performance improvement]. it will improve performance a lot if MetaData is cached.
            final ResultSetMetaData rsmd = rs.getMetaData();
            final int columnCount = rsmd.getColumnCount();
            final List<String> columnNameList = new ArrayList<>(columnCount);
            final List<List<Object>> columnList = new ArrayList<>(columnCount);

            for (int i = 0; i < columnCount;) {
                columnNameList.add(JdbcUtil.getColumnLabel(rsmd, ++i));
                columnList.add(new ArrayList<>());
            }

            JdbcUtil.skip(rs, offset);

            if (filter == INTERNAL_DUMMY_ROW_FILTER) {
                if (rowExtractor == INTERNAL_DUMMY_ROW_EXTRACTOR) {
                    while (count > 0 && rs.next()) {
                        for (int i = 0; i < columnCount;) {
                            columnList.get(i).add(JdbcUtil.getColumnValue(rs, ++i));
                        }

                        count--;
                    }
                } else {
                    final Object[] outputRow = new Object[columnCount];

                    while (count > 0 && rs.next()) {
                        rowExtractor.accept(rs, outputRow);

                        for (int i = 0; i < columnCount; i++) {
                            columnList.get(i).add(outputRow[i]);
                        }

                        count--;
                    }
                }
            } else {
                if (rowExtractor == INTERNAL_DUMMY_ROW_EXTRACTOR) {
                    while (count > 0 && rs.next()) {
                        if (filter.test(rs)) {
                            for (int i = 0; i < columnCount;) {
                                columnList.get(i).add(JdbcUtil.getColumnValue(rs, ++i));
                            }

                            count--;
                        }
                    }
                } else {
                    final Object[] outputRow = new Object[columnCount];

                    while (count > 0 && rs.next()) {
                        if (filter.test(rs)) {
                            rowExtractor.accept(rs, outputRow);

                            for (int i = 0; i < columnCount; i++) {
                                columnList.get(i).add(outputRow[i]);
                            }

                            count--;
                        }
                    }
                }
            }

            // return new RowDataSet(null, entityClass, columnNameList, columnList);
            return new RowDataSet(columnNameList, columnList);
        } finally {
            if (closeResultSet) {
                closeQuietly(rs);
            }
        }
    }

    /**
     * Does table exist.
     *
     * @param conn
     * @param tableName
     * @return true, if successful
     */
    public static boolean doesTableExist(final Connection conn, final String tableName) {
        try {
            executeQuery(conn, "SELECT 1 FROM " + tableName + " WHERE 1 > 2");

            return true;
        } catch (SQLException e) {
            if (isTableNotExistsException(e)) {
                return false;
            }

            throw new UncheckedSQLException(e);
        }
    }

    /**
     * Returns {@code true} if succeed to create table, otherwise {@code false} is returned.
     *
     * @param conn
     * @param tableName
     * @param schema
     * @return true, if successful
     */
    public static boolean createTableIfNotExists(final Connection conn, final String tableName, final String schema) {
        if (doesTableExist(conn, tableName)) {
            return false;
        }

        try {
            execute(conn, schema);

            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Returns {@code true} if succeed to drop table, otherwise {@code false} is returned.
     *
     * @param conn
     * @param tableName
     * @return true, if successful
     */
    public static boolean dropTableIfExists(final Connection conn, final String tableName) {
        try {
            if (doesTableExist(conn, tableName)) {
                execute(conn, "DROP TABLE " + tableName);

                return true;
            }
        } catch (SQLException e) {
            // ignore.
        }

        return false;
    }

    /**
     * Gets the named parameters.
     *
     * @param sql
     * @return
     */
    public static List<String> getNamedParameters(String sql) {
        return ParsedSql.parse(sql).getNamedParameters();
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final Connection conn, final String insertSQL) throws UncheckedSQLException {
        return importData(dataset, dataset.columnNameList(), conn, insertSQL);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param selectColumnNames
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final Collection<String> selectColumnNames, final Connection conn, final String insertSQL)
            throws UncheckedSQLException {
        return importData(dataset, selectColumnNames, 0, dataset.size(), conn, insertSQL);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param selectColumnNames
     * @param offset
     * @param count
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final Collection<String> selectColumnNames, final int offset, final int count, final Connection conn,
            final String insertSQL) throws UncheckedSQLException {
        return importData(dataset, selectColumnNames, offset, count, conn, insertSQL, 200, 0);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param selectColumnNames
     * @param offset
     * @param count
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @param batchSize
     * @param batchInterval
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final Collection<String> selectColumnNames, final int offset, final int count, final Connection conn,
            final String insertSQL, final int batchSize, final int batchInterval) throws UncheckedSQLException {
        return importData(dataset, selectColumnNames, offset, count, Fn.alwaysTrue(), conn, insertSQL, batchSize, batchInterval);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param <E>
     * @param dataset
     * @param selectColumnNames
     * @param offset
     * @param count
     * @param filter
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @param batchSize
     * @param batchInterval
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> int importData(final DataSet dataset, final Collection<String> selectColumnNames, final int offset, final int count,
            final Throwables.Predicate<? super Object[], E> filter, final Connection conn, final String insertSQL, final int batchSize, final int batchInterval)
            throws UncheckedSQLException, E {
        PreparedStatement stmt = null;

        try {
            stmt = prepareStatement(conn, insertSQL);

            return importData(dataset, selectColumnNames, offset, count, filter, stmt, batchSize, batchInterval);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.closeQuietly(stmt);
        }
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @param columnTypeMap
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("rawtypes")
    public static int importData(final DataSet dataset, final Connection conn, final String insertSQL, final Map<String, ? extends Type> columnTypeMap)
            throws UncheckedSQLException {
        return importData(dataset, 0, dataset.size(), conn, insertSQL, columnTypeMap);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param offset
     * @param count
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @param columnTypeMap
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("rawtypes")
    public static int importData(final DataSet dataset, final int offset, final int count, final Connection conn, final String insertSQL,
            final Map<String, ? extends Type> columnTypeMap) throws UncheckedSQLException {
        return importData(dataset, offset, count, conn, insertSQL, 200, 0, columnTypeMap);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param offset
     * @param count
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @param batchSize
     * @param batchInterval
     * @param columnTypeMap
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("rawtypes")
    public static int importData(final DataSet dataset, final int offset, final int count, final Connection conn, final String insertSQL, final int batchSize,
            final int batchInterval, final Map<String, ? extends Type> columnTypeMap) throws UncheckedSQLException {
        return importData(dataset, offset, count, Fn.alwaysTrue(), conn, insertSQL, batchSize, batchInterval, columnTypeMap);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param <E>
     * @param dataset
     * @param offset
     * @param count
     * @param filter
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @param batchSize
     * @param batchInterval
     * @param columnTypeMap
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    @SuppressWarnings("rawtypes")
    public static <E extends Exception> int importData(final DataSet dataset, final int offset, final int count,
            final Throwables.Predicate<? super Object[], E> filter, final Connection conn, final String insertSQL, final int batchSize, final int batchInterval,
            final Map<String, ? extends Type> columnTypeMap) throws UncheckedSQLException, E {
        PreparedStatement stmt = null;

        try {
            stmt = prepareStatement(conn, insertSQL);

            return importData(dataset, offset, count, filter, stmt, batchSize, batchInterval, columnTypeMap);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.closeQuietly(stmt);
        }
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final Connection conn, final String insertSQL,
            final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> stmtSetter) throws UncheckedSQLException {
        return importData(dataset, 0, dataset.size(), conn, insertSQL, stmtSetter);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param offset
     * @param count
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final int offset, final int count, final Connection conn, final String insertSQL,
            final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> stmtSetter) throws UncheckedSQLException {
        return importData(dataset, offset, count, conn, insertSQL, 200, 0, stmtSetter);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param offset
     * @param count
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @param batchSize
     * @param batchInterval
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final int offset, final int count, final Connection conn, final String insertSQL, final int batchSize,
            final int batchInterval, final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> stmtSetter) throws UncheckedSQLException {
        return importData(dataset, offset, count, Fn.alwaysTrue(), conn, insertSQL, batchSize, batchInterval, stmtSetter);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param <E>
     * @param dataset
     * @param offset
     * @param count
     * @param filter
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @param batchSize
     * @param batchInterval
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> int importData(final DataSet dataset, final int offset, final int count,
            final Throwables.Predicate<? super Object[], E> filter, final Connection conn, final String insertSQL, final int batchSize, final int batchInterval,
            final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> stmtSetter) throws UncheckedSQLException, E {
        PreparedStatement stmt = null;

        try {
            stmt = prepareStatement(conn, insertSQL);

            return importData(dataset, offset, count, filter, stmt, batchSize, batchInterval, stmtSetter);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.closeQuietly(stmt);
        }
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final PreparedStatement stmt) throws UncheckedSQLException {
        return importData(dataset, dataset.columnNameList(), stmt);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param selectColumnNames
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final Collection<String> selectColumnNames, final PreparedStatement stmt) throws UncheckedSQLException {
        return importData(dataset, selectColumnNames, 0, dataset.size(), stmt);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param selectColumnNames
     * @param offset
     * @param count
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final Collection<String> selectColumnNames, final int offset, final int count,
            final PreparedStatement stmt) throws UncheckedSQLException {
        return importData(dataset, selectColumnNames, offset, count, stmt, 200, 0);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param selectColumnNames
     * @param offset
     * @param count
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @param batchSize
     * @param batchInterval
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final Collection<String> selectColumnNames, final int offset, final int count,
            final PreparedStatement stmt, final int batchSize, final int batchInterval) throws UncheckedSQLException {
        return importData(dataset, selectColumnNames, offset, count, Fn.alwaysTrue(), stmt, batchSize, batchInterval);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param <E>
     * @param dataset
     * @param selectColumnNames
     * @param offset
     * @param count
     * @param filter
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @param batchSize
     * @param batchInterval
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> int importData(final DataSet dataset, final Collection<String> selectColumnNames, final int offset, final int count,
            final Throwables.Predicate<? super Object[], E> filter, final PreparedStatement stmt, final int batchSize, final int batchInterval)
            throws UncheckedSQLException, E {
        final Type<?> objType = N.typeOf(Object.class);
        final Map<String, Type<?>> columnTypeMap = new HashMap<>();

        for (String propName : selectColumnNames) {
            columnTypeMap.put(propName, objType);
        }

        return importData(dataset, offset, count, filter, stmt, batchSize, batchInterval, columnTypeMap);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @param columnTypeMap
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("rawtypes")
    public static int importData(final DataSet dataset, final PreparedStatement stmt, final Map<String, ? extends Type> columnTypeMap)
            throws UncheckedSQLException {
        return importData(dataset, 0, dataset.size(), stmt, columnTypeMap);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param offset
     * @param count
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @param columnTypeMap
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("rawtypes")
    public static int importData(final DataSet dataset, final int offset, final int count, final PreparedStatement stmt,
            final Map<String, ? extends Type> columnTypeMap) throws UncheckedSQLException {
        return importData(dataset, offset, count, stmt, 200, 0, columnTypeMap);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param offset
     * @param count
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @param batchSize
     * @param batchInterval
     * @param columnTypeMap
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("rawtypes")
    public static int importData(final DataSet dataset, final int offset, final int count, final PreparedStatement stmt, final int batchSize,
            final int batchInterval, final Map<String, ? extends Type> columnTypeMap) throws UncheckedSQLException {
        return importData(dataset, offset, count, Fn.alwaysTrue(), stmt, batchSize, batchInterval, columnTypeMap);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param <E>
     * @param dataset
     * @param offset
     * @param count
     * @param filter
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @param batchSize
     * @param batchInterval
     * @param columnTypeMap
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    @SuppressWarnings("rawtypes")
    public static <E extends Exception> int importData(final DataSet dataset, final int offset, final int count,
            final Throwables.Predicate<? super Object[], E> filter, final PreparedStatement stmt, final int batchSize, final int batchInterval,
            final Map<String, ? extends Type> columnTypeMap) throws UncheckedSQLException, E {
        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative", offset, count);
        N.checkArgument(batchSize > 0 && batchInterval >= 0, "'batchSize'=%s must be greater than 0 and 'batchInterval'=%s can't be negative", batchSize,
                batchInterval);

        int result = 0;

        try {
            final int columnCount = columnTypeMap.size();
            final List<String> columnNameList = dataset.columnNameList();
            final int[] columnIndexes = new int[columnCount];
            final Type<Object>[] columnTypes = new Type[columnCount];
            final Set<String> columnNameSet = N.newHashSet(columnCount);

            int idx = 0;
            for (String columnName : columnNameList) {
                if (columnTypeMap.containsKey(columnName)) {
                    columnIndexes[idx] = dataset.getColumnIndex(columnName);
                    columnTypes[idx] = columnTypeMap.get(columnName);
                    columnNameSet.add(columnName);
                    idx++;
                }
            }

            if (columnNameSet.size() != columnTypeMap.size()) {
                final List<String> keys = new ArrayList<>(columnTypeMap.keySet());
                keys.removeAll(columnNameSet);
                throw new RuntimeException(keys + " are not included in titles: " + N.toString(columnNameList));
            }

            final Object[] row = filter == null ? null : new Object[columnCount];
            for (int i = offset, size = dataset.size(); result < count && i < size; i++) {
                dataset.absolute(i);

                if (filter == null) {
                    for (int j = 0; j < columnCount; j++) {
                        columnTypes[j].set(stmt, j + 1, dataset.get(columnIndexes[j]));
                    }
                } else {
                    for (int j = 0; j < columnCount; j++) {
                        row[j] = dataset.get(columnIndexes[j]);
                    }

                    if (filter.test(row) == false) {
                        continue;
                    }

                    for (int j = 0; j < columnCount; j++) {
                        columnTypes[j].set(stmt, j + 1, row[j]);
                    }
                }

                stmt.addBatch();

                if ((++result % batchSize) == 0) {
                    executeBatch(stmt);

                    if (batchInterval > 0) {
                        N.sleep(batchInterval);
                    }
                }
            }

            if ((result % batchSize) > 0) {
                executeBatch(stmt);
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }

        return result;
    }

    /**
     *
     * @param dataset
     * @param stmt
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final PreparedStatement stmt,
            final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> stmtSetter) throws UncheckedSQLException {
        return importData(dataset, 0, dataset.size(), stmt, stmtSetter);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param offset
     * @param count
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final int offset, final int count, final PreparedStatement stmt,
            final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> stmtSetter) throws UncheckedSQLException {
        return importData(dataset, offset, count, stmt, 200, 0, stmtSetter);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param offset
     * @param count
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @param batchSize
     * @param batchInterval
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final int offset, final int count, final PreparedStatement stmt, final int batchSize,
            final int batchInterval, final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> stmtSetter) throws UncheckedSQLException {
        return importData(dataset, offset, count, Fn.alwaysTrue(), stmt, batchSize, batchInterval, stmtSetter);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param <E>
     * @param dataset
     * @param offset
     * @param count
     * @param filter
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @param batchSize
     * @param batchInterval
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> int importData(final DataSet dataset, final int offset, final int count,
            final Throwables.Predicate<? super Object[], E> filter, final PreparedStatement stmt, final int batchSize, final int batchInterval,
            final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> stmtSetter) throws UncheckedSQLException, E {
        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative", offset, count);
        N.checkArgument(batchSize > 0 && batchInterval >= 0, "'batchSize'=%s must be greater than 0 and 'batchInterval'=%s can't be negative", batchSize,
                batchInterval);

        final int columnCount = dataset.columnNameList().size();
        final Object[] row = new Object[columnCount];
        int result = 0;

        try {
            for (int i = offset, size = dataset.size(); result < count && i < size; i++) {
                dataset.absolute(i);

                for (int j = 0; j < columnCount; j++) {
                    row[j] = dataset.get(j);
                }

                if (filter != null && filter.test(row) == false) {
                    continue;
                }

                stmtSetter.accept(stmt, row);

                stmt.addBatch();

                if ((++result % batchSize) == 0) {
                    executeBatch(stmt);

                    if (batchInterval > 0) {
                        N.sleep(batchInterval);
                    }
                }
            }

            if ((result % batchSize) > 0) {
                executeBatch(stmt);
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }

        return result;
    }

    /**
     *
     * @param <E>
     * @param file
     * @param conn
     * @param insertSQL
     * @param func
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> long importData(final File file, final Connection conn, final String insertSQL,
            final Throwables.Function<String, Object[], E> func) throws UncheckedSQLException, E {
        return importData(file, 0, Long.MAX_VALUE, conn, insertSQL, 200, 0, func);
    }

    /**
     *
     * @param <E>
     * @param file
     * @param offset
     * @param count
     * @param conn
     * @param insertSQL
     * @param batchSize
     * @param batchInterval
     * @param func
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> long importData(final File file, final long offset, final long count, final Connection conn, final String insertSQL,
            final int batchSize, final int batchInterval, final Throwables.Function<String, Object[], E> func) throws UncheckedSQLException, E {
        PreparedStatement stmt = null;

        try {
            stmt = prepareStatement(conn, insertSQL);

            return importData(file, offset, count, stmt, batchSize, batchInterval, func);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.closeQuietly(stmt);
        }
    }

    /**
     *
     * @param <E>
     * @param file
     * @param stmt
     * @param func
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> long importData(final File file, final PreparedStatement stmt, final Throwables.Function<String, Object[], E> func)
            throws UncheckedSQLException, E {
        return importData(file, 0, Long.MAX_VALUE, stmt, 200, 0, func);
    }

    /**
     * Imports the data from file to database.
     *
     * @param <E>
     * @param file
     * @param offset
     * @param count
     * @param stmt
     * @param batchSize
     * @param batchInterval
     * @param func convert line to the parameters for record insert. Returns a <code>null</code> array to skip the line.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> long importData(final File file, final long offset, final long count, final PreparedStatement stmt, final int batchSize,
            final int batchInterval, final Throwables.Function<String, Object[], E> func) throws UncheckedSQLException, E {
        Reader reader = null;

        try {
            reader = new FileReader(file);

            return importData(reader, offset, count, stmt, batchSize, batchInterval, func);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            IOUtil.close(reader);
        }
    }

    /**
     *
     * @param <E>
     * @param is
     * @param conn
     * @param insertSQL
     * @param func
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> long importData(final InputStream is, final Connection conn, final String insertSQL,
            final Throwables.Function<String, Object[], E> func) throws UncheckedSQLException, E {
        return importData(is, 0, Long.MAX_VALUE, conn, insertSQL, 200, 0, func);
    }

    /**
     *
     * @param <E>
     * @param is
     * @param offset
     * @param count
     * @param conn
     * @param insertSQL
     * @param batchSize
     * @param batchInterval
     * @param func
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> long importData(final InputStream is, final long offset, final long count, final Connection conn,
            final String insertSQL, final int batchSize, final int batchInterval, final Throwables.Function<String, Object[], E> func)
            throws UncheckedSQLException, E {
        PreparedStatement stmt = null;

        try {
            stmt = prepareStatement(conn, insertSQL);

            return importData(is, offset, count, stmt, batchSize, batchInterval, func);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.closeQuietly(stmt);
        }
    }

    /**
     *
     * @param <E>
     * @param is
     * @param stmt
     * @param func
     * @return
     * @throws E the e
     */
    public static <E extends Exception> long importData(final InputStream is, final PreparedStatement stmt, final Throwables.Function<String, Object[], E> func)
            throws E {
        return importData(is, 0, Long.MAX_VALUE, stmt, 200, 0, func);
    }

    /**
     * Imports the data from file to database.
     *
     * @param <E>
     * @param is
     * @param offset
     * @param count
     * @param stmt
     * @param batchSize
     * @param batchInterval
     * @param func convert line to the parameters for record insert. Returns a <code>null</code> array to skip the line.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> long importData(final InputStream is, final long offset, final long count, final PreparedStatement stmt,
            final int batchSize, final int batchInterval, final Throwables.Function<String, Object[], E> func) throws UncheckedSQLException, E {
        final Reader reader = new InputStreamReader(is);

        return importData(reader, offset, count, stmt, batchSize, batchInterval, func);
    }

    /**
     *
     * @param <E>
     * @param reader
     * @param conn
     * @param insertSQL
     * @param func
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> long importData(final Reader reader, final Connection conn, final String insertSQL,
            final Throwables.Function<String, Object[], E> func) throws UncheckedSQLException, E {
        return importData(reader, 0, Long.MAX_VALUE, conn, insertSQL, 200, 0, func);
    }

    /**
     *
     * @param <E>
     * @param reader
     * @param offset
     * @param count
     * @param conn
     * @param insertSQL
     * @param batchSize
     * @param batchInterval
     * @param func
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> long importData(final Reader reader, final long offset, final long count, final Connection conn, final String insertSQL,
            final int batchSize, final int batchInterval, final Throwables.Function<String, Object[], E> func) throws UncheckedSQLException, E {
        PreparedStatement stmt = null;

        try {
            stmt = prepareStatement(conn, insertSQL);

            return importData(reader, offset, count, stmt, batchSize, batchInterval, func);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.closeQuietly(stmt);
        }
    }

    /**
     *
     * @param <E>
     * @param reader
     * @param stmt
     * @param func
     * @return
     * @throws E the e
     */
    public static <E extends Exception> long importData(final Reader reader, final PreparedStatement stmt, final Throwables.Function<String, Object[], E> func)
            throws E {
        return importData(reader, 0, Long.MAX_VALUE, stmt, 200, 0, func);
    }

    /**
     * Imports the data from file to database.
     *
     * @param <E>
     * @param reader
     * @param offset
     * @param count
     * @param stmt
     * @param batchSize
     * @param batchInterval
     * @param func convert line to the parameters for record insert. Returns a <code>null</code> array to skip the line.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> long importData(final Reader reader, long offset, final long count, final PreparedStatement stmt, final int batchSize,
            final int batchInterval, final Throwables.Function<String, Object[], E> func) throws UncheckedSQLException, E {
        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative", offset, count);
        N.checkArgument(batchSize > 0 && batchInterval >= 0, "'batchSize'=%s must be greater than 0 and 'batchInterval'=%s can't be negative", batchSize,
                batchInterval);

        long result = 0;
        final BufferedReader br = Objectory.createBufferedReader(reader);

        try {
            while (offset-- > 0 && br.readLine() != null) {
            }

            String line = null;
            Object[] row = null;

            while (result < count && (line = br.readLine()) != null) {
                row = func.apply(line);

                if (row == null) {
                    continue;
                }

                for (int i = 0, len = row.length; i < len; i++) {
                    stmt.setObject(i + 1, row[i]);
                }

                stmt.addBatch();

                if ((++result % batchSize) == 0) {
                    executeBatch(stmt);

                    if (batchInterval > 0) {
                        N.sleep(batchInterval);
                    }
                }
            }

            if ((result % batchSize) > 0) {
                executeBatch(stmt);
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            Objectory.recycle(br);
        }

        return result;
    }

    /**
     *
     * @param <T>
     * @param <E>
     * @param iter
     * @param conn
     * @param insertSQL
     * @param func
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <T, E extends Exception> long importData(final Iterator<T> iter, final Connection conn, final String insertSQL,
            final Throwables.Function<? super T, Object[], E> func) throws UncheckedSQLException, E {
        return importData(iter, 0, Long.MAX_VALUE, conn, insertSQL, 200, 0, func);
    }

    /**
     *
     * @param <T>
     * @param <E>
     * @param iter
     * @param offset
     * @param count
     * @param conn
     * @param insertSQL
     * @param batchSize
     * @param batchInterval
     * @param func
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <T, E extends Exception> long importData(final Iterator<T> iter, final long offset, final long count, final Connection conn,
            final String insertSQL, final int batchSize, final int batchInterval, final Throwables.Function<? super T, Object[], E> func)
            throws UncheckedSQLException, E {
        PreparedStatement stmt = null;

        try {
            stmt = prepareStatement(conn, insertSQL);

            return importData(iter, offset, count, stmt, batchSize, batchInterval, func);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.closeQuietly(stmt);
        }
    }

    /**
     *
     * @param <T>
     * @param <E>
     * @param iter
     * @param stmt
     * @param func
     * @return
     * @throws E the e
     */
    public static <T, E extends Exception> long importData(final Iterator<T> iter, final PreparedStatement stmt,
            final Throwables.Function<? super T, Object[], E> func) throws E {
        return importData(iter, 0, Long.MAX_VALUE, stmt, 200, 0, func);
    }

    /**
     * Imports the data from Iterator to database.
     *
     * @param <T>
     * @param <E>
     * @param iter
     * @param offset
     * @param count
     * @param stmt
     * @param batchSize
     * @param batchInterval
     * @param func convert element to the parameters for record insert. Returns a <code>null</code> array to skip the line.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <T, E extends Exception> long importData(final Iterator<T> iter, long offset, final long count, final PreparedStatement stmt,
            final int batchSize, final int batchInterval, final Throwables.Function<? super T, Object[], E> func) throws UncheckedSQLException, E {
        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative", offset, count);
        N.checkArgument(batchSize > 0 && batchInterval >= 0, "'batchSize'=%s must be greater than 0 and 'batchInterval'=%s can't be negative", batchSize,
                batchInterval);

        long result = 0;

        try {
            while (offset-- > 0 && iter.hasNext()) {
                iter.next();
            }

            Object[] row = null;

            while (result < count && iter.hasNext()) {
                row = func.apply(iter.next());

                if (row == null) {
                    continue;
                }

                for (int i = 0, len = row.length; i < len; i++) {
                    stmt.setObject(i + 1, row[i]);
                }

                stmt.addBatch();

                if ((++result % batchSize) == 0) {
                    executeBatch(stmt);

                    if (batchInterval > 0) {
                        N.sleep(batchInterval);
                    }
                }
            }

            if ((result % batchSize) > 0) {
                executeBatch(stmt);
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }

        return result;
    }

    /**
     *
     * @param <T>
     * @param iter
     * @param conn
     * @param insertSQL
     * @param stmtSetter
     * @return
     */
    public static <T> long importData(final Iterator<T> iter, final Connection conn, final String insertSQL,
            final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super T> stmtSetter) {
        return importData(iter, 0, Long.MAX_VALUE, conn, insertSQL, 200, 0, stmtSetter);
    }

    /**
     *
     * @param <T>
     * @param iter
     * @param offset
     * @param count
     * @param conn
     * @param insertSQL
     * @param batchSize
     * @param batchInterval
     * @param stmtSetter
     * @return
     */
    public static <T> long importData(final Iterator<T> iter, final long offset, final long count, final Connection conn, final String insertSQL,
            final int batchSize, final int batchInterval, final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super T> stmtSetter) {
        return importData(iter, offset, count, Fn.alwaysTrue(), conn, insertSQL, batchSize, batchInterval, stmtSetter);
    }

    /**
     *
     * @param <T>
     * @param <E>
     * @param iter
     * @param offset
     * @param count
     * @param filter
     * @param conn
     * @param insertSQL
     * @param batchSize
     * @param batchInterval
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <T, E extends Exception> long importData(final Iterator<T> iter, final long offset, final long count,
            final Throwables.Predicate<? super T, E> filter, final Connection conn, final String insertSQL, final int batchSize, final int batchInterval,
            final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super T> stmtSetter) throws UncheckedSQLException, E {
        PreparedStatement stmt = null;

        try {
            stmt = prepareStatement(conn, insertSQL);

            return importData(iter, offset, count, filter, stmt, batchSize, batchInterval, stmtSetter);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.closeQuietly(stmt);
        }
    }

    /**
     *
     * @param <T>
     * @param iter
     * @param stmt
     * @param stmtSetter
     * @return
     */
    public static <T> long importData(final Iterator<T> iter, final PreparedStatement stmt,
            final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super T> stmtSetter) {
        return importData(iter, 0, Long.MAX_VALUE, stmt, 200, 0, stmtSetter);
    }

    /**
     *
     * @param <T>
     * @param iter
     * @param offset
     * @param count
     * @param stmt
     * @param batchSize
     * @param batchInterval
     * @param stmtSetter
     * @return
     */
    public static <T> long importData(final Iterator<T> iter, long offset, final long count, final PreparedStatement stmt, final int batchSize,
            final int batchInterval, final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super T> stmtSetter) {
        return importData(iter, offset, count, Fn.alwaysTrue(), stmt, batchSize, batchInterval, stmtSetter);
    }

    /**
     * Imports the data from Iterator to database.
     *
     * @param <T>
     * @param <E>
     * @param iter
     * @param offset
     * @param count
     * @param filter
     * @param stmt
     * @param batchSize
     * @param batchInterval
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <T, E extends Exception> long importData(final Iterator<T> iter, long offset, final long count,
            final Throwables.Predicate<? super T, E> filter, final PreparedStatement stmt, final int batchSize, final int batchInterval,
            final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super T> stmtSetter) throws UncheckedSQLException, E {
        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative", offset, count);
        N.checkArgument(batchSize > 0 && batchInterval >= 0, "'batchSize'=%s must be greater than 0 and 'batchInterval'=%s can't be negative", batchSize,
                batchInterval);

        long result = 0;

        try {
            while (offset-- > 0 && iter.hasNext()) {
                iter.next();
            }
            T next = null;
            while (result < count && iter.hasNext()) {
                next = iter.next();

                if (filter != null && filter.test(next) == false) {
                    continue;
                }

                stmtSetter.accept(stmt, next);
                stmt.addBatch();

                if ((++result % batchSize) == 0) {
                    executeBatch(stmt);

                    if (batchInterval > 0) {
                        N.sleep(batchInterval);
                    }
                }
            }

            if ((result % batchSize) > 0) {
                executeBatch(stmt);
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }

        return result;
    }

    /**
     *
     * @param <E>
     * @param conn
     * @param sql
     * @param rowParser
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> void parse(final Connection conn, final String sql, final Throwables.Consumer<Object[], E> rowParser)
            throws UncheckedSQLException, E {
        parse(conn, sql, rowParser, Fn.emptyAction());
    }

    /**
     *
     * @param <E>
     * @param <E2>
     * @param conn
     * @param sql
     * @param rowParser
     * @param onComplete
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     * @throws E2 the e2
     */
    public static <E extends Exception, E2 extends Exception> void parse(final Connection conn, final String sql,
            final Throwables.Consumer<Object[], E> rowParser, final Throwables.Runnable<E2> onComplete) throws UncheckedSQLException, E, E2 {
        parse(conn, sql, 0, Long.MAX_VALUE, rowParser, onComplete);
    }

    /**
     *
     * @param <E>
     * @param conn
     * @param sql
     * @param offset
     * @param count
     * @param rowParser
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> void parse(final Connection conn, final String sql, final long offset, final long count,
            final Throwables.Consumer<Object[], E> rowParser) throws UncheckedSQLException, E {
        parse(conn, sql, offset, count, rowParser, Fn.emptyAction());
    }

    /**
     *
     * @param <E>
     * @param <E2>
     * @param conn
     * @param sql
     * @param offset
     * @param count
     * @param rowParser
     * @param onComplete
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     * @throws E2 the e2
     */
    public static <E extends Exception, E2 extends Exception> void parse(final Connection conn, final String sql, final long offset, final long count,
            final Throwables.Consumer<Object[], E> rowParser, final Throwables.Runnable<E2> onComplete) throws UncheckedSQLException, E, E2 {
        parse(conn, sql, offset, count, 0, 0, rowParser, onComplete);
    }

    /**
     *
     * @param <E>
     * @param conn
     * @param sql
     * @param offset
     * @param count
     * @param processThreadNum
     * @param queueSize
     * @param rowParser
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> void parse(final Connection conn, final String sql, final long offset, final long count, final int processThreadNum,
            final int queueSize, final Throwables.Consumer<Object[], E> rowParser) throws UncheckedSQLException, E {
        parse(conn, sql, offset, count, processThreadNum, queueSize, rowParser, Fn.emptyAction());
    }

    /**
     * Parse the ResultSet obtained by executing query with the specified Connection and sql.
     *
     * @param <E>
     * @param <E2>
     * @param conn
     * @param sql
     * @param offset
     * @param count
     * @param processThreadNum new threads started to parse/process the lines/records
     * @param queueSize size of queue to save the processing records/lines loaded from source data. Default size is 1024.
     * @param rowParser
     * @param onComplete
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     * @throws E2 the e2
     */
    public static <E extends Exception, E2 extends Exception> void parse(final Connection conn, final String sql, final long offset, final long count,
            final int processThreadNum, final int queueSize, final Throwables.Consumer<Object[], E> rowParser, final Throwables.Runnable<E2> onComplete)
            throws UncheckedSQLException, E, E2 {
        PreparedStatement stmt = null;
        try {
            stmt = prepareStatement(conn, sql);

            stmt.setFetchDirection(ResultSet.FETCH_FORWARD);

            stmt.setFetchSize(200);

            parse(stmt, offset, count, processThreadNum, queueSize, rowParser, onComplete);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            closeQuietly(stmt);
        }
    }

    /**
     *
     * @param <E>
     * @param stmt
     * @param rowParser
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> void parse(final PreparedStatement stmt, final Throwables.Consumer<Object[], E> rowParser)
            throws UncheckedSQLException, E {
        parse(stmt, rowParser, Fn.emptyAction());
    }

    /**
     *
     * @param <E>
     * @param <E2>
     * @param stmt
     * @param rowParser
     * @param onComplete
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     * @throws E2 the e2
     */
    public static <E extends Exception, E2 extends Exception> void parse(final PreparedStatement stmt, final Throwables.Consumer<Object[], E> rowParser,
            final Throwables.Runnable<E2> onComplete) throws UncheckedSQLException, E, E2 {
        parse(stmt, 0, Long.MAX_VALUE, rowParser, onComplete);
    }

    /**
     *
     * @param <E>
     * @param stmt
     * @param offset
     * @param count
     * @param rowParser
     * @throws E the e
     */
    public static <E extends Exception> void parse(final PreparedStatement stmt, final long offset, final long count,
            final Throwables.Consumer<Object[], E> rowParser) throws E {
        parse(stmt, offset, count, rowParser, Fn.emptyAction());
    }

    /**
     *
     * @param <E>
     * @param <E2>
     * @param stmt
     * @param offset
     * @param count
     * @param rowParser
     * @param onComplete
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     * @throws E2 the e2
     */
    public static <E extends Exception, E2 extends Exception> void parse(final PreparedStatement stmt, final long offset, final long count,
            final Throwables.Consumer<Object[], E> rowParser, final Throwables.Runnable<E2> onComplete) throws UncheckedSQLException, E, E2 {
        parse(stmt, offset, count, 0, 0, rowParser, onComplete);
    }

    /**
     *
     * @param <E>
     * @param stmt
     * @param offset
     * @param count
     * @param processThreadNum
     * @param queueSize
     * @param rowParser
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> void parse(final PreparedStatement stmt, final long offset, final long count, final int processThreadNum,
            final int queueSize, final Throwables.Consumer<Object[], E> rowParser) throws UncheckedSQLException, E {
        parse(stmt, offset, count, processThreadNum, queueSize, rowParser, Fn.emptyAction());
    }

    /**
     * Parse the ResultSet obtained by executing query with the specified PreparedStatement.
     *
     * @param <E>
     * @param <E2>
     * @param stmt
     * @param offset
     * @param count
     * @param processThreadNum new threads started to parse/process the lines/records
     * @param queueSize size of queue to save the processing records/lines loaded from source data. Default size is 1024.
     * @param rowParser
     * @param onComplete
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     * @throws E2 the e2
     */
    public static <E extends Exception, E2 extends Exception> void parse(final PreparedStatement stmt, final long offset, final long count,
            final int processThreadNum, final int queueSize, final Throwables.Consumer<Object[], E> rowParser, final Throwables.Runnable<E2> onComplete)
            throws UncheckedSQLException, E, E2 {
        ResultSet rs = null;

        try {
            rs = executeQuery(stmt);

            parse(rs, offset, count, processThreadNum, queueSize, rowParser, onComplete);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            closeQuietly(rs);
        }
    }

    /**
     *
     * @param <E>
     * @param rs
     * @param rowParser
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> void parse(final ResultSet rs, final Throwables.Consumer<Object[], E> rowParser) throws UncheckedSQLException, E {
        parse(rs, rowParser, Fn.emptyAction());
    }

    /**
     *
     * @param <E>
     * @param <E2>
     * @param rs
     * @param rowParser
     * @param onComplete
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     * @throws E2 the e2
     */
    public static <E extends Exception, E2 extends Exception> void parse(final ResultSet rs, final Throwables.Consumer<Object[], E> rowParser,
            final Throwables.Runnable<E2> onComplete) throws UncheckedSQLException, E, E2 {
        parse(rs, 0, Long.MAX_VALUE, rowParser, onComplete);
    }

    /**
     *
     * @param <E>
     * @param rs
     * @param offset
     * @param count
     * @param rowParser
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> void parse(final ResultSet rs, long offset, long count, final Throwables.Consumer<Object[], E> rowParser)
            throws UncheckedSQLException, E {
        parse(rs, offset, count, rowParser, Fn.emptyAction());
    }

    /**
     *
     * @param <E>
     * @param <E2>
     * @param rs
     * @param offset
     * @param count
     * @param rowParser
     * @param onComplete
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     * @throws E2 the e2
     */
    public static <E extends Exception, E2 extends Exception> void parse(final ResultSet rs, long offset, long count,
            final Throwables.Consumer<Object[], E> rowParser, final Throwables.Runnable<E2> onComplete) throws UncheckedSQLException, E, E2 {
        parse(rs, offset, count, 0, 0, rowParser, onComplete);
    }

    /**
     *
     * @param <E>
     * @param rs
     * @param offset
     * @param count
     * @param processThreadNum
     * @param queueSize
     * @param rowParser
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> void parse(final ResultSet rs, long offset, long count, final int processThreadNum, final int queueSize,
            final Throwables.Consumer<Object[], E> rowParser) throws UncheckedSQLException, E {
        parse(rs, offset, count, processThreadNum, queueSize, rowParser, Fn.emptyAction());
    }

    /**
     * Parse the ResultSet.
     *
     * @param <E>
     * @param <E2>
     * @param rs
     * @param offset
     * @param count
     * @param processThreadNum new threads started to parse/process the lines/records
     * @param queueSize size of queue to save the processing records/lines loaded from source data. Default size is 1024.
     * @param rowParser
     * @param onComplete
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     * @throws E2 the e2
     */
    public static <E extends Exception, E2 extends Exception> void parse(final ResultSet rs, long offset, long count, final int processThreadNum,
            final int queueSize, final Throwables.Consumer<Object[], E> rowParser, final Throwables.Runnable<E2> onComplete)
            throws UncheckedSQLException, E, E2 {

        final Iterator<Object[]> iter = new ObjIterator<Object[]>() {
            private final JdbcUtil.BiRowMapper<Object[]> biFunc = BiRowMapper.TO_ARRAY;
            private List<String> columnLabels = null;
            private boolean hasNext;

            @Override
            public boolean hasNext() {
                if (hasNext == false) {
                    try {
                        hasNext = rs.next();
                    } catch (SQLException e) {
                        throw new UncheckedSQLException(e);
                    }
                }

                return hasNext;
            }

            @Override
            public Object[] next() {
                if (hasNext() == false) {
                    throw new NoSuchElementException();
                }

                hasNext = false;

                try {
                    if (columnLabels == null) {
                        columnLabels = JdbcUtil.getColumnLabelList(rs);
                    }

                    return biFunc.apply(rs, columnLabels);
                } catch (SQLException e) {
                    throw new UncheckedSQLException(e);
                }
            }
        };

        Iterables.parse(iter, offset, count, processThreadNum, queueSize, rowParser, onComplete);
    }

    /**
     *
     * @param sourceConn
     * @param selectSql
     * @param targetConn
     * @param insertSql
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static long copy(final Connection sourceConn, final String selectSql, final Connection targetConn, final String insertSql)
            throws UncheckedSQLException {
        return copy(sourceConn, selectSql, 200, 0, Integer.MAX_VALUE, targetConn, insertSql, DEFAULT_STMT_SETTER, 200, 0, false);
    }

    /**
     *
     * @param sourceConn
     * @param selectSql
     * @param fetchSize
     * @param offset
     * @param count
     * @param targetConn
     * @param insertSql
     * @param stmtSetter
     * @param batchSize
     * @param batchInterval
     * @param inParallel do the read and write in separated threads.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static long copy(final Connection sourceConn, final String selectSql, final int fetchSize, final long offset, final long count,
            final Connection targetConn, final String insertSql, final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> stmtSetter,
            final int batchSize, final int batchInterval, final boolean inParallel) throws UncheckedSQLException {
        PreparedStatement selectStmt = null;
        PreparedStatement insertStmt = null;

        int result = 0;

        try {
            insertStmt = prepareStatement(targetConn, insertSql);

            selectStmt = prepareStatement(sourceConn, selectSql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            selectStmt.setFetchSize(fetchSize);

            copy(selectStmt, offset, count, insertStmt, stmtSetter, batchSize, batchInterval, inParallel);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            closeQuietly(selectStmt);
            closeQuietly(insertStmt);
        }

        return result;
    }

    /**
     *
     * @param selectStmt
     * @param insertStmt
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static long copy(final PreparedStatement selectStmt, final PreparedStatement insertStmt,
            final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> stmtSetter) throws UncheckedSQLException {
        return copy(selectStmt, 0, Integer.MAX_VALUE, insertStmt, stmtSetter, 200, 0, false);
    }

    /**
     *
     * @param selectStmt
     * @param offset
     * @param count
     * @param insertStmt
     * @param stmtSetter
     * @param batchSize
     * @param batchInterval
     * @param inParallel do the read and write in separated threads.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static long copy(final PreparedStatement selectStmt, final long offset, final long count, final PreparedStatement insertStmt,
            final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> stmtSetter, final int batchSize, final int batchInterval,
            final boolean inParallel) throws UncheckedSQLException {
        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative", offset, count);
        N.checkArgument(batchSize > 0 && batchInterval >= 0, "'batchSize'=%s must be greater than 0 and 'batchInterval'=%s can't be negative", batchSize,
                batchInterval);

        @SuppressWarnings("rawtypes")
        final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> setter = (JdbcUtil.BiParametersSetter) (stmtSetter == null
                ? DEFAULT_STMT_SETTER
                : stmtSetter);
        final AtomicLong result = new AtomicLong();

        final Throwables.Consumer<Object[], RuntimeException> rowParser = new Throwables.Consumer<Object[], RuntimeException>() {
            @Override
            public void accept(Object[] row) {
                try {
                    setter.accept(insertStmt, row);

                    insertStmt.addBatch();
                    result.incrementAndGet();

                    if ((result.longValue() % batchSize) == 0) {
                        executeBatch(insertStmt);

                        if (batchInterval > 0) {
                            N.sleep(batchInterval);
                        }
                    }
                } catch (SQLException e) {
                    throw new UncheckedSQLException(e);
                }
            }
        };

        final Throwables.Runnable<RuntimeException> onComplete = new Throwables.Runnable<RuntimeException>() {
            @Override
            public void run() {
                if ((result.longValue() % batchSize) > 0) {
                    try {
                        executeBatch(insertStmt);
                    } catch (SQLException e) {
                        throw new UncheckedSQLException(e);
                    }
                }
            }
        };

        parse(selectStmt, offset, count, 0, inParallel ? DEFAULT_QUEUE_SIZE_FOR_ROW_PARSER : 0, rowParser, onComplete);

        return result.longValue();
    }

    /**
     * Checks if is table not exists exception.
     *
     * @param e
     * @return true, if is table not exists exception
     */
    static boolean isTableNotExistsException(final Throwable e) {
        if (e instanceof SQLException) {
            SQLException sqlException = (SQLException) e;

            if (sqlException.getSQLState() != null && sqlStateForTableNotExists.contains(sqlException.getSQLState())) {
                return true;
            }

            final String msg = N.defaultIfNull(e.getMessage(), "").toLowerCase();
            return N.notNullOrEmpty(msg) && (msg.contains("not exist") || msg.contains("doesn't exist") || msg.contains("not found"));
        } else if (e instanceof UncheckedSQLException) {
            UncheckedSQLException sqlException = (UncheckedSQLException) e;

            if (sqlException.getSQLState() != null && sqlStateForTableNotExists.contains(sqlException.getSQLState())) {
                return true;
            }

            final String msg = N.defaultIfNull(e.getMessage(), "").toLowerCase();
            return N.notNullOrEmpty(msg) && (msg.contains("not exist") || msg.contains("doesn't exist") || msg.contains("not found"));
        }

        return false;
    }

    static final ThreadLocal<Boolean> isSQLLogEnabled_TL = ThreadLocal.withInitial(() -> false);

    /**
     * Enable/Disable sql log in current thread.
     *
     * @param b {@code true} to enable, {@code false} to disable.
     */
    public static void enableSQLLog(boolean b) {
        // synchronized (isSQLLogEnabled_TL) {
        if (logger.isDebugEnabled() && isSQLLogEnabled_TL.get() != b) {
            if (b) {
                logger.debug("Turn on [SQL] log");
            } else {
                logger.debug("Turn off [SQL] log");
            }
        }

        isSQLLogEnabled_TL.set(b);
        // }
    }

    /**
     * Checks if sql log is enabled or not in current thread.
     *
     * @return {@code true} if it's enabled, otherwise {@code false} is returned.
     */
    public static boolean isSQLLogEnabled() {
        return isSQLLogEnabled_TL.get();
    }

    static final ThreadLocal<Long> minExecutionTimeForSQLPerfLog_TL = ThreadLocal.withInitial(() -> 1000L);

    /**
     * Set minimum execution time to log sql performance in current thread.
     *
     * @param minExecutionTimeForSQLPerfLog Default value is 1000 (milliseconds).
     */
    public static void setMinExecutionTimeForSQLPerfLog(long minExecutionTimeForSQLPerfLog) {
        // synchronized (minExecutionTimeForSQLPerfLog_TL) {
        if (logger.isDebugEnabled() && minExecutionTimeForSQLPerfLog_TL.get() != minExecutionTimeForSQLPerfLog) {
            if (minExecutionTimeForSQLPerfLog >= 0) {
                logger.debug("set 'minExecutionTimeForSQLPerfLog' to: " + minExecutionTimeForSQLPerfLog);
            } else {
                logger.debug("Turn off SQL perfermance log");
            }
        }

        minExecutionTimeForSQLPerfLog_TL.set(minExecutionTimeForSQLPerfLog);
        // }
    }

    /**
     * Return the minimum execution time in milliseconds to log SQL performance. Default value is 1000 (milliseconds).
     *
     * @return
     */
    public static long getMinExecutionTimeForSQLPerfLog() {
        return minExecutionTimeForSQLPerfLog_TL.get();
    }

    static final ThreadLocal<Boolean> isSpringTransactionalDisabled_TL = ThreadLocal.withInitial(() -> false);

    /**
     * Disable/enable {@code Spring Transactional} in current thread.
     *
     * {@code Spring Transactional} won't be used in fetching Connection if it's disabled.
     *
     * @param b {@code true} to disable, {@code false} to enable it again.
     */
    public static void disableSpringTransactional(boolean b) {
        // synchronized (isSpringTransactionalDisabled_TL) {
        if (isInSpring) {
            if (logger.isWarnEnabled() && isSpringTransactionalDisabled_TL.get() != b) {
                if (b) {
                    logger.warn("Disable Spring Transactional");
                } else {
                    logger.warn("Enable Spring Transactional again");
                }
            }

            isSpringTransactionalDisabled_TL.set(b);
        } else {
            logger.warn("Not in Spring or not able to retrieve Spring Transactional");
        }
        // }
    }

    /**
     * Check if {@code Spring Transactional} is disabled or not in current thread.
     *
     * @return {@code true} if it's disabled, otherwise {@code false} is returned.
     */
    public static boolean isSpringTransactionalDisabled() {
        return isSpringTransactionalDisabled_TL.get();
    }

    /**
     * Checks if is default id prop value.
     *
     * @param propValue
     * @return true, if is default id prop value
     * @deprecated for internal only.
     */
    @Deprecated
    @Internal
    public static boolean isDefaultIdPropValue(final Object propValue) {
        return (propValue == null) || (propValue instanceof Number && (((Number) propValue).longValue() == 0));
    }

    /**
     *
     * @param sqlAction 
     */
    @Beta
    public static ContinuableFuture<Void> asyncRun(final Throwables.Runnable<Exception> sqlAction) {
        return asyncExecutor.execute(sqlAction);
    }

    /**
     *
     * @param sqlAction
     */
    @Beta
    public static <R> ContinuableFuture<R> asyncCall(final Callable<R> sqlAction) {
        return asyncExecutor.execute(sqlAction);
    }

    /**
     * The backed {@code PreparedStatement/CallableStatement} will be closed by default
     * after any execution methods(which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/list/execute/...).
     * except the {@code 'closeAfterExecution'} flag is set to {@code false} by calling {@code #closeAfterExecution(false)}.
     *
     * <br />
     * Generally, don't cache or reuse the instance of this class,
     * except the {@code 'closeAfterExecution'} flag is set to {@code false} by calling {@code #closeAfterExecution(false)}.
     *
     * <br />
     * The {@code ResultSet} returned by query will always be closed after execution, even {@code 'closeAfterExecution'} flag is set to {@code false}.
     *
     * <br />
     * Remember: parameter/column index in {@code PreparedStatement/ResultSet} starts from 1, not 0.
     *
     * @author haiyangl
     *
     * @see {@link com.landawn.abacus.annotation.ReadOnly}
     * @see {@link com.landawn.abacus.annotation.ReadOnlyId}
     * @see {@link com.landawn.abacus.annotation.NonUpdatable}
     * @see {@link com.landawn.abacus.annotation.Transient}
     * @see {@link com.landawn.abacus.annotation.Table}
     * @see {@link com.landawn.abacus.annotation.Column}
     *
     * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/Connection.html">http://docs.oracle.com/javase/8/docs/api/java/sql/Connection.html</a>
     * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/Statement.html">http://docs.oracle.com/javase/8/docs/api/java/sql/Statement.html</a>
     * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/PreparedStatement.html">http://docs.oracle.com/javase/8/docs/api/java/sql/PreparedStatement.html</a>
     * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/ResultSet.html">http://docs.oracle.com/javase/8/docs/api/java/sql/ResultSet.html</a>
     */
    public static class PreparedQuery extends AbstractPreparedQuery<PreparedStatement, PreparedQuery> {

        /**
         * Instantiates a new prepared query.
         *
         * @param stmt
         */
        PreparedQuery(PreparedStatement stmt) {
            super(stmt);
        }
    }

    /**
     * The backed {@code PreparedStatement/CallableStatement} will be closed by default
     * after any execution methods(which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/list/execute/...).
     * except the {@code 'closeAfterExecution'} flag is set to {@code false} by calling {@code #closeAfterExecution(false)}.
     *
     * <br />
     * Generally, don't cache or reuse the instance of this class,
     * except the {@code 'closeAfterExecution'} flag is set to {@code false} by calling {@code #closeAfterExecution(false)}.
     *
     * <br />
     * The {@code ResultSet} returned by query will always be closed after execution, even {@code 'closeAfterExecution'} flag is set to {@code false}.
     *
     * <br />
     * Remember: parameter/column index in {@code PreparedStatement/ResultSet} starts from 1, not 0.
     *
     * @author haiyangl
     *
     * @see {@link com.landawn.abacus.annotation.ReadOnly}
     * @see {@link com.landawn.abacus.annotation.ReadOnlyId}
     * @see {@link com.landawn.abacus.annotation.NonUpdatable}
     * @see {@link com.landawn.abacus.annotation.Transient}
     * @see {@link com.landawn.abacus.annotation.Table}
     * @see {@link com.landawn.abacus.annotation.Column}
     *
     * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/Connection.html">http://docs.oracle.com/javase/8/docs/api/java/sql/Connection.html</a>
     * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/Statement.html">http://docs.oracle.com/javase/8/docs/api/java/sql/Statement.html</a>
     * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/PreparedStatement.html">http://docs.oracle.com/javase/8/docs/api/java/sql/PreparedStatement.html</a>
     * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/ResultSet.html">http://docs.oracle.com/javase/8/docs/api/java/sql/ResultSet.html</a>
     */
    public static class PreparedCallableQuery extends AbstractPreparedQuery<CallableStatement, PreparedCallableQuery> {

        /** The stmt. */
        final CallableStatement stmt;

        /**
         * Instantiates a new prepared callable query.
         *
         * @param stmt
         */
        PreparedCallableQuery(CallableStatement stmt) {
            super(stmt);
            this.stmt = stmt;
        }

        /**
         * Sets the null.
         *
         * @param parameterName
         * @param sqlType
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setNull(String parameterName, int sqlType) throws SQLException {
            stmt.setNull(parameterName, sqlType);

            return this;
        }

        /**
         * Sets the null.
         *
         * @param parameterName
         * @param sqlType
         * @param typeName
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setNull(String parameterName, int sqlType, String typeName) throws SQLException {
            stmt.setNull(parameterName, sqlType, typeName);

            return this;
        }

        /**
         * Sets the boolean.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setBoolean(String parameterName, boolean x) throws SQLException {
            stmt.setBoolean(parameterName, x);

            return this;
        }

        /**
         * Sets the boolean.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setBoolean(String parameterName, Boolean x) throws SQLException {
            stmt.setBoolean(parameterName, N.defaultIfNull(x));

            return this;
        }

        /**
         * Sets the byte.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setByte(String parameterName, byte x) throws SQLException {
            stmt.setByte(parameterName, x);

            return this;
        }

        /**
         * Sets the byte.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setByte(String parameterName, Byte x) throws SQLException {
            stmt.setByte(parameterName, N.defaultIfNull(x));

            return this;
        }

        /**
         * Sets the short.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setShort(String parameterName, short x) throws SQLException {
            stmt.setShort(parameterName, x);

            return this;
        }

        /**
         * Sets the short.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setShort(String parameterName, Short x) throws SQLException {
            stmt.setShort(parameterName, N.defaultIfNull(x));

            return this;
        }

        /**
         * Sets the int.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setInt(String parameterName, int x) throws SQLException {
            stmt.setInt(parameterName, x);

            return this;
        }

        /**
         * Sets the int.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setInt(String parameterName, Integer x) throws SQLException {
            stmt.setInt(parameterName, N.defaultIfNull(x));

            return this;
        }

        /**
         * Sets the long.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setLong(String parameterName, long x) throws SQLException {
            stmt.setLong(parameterName, x);

            return this;
        }

        /**
         * Sets the long.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setLong(String parameterName, Long x) throws SQLException {
            stmt.setLong(parameterName, N.defaultIfNull(x));

            return this;
        }

        /**
         * Sets the float.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setFloat(String parameterName, float x) throws SQLException {
            stmt.setFloat(parameterName, x);

            return this;
        }

        /**
         * Sets the float.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setFloat(String parameterName, Float x) throws SQLException {
            stmt.setFloat(parameterName, N.defaultIfNull(x));

            return this;
        }

        /**
         * Sets the double.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setDouble(String parameterName, double x) throws SQLException {
            stmt.setDouble(parameterName, x);

            return this;
        }

        /**
         * Sets the double.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setDouble(String parameterName, Double x) throws SQLException {
            stmt.setDouble(parameterName, N.defaultIfNull(x));

            return this;
        }

        /**
         * Sets the big decimal.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setBigDecimal(String parameterName, BigDecimal x) throws SQLException {
            stmt.setBigDecimal(parameterName, x);

            return this;
        }

        /**
         * Sets the string.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setString(String parameterName, String x) throws SQLException {
            stmt.setString(parameterName, x);

            return this;
        }

        /**
         * Sets the date.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setDate(String parameterName, java.sql.Date x) throws SQLException {
            stmt.setDate(parameterName, x);

            return this;
        }

        /**
         * Sets the date.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setDate(String parameterName, java.util.Date x) throws SQLException {
            stmt.setDate(parameterName, x == null ? null : x instanceof java.sql.Date ? (java.sql.Date) x : new java.sql.Date(x.getTime()));

            return this;
        }

        /**
         * Sets the time.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setTime(String parameterName, java.sql.Time x) throws SQLException {
            stmt.setTime(parameterName, x);

            return this;
        }

        /**
         * Sets the time.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setTime(String parameterName, java.util.Date x) throws SQLException {
            stmt.setTime(parameterName, x == null ? null : x instanceof java.sql.Time ? (java.sql.Time) x : new java.sql.Time(x.getTime()));

            return this;
        }

        /**
         * Sets the timestamp.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setTimestamp(String parameterName, java.sql.Timestamp x) throws SQLException {
            stmt.setTimestamp(parameterName, x);

            return this;
        }

        /**
         * Sets the timestamp.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setTimestamp(String parameterName, java.util.Date x) throws SQLException {
            stmt.setTimestamp(parameterName, x == null ? null : x instanceof java.sql.Timestamp ? (java.sql.Timestamp) x : new java.sql.Timestamp(x.getTime()));

            return this;
        }

        /**
         * Sets the bytes.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setBytes(String parameterName, byte[] x) throws SQLException {
            stmt.setBytes(parameterName, x);

            return this;
        }

        /**
         * Sets the ascii stream.
         *
         * @param parameterName
         * @param inputStream
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setAsciiStream(String parameterName, InputStream inputStream) throws SQLException {
            stmt.setAsciiStream(parameterName, inputStream);

            return this;
        }

        /**
         * Sets the ascii stream.
         *
         * @param parameterName
         * @param inputStream
         * @param length
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setAsciiStream(String parameterName, InputStream inputStream, long length) throws SQLException {
            stmt.setAsciiStream(parameterName, inputStream, length);

            return this;
        }

        /**
         * Sets the binary stream.
         *
         * @param parameterName
         * @param inputStream
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setBinaryStream(String parameterName, InputStream inputStream) throws SQLException {
            stmt.setBinaryStream(parameterName, inputStream);

            return this;
        }

        /**
         * Sets the binary stream.
         *
         * @param parameterName
         * @param inputStream
         * @param length
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setBinaryStream(String parameterName, InputStream inputStream, long length) throws SQLException {
            stmt.setBinaryStream(parameterName, inputStream, length);

            return this;
        }

        /**
         * Sets the character stream.
         *
         * @param parameterName
         * @param reader
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setCharacterStream(String parameterName, Reader reader) throws SQLException {
            stmt.setCharacterStream(parameterName, reader);

            return this;
        }

        /**
         * Sets the character stream.
         *
         * @param parameterName
         * @param reader
         * @param length
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setCharacterStream(String parameterName, Reader reader, long length) throws SQLException {
            stmt.setCharacterStream(parameterName, reader, length);

            return this;
        }

        /**
         * Sets the N character stream.
         *
         * @param parameterName
         * @param reader
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setNCharacterStream(String parameterName, Reader reader) throws SQLException {
            stmt.setNCharacterStream(parameterName, reader);

            return this;
        }

        /**
         * Sets the N character stream.
         *
         * @param parameterName
         * @param reader
         * @param length
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setNCharacterStream(String parameterName, Reader reader, long length) throws SQLException {
            stmt.setNCharacterStream(parameterName, reader, length);

            return this;
        }

        /**
         * Sets the blob.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setBlob(String parameterName, java.sql.Blob x) throws SQLException {
            stmt.setBlob(parameterName, x);

            return this;
        }

        /**
         * Sets the blob.
         *
         * @param parameterName
         * @param inputStream
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setBlob(String parameterName, InputStream inputStream) throws SQLException {
            stmt.setBlob(parameterName, inputStream);

            return this;
        }

        /**
         * Sets the blob.
         *
         * @param parameterName
         * @param inputStream
         * @param length
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setBlob(String parameterName, InputStream inputStream, long length) throws SQLException {
            stmt.setBlob(parameterName, inputStream, length);

            return this;
        }

        /**
         * Sets the clob.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setClob(String parameterName, java.sql.Clob x) throws SQLException {
            stmt.setClob(parameterName, x);

            return this;
        }

        /**
         * Sets the clob.
         *
         * @param parameterName
         * @param reader
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setClob(String parameterName, Reader reader) throws SQLException {
            stmt.setClob(parameterName, reader);

            return this;
        }

        /**
         * Sets the clob.
         *
         * @param parameterName
         * @param reader
         * @param length
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setClob(String parameterName, Reader reader, long length) throws SQLException {
            stmt.setClob(parameterName, reader, length);

            return this;
        }

        /**
         * Sets the N clob.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setNClob(String parameterName, java.sql.NClob x) throws SQLException {
            stmt.setNClob(parameterName, x);

            return this;
        }

        /**
         * Sets the N clob.
         *
         * @param parameterName
         * @param reader
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setNClob(String parameterName, Reader reader) throws SQLException {
            stmt.setNClob(parameterName, reader);

            return this;
        }

        /**
         * Sets the N clob.
         *
         * @param parameterName
         * @param reader
         * @param length
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setNClob(String parameterName, Reader reader, long length) throws SQLException {
            stmt.setNClob(parameterName, reader, length);

            return this;
        }

        /**
         *
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setURL(String parameterName, URL x) throws SQLException {
            stmt.setURL(parameterName, x);

            return this;
        }

        /**
         *
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setSQLXML(String parameterName, java.sql.SQLXML x) throws SQLException {
            stmt.setSQLXML(parameterName, x);

            return this;
        }

        /**
         *
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setRowId(String parameterName, java.sql.RowId x) throws SQLException {
            stmt.setRowId(parameterName, x);

            return this;
        }

        /**
         * Sets the object.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setObject(String parameterName, Object x) throws SQLException {
            if (x == null) {
                stmt.setObject(parameterName, x);
            } else {
                N.typeOf(x.getClass()).set(stmt, parameterName, x);
            }

            return this;
        }

        /**
         * Sets the object.
         *
         * @param parameterName
         * @param x
         * @param sqlType
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setObject(String parameterName, Object x, int sqlType) throws SQLException {
            stmt.setObject(parameterName, x, sqlType);

            return this;
        }

        /**
         * Sets the object.
         *
         * @param parameterName
         * @param x
         * @param sqlType
         * @param scaleOrLength
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setObject(String parameterName, Object x, int sqlType, int scaleOrLength) throws SQLException {
            stmt.setObject(parameterName, x, sqlType, scaleOrLength);

            return this;
        }

        /**
         * Sets the parameters.
         *
         * @param parameters
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setParameters(Map<String, ?> parameters) throws SQLException {
            checkArgNotNull(parameters, "parameters");

            for (Map.Entry<String, ?> entry : parameters.entrySet()) {
                setObject(entry.getKey(), entry.getValue());
            }

            return this;
        }

        /**
         * Sets the parameters.
         *
         * @param parameterNames
         * @param entity
         * @return
         * @throws SQLException the SQL exception
         * @see {@link ClassUtil#getPropNameList(Class)}
         * @see {@link ClassUtil#getPropNameListExclusively(Class, Set)}
         * @see {@link ClassUtil#getPropNameListExclusively(Class, Collection)}
         * @see {@link JdbcUtil#getNamedParameters(String)}
         */
        public PreparedCallableQuery setParameters(List<String> parameterNames, Object entity) throws SQLException {
            checkArgNotNull(parameterNames, "parameterNames");
            checkArgNotNull(entity, "entity");

            final Class<?> cls = entity.getClass();
            final EntityInfo entityInfo = ParserUtil.getEntityInfo(cls);
            PropInfo propInfo = null;

            for (String parameterName : parameterNames) {
                propInfo = entityInfo.getPropInfo(parameterName);
                propInfo.dbType.set(stmt, parameterName, propInfo.getPropValue(entity));
            }

            return this;
        }

        /**
         * Register out parameter.
         *
         * @param parameterIndex starts from 1, not 0.
         * @param sqlType
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery registerOutParameter(int parameterIndex, int sqlType) throws SQLException {
            stmt.registerOutParameter(parameterIndex, sqlType);

            return this;
        }

        /**
         * Register out parameter.
         *
         * @param parameterIndex starts from 1, not 0.
         * @param sqlType
         * @param scale
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery registerOutParameter(int parameterIndex, int sqlType, int scale) throws SQLException {
            stmt.registerOutParameter(parameterIndex, sqlType, scale);

            return this;
        }

        /**
         * Register out parameter.
         *
         * @param parameterIndex starts from 1, not 0.
         * @param sqlType
         * @param typeName
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery registerOutParameter(int parameterIndex, int sqlType, String typeName) throws SQLException {
            stmt.registerOutParameter(parameterIndex, sqlType, typeName);

            return this;
        }

        /**
         * Register out parameter.
         *
         * @param parameterName
         * @param sqlType
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery registerOutParameter(String parameterName, int sqlType) throws SQLException {
            stmt.registerOutParameter(parameterName, sqlType);

            return this;
        }

        /**
         * Register out parameter.
         *
         * @param parameterName
         * @param sqlType
         * @param scale
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery registerOutParameter(String parameterName, int sqlType, int scale) throws SQLException {
            stmt.registerOutParameter(parameterName, sqlType, scale);

            return this;
        }

        /**
         * Register out parameter.
         *
         * @param parameterName
         * @param sqlType
         * @param typeName
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery registerOutParameter(String parameterName, int sqlType, String typeName) throws SQLException {
            stmt.registerOutParameter(parameterName, sqlType, typeName);

            return this;
        }

        /**
         * Register out parameter.
         *
         * @param parameterIndex starts from 1, not 0.
         * @param sqlType
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery registerOutParameter(int parameterIndex, SQLType sqlType) throws SQLException {
            stmt.registerOutParameter(parameterIndex, sqlType);

            return this;
        }

        /**
         * Register out parameter.
         *
         * @param parameterIndex starts from 1, not 0.
         * @param sqlType
         * @param scale
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery registerOutParameter(int parameterIndex, SQLType sqlType, int scale) throws SQLException {
            stmt.registerOutParameter(parameterIndex, sqlType, scale);

            return this;
        }

        /**
         * Register out parameter.
         *
         * @param parameterIndex starts from 1, not 0.
         * @param sqlType
         * @param typeName
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery registerOutParameter(int parameterIndex, SQLType sqlType, String typeName) throws SQLException {
            stmt.registerOutParameter(parameterIndex, sqlType, typeName);

            return this;
        }

        /**
         * Register out parameter.
         *
         * @param parameterName
         * @param sqlType
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery registerOutParameter(String parameterName, SQLType sqlType) throws SQLException {
            stmt.registerOutParameter(parameterName, sqlType);

            return this;
        }

        /**
         * Register out parameter.
         *
         * @param parameterName
         * @param sqlType
         * @param scale
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery registerOutParameter(String parameterName, SQLType sqlType, int scale) throws SQLException {
            stmt.registerOutParameter(parameterName, sqlType, scale);

            return this;
        }

        /**
         * Register out parameter.
         *
         * @param parameterName
         * @param sqlType
         * @param typeName
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery registerOutParameter(String parameterName, SQLType sqlType, String typeName) throws SQLException {
            stmt.registerOutParameter(parameterName, sqlType, typeName);

            return this;
        }

        /**
         * Register out parameters.
         *
         * @param register
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery registerOutParameters(final ParametersSetter<? super CallableStatement> register) throws SQLException {
            checkArgNotNull(register, "register");

            boolean noException = false;

            try {
                register.accept(stmt);

                noException = true;
            } finally {
                if (noException == false) {
                    close();
                }
            }

            return this;
        }

        /**
         * Register out parameters.
         *
         * @param <T>
         * @param parameter
         * @param register
         * @return
         * @throws SQLException the SQL exception
         */
        public <T> PreparedCallableQuery registerOutParameters(final T parameter, final BiParametersSetter<? super T, ? super CallableStatement> register)
                throws SQLException {
            checkArgNotNull(register, "register");

            boolean noException = false;

            try {
                register.accept(parameter, stmt);

                noException = true;
            } finally {
                if (noException == false) {
                    close();
                }
            }

            return this;
        }

        /**
         *
         * @param <R1>
         * @param resultExtrator1
         * @return
         * @throws SQLException the SQL exception
         */
        public <R1> Optional<R1> call(final ResultExtractor<R1> resultExtrator1) throws SQLException {
            checkArgNotNull(resultExtrator1, "resultExtrator1");
            assertNotClosed();

            try {
                if (JdbcUtil.execute(stmt)) {
                    if (stmt.getUpdateCount() == -1) {
                        try (ResultSet rs = stmt.getResultSet()) {
                            return Optional.of(checkNotResultSet(resultExtrator1.apply(rs)));
                        }
                    }
                }
            } finally {
                closeAfterExecutionIfAllowed();
            }

            return Optional.empty();
        }

        /**
         *
         * @param <R1>
         * @param <R2>
         * @param resultExtrator1
         * @param resultExtrator2
         * @return
         * @throws SQLException the SQL exception
         */
        public <R1, R2> Tuple2<Optional<R1>, Optional<R2>> call(final ResultExtractor<R1> resultExtrator1, final ResultExtractor<R2> resultExtrator2)
                throws SQLException {
            checkArgNotNull(resultExtrator1, "resultExtrator1");
            checkArgNotNull(resultExtrator2, "resultExtrator2");
            assertNotClosed();

            Optional<R1> result1 = Optional.empty();
            Optional<R2> result2 = Optional.empty();

            try {
                if (JdbcUtil.execute(stmt)) {
                    if (stmt.getUpdateCount() == -1) {
                        try (ResultSet rs = stmt.getResultSet()) {
                            result1 = Optional.of(checkNotResultSet(resultExtrator1.apply(rs)));
                        }
                    }

                    if (stmt.getMoreResults() && stmt.getUpdateCount() == -1) {
                        try (ResultSet rs = stmt.getResultSet()) {
                            result2 = Optional.of(checkNotResultSet(resultExtrator2.apply(rs)));
                        }
                    }
                }
            } finally {
                closeAfterExecutionIfAllowed();
            }

            return Tuple.of(result1, result2);
        }

        /**
         *
         * @param <R1>
         * @param <R2>
         * @param <R3>
         * @param resultExtrator1
         * @param resultExtrator2
         * @param resultExtrator3
         * @return
         * @throws SQLException the SQL exception
         */
        public <R1, R2, R3> Tuple3<Optional<R1>, Optional<R2>, Optional<R3>> call(final ResultExtractor<R1> resultExtrator1,
                final ResultExtractor<R2> resultExtrator2, final ResultExtractor<R3> resultExtrator3) throws SQLException {
            checkArgNotNull(resultExtrator1, "resultExtrator1");
            checkArgNotNull(resultExtrator2, "resultExtrator2");
            checkArgNotNull(resultExtrator3, "resultExtrator3");
            assertNotClosed();

            Optional<R1> result1 = Optional.empty();
            Optional<R2> result2 = Optional.empty();
            Optional<R3> result3 = Optional.empty();

            try {
                if (JdbcUtil.execute(stmt)) {
                    if (stmt.getUpdateCount() == -1) {
                        try (ResultSet rs = stmt.getResultSet()) {
                            result1 = Optional.of(checkNotResultSet(resultExtrator1.apply(rs)));
                        }
                    }

                    if (stmt.getMoreResults() && stmt.getUpdateCount() == -1) {
                        try (ResultSet rs = stmt.getResultSet()) {
                            result2 = Optional.of(checkNotResultSet(resultExtrator2.apply(rs)));
                        }
                    }

                    if (stmt.getMoreResults() && stmt.getUpdateCount() == -1) {
                        try (ResultSet rs = stmt.getResultSet()) {
                            result3 = Optional.of(checkNotResultSet(resultExtrator3.apply(rs)));
                        }
                    }
                }
            } finally {
                closeAfterExecutionIfAllowed();
            }

            return Tuple.of(result1, result2, result3);
        }

        /**
         *
         * @param <R1>
         * @param <R2>
         * @param <R3>
         * @param <R4>
         * @param resultExtrator1
         * @param resultExtrator2
         * @param resultExtrator3
         * @param resultExtrator4
         * @return
         * @throws SQLException the SQL exception
         */
        public <R1, R2, R3, R4> Tuple4<Optional<R1>, Optional<R2>, Optional<R3>, Optional<R4>> call(final ResultExtractor<R1> resultExtrator1,
                final ResultExtractor<R2> resultExtrator2, final ResultExtractor<R3> resultExtrator3, final ResultExtractor<R4> resultExtrator4)
                throws SQLException {
            checkArgNotNull(resultExtrator1, "resultExtrator1");
            checkArgNotNull(resultExtrator2, "resultExtrator2");
            checkArgNotNull(resultExtrator3, "resultExtrator3");
            checkArgNotNull(resultExtrator4, "resultExtrator4");
            assertNotClosed();

            Optional<R1> result1 = Optional.empty();
            Optional<R2> result2 = Optional.empty();
            Optional<R3> result3 = Optional.empty();
            Optional<R4> result4 = Optional.empty();

            try {
                if (JdbcUtil.execute(stmt)) {
                    if (stmt.getUpdateCount() == -1) {
                        try (ResultSet rs = stmt.getResultSet()) {
                            result1 = Optional.of(checkNotResultSet(resultExtrator1.apply(rs)));
                        }
                    }

                    if (stmt.getMoreResults() && stmt.getUpdateCount() == -1) {
                        try (ResultSet rs = stmt.getResultSet()) {
                            result2 = Optional.of(checkNotResultSet(resultExtrator2.apply(rs)));
                        }
                    }

                    if (stmt.getMoreResults() && stmt.getUpdateCount() == -1) {
                        try (ResultSet rs = stmt.getResultSet()) {
                            result3 = Optional.of(checkNotResultSet(resultExtrator3.apply(rs)));
                        }
                    }

                    if (stmt.getMoreResults() && stmt.getUpdateCount() == -1) {
                        try (ResultSet rs = stmt.getResultSet()) {
                            result4 = Optional.of(checkNotResultSet(resultExtrator4.apply(rs)));
                        }
                    }
                }
            } finally {
                closeAfterExecutionIfAllowed();
            }

            return Tuple.of(result1, result2, result3, result4);
        }

        /**
         *
         * @param <R1>
         * @param <R2>
         * @param <R3>
         * @param <R4>
         * @param <R5>
         * @param resultExtrator1
         * @param resultExtrator2
         * @param resultExtrator3
         * @param resultExtrator4
         * @param resultExtrator5
         * @return
         * @throws SQLException the SQL exception
         */
        public <R1, R2, R3, R4, R5> Tuple5<Optional<R1>, Optional<R2>, Optional<R3>, Optional<R4>, Optional<R5>> call(final ResultExtractor<R1> resultExtrator1,
                final ResultExtractor<R2> resultExtrator2, final ResultExtractor<R3> resultExtrator3, final ResultExtractor<R4> resultExtrator4,
                final ResultExtractor<R5> resultExtrator5) throws SQLException {
            checkArgNotNull(resultExtrator1, "resultExtrator1");
            checkArgNotNull(resultExtrator2, "resultExtrator2");
            checkArgNotNull(resultExtrator3, "resultExtrator3");
            checkArgNotNull(resultExtrator4, "resultExtrator4");
            checkArgNotNull(resultExtrator5, "resultExtrator5");
            assertNotClosed();

            Optional<R1> result1 = Optional.empty();
            Optional<R2> result2 = Optional.empty();
            Optional<R3> result3 = Optional.empty();
            Optional<R4> result4 = Optional.empty();
            Optional<R5> result5 = Optional.empty();

            try {
                if (JdbcUtil.execute(stmt)) {
                    if (stmt.getUpdateCount() == -1) {
                        try (ResultSet rs = stmt.getResultSet()) {
                            result1 = Optional.of(checkNotResultSet(resultExtrator1.apply(rs)));
                        }
                    }

                    if (stmt.getMoreResults() && stmt.getUpdateCount() == -1) {
                        try (ResultSet rs = stmt.getResultSet()) {
                            result2 = Optional.of(checkNotResultSet(resultExtrator2.apply(rs)));
                        }
                    }

                    if (stmt.getMoreResults() && stmt.getUpdateCount() == -1) {
                        try (ResultSet rs = stmt.getResultSet()) {
                            result3 = Optional.of(checkNotResultSet(resultExtrator3.apply(rs)));
                        }
                    }

                    if (stmt.getMoreResults() && stmt.getUpdateCount() == -1) {
                        try (ResultSet rs = stmt.getResultSet()) {
                            result4 = Optional.of(checkNotResultSet(resultExtrator4.apply(rs)));
                        }
                    }

                    if (stmt.getMoreResults() && stmt.getUpdateCount() == -1) {
                        try (ResultSet rs = stmt.getResultSet()) {
                            result5 = Optional.of(checkNotResultSet(resultExtrator5.apply(rs)));
                        }
                    }
                }
            } finally {
                closeAfterExecutionIfAllowed();
            }

            return Tuple.of(result1, result2, result3, result4, result5);
        }

    }

    /**
     * The backed {@code PreparedStatement/CallableStatement} will be closed by default
     * after any execution methods(which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/list/execute/...).
     * except the {@code 'closeAfterExecution'} flag is set to {@code false} by calling {@code #closeAfterExecution(false)}.
     *
     * <br />
     * Generally, don't cache or reuse the instance of this class,
     * except the {@code 'closeAfterExecution'} flag is set to {@code false} by calling {@code #closeAfterExecution(false)}.
     *
     * <br />
     * The {@code ResultSet} returned by query will always be closed after execution, even {@code 'closeAfterExecution'} flag is set to {@code false}.
     *
     * <br />
     * Remember: parameter/column index in {@code PreparedStatement/ResultSet} starts from 1, not 0.
     *
     * @author haiyangl
     *
     * @see {@link com.landawn.abacus.annotation.ReadOnly}
     * @see {@link com.landawn.abacus.annotation.ReadOnlyId}
     * @see {@link com.landawn.abacus.annotation.NonUpdatable}
     * @see {@link com.landawn.abacus.annotation.Transient}
     * @see {@link com.landawn.abacus.annotation.Table}
     * @see {@link com.landawn.abacus.annotation.Column}
     *
     * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/Connection.html">http://docs.oracle.com/javase/8/docs/api/java/sql/Connection.html</a>
     * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/Statement.html">http://docs.oracle.com/javase/8/docs/api/java/sql/Statement.html</a>
     * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/PreparedStatement.html">http://docs.oracle.com/javase/8/docs/api/java/sql/PreparedStatement.html</a>
     * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/ResultSet.html">http://docs.oracle.com/javase/8/docs/api/java/sql/ResultSet.html</a>
     */
    public static class NamedQuery extends AbstractPreparedQuery<PreparedStatement, NamedQuery> {

        /** The named SQL. */
        private final ParsedSql namedSql;

        /** The parameter names. */
        private final List<String> parameterNames;

        /** The parameter count. */
        private final int parameterCount;

        /** The param name index map. */
        private Map<String, IntList> paramNameIndexMap;

        /**
         * Instantiates a new named query.
         *
         * @param stmt
         * @param namedSql
         */
        NamedQuery(final PreparedStatement stmt, final ParsedSql namedSql) {
            super(stmt);
            this.namedSql = namedSql;
            this.parameterNames = namedSql.getNamedParameters();
            this.parameterCount = namedSql.getParameterCount();

            if (N.size(namedSql.getNamedParameters()) != parameterCount) {
                throw new IllegalArgumentException("Invalid named sql: " + namedSql.sql());
            }
        }

        /**
         * Inits the param name index map.
         */
        private void initParamNameIndexMap() {
            paramNameIndexMap = new HashMap<>(parameterCount);
            int index = 1;

            for (String paramName : parameterNames) {
                IntList indexes = paramNameIndexMap.get(paramName);

                if (indexes == null) {
                    indexes = new IntList(1);
                    paramNameIndexMap.put(paramName, indexes);
                }

                indexes.add(index++);
            }
        }

        /**
         * Sets the null.
         *
         * @param parameterName
         * @param sqlType
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setNull(String parameterName, int sqlType) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setNull(i + 1, sqlType);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setNull(indexes.get(0), sqlType);
                    } else if (indexes.size() == 2) {
                        setNull(indexes.get(0), sqlType);
                        setNull(indexes.get(1), sqlType);
                    } else if (indexes.size() == 3) {
                        setNull(indexes.get(0), sqlType);
                        setNull(indexes.get(1), sqlType);
                        setNull(indexes.get(2), sqlType);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setNull(indexes.get(i), sqlType);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the null.
         *
         * @param parameterName
         * @param sqlType
         * @param typeName
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setNull(String parameterName, int sqlType, String typeName) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setNull(i + 1, sqlType, typeName);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setNull(indexes.get(0), sqlType, typeName);
                    } else if (indexes.size() == 2) {
                        setNull(indexes.get(0), sqlType, typeName);
                        setNull(indexes.get(1), sqlType, typeName);
                    } else if (indexes.size() == 3) {
                        setNull(indexes.get(0), sqlType, typeName);
                        setNull(indexes.get(1), sqlType, typeName);
                        setNull(indexes.get(2), sqlType, typeName);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setNull(indexes.get(i), sqlType, typeName);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the boolean.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setBoolean(String parameterName, boolean x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setBoolean(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setBoolean(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setBoolean(indexes.get(0), x);
                        setBoolean(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setBoolean(indexes.get(0), x);
                        setBoolean(indexes.get(1), x);
                        setBoolean(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setBoolean(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the boolean.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setBoolean(String parameterName, Boolean x) throws SQLException {
            setBoolean(parameterName, N.defaultIfNull(x));

            return this;
        }

        /**
         * Sets the byte.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setByte(String parameterName, byte x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setByte(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setByte(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setByte(indexes.get(0), x);
                        setByte(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setByte(indexes.get(0), x);
                        setByte(indexes.get(1), x);
                        setByte(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setByte(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the byte.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setByte(String parameterName, Byte x) throws SQLException {
            setByte(parameterName, N.defaultIfNull(x));

            return this;
        }

        /**
         * Sets the short.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setShort(String parameterName, short x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setShort(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setShort(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setShort(indexes.get(0), x);
                        setShort(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setShort(indexes.get(0), x);
                        setShort(indexes.get(1), x);
                        setShort(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setShort(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the short.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setShort(String parameterName, Short x) throws SQLException {
            setShort(parameterName, N.defaultIfNull(x));

            return this;
        }

        /**
         * Sets the int.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setInt(String parameterName, int x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setInt(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setInt(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setInt(indexes.get(0), x);
                        setInt(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setInt(indexes.get(0), x);
                        setInt(indexes.get(1), x);
                        setInt(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setInt(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the int.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setInt(String parameterName, Integer x) throws SQLException {
            setInt(parameterName, N.defaultIfNull(x));

            return this;
        }

        /**
         * Sets the long.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setLong(String parameterName, long x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setLong(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setLong(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setLong(indexes.get(0), x);
                        setLong(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setLong(indexes.get(0), x);
                        setLong(indexes.get(1), x);
                        setLong(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setLong(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the long.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setLong(String parameterName, Long x) throws SQLException {
            setLong(parameterName, N.defaultIfNull(x));

            return this;
        }

        /**
         * Sets the float.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setFloat(String parameterName, float x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setFloat(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setFloat(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setFloat(indexes.get(0), x);
                        setFloat(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setFloat(indexes.get(0), x);
                        setFloat(indexes.get(1), x);
                        setFloat(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setFloat(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the float.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setFloat(String parameterName, Float x) throws SQLException {
            setFloat(parameterName, N.defaultIfNull(x));

            return this;
        }

        /**
         * Sets the double.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setDouble(String parameterName, double x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setDouble(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setDouble(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setDouble(indexes.get(0), x);
                        setDouble(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setDouble(indexes.get(0), x);
                        setDouble(indexes.get(1), x);
                        setDouble(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setDouble(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the double.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setDouble(String parameterName, Double x) throws SQLException {
            setDouble(parameterName, N.defaultIfNull(x));

            return this;
        }

        /**
         * Sets the big decimal.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setBigDecimal(String parameterName, BigDecimal x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setBigDecimal(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setBigDecimal(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setBigDecimal(indexes.get(0), x);
                        setBigDecimal(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setBigDecimal(indexes.get(0), x);
                        setBigDecimal(indexes.get(1), x);
                        setBigDecimal(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setBigDecimal(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the string.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setString(String parameterName, String x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setString(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setString(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setString(indexes.get(0), x);
                        setString(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setString(indexes.get(0), x);
                        setString(indexes.get(1), x);
                        setString(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setString(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the date.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setDate(String parameterName, java.sql.Date x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setDate(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setDate(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setDate(indexes.get(0), x);
                        setDate(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setDate(indexes.get(0), x);
                        setDate(indexes.get(1), x);
                        setDate(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setDate(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the date.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setDate(String parameterName, java.util.Date x) throws SQLException {
            setDate(parameterName, x == null ? null : x instanceof java.sql.Date ? (java.sql.Date) x : new java.sql.Date(x.getTime()));

            return this;
        }

        /**
         * Sets the time.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setTime(String parameterName, java.sql.Time x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setTime(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setTime(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setTime(indexes.get(0), x);
                        setTime(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setTime(indexes.get(0), x);
                        setTime(indexes.get(1), x);
                        setTime(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setTime(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the time.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setTime(String parameterName, java.util.Date x) throws SQLException {
            setTime(parameterName, x == null ? null : x instanceof java.sql.Time ? (java.sql.Time) x : new java.sql.Time(x.getTime()));

            return this;
        }

        /**
         * Sets the timestamp.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setTimestamp(String parameterName, java.sql.Timestamp x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setTimestamp(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setTimestamp(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setTimestamp(indexes.get(0), x);
                        setTimestamp(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setTimestamp(indexes.get(0), x);
                        setTimestamp(indexes.get(1), x);
                        setTimestamp(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setTimestamp(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the timestamp.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setTimestamp(String parameterName, java.util.Date x) throws SQLException {
            setTimestamp(parameterName, x == null ? null : x instanceof java.sql.Timestamp ? (java.sql.Timestamp) x : new java.sql.Timestamp(x.getTime()));

            return this;
        }

        /**
         * Sets the bytes.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setBytes(String parameterName, byte[] x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setBytes(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setBytes(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setBytes(indexes.get(0), x);
                        setBytes(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setBytes(indexes.get(0), x);
                        setBytes(indexes.get(1), x);
                        setBytes(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setBytes(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the ascii stream.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setAsciiStream(String parameterName, InputStream x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setAsciiStream(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setAsciiStream(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setAsciiStream(indexes.get(0), x);
                        setAsciiStream(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setAsciiStream(indexes.get(0), x);
                        setAsciiStream(indexes.get(1), x);
                        setAsciiStream(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setAsciiStream(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the ascii stream.
         *
         * @param parameterName
         * @param x
         * @param length
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setAsciiStream(String parameterName, InputStream x, long length) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setAsciiStream(i + 1, x, length);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setAsciiStream(indexes.get(0), x, length);
                    } else if (indexes.size() == 2) {
                        setAsciiStream(indexes.get(0), x, length);
                        setAsciiStream(indexes.get(1), x, length);
                    } else if (indexes.size() == 3) {
                        setAsciiStream(indexes.get(0), x, length);
                        setAsciiStream(indexes.get(1), x, length);
                        setAsciiStream(indexes.get(2), x, length);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setAsciiStream(indexes.get(i), x, length);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the binary stream.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setBinaryStream(String parameterName, InputStream x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setBinaryStream(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setBinaryStream(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setBinaryStream(indexes.get(0), x);
                        setBinaryStream(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setBinaryStream(indexes.get(0), x);
                        setBinaryStream(indexes.get(1), x);
                        setBinaryStream(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setBinaryStream(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the binary stream.
         *
         * @param parameterName
         * @param x
         * @param length
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setBinaryStream(String parameterName, InputStream x, long length) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setBinaryStream(i + 1, x, length);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setBinaryStream(indexes.get(0), x, length);
                    } else if (indexes.size() == 2) {
                        setBinaryStream(indexes.get(0), x, length);
                        setBinaryStream(indexes.get(1), x, length);
                    } else if (indexes.size() == 3) {
                        setBinaryStream(indexes.get(0), x, length);
                        setBinaryStream(indexes.get(1), x, length);
                        setBinaryStream(indexes.get(2), x, length);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setBinaryStream(indexes.get(i), x, length);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the character stream.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setCharacterStream(String parameterName, Reader x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setCharacterStream(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setCharacterStream(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setCharacterStream(indexes.get(0), x);
                        setCharacterStream(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setCharacterStream(indexes.get(0), x);
                        setCharacterStream(indexes.get(1), x);
                        setCharacterStream(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setCharacterStream(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the character stream.
         *
         * @param parameterName
         * @param x
         * @param length
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setCharacterStream(String parameterName, Reader x, long length) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setCharacterStream(i + 1, x, length);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setCharacterStream(indexes.get(0), x, length);
                    } else if (indexes.size() == 2) {
                        setCharacterStream(indexes.get(0), x, length);
                        setCharacterStream(indexes.get(1), x, length);
                    } else if (indexes.size() == 3) {
                        setCharacterStream(indexes.get(0), x, length);
                        setCharacterStream(indexes.get(1), x, length);
                        setCharacterStream(indexes.get(2), x, length);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setCharacterStream(indexes.get(i), x, length);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the N character stream.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setNCharacterStream(String parameterName, Reader x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setNCharacterStream(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setNCharacterStream(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setNCharacterStream(indexes.get(0), x);
                        setNCharacterStream(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setNCharacterStream(indexes.get(0), x);
                        setNCharacterStream(indexes.get(1), x);
                        setNCharacterStream(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setNCharacterStream(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the N character stream.
         *
         * @param parameterName
         * @param x
         * @param length
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setNCharacterStream(String parameterName, Reader x, long length) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setNCharacterStream(i + 1, x, length);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setNCharacterStream(indexes.get(0), x, length);
                    } else if (indexes.size() == 2) {
                        setNCharacterStream(indexes.get(0), x, length);
                        setNCharacterStream(indexes.get(1), x, length);
                    } else if (indexes.size() == 3) {
                        setNCharacterStream(indexes.get(0), x, length);
                        setNCharacterStream(indexes.get(1), x, length);
                        setNCharacterStream(indexes.get(2), x, length);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setNCharacterStream(indexes.get(i), x, length);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the blob.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setBlob(String parameterName, java.sql.Blob x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setBlob(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setBlob(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setBlob(indexes.get(0), x);
                        setBlob(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setBlob(indexes.get(0), x);
                        setBlob(indexes.get(1), x);
                        setBlob(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setBlob(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the blob.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setBlob(String parameterName, InputStream x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setBlob(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setBlob(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setBlob(indexes.get(0), x);
                        setBlob(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setBlob(indexes.get(0), x);
                        setBlob(indexes.get(1), x);
                        setBlob(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setBlob(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the blob.
         *
         * @param parameterName
         * @param x
         * @param length
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setBlob(String parameterName, InputStream x, long length) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setBlob(i + 1, x, length);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setBlob(indexes.get(0), x, length);
                    } else if (indexes.size() == 2) {
                        setBlob(indexes.get(0), x, length);
                        setBlob(indexes.get(1), x, length);
                    } else if (indexes.size() == 3) {
                        setBlob(indexes.get(0), x, length);
                        setBlob(indexes.get(1), x, length);
                        setBlob(indexes.get(2), x, length);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setBlob(indexes.get(i), x, length);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the clob.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setClob(String parameterName, java.sql.Clob x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setClob(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setClob(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setClob(indexes.get(0), x);
                        setClob(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setClob(indexes.get(0), x);
                        setClob(indexes.get(1), x);
                        setClob(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setClob(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the clob.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setClob(String parameterName, Reader x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setClob(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setClob(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setClob(indexes.get(0), x);
                        setClob(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setClob(indexes.get(0), x);
                        setClob(indexes.get(1), x);
                        setClob(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setClob(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the clob.
         *
         * @param parameterName
         * @param x
         * @param length
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setClob(String parameterName, Reader x, long length) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setClob(i + 1, x, length);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setClob(indexes.get(0), x, length);
                    } else if (indexes.size() == 2) {
                        setClob(indexes.get(0), x, length);
                        setClob(indexes.get(1), x, length);
                    } else if (indexes.size() == 3) {
                        setClob(indexes.get(0), x, length);
                        setClob(indexes.get(1), x, length);
                        setClob(indexes.get(2), x, length);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setClob(indexes.get(i), x, length);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the N clob.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setNClob(String parameterName, java.sql.NClob x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setNClob(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setNClob(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setNClob(indexes.get(0), x);
                        setNClob(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setNClob(indexes.get(0), x);
                        setNClob(indexes.get(1), x);
                        setNClob(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setNClob(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the N clob.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setNClob(String parameterName, Reader x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setNClob(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setNClob(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setNClob(indexes.get(0), x);
                        setNClob(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setNClob(indexes.get(0), x);
                        setNClob(indexes.get(1), x);
                        setNClob(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setNClob(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the N clob.
         *
         * @param parameterName
         * @param x
         * @param length
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setNClob(String parameterName, Reader x, long length) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setNClob(i + 1, x, length);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setNClob(indexes.get(0), x, length);
                    } else if (indexes.size() == 2) {
                        setNClob(indexes.get(0), x, length);
                        setNClob(indexes.get(1), x, length);
                    } else if (indexes.size() == 3) {
                        setNClob(indexes.get(0), x, length);
                        setNClob(indexes.get(1), x, length);
                        setNClob(indexes.get(2), x, length);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setNClob(indexes.get(i), x, length);
                        }
                    }
                }
            }

            return this;
        }

        /**
         *
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setURL(String parameterName, URL x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setURL(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setURL(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setURL(indexes.get(0), x);
                        setURL(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setURL(indexes.get(0), x);
                        setURL(indexes.get(1), x);
                        setURL(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setURL(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         *
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setSQLXML(String parameterName, java.sql.SQLXML x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setSQLXML(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setSQLXML(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setSQLXML(indexes.get(0), x);
                        setSQLXML(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setSQLXML(indexes.get(0), x);
                        setSQLXML(indexes.get(1), x);
                        setSQLXML(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setSQLXML(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         *
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setRowId(String parameterName, java.sql.RowId x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setRowId(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setRowId(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setRowId(indexes.get(0), x);
                        setRowId(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setRowId(indexes.get(0), x);
                        setRowId(indexes.get(1), x);
                        setRowId(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setRowId(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         *
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setRef(String parameterName, java.sql.Ref x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setRef(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setRef(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setRef(indexes.get(0), x);
                        setRef(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setRef(indexes.get(0), x);
                        setRef(indexes.get(1), x);
                        setRef(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setRef(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         *
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setArray(String parameterName, java.sql.Array x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setArray(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setArray(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setArray(indexes.get(0), x);
                        setArray(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setArray(indexes.get(0), x);
                        setArray(indexes.get(1), x);
                        setArray(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setArray(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the object.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setObject(String parameterName, Object x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setObject(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setObject(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setObject(indexes.get(0), x);
                        setObject(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setObject(indexes.get(0), x);
                        setObject(indexes.get(1), x);
                        setObject(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setObject(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the object.
         *
         * @param parameterName
         * @param x
         * @param sqlType
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setObject(String parameterName, Object x, int sqlType) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setObject(i + 1, x, sqlType);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setObject(indexes.get(0), x, sqlType);
                    } else if (indexes.size() == 2) {
                        setObject(indexes.get(0), x, sqlType);
                        setObject(indexes.get(1), x, sqlType);
                    } else if (indexes.size() == 3) {
                        setObject(indexes.get(0), x, sqlType);
                        setObject(indexes.get(1), x, sqlType);
                        setObject(indexes.get(2), x, sqlType);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setObject(indexes.get(i), x, sqlType);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the object.
         *
         * @param parameterName
         * @param x
         * @param sqlType
         * @param scaleOrLength
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setObject(String parameterName, Object x, int sqlType, int scaleOrLength) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setObject(i + 1, x, sqlType, scaleOrLength);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setObject(indexes.get(0), x, sqlType, scaleOrLength);
                    } else if (indexes.size() == 2) {
                        setObject(indexes.get(0), x, sqlType, scaleOrLength);
                        setObject(indexes.get(1), x, sqlType, scaleOrLength);
                    } else if (indexes.size() == 3) {
                        setObject(indexes.get(0), x, sqlType, scaleOrLength);
                        setObject(indexes.get(1), x, sqlType, scaleOrLength);
                        setObject(indexes.get(2), x, sqlType, scaleOrLength);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setObject(indexes.get(i), x, sqlType, scaleOrLength);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the object.
         *
         * @param parameterName
         * @param x
         * @param sqlType
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setObject(String parameterName, Object x, SQLType sqlType) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setObject(i + 1, x, sqlType);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setObject(indexes.get(0), x, sqlType);
                    } else if (indexes.size() == 2) {
                        setObject(indexes.get(0), x, sqlType);
                        setObject(indexes.get(1), x, sqlType);
                    } else if (indexes.size() == 3) {
                        setObject(indexes.get(0), x, sqlType);
                        setObject(indexes.get(1), x, sqlType);
                        setObject(indexes.get(2), x, sqlType);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setObject(indexes.get(i), x, sqlType);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the object.
         *
         * @param parameterName
         * @param x
         * @param sqlType
         * @param scaleOrLength
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setObject(String parameterName, Object x, SQLType sqlType, int scaleOrLength) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setObject(i + 1, x, sqlType, scaleOrLength);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setObject(indexes.get(0), x, sqlType, scaleOrLength);
                    } else if (indexes.size() == 2) {
                        setObject(indexes.get(0), x, sqlType, scaleOrLength);
                        setObject(indexes.get(1), x, sqlType, scaleOrLength);
                    } else if (indexes.size() == 3) {
                        setObject(indexes.get(0), x, sqlType, scaleOrLength);
                        setObject(indexes.get(1), x, sqlType, scaleOrLength);
                        setObject(indexes.get(2), x, sqlType, scaleOrLength);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setObject(indexes.get(i), x, sqlType, scaleOrLength);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the object.
         *
         * @param parameterName
         * @param x
         * @param type
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setObject(final String parameterName, final Object x, final Type<Object> type) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        type.set(stmt, i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        type.set(stmt, indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        type.set(stmt, indexes.get(0), x);
                        type.set(stmt, indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        type.set(stmt, indexes.get(0), x);
                        type.set(stmt, indexes.get(1), x);
                        type.set(stmt, indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            type.set(stmt, indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the parameters.
         *
         * @param parameters
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setParameters(final Map<String, ?> parameters) throws SQLException {
            checkArgNotNull(parameters, "parameters");

            for (String paramName : parameterNames) {
                if (parameters.containsKey(paramName)) {
                    setObject(paramName, parameters.get(paramName));
                }
            }

            return this;
        }

        /**
         * Sets the parameters.
         *
         * @param parameters with getter/setter methods
         * @return
         * @throws SQLException the SQL exception
         */
        @SuppressWarnings("rawtypes")
        public NamedQuery setParameters(final Object parameters) throws SQLException {
            checkArgNotNull(parameters, "parameters");

            if (ClassUtil.isEntity(parameters.getClass())) {
                final Class<?> cls = parameters.getClass();
                final EntityInfo entityInfo = ParserUtil.getEntityInfo(cls);
                PropInfo propInfo = null;

                for (int i = 0; i < parameterCount; i++) {
                    propInfo = entityInfo.getPropInfo(parameterNames.get(i));

                    if (propInfo != null) {
                        propInfo.dbType.set(stmt, i + 1, propInfo.getPropValue(parameters));
                    }
                }
            } else if (parameters instanceof Map) {
                return setParameters((Map<String, ?>) parameters);
            } else if (parameters instanceof Collection) {
                return setParameters((Collection) parameters);
            } else if (parameters instanceof Object[]) {
                return setParameters((Object[]) parameters);
            } else if (parameters instanceof EntityId) {
                final EntityId entityId = (EntityId) parameters;

                for (String paramName : parameterNames) {
                    if (entityId.containsKey(paramName)) {
                        setObject(paramName, entityId.get(paramName));
                    }
                }
            } else if (parameterCount == 1) {
                setObject(1, parameters);
            } else {
                close();
                throw new IllegalArgumentException("Unsupported named parameter type: " + parameters.getClass() + " for named sql: " + namedSql.sql());
            }

            return this;
        }

        /**
         * Sets the parameters.
         *
         * @param <T>
         * @param paramaters
         * @param parametersSetter
         * @return
         * @throws SQLException the SQL exception
         */
        public <T> NamedQuery setParameters(final T paramaters, TriParametersSetter<NamedQuery, T> parametersSetter) throws SQLException {
            checkArgNotNull(parametersSetter, "parametersSetter");

            boolean noException = false;

            try {
                parametersSetter.accept(namedSql, this, paramaters);

                noException = true;
            } finally {
                if (noException == false) {
                    close();
                }
            }

            return this;
        }

        @Override
        @SuppressWarnings("rawtypes")
        public <T> NamedQuery addBatchParameters(final Collection<T> batchParameters) throws SQLException {
            checkArgNotNull(batchParameters, "batchParameters");

            if (N.isNullOrEmpty(batchParameters)) {
                return this;
            }

            boolean noException = false;

            try {
                final T first = N.firstNonNull(batchParameters).orNull();

                if (first == null) {
                    if (parameterCount == 1) {
                        for (int i = 0, size = batchParameters.size(); i < size; i++) {
                            stmt.setObject(1, null);

                            stmt.addBatch();
                        }
                    } else {
                        throw new IllegalArgumentException("Unsupported named parameter type: null for named sql: " + namedSql.sql());
                    }
                } else {
                    final Class<?> cls = first.getClass();

                    if (ClassUtil.isEntity(cls)) {
                        final EntityInfo entityInfo = ParserUtil.getEntityInfo(cls);
                        PropInfo propInfo = null;

                        for (Object entity : batchParameters) {
                            for (int i = 0; i < parameterCount; i++) {
                                propInfo = entityInfo.getPropInfo(parameterNames.get(i));

                                if (propInfo != null) {
                                    propInfo.dbType.set(stmt, i + 1, propInfo.getPropValue(entity));
                                }
                            }

                            stmt.addBatch();
                        }
                    } else if (Map.class.isAssignableFrom(cls)) {
                        for (Object map : batchParameters) {
                            setParameters((Map<String, ?>) map);

                            stmt.addBatch();
                        }

                    } else if (first instanceof Collection) {
                        for (Object parameters : batchParameters) {
                            setParameters((Collection) parameters);

                            stmt.addBatch();
                        }
                    } else if (first instanceof Object[]) {
                        for (Object parameters : batchParameters) {
                            setParameters((Object[]) parameters);

                            stmt.addBatch();
                        }
                    } else if (first instanceof EntityId) {
                        for (Object parameters : batchParameters) {
                            final EntityId entityId = (EntityId) parameters;

                            for (String paramName : parameterNames) {
                                if (entityId.containsKey(paramName)) {
                                    setObject(paramName, entityId.get(paramName));
                                }
                            }

                            stmt.addBatch();
                        }
                    } else if (parameterCount == 1) {
                        for (Object obj : batchParameters) {
                            setObject(1, obj);

                            stmt.addBatch();
                        }
                    } else {
                        throw new IllegalArgumentException("Unsupported named parameter type: " + cls + " for named sql: " + namedSql.sql());
                    }
                }

                isBatch = batchParameters.size() > 0;

                noException = true;
            } finally {
                if (noException == false) {
                    close();
                }
            }

            return this;
        }

        //        /**
        //         * 
        //         * @param batchParameters
        //         * @return
        //         * @throws SQLException the SQL exception
        //         */
        //        @Override
        //        public NamedQuery addSingleBatchParameters(final Collection<?> batchParameters) throws SQLException {
        //            checkArgNotNull(batchParameters, "batchParameters");
        //
        //            if (parameterCount != 1) {
        //                try {
        //                    close();
        //                } catch (Exception e) {
        //                    JdbcUtil.logger.error("Failed to close PreparedQuery", e);
        //                }
        //
        //                throw new IllegalArgumentException("isSingleParameter is true but the count of parameters in query is: " + parameterCount);
        //            }
        //
        //            if (N.isNullOrEmpty(batchParameters)) {
        //                return this;
        //            }
        //
        //            boolean noException = false;
        //
        //            try {
        //                for (Object obj : batchParameters) {
        //                    setObject(1, obj);
        //
        //                    stmt.addBatch();
        //                }
        //
        //                isBatch = batchParameters.size() > 0;
        //
        //                noException = true;
        //            } finally {
        //                if (noException == false) {
        //                    close();
        //                }
        //            }
        //
        //            return this;
        //        }
        //
        //        /**
        //         *
        //         * @param batchParameters
        //         * @return
        //         * @throws SQLException the SQL exception
        //         */
        //        @Override
        //        public NamedQuery addSingleBatchParameters(final Iterator<?> batchParameters) throws SQLException {
        //            checkArgNotNull(batchParameters, "batchParameters");
        //
        //            return addSingleBatchParameters(Iterators.toList(batchParameters));
        //        }
    }

    /**
     * The Interface ParametersSetter.
     *
     * @param <QS>
     */
    public interface ParametersSetter<QS> extends Throwables.Consumer<QS, SQLException> {
        @SuppressWarnings("rawtypes")
        public static final ParametersSetter DO_NOTHING = new ParametersSetter<Object>() {
            @Override
            public void accept(Object preparedQuery) throws SQLException {
                // Do nothing.
            }
        };

        /**
         *
         * @param preparedQuery
         * @throws SQLException the SQL exception
         */
        @Override
        void accept(QS preparedQuery) throws SQLException;
    }

    /**
     * The Interface BiParametersSetter.
     *
     * @param <QS>
     * @param <T>
     */
    public interface BiParametersSetter<QS, T> extends Throwables.BiConsumer<QS, T, SQLException> {
        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter DO_NOTHING = new BiParametersSetter<Object, Object>() {
            @Override
            public void accept(Object preparedQuery, Object param) throws SQLException {
                // Do nothing.
            }
        };

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, String> SET_FIRST_STRING = new BiParametersSetter<AbstractPreparedQuery, String>() {
            @Override
            public void accept(AbstractPreparedQuery preparedQuery, String param) throws SQLException {
                preparedQuery.setString(1, param);
            }
        };

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Object> SET_FIRST_OBJECT = new BiParametersSetter<AbstractPreparedQuery, Object>() {
            @Override
            public void accept(AbstractPreparedQuery preparedQuery, Object param) throws SQLException {
                preparedQuery.setObject(1, param);
            }
        };

        /**
         *
         * @param preparedQuery
         * @param param
         * @throws SQLException the SQL exception
         */
        @Override
        void accept(QS preparedQuery, T param) throws SQLException;
    }

    /**
     * The Interface TriParametersSetter.
     *
     * @param <QS>
     * @param <T>
     */
    public interface TriParametersSetter<QS, T> extends Throwables.TriConsumer<ParsedSql, QS, T, SQLException> {
        @SuppressWarnings("rawtypes")
        public static final TriParametersSetter DO_NOTHING = new TriParametersSetter<Object, Object>() {
            @Override
            public void accept(ParsedSql parsedSql, Object preparedQuery, Object param) throws SQLException {
                // Do nothing.
            }
        };

        /**
         *
         * @param parsedSql
         * @param preparedQuery
         * @param param
         * @throws SQLException the SQL exception
         */
        @Override
        void accept(ParsedSql parsedSql, QS preparedQuery, T param) throws SQLException;
    }

    /**
     * The Interface ResultExtractor.
     *
     * @param <T>
     */
    public interface ResultExtractor<T> extends Throwables.Function<ResultSet, T, SQLException> {

        /** The Constant TO_DATA_SET. */
        ResultExtractor<DataSet> TO_DATA_SET = new ResultExtractor<DataSet>() {
            @Override
            public DataSet apply(final ResultSet rs) throws SQLException {
                return JdbcUtil.extractData(rs);
            }
        };

        /**
         *
         * @param rs
         * @return
         * @throws SQLException the SQL exception
         */
        @Override
        T apply(ResultSet rs) throws SQLException;

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
                public M apply(final ResultSet rs) throws SQLException {
                    final M result = supplier.get();

                    while (rs.next()) {
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
                public M apply(final ResultSet rs) throws SQLException {

                    final Supplier<A> downstreamSupplier = downstream.supplier();
                    final BiConsumer<A, ? super V> downstreamAccumulator = downstream.accumulator();
                    final Function<A, D> downstreamFinisher = downstream.finisher();

                    final M result = supplier.get();
                    final Map<K, A> tmp = (Map<K, A>) result;
                    K key = null;
                    A container = null;

                    while (rs.next()) {
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
                public M apply(final ResultSet rs) throws SQLException {
                    final M result = multimapSupplier.get();

                    while (rs.next()) {
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
         * @throws SQLException the SQL exception
         */
        static <K, V> ResultExtractor<Map<K, List<V>>> groupTo(final RowMapper<K> keyExtractor, final RowMapper<V> valueExtractor) throws SQLException {
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
                public M apply(final ResultSet rs) throws SQLException {

                    final M result = supplier.get();
                    K key = null;
                    List<V> value = null;

                    while (rs.next()) {
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

        static ResultExtractor<DataSet> toDataSet(final RowFilter rowFilter) {
            return new ResultExtractor<DataSet>() {
                @Override
                public DataSet apply(final ResultSet rs) throws SQLException {
                    return JdbcUtil.extractData(rs, 0, Integer.MAX_VALUE, rowFilter, false);
                }
            };
        }

        static ResultExtractor<DataSet> toDataSet(final RowExtractor rowExtractor) {
            return new ResultExtractor<DataSet>() {
                @Override
                public DataSet apply(final ResultSet rs) throws SQLException {
                    return JdbcUtil.extractData(rs, 0, Integer.MAX_VALUE, rowExtractor, false);
                }
            };
        }

        static ResultExtractor<DataSet> toDataSet(final RowFilter rowFilter, final RowExtractor rowExtractor) {
            return new ResultExtractor<DataSet>() {
                @Override
                public DataSet apply(final ResultSet rs) throws SQLException {
                    return JdbcUtil.extractData(rs, 0, Integer.MAX_VALUE, rowFilter, rowExtractor, false);
                }
            };
        }

        static <R> ResultExtractor<R> to(final Throwables.Function<DataSet, R, SQLException> finisher) {
            return rs -> finisher.apply(TO_DATA_SET.apply(rs));
        }
    }

    /**
     * The Interface BiResultExtractor.
     *
     * @param <T>
     */
    public interface BiResultExtractor<T> extends Throwables.BiFunction<ResultSet, List<String>, T, SQLException> {

        /**
         *
         * @param rs
         * @param columnLabels
         * @return
         * @throws SQLException the SQL exception
         */
        @Override
        T apply(ResultSet rs, List<String> columnLabels) throws SQLException;

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor
         * @param valueExtractor
         * @return
         */
        static <K, V> BiResultExtractor<Map<K, V>> toMap(final BiRowMapper<K> keyExtractor, final BiRowMapper<V> valueExtractor) {
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
        static <K, V, M extends Map<K, V>> BiResultExtractor<M> toMap(final BiRowMapper<K> keyExtractor, final BiRowMapper<V> valueExtractor,
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
        static <K, V> BiResultExtractor<Map<K, V>> toMap(final BiRowMapper<K> keyExtractor, final BiRowMapper<V> valueExtractor,
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
        static <K, V, M extends Map<K, V>> BiResultExtractor<M> toMap(final BiRowMapper<K> keyExtractor, final BiRowMapper<V> valueExtractor,
                final BinaryOperator<V> mergeFunction, final Supplier<? extends M> supplier) {
            N.checkArgNotNull(keyExtractor, "keyExtractor");
            N.checkArgNotNull(valueExtractor, "valueExtractor");
            N.checkArgNotNull(mergeFunction, "mergeFunction");
            N.checkArgNotNull(supplier, "supplier");

            return new BiResultExtractor<M>() {
                @Override
                public M apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                    final M result = supplier.get();

                    while (rs.next()) {
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
        static <K, V, A, D> BiResultExtractor<Map<K, D>> toMap(final BiRowMapper<K> keyExtractor, final BiRowMapper<V> valueExtractor,
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
        static <K, V, A, D, M extends Map<K, D>> BiResultExtractor<M> toMap(final BiRowMapper<K> keyExtractor, final BiRowMapper<V> valueExtractor,
                final Collector<? super V, A, D> downstream, final Supplier<? extends M> supplier) {
            N.checkArgNotNull(keyExtractor, "keyExtractor");
            N.checkArgNotNull(valueExtractor, "valueExtractor");
            N.checkArgNotNull(downstream, "downstream");
            N.checkArgNotNull(supplier, "supplier");

            return new BiResultExtractor<M>() {
                @Override
                public M apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {

                    final Supplier<A> downstreamSupplier = downstream.supplier();
                    final BiConsumer<A, ? super V> downstreamAccumulator = downstream.accumulator();
                    final Function<A, D> downstreamFinisher = downstream.finisher();

                    final M result = supplier.get();
                    final Map<K, A> tmp = (Map<K, A>) result;
                    K key = null;
                    A container = null;

                    while (rs.next()) {
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
        static <K, V> BiResultExtractor<ListMultimap<K, V>> toMultimap(final BiRowMapper<K> keyExtractor, final BiRowMapper<V> valueExtractor) {
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
        static <K, V, C extends Collection<V>, M extends Multimap<K, V, C>> BiResultExtractor<M> toMultimap(final BiRowMapper<K> keyExtractor,
                final BiRowMapper<V> valueExtractor, final Supplier<? extends M> multimapSupplier) {
            N.checkArgNotNull(keyExtractor, "keyExtractor");
            N.checkArgNotNull(valueExtractor, "valueExtractor");
            N.checkArgNotNull(multimapSupplier, "multimapSupplier");

            return new BiResultExtractor<M>() {
                @Override
                public M apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                    final M result = multimapSupplier.get();

                    while (rs.next()) {
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
         * @throws SQLException the SQL exception
         */
        static <K, V> BiResultExtractor<Map<K, List<V>>> groupTo(final BiRowMapper<K> keyExtractor, final BiRowMapper<V> valueExtractor) throws SQLException {
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
        static <K, V, M extends Map<K, List<V>>> BiResultExtractor<M> groupTo(final BiRowMapper<K> keyExtractor, final BiRowMapper<V> valueExtractor,
                final Supplier<? extends M> supplier) {
            N.checkArgNotNull(keyExtractor, "keyExtractor");
            N.checkArgNotNull(valueExtractor, "valueExtractor");
            N.checkArgNotNull(supplier, "supplier");

            return new BiResultExtractor<M>() {
                @Override
                public M apply(final ResultSet rs, List<String> columnLabels) throws SQLException {
                    final M result = supplier.get();
                    K key = null;
                    List<V> value = null;

                    while (rs.next()) {
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
    }

    /** The Constant NO_GENERATED_KEY_EXTRACTOR. */
    static final RowMapper<Object> NO_GENERATED_KEY_EXTRACTOR = rs -> null;

    /** The Constant SINGLE_GENERATED_KEY_EXTRACTOR. */
    static final RowMapper<Object> SINGLE_GENERATED_KEY_EXTRACTOR = rs -> getColumnValue(rs, 1);

    /** The Constant MULTI_GENERATED_KEY_EXTRACTOR. */
    @SuppressWarnings("deprecation")
    static final RowMapper<Object> MULTI_GENERATED_KEY_EXTRACTOR = rs -> {
        final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

        if (columnLabels.size() == 1) {
            return getColumnValue(rs, 1);
        } else {
            final int columnCount = columnLabels.size();
            final Seid id = Seid.of(N.EMPTY_STRING);

            for (int i = 1; i <= columnCount; i++) {
                id.set(columnLabels.get(i - 1), getColumnValue(rs, i));
            }

            return id;
        }
    };

    /** The Constant NO_BI_GENERATED_KEY_EXTRACTOR. */
    static final BiRowMapper<Object> NO_BI_GENERATED_KEY_EXTRACTOR = (rs, columnLabels) -> null;

    /** The Constant SINGLE_BI_GENERATED_KEY_EXTRACTOR. */
    static final BiRowMapper<Object> SINGLE_BI_GENERATED_KEY_EXTRACTOR = (rs, columnLabels) -> getColumnValue(rs, 1);

    /** The Constant MULTI_BI_GENERATED_KEY_EXTRACTOR. */
    @SuppressWarnings("deprecation")
    static final BiRowMapper<Object> MULTI_BI_GENERATED_KEY_EXTRACTOR = (rs, columnLabels) -> {
        if (columnLabels.size() == 1) {
            return getColumnValue(rs, 1);
        } else {
            final int columnCount = columnLabels.size();
            final Seid id = Seid.of(N.EMPTY_STRING);

            for (int i = 1; i <= columnCount; i++) {
                id.set(columnLabels.get(i - 1), getColumnValue(rs, i));
            }

            return id;
        }
    };

    @SuppressWarnings("rawtypes")
    private static final Map<Type<?>, RowMapper> singleGetRowMapperPool = new ObjectPool<>(1024);

    /**
     * Don't use {@code RowMapper} in {@link PreparedQuery#list(RowMapper)} or any place where multiple records will be retrieved by it, if column labels/count are used in {@link RowMapper#apply(ResultSet)}.
     * Consider using {@code BiRowMapper} instead because it's more efficient to retrieve multiple records when column labels/count are used.
     *
     * @param <T>
     */
    public interface RowMapper<T> extends Throwables.Function<ResultSet, T, SQLException> {

        /** The Constant GET_BOOLEAN. */
        RowMapper<Boolean> GET_BOOLEAN = new RowMapper<Boolean>() {
            @Override
            public Boolean apply(final ResultSet rs) throws SQLException {
                return rs.getBoolean(1);
            }
        };

        /** The Constant GET_BYTE. */
        RowMapper<Byte> GET_BYTE = new RowMapper<Byte>() {
            @Override
            public Byte apply(final ResultSet rs) throws SQLException {
                return rs.getByte(1);
            }
        };

        /** The Constant GET_SHORT. */
        RowMapper<Short> GET_SHORT = new RowMapper<Short>() {
            @Override
            public Short apply(final ResultSet rs) throws SQLException {
                return rs.getShort(1);
            }
        };

        /** The Constant GET_INT. */
        RowMapper<Integer> GET_INT = new RowMapper<Integer>() {
            @Override
            public Integer apply(final ResultSet rs) throws SQLException {
                return rs.getInt(1);
            }
        };

        /** The Constant GET_LONG. */
        RowMapper<Long> GET_LONG = new RowMapper<Long>() {
            @Override
            public Long apply(final ResultSet rs) throws SQLException {
                return rs.getLong(1);
            }
        };

        /** The Constant GET_FLOAT. */
        RowMapper<Float> GET_FLOAT = new RowMapper<Float>() {
            @Override
            public Float apply(final ResultSet rs) throws SQLException {
                return rs.getFloat(1);
            }
        };

        /** The Constant GET_DOUBLE. */
        RowMapper<Double> GET_DOUBLE = new RowMapper<Double>() {
            @Override
            public Double apply(final ResultSet rs) throws SQLException {
                return rs.getDouble(1);
            }
        };

        /** The Constant GET_BIG_DECIMAL. */
        RowMapper<BigDecimal> GET_BIG_DECIMAL = new RowMapper<BigDecimal>() {
            @Override
            public BigDecimal apply(final ResultSet rs) throws SQLException {
                return rs.getBigDecimal(1);
            }
        };

        /** The Constant GET_STRING. */
        RowMapper<String> GET_STRING = new RowMapper<String>() {
            @Override
            public String apply(final ResultSet rs) throws SQLException {
                return rs.getString(1);
            }
        };

        /** The Constant GET_DATE. */
        RowMapper<Date> GET_DATE = new RowMapper<Date>() {
            @Override
            public Date apply(final ResultSet rs) throws SQLException {
                return rs.getDate(1);
            }
        };

        /** The Constant GET_TIME. */
        RowMapper<Time> GET_TIME = new RowMapper<Time>() {
            @Override
            public Time apply(final ResultSet rs) throws SQLException {
                return rs.getTime(1);
            }
        };

        /** The Constant GET_TIMESTAMP. */
        RowMapper<Timestamp> GET_TIMESTAMP = new RowMapper<Timestamp>() {
            @Override
            public Timestamp apply(final ResultSet rs) throws SQLException {
                return rs.getTimestamp(1);
            }
        };

        /** The Constant GET_OBJECT. */
        @SuppressWarnings("rawtypes")
        RowMapper GET_OBJECT = new RowMapper<Object>() {
            @Override
            public Object apply(final ResultSet rs) throws SQLException {
                return rs.getObject(1);
            }
        };

        /**
         *
         * @param rs
         * @return generally should not return {@code null}.
         * @throws SQLException the SQL exception
         */
        @Override
        T apply(ResultSet rs) throws SQLException;

        /**
         * Gets the values from the first column.
         *
         * @param <T>
         * @param firstColumnType
         * @return
         */
        static <T> RowMapper<T> get(final Class<? extends T> firstColumnType) {
            return get(N.typeOf(firstColumnType));
        }

        /**
         * Gets the values from the first column.
         *
         * @param <T>
         * @param firstColumnType
         * @return
         */
        static <T> RowMapper<T> get(final Type<? extends T> firstColumnType) {
            RowMapper<T> result = singleGetRowMapperPool.get(firstColumnType);

            if (result == null) {
                result = new RowMapper<T>() {
                    @Override
                    public T apply(final ResultSet rs) throws SQLException {
                        return firstColumnType.get(rs, 1);
                    }
                };

                singleGetRowMapperPool.put(firstColumnType, result);
            }

            return result;
        }

        static RowMapperBuilder builder() {
            return builder(ColumnGetter.GET_OBJECT);
        }

        static RowMapperBuilder builder(final ColumnGetter<?> defaultColumnGetter) {
            return new RowMapperBuilder(defaultColumnGetter);
        }

        //    static RowMapperBuilder builder(final int columnCount) {
        //        return new RowMapperBuilder(columnCount);
        //    }

        public static class RowMapperBuilder {
            private final Map<Integer, ColumnGetter<?>> columnGetterMap;

            RowMapperBuilder(final ColumnGetter<?> defaultColumnGetter) {
                N.checkArgNotNull(defaultColumnGetter, "defaultColumnGetter");

                columnGetterMap = new HashMap<>(9);
                columnGetterMap.put(0, defaultColumnGetter);
            }

            public RowMapperBuilder getBoolean(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_BOOLEAN);
            }

            public RowMapperBuilder getByte(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_BYTE);
            }

            public RowMapperBuilder getShort(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_SHORT);
            }

            public RowMapperBuilder getInt(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_INT);
            }

            public RowMapperBuilder getLong(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_LONG);
            }

            public RowMapperBuilder getFloat(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_FLOAT);
            }

            public RowMapperBuilder getDouble(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_DOUBLE);
            }

            public RowMapperBuilder getBigDecimal(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_BIG_DECIMAL);
            }

            public RowMapperBuilder getString(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_STRING);
            }

            public RowMapperBuilder getDate(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_DATE);
            }

            public RowMapperBuilder getTime(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_TIME);
            }

            public RowMapperBuilder getTimestamp(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_TIMESTAMP);
            }

            public RowMapperBuilder get(final int columnIndex, final ColumnGetter<?> columnGetter) {
                N.checkArgPositive(columnIndex, "columnIndex");
                N.checkArgNotNull(columnGetter, "columnGetter");

                //        if (columnGetters == null) {
                //            columnGetterMap.put(columnIndex, columnGetter);
                //        } else {
                //            columnGetters[columnIndex] = columnGetter;
                //        }

                columnGetterMap.put(columnIndex, columnGetter);
                return this;
            }

            /**
             *
             * Set column getter function for column[columnIndex].
             *
             * @param columnIndex start from 1.
             * @param columnGetter
             * @return
             * @deprecated replaced by {@link #get(int, ColumnGetter)}
             */
            @Deprecated
            public RowMapperBuilder column(final int columnIndex, final ColumnGetter<?> columnGetter) {
                return get(columnIndex, columnGetter);
            }

            //    /**
            //     * Set default column getter function.
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder __(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(0, columnGetter);
            //        } else {
            //            columnGetters[0] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     *
            //     * Set column getter function for column[columnIndex].
            //     *
            //     * @param columnIndex start from 1.
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder __(final int columnIndex, final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(columnIndex, columnGetter);
            //        } else {
            //            columnGetters[columnIndex] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     * Set column getter function for column[1].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder _1(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(1, columnGetter);
            //        } else {
            //            columnGetters[1] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     *
            //     * Set column getter function for column[1].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder _2(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(2, columnGetter);
            //        } else {
            //            columnGetters[2] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     *
            //     * Set column getter function for column[3].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder _3(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(3, columnGetter);
            //        } else {
            //            columnGetters[3] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     *
            //     * Set column getter function for column[4].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder _4(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(4, columnGetter);
            //        } else {
            //            columnGetters[4] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     *
            //     * Set column getter function for column[5].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder _5(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(5, columnGetter);
            //        } else {
            //            columnGetters[5] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     *
            //     * Set column getter function for column[6].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder _6(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(6, columnGetter);
            //        } else {
            //            columnGetters[6] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     *
            //     * Set column getter function for column[7].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder _7(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(7, columnGetter);
            //        } else {
            //            columnGetters[7] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     *
            //     * Set column getter function for column[8].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder _8(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(8, columnGetter);
            //        } else {
            //            columnGetters[8] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     *
            //     * Set column getter function for column[9].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder _9(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(9, columnGetter);
            //        } else {
            //            columnGetters[9] = columnGetter;
            //        }
            //
            //        return this;
            //    }

            //    void setDefaultColumnGetter() {
            //        if (columnGetters != null) {
            //            for (int i = 1, len = columnGetters.length; i < len; i++) {
            //                if (columnGetters[i] == null) {
            //                    columnGetters[i] = columnGetters[0];
            //                }
            //            }
            //        }
            //    }

            ColumnGetter<?>[] initColumnGetter(ResultSet rs) throws SQLException {
                return initColumnGetter(rs.getMetaData().getColumnCount());
            }

            ColumnGetter<?>[] initColumnGetter(final int columnCount) throws SQLException {
                final ColumnGetter<?>[] rsColumnGetters = new ColumnGetter<?>[columnCount + 1];
                rsColumnGetters[0] = columnGetterMap.get(0);

                for (int i = 1, len = rsColumnGetters.length; i < len; i++) {
                    rsColumnGetters[i] = columnGetterMap.getOrDefault(i, rsColumnGetters[0]);
                }

                return rsColumnGetters;
            }

            /**
             * Don't cache or reuse the returned {@code RowMapper} instance.
             *
             * @return
             */
            public RowMapper<Object[]> toArray() {
                // setDefaultColumnGetter();

                return new RowMapper<Object[]>() {
                    private volatile int rsColumnCount = -1;
                    private volatile ColumnGetter<?>[] rsColumnGetters = null;

                    @Override
                    public Object[] apply(ResultSet rs) throws SQLException {
                        ColumnGetter<?>[] rsColumnGetters = this.rsColumnGetters;

                        if (rsColumnGetters == null) {
                            rsColumnGetters = initColumnGetter(rs);
                            rsColumnCount = rsColumnGetters.length - 1;
                            this.rsColumnGetters = rsColumnGetters;
                        }

                        final Object[] row = new Object[rsColumnCount];

                        for (int i = 0; i < rsColumnCount;) {
                            row[i] = rsColumnGetters[++i].apply(i, rs);
                        }

                        return row;
                    }
                };
            }

            /**
             * Don't cache or reuse the returned {@code RowMapper} instance.
             *
             * @return
             */
            public RowMapper<List<Object>> toList() {
                // setDefaultColumnGetter();

                return new RowMapper<List<Object>>() {
                    private volatile int rsColumnCount = -1;
                    private volatile ColumnGetter<?>[] rsColumnGetters = null;

                    @Override
                    public List<Object> apply(ResultSet rs) throws SQLException {
                        ColumnGetter<?>[] rsColumnGetters = this.rsColumnGetters;

                        if (rsColumnGetters == null) {
                            rsColumnGetters = initColumnGetter(rs);
                            rsColumnCount = rsColumnGetters.length - 1;
                            this.rsColumnGetters = rsColumnGetters;
                        }

                        final List<Object> row = new ArrayList<>(rsColumnCount);

                        for (int i = 0; i < rsColumnCount;) {
                            row.add(rsColumnGetters[++i].apply(i, rs));
                        }

                        return row;
                    }
                };
            }

            public <R> RowMapper<R> to(final Throwables.Function<DisposableObjArray, R, SQLException> finisher) {
                return new RowMapper<R>() {
                    private volatile int rsColumnCount = -1;
                    private volatile ColumnGetter<?>[] rsColumnGetters = null;
                    private Object[] outputRow = null;
                    private DisposableObjArray output;

                    @Override
                    public R apply(ResultSet rs) throws SQLException {
                        ColumnGetter<?>[] rsColumnGetters = this.rsColumnGetters;

                        if (rsColumnGetters == null) {
                            rsColumnGetters = initColumnGetter(rs);
                            this.rsColumnCount = rsColumnGetters.length - 1;
                            this.rsColumnGetters = rsColumnGetters;
                            this.outputRow = new Object[rsColumnCount];
                            this.output = DisposableObjArray.wrap(outputRow);
                        }

                        for (int i = 0; i < rsColumnCount;) {
                            outputRow[i] = rsColumnGetters[++i].apply(i, rs);
                        }

                        return finisher.apply(output);
                    }
                };
            }
        }
    }

    /**
     * The Interface BiRowMapper.
     *
     * @param <T>
     */
    public interface BiRowMapper<T> extends Throwables.BiFunction<ResultSet, List<String>, T, SQLException> {

        /** The Constant GET_BOOLEAN. */
        BiRowMapper<Boolean> GET_BOOLEAN = new BiRowMapper<Boolean>() {
            @Override
            public Boolean apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                return rs.getBoolean(1);
            }
        };

        /** The Constant GET_BYTE. */
        BiRowMapper<Byte> GET_BYTE = new BiRowMapper<Byte>() {
            @Override
            public Byte apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                return rs.getByte(1);
            }
        };

        /** The Constant GET_SHORT. */
        BiRowMapper<Short> GET_SHORT = new BiRowMapper<Short>() {
            @Override
            public Short apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                return rs.getShort(1);
            }
        };

        /** The Constant GET_INT. */
        BiRowMapper<Integer> GET_INT = new BiRowMapper<Integer>() {
            @Override
            public Integer apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                return rs.getInt(1);
            }
        };

        /** The Constant GET_LONG. */
        BiRowMapper<Long> GET_LONG = new BiRowMapper<Long>() {
            @Override
            public Long apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                return rs.getLong(1);
            }
        };

        /** The Constant GET_FLOAT. */
        BiRowMapper<Float> GET_FLOAT = new BiRowMapper<Float>() {
            @Override
            public Float apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                return rs.getFloat(1);
            }
        };

        /** The Constant GET_DOUBLE. */
        BiRowMapper<Double> GET_DOUBLE = new BiRowMapper<Double>() {
            @Override
            public Double apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                return rs.getDouble(1);
            }
        };

        /** The Constant GET_BIG_DECIMAL. */
        BiRowMapper<BigDecimal> GET_BIG_DECIMAL = new BiRowMapper<BigDecimal>() {
            @Override
            public BigDecimal apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                return rs.getBigDecimal(1);
            }
        };

        /** The Constant GET_STRING. */
        BiRowMapper<String> GET_STRING = new BiRowMapper<String>() {
            @Override
            public String apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                return rs.getString(1);
            }
        };

        /** The Constant GET_DATE. */
        BiRowMapper<Date> GET_DATE = new BiRowMapper<Date>() {
            @Override
            public Date apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                return rs.getDate(1);
            }
        };

        /** The Constant GET_TIME. */
        BiRowMapper<Time> GET_TIME = new BiRowMapper<Time>() {
            @Override
            public Time apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                return rs.getTime(1);
            }
        };

        /** The Constant GET_TIMESTAMP. */
        BiRowMapper<Timestamp> GET_TIMESTAMP = new BiRowMapper<Timestamp>() {
            @Override
            public Timestamp apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                return rs.getTimestamp(1);
            }
        };

        /** The Constant TO_ARRAY. */
        BiRowMapper<Object[]> TO_ARRAY = new BiRowMapper<Object[]>() {
            @Override
            public Object[] apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                final int columnCount = columnLabels.size();
                final Object[] result = new Object[columnCount];

                for (int i = 1; i <= columnCount; i++) {
                    result[i - 1] = JdbcUtil.getColumnValue(rs, i);
                }

                return result;
            }
        };

        /** The Constant TO_LIST. */
        BiRowMapper<List<Object>> TO_LIST = new BiRowMapper<List<Object>>() {
            @Override
            public List<Object> apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                final int columnCount = columnLabels.size();
                final List<Object> result = new ArrayList<>(columnCount);

                for (int i = 1; i <= columnCount; i++) {
                    result.add(JdbcUtil.getColumnValue(rs, i));
                }

                return result;
            }
        };

        /** The Constant TO_MAP. */
        BiRowMapper<Map<String, Object>> TO_MAP = new BiRowMapper<Map<String, Object>>() {
            @Override
            public Map<String, Object> apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                final int columnCount = columnLabels.size();
                final Map<String, Object> result = new HashMap<>(columnCount);

                for (int i = 1; i <= columnCount; i++) {
                    result.put(columnLabels.get(i - 1), JdbcUtil.getColumnValue(rs, i));
                }

                return result;
            }
        };

        /** The Constant TO_LINKED_HASH_MAP. */
        BiRowMapper<Map<String, Object>> TO_LINKED_HASH_MAP = new BiRowMapper<Map<String, Object>>() {
            @Override
            public Map<String, Object> apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                final int columnCount = columnLabels.size();
                final Map<String, Object> result = new LinkedHashMap<>(columnCount);

                for (int i = 1; i <= columnCount; i++) {
                    result.put(columnLabels.get(i - 1), JdbcUtil.getColumnValue(rs, i));
                }

                return result;
            }
        };

        BiRowMapper<EntityId> TO_ENTITY_ID = new BiRowMapper<EntityId>() {
            @SuppressWarnings("deprecation")
            @Override
            public EntityId apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                final int columnCount = columnLabels.size();
                final Seid entityId = Seid.of(N.EMPTY_STRING);

                for (int i = 1; i <= columnCount; i++) {
                    entityId.set(columnLabels.get(i - 1), JdbcUtil.getColumnValue(rs, i));
                }

                return entityId;
            }
        };

        /**
         *
         * @param rs
         * @param columnLabels
         * @return generally should not return {@code null}.
         * @throws SQLException the SQL exception
         */
        @Override
        T apply(ResultSet rs, List<String> columnLabels) throws SQLException;

        /**
         * Don't cache or reuse the returned {@code BiRowMapper} instance. It's stateful.
         *
         * @param <T>
         * @param targetClass
         * @return
         */
        @SequentialOnly
        @Stateful
        static <T> BiRowMapper<T> to(Class<? extends T> targetClass) {
            return to(targetClass, false);
        }

        /**
         * Don't cache or reuse the returned {@code BiRowMapper} instance. It's stateful.
         *
         * @param <T>
         * @param targetClass
         * @param ignoreNonMatchedColumns
         * @return
         */
        @SequentialOnly
        @Stateful
        static <T> BiRowMapper<T> to(Class<? extends T> targetClass, final boolean ignoreNonMatchedColumns) {
            return new BiRowMapper<T>() {
                @SuppressWarnings("deprecation")
                private final Throwables.BiFunction<ResultSet, List<String>, T, SQLException> mapper = InternalUtil.to(targetClass, ignoreNonMatchedColumns);

                @Override
                public T apply(ResultSet rs, List<String> columnLabelList) throws SQLException {
                    return mapper.apply(rs, columnLabelList);
                }
            };
        }

        static BiRowMapperBuilder builder() {
            return builder(ColumnGetter.GET_OBJECT);
        }

        static BiRowMapperBuilder builder(final ColumnGetter<?> defaultColumnGetter) {
            return new BiRowMapperBuilder(defaultColumnGetter);
        }

        //    static BiRowMapperBuilder builder(final int columnCount) {
        //        return new BiRowMapperBuilder(columnCount);
        //    }

        public static class BiRowMapperBuilder {
            private final ColumnGetter<?> defaultColumnGetter;
            private final Map<String, ColumnGetter<?>> columnGetterMap;

            BiRowMapperBuilder(final ColumnGetter<?> defaultColumnGetter) {
                this.defaultColumnGetter = defaultColumnGetter;

                columnGetterMap = new HashMap<>(9);
            }

            public BiRowMapperBuilder getBoolean(final String columnName) {
                return get(columnName, ColumnGetter.GET_BOOLEAN);
            }

            public BiRowMapperBuilder getByte(final String columnName) {
                return get(columnName, ColumnGetter.GET_BYTE);
            }

            public BiRowMapperBuilder getShort(final String columnName) {
                return get(columnName, ColumnGetter.GET_SHORT);
            }

            public BiRowMapperBuilder getInt(final String columnName) {
                return get(columnName, ColumnGetter.GET_INT);
            }

            public BiRowMapperBuilder getLong(final String columnName) {
                return get(columnName, ColumnGetter.GET_LONG);
            }

            public BiRowMapperBuilder getFloat(final String columnName) {
                return get(columnName, ColumnGetter.GET_FLOAT);
            }

            public BiRowMapperBuilder getDouble(final String columnName) {
                return get(columnName, ColumnGetter.GET_DOUBLE);
            }

            public BiRowMapperBuilder getBigDecimal(final String columnName) {
                return get(columnName, ColumnGetter.GET_BIG_DECIMAL);
            }

            public BiRowMapperBuilder getString(final String columnName) {
                return get(columnName, ColumnGetter.GET_STRING);
            }

            public BiRowMapperBuilder getDate(final String columnName) {
                return get(columnName, ColumnGetter.GET_DATE);
            }

            public BiRowMapperBuilder getTime(final String columnName) {
                return get(columnName, ColumnGetter.GET_TIME);
            }

            public BiRowMapperBuilder getTimestamp(final String columnName) {
                return get(columnName, ColumnGetter.GET_TIMESTAMP);
            }

            public BiRowMapperBuilder get(final String columnName, final ColumnGetter<?> columnGetter) {
                N.checkArgNotNull(columnName, "columnName");
                N.checkArgNotNull(columnGetter, "columnGetter");

                columnGetterMap.put(columnName, columnGetter);

                return this;
            }

            /**
             *
             * @param columnName
             * @param columnGetter
             * @return
             * @deprecated replaced by {@link #get(String, ColumnGetter)}
             */
            @Deprecated
            public BiRowMapperBuilder column(final String columnName, final ColumnGetter<?> columnGetter) {
                return get(columnName, columnGetter);
            }

            //    /**
            //     * Set default column getter function.
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public BiRowMapperBuilder __(final ColumnGetter<?> columnGetter) {
            //        defaultColumnGetter = columnGetter;
            //
            //        return this;
            //    }
            //
            //    /**
            //     * Set column getter function for column[columnName].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public BiRowMapperBuilder __(final String columnName, final ColumnGetter<?> columnGetter) {
            //        columnGetterMap.put(columnName, columnGetter);
            //
            //        return this;
            //    }

            ColumnGetter<?>[] initColumnGetter(final List<String> columnLabelList) {
                final int rsColumnCount = columnLabelList.size();
                final ColumnGetter<?>[] rsColumnGetters = new ColumnGetter<?>[rsColumnCount + 1];
                rsColumnGetters[0] = defaultColumnGetter;

                int cnt = 0;
                ColumnGetter<?> columnGetter = null;

                for (int i = 0; i < rsColumnCount; i++) {
                    columnGetter = columnGetterMap.get(columnLabelList.get(i));

                    if (columnGetter != null) {
                        cnt++;
                    }

                    rsColumnGetters[i + 1] = columnGetter == null ? defaultColumnGetter : columnGetter;
                }

                if (cnt < columnGetterMap.size()) {
                    final List<String> tmp = new ArrayList<>(columnGetterMap.keySet());
                    tmp.removeAll(columnLabelList);
                    throw new IllegalArgumentException("ColumnGetters for " + tmp + " are not found in ResultSet columns: " + columnLabelList);
                }

                return rsColumnGetters;
            }

            public <T> BiRowMapper<T> to(final Class<? extends T> targetClass) {
                return to(targetClass, false);
            }

            public <T> BiRowMapper<T> to(final Class<? extends T> targetClass, final boolean ignoreNonMatchedColumns) {
                if (Object[].class.isAssignableFrom(targetClass)) {
                    return new BiRowMapper<T>() {
                        private volatile int rsColumnCount = -1;
                        private volatile ColumnGetter<?>[] rsColumnGetters = null;

                        @Override
                        public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                            ColumnGetter<?>[] rsColumnGetters = this.rsColumnGetters;

                            if (rsColumnGetters == null) {
                                rsColumnCount = columnLabelList.size();
                                rsColumnGetters = initColumnGetter(columnLabelList);
                                this.rsColumnGetters = rsColumnGetters;
                            }

                            final Object[] a = Array.newInstance(targetClass.getComponentType(), rsColumnCount);

                            for (int i = 0; i < rsColumnCount;) {
                                a[i] = rsColumnGetters[++i].apply(i, rs);
                            }

                            return (T) a;
                        }
                    };
                } else if (List.class.isAssignableFrom(targetClass)) {
                    return new BiRowMapper<T>() {
                        private final boolean isListOrArrayList = targetClass.equals(List.class) || targetClass.equals(ArrayList.class);

                        private volatile int rsColumnCount = -1;
                        private volatile ColumnGetter<?>[] rsColumnGetters = null;

                        @Override
                        public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                            ColumnGetter<?>[] rsColumnGetters = this.rsColumnGetters;

                            if (rsColumnGetters == null) {
                                rsColumnCount = columnLabelList.size();
                                rsColumnGetters = initColumnGetter(columnLabelList);
                                this.rsColumnGetters = rsColumnGetters;
                            }

                            final List<Object> c = isListOrArrayList ? new ArrayList<>(rsColumnCount) : (List<Object>) N.newInstance(targetClass);

                            for (int i = 0; i < rsColumnCount;) {
                                c.add(rsColumnGetters[++i].apply(i, rs));
                            }

                            return (T) c;
                        }
                    };
                } else if (Map.class.isAssignableFrom(targetClass)) {
                    return new BiRowMapper<T>() {
                        private final boolean isMapOrHashMap = targetClass.equals(Map.class) || targetClass.equals(HashMap.class);
                        private final boolean isLinkedHashMap = targetClass.equals(LinkedHashMap.class);

                        private volatile int rsColumnCount = -1;
                        private volatile ColumnGetter<?>[] rsColumnGetters = null;
                        private String[] columnLabels = null;

                        @Override
                        public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                            ColumnGetter<?>[] rsColumnGetters = this.rsColumnGetters;

                            if (rsColumnGetters == null) {
                                rsColumnCount = columnLabelList.size();
                                rsColumnGetters = initColumnGetter(columnLabelList);
                                this.rsColumnGetters = rsColumnGetters;

                                columnLabels = columnLabelList.toArray(new String[rsColumnCount]);
                            }

                            final Map<String, Object> m = isMapOrHashMap ? new HashMap<>(rsColumnCount)
                                    : (isLinkedHashMap ? new LinkedHashMap<>(rsColumnCount) : (Map<String, Object>) N.newInstance(targetClass));

                            for (int i = 0; i < rsColumnCount;) {
                                m.put(columnLabels[i], rsColumnGetters[++i].apply(i, rs));
                            }

                            return (T) m;
                        }
                    };
                } else if (ClassUtil.isEntity(targetClass)) {
                    return new BiRowMapper<T>() {
                        private final boolean isDirtyMarker = DirtyMarkerUtil.isDirtyMarker(targetClass);
                        private final EntityInfo entityInfo = ParserUtil.getEntityInfo(targetClass);

                        private volatile int rsColumnCount = -1;
                        private volatile ColumnGetter<?>[] rsColumnGetters = null;
                        private volatile String[] columnLabels = null;
                        private volatile PropInfo[] propInfos;

                        @Override
                        public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                            ColumnGetter<?>[] rsColumnGetters = this.rsColumnGetters;

                            if (rsColumnGetters == null) {
                                rsColumnCount = columnLabelList.size();
                                rsColumnGetters = initColumnGetter(columnLabelList);
                                this.rsColumnGetters = rsColumnGetters;

                                columnLabels = columnLabelList.toArray(new String[rsColumnCount]);
                                final PropInfo[] propInfos = new PropInfo[rsColumnCount];

                                @SuppressWarnings("deprecation")
                                final Map<String, String> column2FieldNameMap = InternalUtil.getColumn2FieldNameMap(targetClass);

                                for (int i = 0; i < rsColumnCount; i++) {
                                    propInfos[i] = entityInfo.getPropInfo(columnLabels[i]);

                                    if (propInfos[i] == null) {
                                        String fieldName = column2FieldNameMap.get(columnLabels[i]);

                                        if (N.isNullOrEmpty(fieldName)) {
                                            fieldName = column2FieldNameMap.get(columnLabels[i].toLowerCase());
                                        }

                                        if (N.notNullOrEmpty(fieldName)) {
                                            propInfos[i] = entityInfo.getPropInfo(fieldName);
                                        }
                                    }

                                    if (propInfos[i] == null) {
                                        if (ignoreNonMatchedColumns) {
                                            columnLabels[i] = null;
                                        } else {
                                            throw new IllegalArgumentException("No property in class: " + ClassUtil.getCanonicalClassName(targetClass)
                                                    + " mapping to column: " + columnLabels[i]);
                                        }
                                    } else {
                                        if (rsColumnGetters[i + 1] == ColumnGetter.GET_OBJECT) {
                                            rsColumnGetters[i + 1] = ColumnGetter.get(entityInfo.getPropInfo(columnLabels[i]).dbType);
                                        }
                                    }
                                }

                                this.propInfos = propInfos;
                            }

                            final Object entity = N.newInstance(targetClass);

                            for (int i = 0; i < rsColumnCount;) {
                                if (columnLabels[i] == null) {
                                    continue;
                                }

                                propInfos[i].setPropValue(entity, rsColumnGetters[++i].apply(i, rs));
                            }

                            if (isDirtyMarker) {
                                DirtyMarkerUtil.markDirty((DirtyMarker) entity, false);
                            }

                            return (T) entity;
                        }
                    };
                } else {
                    return new BiRowMapper<T>() {
                        private volatile int rsColumnCount = -1;
                        private volatile ColumnGetter<?>[] rsColumnGetters = null;

                        @Override
                        public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                            ColumnGetter<?>[] rsColumnGetters = this.rsColumnGetters;

                            if (rsColumnGetters == null) {
                                rsColumnCount = columnLabelList.size();
                                rsColumnGetters = initColumnGetter(columnLabelList);

                                if (rsColumnGetters[1] == ColumnGetter.GET_OBJECT) {
                                    rsColumnGetters[1] = ColumnGetter.get(N.typeOf(targetClass));
                                }

                                this.rsColumnGetters = rsColumnGetters;
                            }

                            if (rsColumnCount != 1 && (rsColumnCount = columnLabelList.size()) != 1) {
                                throw new IllegalArgumentException(
                                        "It's not supported to retrieve value from multiple columns: " + columnLabelList + " for type: " + targetClass);
                            }

                            return (T) rsColumnGetters[1].apply(1, rs);
                        }
                    };
                }
            }
        }
    }

    /**
     * Don't use {@code RowConsumer} in {@link PreparedQuery#forEach(RowConsumer)} or any place where multiple records will be consumed by it, if column labels/count are used in {@link RowConsumer#accept(ResultSet)}.
     * Consider using {@code BiRowConsumer} instead because it's more efficient to consume multiple records when column labels/count are used.
     *
     */
    public interface RowConsumer extends Throwables.Consumer<ResultSet, SQLException> {

        static final RowConsumer DO_NOTHING = rs -> {
        };

        /**
         *
         * @param rs
         * @throws SQLException the SQL exception
         */
        @Override
        void accept(ResultSet rs) throws SQLException;
    }

    /**
     * The Interface BiRowConsumer.
     */
    public interface BiRowConsumer extends Throwables.BiConsumer<ResultSet, List<String>, SQLException> {

        static final BiRowConsumer DO_NOTHING = (rs, cls) -> {
        };

        /**
         *
         * @param rs
         * @param columnLabels
         * @throws SQLException the SQL exception
         */
        @Override
        void accept(ResultSet rs, List<String> columnLabels) throws SQLException;
    }

    /**
     * Generally, the result should be filtered in database side by SQL scripts.
     * Only user {@code RowFilter/BiRowFilter} if there is a specific reason or the filter can't be done by SQL scripts in database server side.
     * Consider using {@code BiRowConsumer} instead because it's more efficient to test multiple records when column labels/count are used.
     *
     */
    public interface RowFilter extends Throwables.Predicate<ResultSet, SQLException> {

        /** The Constant ALWAYS_TRUE. */
        RowFilter ALWAYS_TRUE = new RowFilter() {
            @Override
            public boolean test(ResultSet rs) throws SQLException {
                return true;
            }
        };

        /** The Constant ALWAYS_FALSE. */
        RowFilter ALWAYS_FALSE = new RowFilter() {
            @Override
            public boolean test(ResultSet rs) throws SQLException {
                return false;
            }
        };

        /**
         *
         * @param rs
         * @return true, if successful
         * @throws SQLException the SQL exception
         */
        @Override
        boolean test(ResultSet rs) throws SQLException;
    }

    /**
     * Generally, the result should be filtered in database side by SQL scripts.
     * Only user {@code RowFilter/BiRowFilter} if there is a specific reason or the filter can't be done by SQL scripts in database server side.
     *
     */
    public interface BiRowFilter extends Throwables.BiPredicate<ResultSet, List<String>, SQLException> {

        /** The Constant ALWAYS_TRUE. */
        BiRowFilter ALWAYS_TRUE = new BiRowFilter() {
            @Override
            public boolean test(ResultSet rs, List<String> columnLabels) throws SQLException {
                return true;
            }
        };

        /** The Constant ALWAYS_FALSE. */
        BiRowFilter ALWAYS_FALSE = new BiRowFilter() {
            @Override
            public boolean test(ResultSet rs, List<String> columnLabels) throws SQLException {
                return false;
            }
        };

        /**
         *
         * @param rs
         * @param columnLabels
         * @return true, if successful
         * @throws SQLException the SQL exception
         */
        @Override
        boolean test(ResultSet rs, List<String> columnLabels) throws SQLException;
    }

    private static final ObjectPool<Type<?>, ColumnGetter<?>> COLUMN_GETTER_POOL = new ObjectPool<>(1024);

    static {
        COLUMN_GETTER_POOL.put(N.typeOf(boolean.class), ColumnGetter.GET_BOOLEAN);
        COLUMN_GETTER_POOL.put(N.typeOf(Boolean.class), ColumnGetter.GET_BOOLEAN);
        COLUMN_GETTER_POOL.put(N.typeOf(byte.class), ColumnGetter.GET_BYTE);
        COLUMN_GETTER_POOL.put(N.typeOf(Byte.class), ColumnGetter.GET_BYTE);
        COLUMN_GETTER_POOL.put(N.typeOf(short.class), ColumnGetter.GET_SHORT);
        COLUMN_GETTER_POOL.put(N.typeOf(Short.class), ColumnGetter.GET_SHORT);
        COLUMN_GETTER_POOL.put(N.typeOf(int.class), ColumnGetter.GET_INT);
        COLUMN_GETTER_POOL.put(N.typeOf(Integer.class), ColumnGetter.GET_INT);
        COLUMN_GETTER_POOL.put(N.typeOf(long.class), ColumnGetter.GET_LONG);
        COLUMN_GETTER_POOL.put(N.typeOf(Long.class), ColumnGetter.GET_LONG);
        COLUMN_GETTER_POOL.put(N.typeOf(float.class), ColumnGetter.GET_FLOAT);
        COLUMN_GETTER_POOL.put(N.typeOf(Float.class), ColumnGetter.GET_FLOAT);
        COLUMN_GETTER_POOL.put(N.typeOf(double.class), ColumnGetter.GET_DOUBLE);
        COLUMN_GETTER_POOL.put(N.typeOf(Double.class), ColumnGetter.GET_DOUBLE);
        COLUMN_GETTER_POOL.put(N.typeOf(BigDecimal.class), ColumnGetter.GET_BIG_DECIMAL);
        COLUMN_GETTER_POOL.put(N.typeOf(String.class), ColumnGetter.GET_STRING);
        COLUMN_GETTER_POOL.put(N.typeOf(java.sql.Date.class), ColumnGetter.GET_DATE);
        COLUMN_GETTER_POOL.put(N.typeOf(java.sql.Time.class), ColumnGetter.GET_TIME);
        COLUMN_GETTER_POOL.put(N.typeOf(java.sql.Timestamp.class), ColumnGetter.GET_TIMESTAMP);
        COLUMN_GETTER_POOL.put(N.typeOf(Object.class), ColumnGetter.GET_OBJECT);
    }

    public interface RowExtractor extends Throwables.BiConsumer<ResultSet, Object[], SQLException> {
        @Override
        void accept(final ResultSet rs, final Object[] outputRow) throws SQLException;

        static RowExtractorBuilder builder() {
            return builder(ColumnGetter.GET_OBJECT);
        }

        static RowExtractorBuilder builder(final ColumnGetter<?> defaultColumnGetter) {
            return new RowExtractorBuilder(defaultColumnGetter);
        }

        public static class RowExtractorBuilder {
            private final Map<Integer, ColumnGetter<?>> columnGetterMap;

            RowExtractorBuilder(final ColumnGetter<?> defaultColumnGetter) {
                N.checkArgNotNull(defaultColumnGetter, "defaultColumnGetter");

                columnGetterMap = new HashMap<>(9);
                columnGetterMap.put(0, defaultColumnGetter);
            }

            public RowExtractorBuilder getBoolean(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_BOOLEAN);
            }

            public RowExtractorBuilder getByte(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_BYTE);
            }

            public RowExtractorBuilder getShort(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_SHORT);
            }

            public RowExtractorBuilder getInt(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_INT);
            }

            public RowExtractorBuilder getLong(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_LONG);
            }

            public RowExtractorBuilder getFloat(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_FLOAT);
            }

            public RowExtractorBuilder getDouble(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_DOUBLE);
            }

            public RowExtractorBuilder getBigDecimal(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_BIG_DECIMAL);
            }

            public RowExtractorBuilder getString(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_STRING);
            }

            public RowExtractorBuilder getDate(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_DATE);
            }

            public RowExtractorBuilder getTime(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_TIME);
            }

            public RowExtractorBuilder getTimestamp(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_TIMESTAMP);
            }

            public RowExtractorBuilder get(final int columnIndex, final ColumnGetter<?> columnGetter) {
                N.checkArgPositive(columnIndex, "columnIndex");
                N.checkArgNotNull(columnGetter, "columnGetter");

                //        if (columnGetters == null) {
                //            columnGetterMap.put(columnIndex, columnGetter);
                //        } else {
                //            columnGetters[columnIndex] = columnGetter;
                //        }

                columnGetterMap.put(columnIndex, columnGetter);
                return this;
            }

            ColumnGetter<?>[] initColumnGetter(ResultSet rs) throws SQLException {
                return initColumnGetter(rs.getMetaData().getColumnCount());
            }

            ColumnGetter<?>[] initColumnGetter(final int columnCount) throws SQLException {
                final ColumnGetter<?>[] rsColumnGetters = new ColumnGetter<?>[columnCount + 1];
                rsColumnGetters[0] = columnGetterMap.get(0);

                for (int i = 1, len = rsColumnGetters.length; i < len; i++) {
                    rsColumnGetters[i] = columnGetterMap.getOrDefault(i, rsColumnGetters[0]);
                }

                return rsColumnGetters;
            }

            /**
             * Don't cache or reuse the returned {@code RowExtractor} instance.
             *
             * @return
             */
            public RowExtractor build() {
                return new RowExtractor() {
                    private volatile int rsColumnCount = -1;
                    private volatile ColumnGetter<?>[] rsColumnGetters = null;

                    @Override
                    public void accept(final ResultSet rs, final Object[] outputRow) throws SQLException {
                        ColumnGetter<?>[] rsColumnGetters = this.rsColumnGetters;

                        if (rsColumnGetters == null) {
                            rsColumnGetters = initColumnGetter(outputRow.length);
                            rsColumnCount = rsColumnGetters.length - 1;
                            this.rsColumnGetters = rsColumnGetters;
                        }

                        for (int i = 0; i < rsColumnCount;) {
                            outputRow[i] = rsColumnGetters[++i].apply(i, rs);
                        }
                    }
                };
            }
        }
    }

    public interface ColumnGetter<V> {

        ColumnGetter<Object> GET_OBJECT = new ColumnGetter<Object>() {
            @Override
            @SuppressWarnings("deprecation")
            public Object apply(final int columnIndex, final ResultSet rs) throws SQLException {
                return InternalUtil.getColumnValue(rs, columnIndex);
            }
        };

        ColumnGetter<Boolean> GET_BOOLEAN = new ColumnGetter<Boolean>() {
            @Override
            public Boolean apply(final int columnIndex, final ResultSet rs) throws SQLException {
                return rs.getBoolean(columnIndex);
            }
        };

        ColumnGetter<Byte> GET_BYTE = new ColumnGetter<Byte>() {
            @Override
            public Byte apply(final int columnIndex, final ResultSet rs) throws SQLException {
                return rs.getByte(columnIndex);
            }
        };

        ColumnGetter<Short> GET_SHORT = new ColumnGetter<Short>() {
            @Override
            public Short apply(final int columnIndex, final ResultSet rs) throws SQLException {
                return rs.getShort(columnIndex);
            }
        };

        ColumnGetter<Integer> GET_INT = new ColumnGetter<Integer>() {
            @Override
            public Integer apply(final int columnIndex, final ResultSet rs) throws SQLException {
                return rs.getInt(columnIndex);
            }
        };

        ColumnGetter<Long> GET_LONG = new ColumnGetter<Long>() {
            @Override
            public Long apply(final int columnIndex, final ResultSet rs) throws SQLException {
                return rs.getLong(columnIndex);
            }
        };

        ColumnGetter<Float> GET_FLOAT = new ColumnGetter<Float>() {
            @Override
            public Float apply(final int columnIndex, final ResultSet rs) throws SQLException {
                return rs.getFloat(columnIndex);
            }
        };

        ColumnGetter<Double> GET_DOUBLE = new ColumnGetter<Double>() {
            @Override
            public Double apply(final int columnIndex, final ResultSet rs) throws SQLException {
                return rs.getDouble(columnIndex);
            }
        };

        ColumnGetter<BigDecimal> GET_BIG_DECIMAL = new ColumnGetter<BigDecimal>() {
            @Override
            public BigDecimal apply(final int columnIndex, final ResultSet rs) throws SQLException {
                return rs.getBigDecimal(columnIndex);
            }
        };

        ColumnGetter<String> GET_STRING = new ColumnGetter<String>() {
            @Override
            public String apply(final int columnIndex, final ResultSet rs) throws SQLException {
                return rs.getString(columnIndex);
            }
        };

        ColumnGetter<java.sql.Date> GET_DATE = new ColumnGetter<java.sql.Date>() {
            @Override
            public java.sql.Date apply(final int columnIndex, final ResultSet rs) throws SQLException {
                return rs.getDate(columnIndex);
            }
        };

        ColumnGetter<java.sql.Time> GET_TIME = new ColumnGetter<java.sql.Time>() {
            @Override
            public java.sql.Time apply(final int columnIndex, final ResultSet rs) throws SQLException {
                return rs.getTime(columnIndex);
            }
        };

        ColumnGetter<java.sql.Timestamp> GET_TIMESTAMP = new ColumnGetter<java.sql.Timestamp>() {
            @Override
            public java.sql.Timestamp apply(final int columnIndex, final ResultSet rs) throws SQLException {
                return rs.getTimestamp(columnIndex);
            }
        };

        /**
         *
         * @param columnIndex start from 1
         * @param rs
         * @return
         * @throws SQLException
         */
        V apply(int columnIndex, ResultSet rs) throws SQLException;

        static <T> ColumnGetter<T> get(final Class<? extends T> cls) {
            return get(N.typeOf(cls));
        }

        static <T> ColumnGetter<T> get(final Type<? extends T> type) {
            ColumnGetter<?> columnGetter = COLUMN_GETTER_POOL.get(type);

            if (columnGetter == null) {
                columnGetter = new ColumnGetter<T>() {
                    @Override
                    public T apply(int columnIndex, ResultSet rs) throws SQLException {
                        return type.get(rs, columnIndex);
                    }
                };

                COLUMN_GETTER_POOL.put(type, columnGetter);
            }

            return (ColumnGetter<T>) columnGetter;
        }
    }

    public static interface Handler<T> {
        /**
         *
         * @param targetObject
         * @param args
         * @param methodSignature The first element is {@code Method}, The second element is {@code parameterTypes}(it will be an empty Class<?> List if there is no parameter), the third element is {@code returnType}
         */
        default void beforeInvoke(final T targetObject, final Object[] args, final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
            // empty action.
        }

        /**
         *
         * @param <R>
         * @param result
         * @param targetObject
         * @param args
         * @param methodSignature The first element is {@code Method}, The second element is {@code parameterTypes}(it will be an empty Class<?> List if there is no parameter), the third element is {@code returnType}
         */
        default void afterInvoke(final Result<?, Exception> result, final T targetObject, final Object[] args,
                Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
            // empty action.
        }
    }

    @SuppressWarnings("rawtypes")
    static final class DaoHandler implements JdbcUtil.Handler<Dao> {

    };

    /**
     * This interface is designed to share/manager SQL queries by Java APIs/methods with static parameter types and return type, while hiding the SQL scripts.
     * It's a gift from nature and created by thoughts.
     *
     * <br />
     * Note: Setting parameters by 'ParametersSetter' or Retrieving result/record by 'ResultExtractor/BiResultExtractor/RowMapper/BiRowMapper' is not enabled at present.
     *
     * <br />
     *
     * <li>The SQL operations/methods should be annotated with SQL scripts by {@code @Select/@Insert/@Update/@Delete/@NamedSelect/@NamedInsert/@NamedUpdate/@NamedDelete}.</li>
     *
     * <li>The Order of the parameters in the method should be consistent with parameter order in SQL scripts for parameterized SQL.
     * For named parameterized SQL, the parameters must be binded with names through {@code @Bind}, or {@code Map/Entity} with getter/setter methods.</li>
     *
     * <li>SQL parameters can be set through input method parameters(by multiple parameters or a {@code Collection}, or a {@code Map/Entity} for named sql), or by {@code JdbcUtil.ParametersSetter<PreparedQuery/PreparedCallabeQuery...>}.</li>
     *
     * <li>{@code ResultExtractor/BiResultExtractor/RowMapper/BiRowMapper} can be specified by the last parameter of the method.</li>
     *
     * <li>The return type of the method must be same as the return type of {@code ResultExtractor/BiResultExtractor} if it's specified by the last parameter of the method.</li>
     *
     * <li>The return type of update/delete operations only can int/Integer/long/Long/boolean/Boolean/void. If it's long/Long, {@code PreparedQuery#largeUpdate()} will be called,
     * otherwise, {@code PreparedQuery#update()} will be called.</li>
     *
     * <li>Remember declaring {@code throws SQLException} in the method.</li>
     * <br />
     * <li>Which underline {@code PreparedQuery/PreparedCallableQuery} method to call for SQL methods/operations annotated with {@code @Select/@NamedSelect}:
     * <ul>
     *   <li>If {@code ResultExtractor/BiResultExtractor} is specified by the last parameter of the method, {@code PreparedQuery#query(ResultExtractor/BiResultExtractor)} will be called.</li>
     *   <li>Or else if {@code RowMapper/BiRowMapper} is specified by the last parameter of the method:</li>
     *      <ul>
     *          <li>If the return type of the method is {@code List} and one of below conditions is matched, {@code PreparedQuery#list(RowMapper/BiRowMapper)} will be called:</li>
     *          <ul>
     *              <li>The return type of the method is raw {@code List} without parameterized type, and the method name doesn't start with {@code "get"/"findFirst"/"findOne"}.</li>
     *          </ul>
     *          <ul>
     *              <li>The last parameter type is raw {@code RowMapper/BiRowMapper} without parameterized type, and the method name doesn't start with {@code "get"/"findFirst"/"findOne"}.</li>
     *          </ul>
     *          <ul>
     *              <li>The return type of the method is generic {@code List} with parameterized type and The last parameter type is generic {@code RowMapper/BiRowMapper} with parameterized types, but They're not same.</li>
     *          </ul>
     *      </ul>
     *      <ul>
     *          <li>Or else if the return type of the method is {@code ExceptionalStream/Stream}, {@code PreparedQuery#stream(RowMapper/BiRowMapper)} will be called.</li>
     *      </ul>
     *      <ul>
     *          <li>Or else if the return type of the method is {@code Optional}, {@code PreparedQuery#findFirst(RowMapper/BiRowMapper)} will be called.</li>
     *      </ul>
     *      <ul>
     *          <li>Or else, {@code PreparedQuery#findFirst(RowMapper/BiRowMapper).orElse(N.defaultValueOf(returnType))} will be called.</li>
     *      </ul>
     *   <li>Or else:</li>
     *      <ul>
     *          <li>If the return type of the method is {@code DataSet}, {@code PreparedQuery#query()} will be called.</li>
     *      </ul>
     *      <ul>
     *          <li>Or else if the return type of the method is {@code ExceptionalStream/Stream}, {@code PreparedQuery#stream(Class)} will be called.</li>
     *      </ul>
     *      <ul>
     *          <li>Or else if the return type of the method is {@code Map} or {@code Entity} class with {@code getter/setter} methods, {@code PreparedQuery#findFirst(Class).orNull()} will be called.</li>
     *      </ul>
     *      <ul>
     *          <li>Or else if the return type of the method is {@code Optional}:</li>
     *          <ul>
     *              <li>If the value type of {@code Optional} is {@code Map}, or {@code Entity} class with {@code getter/setter} methods, or {@code List}, or {@code Object[]}, {@code PreparedQuery#findFirst(Class)} will be called.</li>
     *          </ul>
     *          <ul>
     *              <li>Or else, {@code PreparedQuery#queryForSingleNonNull(Class)} will be called.</li>
     *          </ul>
     *      </ul>
     *      <ul>
     *          <li>Or else if the return type of the method is {@code Nullable}:</li>
     *          <ul>
     *              <li>If the value type of {@code Nullable} is {@code Map}, or {@code Entity} class with {@code getter/setter} methods, or {@code List}, or {@code Object[]}, {@code PreparedQuery#findFirst(Class)} will be called.</li>
     *          </ul>
     *          <ul>
     *              <li>Or else, {@code PreparedQuery#queryForSingleResult(Class)} will be called.</li>
     *          </ul>
     *      </ul>
     *      <ul>
     *          <li>Or else if the return type of the method is {@code OptionalBoolean/Byte/.../Double}, {@code PreparedQuery#queryForBoolean/Byte/...Double()} will called.</li>
     *      </ul>
     *      <ul>
     *          <li>Or else if the return type of the method is {@code List}, and the method name doesn't start with {@code "get"/"findFirst"/"findOne"}, {@code PreparedQuery#list(Class)} will be called.</li>
     *      </ul>
     *      <ul>
     *          <li>Or else if the return type of the method is {@code boolean/Boolean}, and the method name starts with {@code "exist"/"exists"/"has"}, {@code PreparedQuery#exist()} will be called.</li>
     *      </ul>
     *      <ul>
     *          <li>Or else, {@code PreparedQuery#queryForSingleResult(Class).orElse(N.defaultValueOf(returnType)} will be called.</li>
     *      </ul>
     * </ul>
     *
     * <br />
     * <br />
     *
     * Here is a simple {@code UserDao} sample.
     *
     * <pre>
     * <code>
     * public static interface UserDao extends JdbcUtil.CrudDao<User, Long, SQLBuilder.PSC> {
     *     &#064NamedInsert("INSERT INTO user (id, first_name, last_name, email) VALUES (:id, :firstName, :lastName, :email)")
     *     void insertWithId(User user) throws SQLException;
     *
     *     &#064NamedUpdate("UPDATE user SET first_name = :firstName, last_name = :lastName WHERE id = :id")
     *     int updateFirstAndLastName(@Bind("firstName") String newFirstName, @Bind("lastName") String newLastName, @Bind("id") long id) throws SQLException;
     *
     *     &#064NamedSelect("SELECT first_name, last_name FROM user WHERE id = :id")
     *     User getFirstAndLastNameBy(@Bind("id") long id) throws SQLException;
     *
     *     &#064NamedSelect("SELECT id, first_name, last_name, email FROM user")
     *     Stream<User> allUsers() throws SQLException;
     * }
     * </code>
     * </pre>
     *
     * Here is the generate way to work with transaction started by {@code SQLExecutor}.
     *
     * <pre>
     * <code>
     * static final UserDao userDao = Dao.newInstance(UserDao.class, dataSource);
     * ...
     *
     * final SQLTransaction tran = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
     *
     * try {
     *      userDao.getById(id);
     *      userDao.update(...);
     *      // more...
     *
     *      tran.commit();
     * } finally {
     *      // The connection will be automatically closed after the transaction is committed or rolled back.
     *      tran.rollbackIfNotCommitted();
     * }
     * </code>
     * </pre>
     *
     * @param <T>
     * @param <SB> {@code SQLBuilder} used to generate sql scripts. Only can be {@code SQLBuilder.PSC/PAC/PLC}
     * @param <TD>
     * @see {@link com.landawn.abacus.condition.ConditionFactory}
     * @see {@link com.landawn.abacus.condition.ConditionFactory.CF}
     * @see JdbcUtil#prepareQuery(javax.sql.DataSource, String)
     * @see JdbcUtil#prepareNamedQuery(javax.sql.DataSource, String)
     * @see JdbcUtil#beginTransaction(javax.sql.DataSource, IsolationLevel, boolean)
     * @see Dao
     * @See CrudDao
     * @see SQLExecutor.Mapper
     * @see com.landawn.abacus.annotation.AccessFieldByMethod
     * @see com.landawn.abacus.condition.ConditionFactory
     * @see com.landawn.abacus.condition.ConditionFactory.CF
     */
    public interface Dao<T, SB extends SQLBuilder, TD extends Dao<T, SB, TD>> {
        /**
         * The Interface Select.
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        public static @interface Select {

            /**
             *
             * @return
             * @deprecated using sql="SELECT ... FROM ..." for explicit call.
             */
            @Deprecated
            String value() default "";

            /**
             *
             * @return
             */
            String id() default ""; // id defined SqlMapper

            /**
             *
             * @return
             */
            String sql() default "";

            /**
             *
             * @return
             */
            int fetchSize() default -1;

            /**
             * Unit is seconds.
             *
             * @return
             */
            int queryTimeout() default -1;

            OP op() default OP.DEFAULT;

            /**
             * Set it to true if there is only one input parameter and the type is Collection/Object Array, and the target db column type is Collection/Object Array.
             * 
             * @return
             */
            boolean isSingleParameter() default false;
        }

        /**
         * The Interface Insert.
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        public static @interface Insert {

            /**
             *
             * @return
             * @deprecated using sql="SELECT ... FROM ..." for explicit call.
             */
            @Deprecated
            String value() default "";

            /**
             *
             * @return
             */
            String id() default ""; // id defined SqlMapper

            /**
             *
             * @return
             */
            String sql() default "";

            /**
             *
             * @return
             */
            boolean isBatch() default false;

            /**
             *
             * @return
             */
            int batchSize() default 0;

            /**
             * Unit is seconds.
             *
             * @return
             */
            int queryTimeout() default -1;

            /**
             * Set it to true if there is only one input parameter and the type is Collection/Object Array, and the target db column type is Collection/Object Array.
             * 
             * @return
             */
            boolean isSingleParameter() default false;
        }

        /**
         * The Interface Update.
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        public static @interface Update {

            /**
             *
             * @return
             * @deprecated using sql="SELECT ... FROM ..." for explicit call.
             */
            @Deprecated
            String value() default "";

            /**
             *
             * @return
             */
            String id() default ""; // id defined SqlMapper

            /**
             *
             * @return
             */
            String sql() default "";

            /**
             *
             * @return
             */
            boolean isBatch() default false;

            /**
             *
             * @return
             */
            int batchSize() default 0;

            /**
             * Unit is seconds.
             *
             * @return
             */
            int queryTimeout() default -1;

            OP op() default OP.DEFAULT;

            /**
             * Set it to true if there is only one input parameter and the type is Collection/Object Array, and the target db column type is Collection/Object Array.
             * 
             * @return
             */
            boolean isSingleParameter() default false;
        }

        /**
         * The Interface Delete.
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        public static @interface Delete {

            /**
             *
             * @return
             * @deprecated using sql="SELECT ... FROM ..." for explicit call.
             */
            @Deprecated
            String value() default "";

            /**
             *
             * @return
             */
            String id() default ""; // id defined SqlMapper

            /**
             *
             * @return
             */
            String sql() default "";

            /**
             *
             * @return
             */
            boolean isBatch() default false;

            /**
             *
             * @return
             */
            int batchSize() default 0;

            /**
             * Unit is seconds.
             *
             * @return
             */
            int queryTimeout() default -1;

            OP op() default OP.DEFAULT;

            /**
             * Set it to true if there is only one input parameter and the type is Collection/Object Array, and the target db column type is Collection/Object Array.
             * 
             * @return
             */
            boolean isSingleParameter() default false;
        }

        /**
         * The Interface NamedSelect.
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        public static @interface NamedSelect {

            /**
             *
             * @return
             * @deprecated using sql="SELECT ... FROM ..." for explicit call.
             */
            @Deprecated
            String value() default "";

            /**
             *
             * @return
             */
            String id() default ""; // id defined SqlMapper

            /**
             *
             * @return
             */
            String sql() default "";

            /**
             *
             * @return
             */
            int fetchSize() default -1;

            /**
             * Unit is seconds.
             *
             * @return
             */
            int queryTimeout() default -1;

            OP op() default OP.DEFAULT;
        }

        /**
         * The Interface NamedInsert.
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        public static @interface NamedInsert {

            /**
             *
             * @return
             * @deprecated using sql="SELECT ... FROM ..." for explicit call.
             */
            @Deprecated
            String value() default "";

            /**
             *
             * @return
             */
            String id() default ""; // id defined SqlMapper

            /**
             *
             * @return
             */
            String sql() default "";

            /**
             *
             * @return
             */
            boolean isBatch() default false;

            /**
             *
             * @return
             */
            int batchSize() default 0;

            /**
             * Unit is seconds.
             *
             * @return
             */
            int queryTimeout() default -1;
        }

        /**
         * The Interface NamedUpdate.
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        public static @interface NamedUpdate {

            /**
             *
             * @return
             * @deprecated using sql="SELECT ... FROM ..." for explicit call.
             */
            @Deprecated
            String value() default "";

            /**
             *
             * @return
             */
            String id() default ""; // id defined SqlMapper

            /**
             *
             * @return
             */
            String sql() default "";

            /**
             *
             * @return
             */
            boolean isBatch() default false;

            /**
             *
             * @return
             */
            int batchSize() default 0;

            /**
             * Unit is seconds.
             *
             * @return
             */
            int queryTimeout() default -1;

            OP op() default OP.DEFAULT;
        }

        /**
         * The Interface NamedDelete.
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        public static @interface NamedDelete {

            /**
             *
             * @return
             * @deprecated using sql="SELECT ... FROM ..." for explicit call.
             */
            @Deprecated
            String value() default "";

            /**
             *
             * @return
             */
            String id() default ""; // id defined SqlMapper

            /**
             *
             * @return
             */
            String sql() default "";

            /**
             *
             * @return
             */
            boolean isBatch() default false;

            /**
             *
             * @return
             */
            int batchSize() default 0;

            /**
             * Unit is seconds.
             *
             * @return
             */
            int queryTimeout() default -1;

            OP op() default OP.DEFAULT;
        }

        /**
         * The Interface Call.
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        public static @interface Call {

            /**
             *
             * @return
             * @deprecated using sql="call update_account(?)" for explicit call.
             */
            @Deprecated
            String value() default "";

            /**
             *
             * @return
             */
            String id() default ""; // id defined SqlMapper

            /**
             *
             * @return
             */
            String sql() default "";

            /**
             * Unit is seconds.
             *
             * @return
             */
            int queryTimeout() default -1;

            /**
             * Set it to true if there is only one input parameter and the type is Collection/Object Array, and the target db column type is Collection/Object Array.
             * 
             * @return
             */
            boolean isSingleParameter() default false;
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        @Repeatable(DaoUtil.OutParameterList.class)
        public @interface OutParameter {
            /**
             *
             * @return
             * @see CallableStatement#registerOutParameter(String, int)
             */
            String name() default "";

            /**
             * Starts from 1.
             * @return
             * @see CallableStatement#registerOutParameter(int, int)
             */
            int position() default -1;

            /**
             *
             * @return
             * @see {@code java.sql.Types}
             */
            int sqlType();
        }

        /**
         * It's only for methods with default implementation in {@code Dao} interfaces. Don't use it for the abstract methods.
         * And the last parameter of the method should be {@code String[]: (param1, param2, ..., String ... sqls)}
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        public static @interface Sqls {
            /**
             *
             * @return
             */
            String[] value() default {};
        }

        /**
         * The Interface Bind.
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.PARAMETER)
        public static @interface Bind {

            /**
             *
             * @return
             */
            String value() default "";
        }

        /**
         * Replace the parts defined with format <code>{part}</code> in the sql annotated to the method.
         * For example:
         * <p>
         * <code>
         * 
         *  @Select("SELECT first_name, last_name FROM {tableName} WHERE id = :id")
         *  <br />
         *  User selectByUserId(@Define("tableName") String realTableName, @Bind("id") int id) throws SQLException;
         * 
         * <br />
         * <br />
         * <br />
         * OR with customized '{whatever}':
         * <br />
         * 
         *  @Select("SELECT first_name, last_name FROM {tableName} WHERE id = :id ORDER BY {whatever -> orderBy{{P}}")
         *  <br/>
         *  User selectByUserId(@Define("tableName") String realTableName, @Bind("id") int id, @Define("{whatever -> orderBy{{P}}") String orderBy) throws SQLException;
         * 
         * </code>
         * </p>
         * 
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(value = { ElementType.PARAMETER })
        static @interface Define {
            String value() default "";
        }

        /**
         *
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        public static @interface Transactional {
            Propagation propagation() default Propagation.REQUIRED;
        }

        /** 
         * Unsupported operation.
         * 
         * @deprecated won't be implemented. It should be defined and done in DB server side.
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(value = { ElementType.METHOD })
        public static @interface OnDelete {
            OnDeleteAction action() default OnDeleteAction.NO_ACTION;
        }

        /**
         *
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(value = { ElementType.METHOD, ElementType.TYPE })
        public static @interface SqlLogEnabled {
            /**
             * 
             * @return
             */
            boolean value() default true;
        }

        /**
         *
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(value = { ElementType.METHOD, ElementType.TYPE })
        public static @interface PerfLog {
            /**
             * start to log performance for sql if the execution time >= the specified(or default) execution time in milliseconds.
             *
             * @return
             */
            long minExecutionTimeForSql() default 1000;

            /**
             * start to log performance for Dao operation/method if the execution time >= the specified(or default) execution time in milliseconds.
             * @return
             */
            long minExecutionTimeForOperation() default 3000;
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(value = { ElementType.METHOD, ElementType.TYPE })
        @Repeatable(DaoUtil.HandlerList.class)
        public static @interface Handler {
            String qualifier() default "";

            @SuppressWarnings("rawtypes")
            Class<? extends JdbcUtil.Handler<? extends Dao>> type() default DaoHandler.class;

            /**
             * Those conditions(by contains ignore case or regular expression match) will be joined by {@code OR}, not {@code AND}.
             * It's only applied if target of annotation {@code Handler} is {@code Type}, and will be ignored if target is method.
             * 
             * @return
             */
            String[] filter() default { ".*" };
        }

        // TODO: First of all, it's bad idea to implement cache in DAL layer?! and how if not?
        /** 
         * 
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(value = { ElementType.TYPE })
        static @interface Cache {
            int capacity() default 1000;

            long evictDelay() default 3000; // unit milliseconds.
        }

        /** 
         * 
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(value = { ElementType.METHOD, ElementType.TYPE })
        static @interface CacheResult {
            /**
             * Flag to identity if {@code CacheResult} is disabled.
             * @return
             */
            boolean disabled() default false;

            /**
             * 
             * @return
             */
            long liveTime() default 30 * 60 * 1000; // unit milliseconds.

            /**
             * 
             * @return
             */
            long idleTime() default 3 * 60 * 1000; // unit milliseconds.

            /**
             * Minimum required size to cache query result if the return type is {@code Collection} or {@code DataSet}.
             * This setting will be ignore if the return types are not {@code Collection} or {@code DataSet}.
             * 
             * @return
             */
            int minSize() default 0; // for list/DataSet.

            /**
             * If the query result won't be cached if it's size is bigger than {@code maxSize} if the return type is {@code Collection} or {@code DataSet}.
             * This setting will be ignore if the return types are not {@code Collection} or {@code DataSet}.
             *  
             * @return
             */
            int maxSize() default Integer.MAX_VALUE; // for list/DataSet.

            /**
             * It's used to copy/clone the result when save result to cache or fetch result from cache.
             * It can be set to {@code "none" and "kryo"}.
             * 
             * @return
             * @see https://github.com/EsotericSoftware/kryo
             */
            String transfer() default "none";

            //    /**
            //     * If it's set to true, the cached result won't be removed by method annotated by {@code RefershCache}.
            //     * 
            //     * @return
            //     */
            //    boolean isStaticData() default false;

            /**
             * Those conditions(by contains ignore case or regular expression match) will be joined by {@code OR}, not {@code AND}.
             * It's only applied if target of annotation {@code RefreshCache} is {@code Type}, and will be ignored if target is method.
             * 
             * @return
             */
            String[] filter() default { "query", "queryFor", "list", "get", "find", "findFirst", "exist", "count" };

            // TODO: second, what will key be like?: {methodName=[args]} -> JSON or kryo? 
            // KeyGenerator keyGenerator() default KeyGenerator.JSON; KeyGenerator.JSON/KRYO;
        }

        /** 
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(value = { ElementType.METHOD, ElementType.TYPE })
        static @interface RefreshCache {

            /**
             * Flag to identity if {@code RefreshCache} is disabled.
             * @return
             */
            boolean disabled() default false;

            //    /**
            //     * 
            //     * @return
            //     */
            //    boolean forceRefreshStaticData() default false;

            /**
             * Those conditions(by contains ignore case or regular expression match) will be joined by {@code OR}, not {@code AND}.
             * It's only applied if target of annotation {@code RefreshCache} is {@code Type}, and will be ignored if target is method.
             * 
             * @return
             */
            String[] filter() default { "save", "insert", "update", "delete", "upsert", "execute" };
        }

        /** 
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(value = { ElementType.TYPE })
        static @interface AllowJoiningByNullOrDefaultValue {
            boolean value() default false;
        }

        /**
         * 
         * @see The operations in {@code AbstractPreparedQuery}
         *
         */
        public static enum OP {
            exists,
            get,
            findFirst,
            list,

            /**
             * @deprecated generally it's unnecessary to specify the {@code "op = OP.stream"} in {@code Select/NamedSelect}.
             */
            query,

            /**
             * 
             * @deprecated generally it's unnecessary to specify the {@code "op = OP.stream"} in {@code Select/NamedSelect}.
             */
            stream,

            /**
             * 
             */
            queryForSingle,

            /**
             * 
             */
            queryForUnique,

            /**
             * 
             */
            update,

            /**
             * 
             */
            largeUpdate,

            /* batchUpdate,*/

            /**
             * 
             */
            DEFAULT;
        }

        /**
         *
         * @return
         */
        @NonDBOperation
        Class<T> targetEntityClass();

        /**
         *
         * @return
         */
        @NonDBOperation
        javax.sql.DataSource dataSource();

        // SQLExecutor sqlExecutor();

        @NonDBOperation
        SQLMapper sqlMapper();

        @NonDBOperation
        Executor executor();

        void cacheSql(String key, String sql);

        void cacheSqls(String key, Collection<String> sqls);

        String getCachedSql(String key);

        ImmutableList<String> getCachedSqls(String key);

        //    /**
        //     *
        //     * @param isolationLevel
        //     * @return
        //     * @throws UncheckedSQLException
        //     */
        //     @NonDBOperation
        //    default SQLTransaction beginTransaction(final IsolationLevel isolationLevel) throws UncheckedSQLException {
        //        return beginTransaction(isolationLevel, false);
        //    }
        //
        //    /**
        //     * The connection opened in the transaction will be automatically closed after the transaction is committed or rolled back.
        //     * DON'T close it again by calling the close method.
        //     * <br />
        //     * <br />
        //     * The transaction will be shared cross the instances of {@code SQLExecutor/Dao} by the methods called in the same thread with same {@code DataSource}.
        //     *
        //     * <br />
        //     * <br />
        //     *
        //     * The general programming way with SQLExecutor/Dao is to execute sql scripts(generated by SQLBuilder) with array/list/map/entity by calling (batch)insert/update/delete/query/... methods.
        //     * If Transaction is required, it can be started:
        //     *
        //     * <pre>
        //     * <code>
        //     *   final SQLTransaction tran = someDao.beginTransaction(IsolationLevel.READ_COMMITTED);
        //     *   try {
        //     *       // sqlExecutor.insert(...);
        //     *       // sqlExecutor.update(...);
        //     *       // sqlExecutor.query(...);
        //     *
        //     *       tran.commit();
        //     *   } finally {
        //     *       // The connection will be automatically closed after the transaction is committed or rolled back.
        //     *       tran.rollbackIfNotCommitted();
        //     *   }
        //     * </code>
        //     * </pre>
        //     *
        //     * @param isolationLevel
        //     * @param forUpdateOnly
        //     * @return
        //     * @throws UncheckedSQLException
        //     * @see {@link SQLExecutor#beginTransaction(IsolationLevel, boolean)}
        //     */
        //     @NonDBOperation
        //    default SQLTransaction beginTransaction(final IsolationLevel isolationLevel, final boolean forUpdateOnly) throws UncheckedSQLException {
        //        N.checkArgNotNull(isolationLevel, "isolationLevel");
        //
        //        final javax.sql.DataSource ds = dataSource();
        //        SQLTransaction tran = SQLTransaction.getTransaction(ds);
        //
        //        if (tran == null) {
        //            Connection conn = null;
        //            boolean noException = false;
        //
        //            try {
        //                conn = getConnection(ds);
        //                tran = new SQLTransaction(ds, conn, isolationLevel, true, true);
        //                tran.incrementAndGetRef(isolationLevel, forUpdateOnly);
        //
        //                noException = true;
        //            } catch (SQLException e) {
        //                throw new UncheckedSQLException(e);
        //            } finally {
        //                if (noException == false) {
        //                    JdbcUtil.releaseConnection(conn, ds);
        //                }
        //            }
        //
        //            logger.info("Create a new SQLTransaction(id={})", tran.id());
        //            SQLTransaction.putTransaction(ds, tran);
        //        } else {
        //            logger.info("Reusing the existing SQLTransaction(id={})", tran.id());
        //            tran.incrementAndGetRef(isolationLevel, forUpdateOnly);
        //        }
        //
        //        return tran;
        //    }

        /**
         *
         * @param query
         * @return
         * @throws SQLException
         */
        @NonDBOperation
        default PreparedQuery prepareQuery(final String query) throws SQLException {
            return JdbcUtil.prepareQuery(dataSource(), query);
        }

        /**
         *
         * @param query
         * @param generateKeys
         * @return
         * @throws SQLException
         */
        @NonDBOperation
        default PreparedQuery prepareQuery(final String query, final boolean generateKeys) throws SQLException {
            return JdbcUtil.prepareQuery(dataSource(), query, generateKeys);
        }

        /**
         *
         * @param query
         * @param returnColumnIndexes
         * @return
         * @throws SQLException
         */
        @NonDBOperation
        default PreparedQuery prepareQuery(final String query, final int[] returnColumnIndexes) throws SQLException {
            return JdbcUtil.prepareQuery(dataSource(), query, returnColumnIndexes);
        }

        /**
         *
         * @param query
         * @param returnColumnIndexes
         * @return
         * @throws SQLException
         */
        @NonDBOperation
        default PreparedQuery prepareQuery(final String query, final String[] returnColumnNames) throws SQLException {
            return JdbcUtil.prepareQuery(dataSource(), query, returnColumnNames);
        }

        /**
         *
         * @param sql
         * @param stmtCreator
         * @return
         * @throws SQLException
         */
        @NonDBOperation
        default PreparedQuery prepareQuery(final String sql, final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator)
                throws SQLException {
            return JdbcUtil.prepareQuery(dataSource(), sql, stmtCreator);
        }

        /**
         *
         * @param namedQuery
         * @return
         * @throws SQLException
         */
        @NonDBOperation
        default NamedQuery prepareNamedQuery(final String namedQuery) throws SQLException {
            return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery);
        }

        /**
         *
         * @param namedQuery
         * @param generateKeys
         * @return
         * @throws SQLException
         */
        @NonDBOperation
        default NamedQuery prepareNamedQuery(final String namedQuery, final boolean generateKeys) throws SQLException {
            return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, generateKeys);
        }

        /**
         *
         * @param namedQuery
         * @param returnColumnIndexes
         * @return
         * @throws SQLException
         */
        @NonDBOperation
        default NamedQuery prepareNamedQuery(final String namedQuery, final int[] returnColumnIndexes) throws SQLException {
            return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, returnColumnIndexes);
        }

        /**
         *
         * @param namedQuery
         * @param returnColumnNames
         * @return
         * @throws SQLException
         */
        @NonDBOperation
        default NamedQuery prepareNamedQuery(final String namedQuery, final String[] returnColumnNames) throws SQLException {
            return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, returnColumnNames);
        }

        /**
         *
         * @param namedQuery
         * @param stmtCreator
         * @return
         * @throws SQLException
         */
        @NonDBOperation
        default NamedQuery prepareNamedQuery(final String namedQuery,
                final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
            return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, stmtCreator);
        }

        /**
         *
         * @param namedSql the named query
         * @return
         * @throws SQLException
         */
        @NonDBOperation
        default NamedQuery prepareNamedQuery(final ParsedSql namedSql) throws SQLException {
            return JdbcUtil.prepareNamedQuery(dataSource(), namedSql);
        }

        /**
         *
         * @param namedSql the named query
         * @param generateKeys
         * @return
         * @throws SQLException
         */
        @NonDBOperation
        default NamedQuery prepareNamedQuery(final ParsedSql namedSql, final boolean generateKeys) throws SQLException {
            return JdbcUtil.prepareNamedQuery(dataSource(), namedSql, generateKeys);
        }

        /**
         *
         * @param namedQuery
         * @param returnColumnIndexes
         * @return
         * @throws SQLException
         */
        @NonDBOperation
        default NamedQuery prepareNamedQuery(final ParsedSql namedQuery, final int[] returnColumnIndexes) throws SQLException {
            return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, returnColumnIndexes);
        }

        /**
         *
         * @param namedQuery
         * @param returnColumnNames
         * @return
         * @throws SQLException
         */
        @NonDBOperation
        default NamedQuery prepareNamedQuery(final ParsedSql namedQuery, final String[] returnColumnNames) throws SQLException {
            return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, returnColumnNames);
        }

        /**
         *
         * @param namedSql the named query
         * @param stmtCreator
         * @return
         * @throws SQLException
         */
        @NonDBOperation
        default NamedQuery prepareNamedQuery(final ParsedSql namedSql,
                final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
            return JdbcUtil.prepareNamedQuery(dataSource(), namedSql, stmtCreator);
        }

        /**
         *
         * @param query
         * @return
         * @throws SQLException
         */
        @NonDBOperation
        default PreparedCallableQuery prepareCallableQuery(final String query) throws SQLException {
            return JdbcUtil.prepareCallableQuery(dataSource(), query);
        }

        /**
         *
         * @param sql
         * @param stmtCreator
         * @return
         * @throws SQLException
         */
        @NonDBOperation
        default PreparedCallableQuery prepareCallableQuery(final String sql,
                final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator) throws SQLException {
            return JdbcUtil.prepareCallableQuery(dataSource(), sql, stmtCreator);
        }

        /**
         *
         * @param entityToSave
         * @return
         * @throws SQLException the SQL exception
         */
        void save(final T entityToSave) throws SQLException;

        /**
         *
         * @param entityToSave
         * @param propNamesToSave
         * @return
         * @throws SQLException the SQL exception
         */
        void save(final T entityToSave, final Collection<String> propNamesToSave) throws SQLException;

        /**
         *
         * @param namedInsertSQL
         * @param entityToSave
         * @return
         * @throws SQLException the SQL exception
         */
        void save(final String namedInsertSQL, final T entityToSave) throws SQLException;

        /**
         * Insert the specified entities to database by batch.
         *
         * @param entitiesToSave
         * @return
         * @throws SQLException the SQL exception
         * @see CrudDao#batchInsert(Collection)
         */
        default void batchSave(final Collection<? extends T> entitiesToSave) throws SQLException {
            batchSave(entitiesToSave, JdbcUtil.DEFAULT_BATCH_SIZE);
        }

        /**
         * Insert the specified entities to database by batch.
         *
         * @param entitiesToSave
         * @param batchSize
         * @return
         * @throws SQLException the SQL exception
         * @see CrudDao#batchInsert(Collection)
         */
        void batchSave(final Collection<? extends T> entitiesToSave, final int batchSize) throws SQLException;

        /**
         * Insert the specified entities to database by batch.
         *
         * @param entitiesToSave
         * @param propNamesToSave
         * @return
         * @throws SQLException the SQL exception
         * @see CrudDao#batchInsert(Collection)
         */
        default void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave) throws SQLException {
            batchSave(entitiesToSave, propNamesToSave, JdbcUtil.DEFAULT_BATCH_SIZE);
        }

        /**
         * Insert the specified entities to database by batch.
         *
         * @param entitiesToSave
         * @param propNamesToSave
         * @param batchSize
         * @return
         * @throws SQLException the SQL exception
         * @see CrudDao#batchInsert(Collection)
         */
        void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave, final int batchSize) throws SQLException;

        /**
         * Insert the specified entities to database by batch.
         *
         * @param namedInsertSQL
         * @param entitiesToSave
         * @return
         * @throws SQLException the SQL exception
         * @see CrudDao#batchInsert(Collection)
         */
        @Beta
        default void batchSave(final String namedInsertSQL, final Collection<? extends T> entitiesToSave) throws SQLException {
            batchSave(namedInsertSQL, entitiesToSave, JdbcUtil.DEFAULT_BATCH_SIZE);
        }

        /**
         * Insert the specified entities to database by batch.
         *
         * @param namedInsertSQL
         * @param entitiesToSave
         * @param batchSize
         * @return
         * @throws SQLException the SQL exception
         * @see CrudDao#batchInsert(Collection)
         */
        @Beta
        void batchSave(final String namedInsertSQL, final Collection<? extends T> entitiesToSave, final int batchSize) throws SQLException;

        /**
         *
         * @param cond
         * @return true, if successful
         * @throws SQLException the SQL exception
         */
        boolean exists(final Condition cond) throws SQLException;

        /**
         *
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        int count(final Condition cond) throws SQLException;

        /**
         *
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        Optional<T> findFirst(final Condition cond) throws SQLException;

        /**
         * @param cond
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> Optional<R> findFirst(final Condition cond, final JdbcUtil.RowMapper<R> rowMapper) throws SQLException;

        /**
         * @param cond
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> Optional<R> findFirst(final Condition cond, final JdbcUtil.BiRowMapper<R> rowMapper) throws SQLException;

        /**
         *
         * @param selectPropNames
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        Optional<T> findFirst(final Collection<String> selectPropNames, final Condition cond) throws SQLException;

        /**
         *
         * @param selectPropNames
         * @param cond
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> Optional<R> findFirst(final Collection<String> selectPropNames, final Condition cond, final JdbcUtil.RowMapper<R> rowMapper) throws SQLException;

        /**
         *
         * @param selectPropNames
         * @param cond
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> Optional<R> findFirst(final Collection<String> selectPropNames, final Condition cond, final JdbcUtil.BiRowMapper<R> rowMapper) throws SQLException;

        /**
         * Query for boolean.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        OptionalBoolean queryForBoolean(final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for char.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        OptionalChar queryForChar(final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for byte.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        OptionalByte queryForByte(final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for short.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        OptionalShort queryForShort(final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for int.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        OptionalInt queryForInt(final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for long.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        OptionalLong queryForLong(final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for float.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        OptionalFloat queryForFloat(final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for double.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        OptionalDouble queryForDouble(final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for string.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        Nullable<String> queryForString(final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for date.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        Nullable<java.sql.Date> queryForDate(final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for time.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        Nullable<java.sql.Time> queryForTime(final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for timestamp.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        Nullable<java.sql.Timestamp> queryForTimestamp(final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for single result.
         *
         * @param <V> the value type
         * @param targetValueClass
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        <V> Nullable<V> queryForSingleResult(final Class<V> targetValueClass, final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for single non null.
         *
         * @param <V> the value type
         * @param targetValueClass
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws DuplicatedResultException if more than one record found.
         * @throws SQLException the SQL exception
         */
        <V> Optional<V> queryForSingleNonNull(final Class<V> targetValueClass, final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for unique result.
         *
         * @param <V> the value type
         * @param targetValueClass
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws DuplicatedResultException if more than one record found.
         * @throws SQLException the SQL exception
         */
        <V> Nullable<V> queryForUniqueResult(final Class<V> targetValueClass, final String singleSelectPropName, final Condition cond)
                throws DuplicatedResultException, SQLException;

        /**
         * Query for unique non null.
         *
         * @param <V> the value type
         * @param targetValueClass
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        <V> Optional<V> queryForUniqueNonNull(final Class<V> targetValueClass, final String singleSelectPropName, final Condition cond)
                throws DuplicatedResultException, SQLException;

        /**
         *
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        DataSet query(final Condition cond) throws SQLException;

        /**
         *
         * @param selectPropNames
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        DataSet query(final Collection<String> selectPropNames, final Condition cond) throws SQLException;

        /**
         *
         * @param cond
         * @param resultExtrator
         * @return
         * @throws SQLException the SQL exception
         */
        <R> R query(final Condition cond, final ResultExtractor<R> resultExtrator) throws SQLException;

        /**
         *
         * @param selectPropNames
         * @param cond
         * @param resultExtrator
         * @return
         * @throws SQLException the SQL exception
         */
        <R> R query(final Collection<String> selectPropNames, final Condition cond, final ResultExtractor<R> resultExtrator) throws SQLException;

        /**
         *
         * @param cond
         * @param resultExtrator
         * @return
         * @throws SQLException the SQL exception
         */
        <R> R query(final Condition cond, final BiResultExtractor<R> resultExtrator) throws SQLException;

        /**
         *
         * @param selectPropNames
         * @param cond
         * @param resultExtrator
         * @return
         * @throws SQLException the SQL exception
         */
        <R> R query(final Collection<String> selectPropNames, final Condition cond, final BiResultExtractor<R> resultExtrator) throws SQLException;

        /**
         *
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        List<T> list(final Condition cond) throws SQLException;

        /**
         *
         * @param cond
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> List<R> list(final Condition cond, final JdbcUtil.RowMapper<R> rowMapper) throws SQLException;

        /**
         *
         * @param cond
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> List<R> list(final Condition cond, final JdbcUtil.BiRowMapper<R> rowMapper) throws SQLException;

        /**
         *
         * @param cond
         * @param rowFilter
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> List<R> list(final Condition cond, final JdbcUtil.RowFilter rowFilter, final JdbcUtil.RowMapper<R> rowMapper) throws SQLException;

        /**
         *
         * @param cond
         * @param rowFilter
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> List<R> list(final Condition cond, final JdbcUtil.BiRowFilter rowFilter, final JdbcUtil.BiRowMapper<R> rowMapper) throws SQLException;

        /**
         *
         * @param selectPropNames
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        List<T> list(final Collection<String> selectPropNames, final Condition cond) throws SQLException;

        /**
         *
         * @param selectPropNames
         * @param cond
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final JdbcUtil.RowMapper<R> rowMapper) throws SQLException;

        /**
         *
         * @param selectPropNames
         * @param cond
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final JdbcUtil.BiRowMapper<R> rowMapper) throws SQLException;

        /**
         *
         * @param selectPropNames
         * @param cond
         * @param rowFilter
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final JdbcUtil.RowFilter rowFilter,
                final JdbcUtil.RowMapper<R> rowMapper) throws SQLException;

        /**
         *
         * @param selectPropNames
         * @param cond
         * @param rowFilter
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final JdbcUtil.BiRowFilter rowFilter,
                final JdbcUtil.BiRowMapper<R> rowMapper) throws SQLException;

        /**
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        default <R> List<R> list(final String singleSelectPropName, final Condition cond) throws SQLException {
            final PropInfo propInfo = ParserUtil.getEntityInfo(targetEntityClass()).getPropInfo(singleSelectPropName);
            final RowMapper<R> rowMapper = propInfo == null ? RowMapper.GET_OBJECT : RowMapper.get((Type<R>) propInfo.dbType);

            return list(singleSelectPropName, cond, rowMapper);
        }

        /**
         *
         * @param singleSelectPropName
         * @param cond
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        default <R> List<R> list(final String singleSelectPropName, final Condition cond, final JdbcUtil.RowMapper<R> rowMapper) throws SQLException {
            return list(N.asList(singleSelectPropName), cond, rowMapper);
        }

        // Will it cause confusion if it's called in transaction?
        /**
         * lazy-execution, lazy-fetch.
         *
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        ExceptionalStream<T, SQLException> stream(final Condition cond) throws SQLException;

        // Will it cause confusion if it's called in transaction?
        /**
         * lazy-execution, lazy-fetch.
         *
         * @param cond
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> ExceptionalStream<R, SQLException> stream(final Condition cond, final JdbcUtil.RowMapper<R> rowMapper) throws SQLException;

        // Will it cause confusion if it's called in transaction?
        /**
         * lazy-execution, lazy-fetch.
         *
         * @param cond
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> ExceptionalStream<R, SQLException> stream(final Condition cond, final JdbcUtil.BiRowMapper<R> rowMapper) throws SQLException;

        /**
         * lazy-execution, lazy-fetch.
         *
         * @param cond
         * @param rowFilter
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> ExceptionalStream<R, SQLException> stream(final Condition cond, final JdbcUtil.RowFilter rowFilter, final JdbcUtil.RowMapper<R> rowMapper)
                throws SQLException;

        /**
         * lazy-execution, lazy-fetch.
         *
         * @param cond
         * @param rowFilter
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> ExceptionalStream<R, SQLException> stream(final Condition cond, final JdbcUtil.BiRowFilter rowFilter, final JdbcUtil.BiRowMapper<R> rowMapper)
                throws SQLException;

        // Will it cause confusion if it's called in transaction?
        /**
         * lazy-execution, lazy-fetch.
         *
         * @param selectPropNames
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        ExceptionalStream<T, SQLException> stream(final Collection<String> selectPropNames, final Condition cond) throws SQLException;

        // Will it cause confusion if it's called in transaction?
        /**
         * lazy-execution, lazy-fetch.
         *
         * @param selectPropNames
         * @param cond
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> ExceptionalStream<R, SQLException> stream(final Collection<String> selectPropNames, final Condition cond, final JdbcUtil.RowMapper<R> rowMapper)
                throws SQLException;

        // Will it cause confusion if it's called in transaction?
        /**
         * lazy-execution, lazy-fetch.
         *
         * @param selectPropNames
         * @param cond
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> ExceptionalStream<R, SQLException> stream(final Collection<String> selectPropNames, final Condition cond, final JdbcUtil.BiRowMapper<R> rowMapper)
                throws SQLException;

        // Will it cause confusion if it's called in transaction?
        /**
         * lazy-execution, lazy-fetch.
         *
         * @param selectPropNames
         * @param cond
         * @param rowFilter
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> ExceptionalStream<R, SQLException> stream(final Collection<String> selectPropNames, final Condition cond, JdbcUtil.RowFilter rowFilter,
                final JdbcUtil.RowMapper<R> rowMapper) throws SQLException;

        // Will it cause confusion if it's called in transaction?
        /**
         * lazy-execution, lazy-fetch.
         *
         * @param selectPropNames
         * @param cond
         * @param rowFilter
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> ExceptionalStream<R, SQLException> stream(final Collection<String> selectPropNames, final Condition cond, final JdbcUtil.BiRowFilter rowFilter,
                final JdbcUtil.BiRowMapper<R> rowMapper) throws SQLException;

        /**
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        default <R> ExceptionalStream<R, SQLException> stream(final String singleSelectPropName, final Condition cond) throws SQLException {
            final PropInfo propInfo = ParserUtil.getEntityInfo(targetEntityClass()).getPropInfo(singleSelectPropName);
            final RowMapper<R> rowMapper = propInfo == null ? RowMapper.GET_OBJECT : RowMapper.get((Type<R>) propInfo.dbType);

            return stream(singleSelectPropName, cond, rowMapper);
        }

        /**
         *
         * @param singleSelectPropName
         * @param cond
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        default <R> ExceptionalStream<R, SQLException> stream(final String singleSelectPropName, final Condition cond, final JdbcUtil.RowMapper<R> rowMapper)
                throws SQLException {
            return stream(N.asList(singleSelectPropName), cond, rowMapper);
        }

        /**
         *
         * @param propName
         * @param propValue
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        default int update(final String propName, final Object propValue, final Condition cond) throws SQLException {
            final Map<String, Object> updateProps = new HashMap<>();
            updateProps.put(propName, propValue);

            return update(updateProps, cond);
        }

        /**
         *
         * @param updateProps
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        int update(final Map<String, Object> updateProps, final Condition cond) throws SQLException;

        /**
         *
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        int delete(final Condition cond) throws SQLException;

        /**
         * Be careful to call asynchronized methods in transaction because Transaction is created on thread level.
         * If the asynchronized method is called in transaction, it won't be executed under the transaction.
         *
         * @param <R>
         * @param func
         * @return
         */
        @Beta
        @NonDBOperation
        default <R> ContinuableFuture<R> asyncCall(final Throwables.Function<TD, R, SQLException> func) {
            return asyncCall(func, executor());
        }

        /**
         * Be careful to call asynchronized methods in transaction because Transaction is created on thread level.
         * If the asynchronized method is called in transaction, it won't be executed under the transaction.
         *
         *
         * @param <R>
         * @param func
         * @param executor
         * @return
         */
        @Beta
        @NonDBOperation
        default <R> ContinuableFuture<R> asyncCall(final Throwables.Function<TD, R, SQLException> func, final Executor executor) {
            N.checkArgNotNull(func, "func");
            N.checkArgNotNull(executor, "executor");

            final TD tdao = (TD) this;

            return ContinuableFuture.call(() -> func.apply(tdao), executor);
        }

        /**
         * Be careful to call asynchronized methods in transaction because Transaction is created on thread level.
         * If the asynchronized method is called in transaction, it won't be executed under the transaction.
         *
         *
         * @param action
         * @return
         */
        @Beta
        @NonDBOperation
        default ContinuableFuture<Void> asyncRun(final Throwables.Consumer<TD, SQLException> action) {
            return asyncRun(action, executor());
        }

        /**
         * Be careful to call asynchronized methods in transaction because Transaction is created on thread level.
         * If the asynchronized method is called in transaction, it won't be executed under the transaction.
         *
         *
         * @param action
         * @param executor
         * @return
         */
        @Beta
        @NonDBOperation
        default ContinuableFuture<Void> asyncRun(final Throwables.Consumer<TD, SQLException> action, final Executor executor) {
            N.checkArgNotNull(action, "action");
            N.checkArgNotNull(executor, "executor");

            final TD tdao = (TD) this;

            return ContinuableFuture.run(() -> action.accept(tdao), executor);
        }
    }

    /**
     * The Interface CrudDao.
     *
     * @param <T>
     * @param <ID> use {@code Void} if there is no id defined/annotated with {@code @Id} in target entity class {@code T}.
     * @param <SB> {@code SQLBuilder} used to generate sql scripts. Only can be {@code SQLBuilder.PSC/PAC/PLC}
     * @see JdbcUtil#prepareQuery(javax.sql.DataSource, String)
     * @see JdbcUtil#prepareNamedQuery(javax.sql.DataSource, String)
     * @see JdbcUtil#beginTransaction(javax.sql.DataSource, IsolationLevel, boolean)
     * @see Dao
     * @see SQLExecutor.Mapper
     * @see com.landawn.abacus.condition.ConditionFactory
     * @see com.landawn.abacus.condition.ConditionFactory.CF
     */
    public interface CrudDao<T, ID, SB extends SQLBuilder, TD extends CrudDao<T, ID, SB, TD>> extends Dao<T, SB, TD> {

        /**
         *
         * @param entityToInsert
         * @return
         * @throws SQLException the SQL exception
         */
        ID insert(final T entityToInsert) throws SQLException;

        /**
         *
         * @param entityToInsert
         * @param propNamesToInsert
         * @return
         * @throws SQLException the SQL exception
         */
        ID insert(final T entityToInsert, final Collection<String> propNamesToInsert) throws SQLException;

        /**
         *
         * @param namedInsertSQL
         * @param entityToInsert
         * @return
         * @throws SQLException the SQL exception
         */
        ID insert(final String namedInsertSQL, final T entityToInsert) throws SQLException;

        /**
         *
         * @param entities
         * @return
         * @throws SQLException the SQL exception
         */
        default List<ID> batchInsert(final Collection<? extends T> entities) throws SQLException {
            return batchInsert(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param entities
         * @param batchSize
         * @return
         * @throws SQLException the SQL exception
         */
        List<ID> batchInsert(final Collection<? extends T> entities, final int batchSize) throws SQLException;

        /**
         *
         * @param entities
         * @param propNamesToInsert
         * @return
         * @throws SQLException the SQL exception
         */
        default List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert) throws SQLException {
            return batchInsert(entities, propNamesToInsert, JdbcUtil.DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param entities
         * @param propNamesToInsert
         * @param batchSize
         * @return
         * @throws SQLException the SQL exception
         */
        List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert, final int batchSize) throws SQLException;

        /**
         *
         * @param namedInsertSQL
         * @param entities
         * @return
         * @throws SQLException the SQL exception
         */
        @Beta
        default List<ID> batchInsert(final String namedInsertSQL, final Collection<? extends T> entities) throws SQLException {
            return batchInsert(namedInsertSQL, entities, JdbcUtil.DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param namedInsertSQL
         * @param entities
         * @param batchSize
         * @return
         * @throws SQLException the SQL exception
         */
        @Beta
        List<ID> batchInsert(final String namedInsertSQL, final Collection<? extends T> entities, final int batchSize) throws SQLException;

        /**
         *
         * @param id
         * @return
         * @throws SQLException the SQL exception
         */
        default Optional<T> get(final ID id) throws SQLException {
            return Optional.ofNullable(gett(id));
        }

        /**
         *
         * @param id
         * @param selectPropNames
         * @return
         * @throws SQLException the SQL exception
         */
        default Optional<T> get(final ID id, final Collection<String> selectPropNames) throws SQLException {
            return Optional.ofNullable(gett(id, selectPropNames));
        }

        /**
         *
         * @param id
         * @param includeAllJoinEntities
         * @return
         * @throws SQLException the SQL exception
         */
        default Optional<T> get(final ID id, final boolean includeAllJoinEntities) throws SQLException {
            return Optional.ofNullable(gett(id, includeAllJoinEntities));
        }

        /**
         * 
         * @param id
         * @param selectPropNames
         * @param includeAllJoinEntities
         * @return
         * @throws SQLException
         */
        default Optional<T> get(final ID id, final Collection<String> selectPropNames, final boolean includeAllJoinEntities) throws SQLException {
            return Optional.ofNullable(gett(id, selectPropNames, includeAllJoinEntities));
        }

        /**
         *
         * @param id
         * @param joinEntitiesToLoad
         * @return
         * @throws SQLException the SQL exception
         */
        default Optional<T> get(final ID id, final Class<?> joinEntitiesToLoad) throws SQLException {
            return Optional.ofNullable(gett(id, joinEntitiesToLoad));
        }

        /**
         * 
         * @param id
         * @param selectPropNames
         * @param joinEntitiesToLoad
         * @return
         * @throws SQLException
         */
        default Optional<T> get(final ID id, final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad) throws SQLException {
            return Optional.ofNullable(gett(id, selectPropNames, joinEntitiesToLoad));
        }

        /**
         * 
         * @param id
         * @param selectPropNames
         * @param joinEntitiesToLoad
         * @return
         * @throws SQLException
         */
        default Optional<T> get(final ID id, final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad) throws SQLException {
            return Optional.ofNullable(gett(id, selectPropNames, joinEntitiesToLoad));
        }

        /**
         * Gets the t.
         *
         * @param id
         * @return
         * @throws SQLException the SQL exception
         */
        T gett(final ID id) throws SQLException;

        /**
         * Gets the t.
         * @param id
         * @param selectPropNames
         *
         * @return
         * @throws SQLException the SQL exception
         */
        T gett(final ID id, final Collection<String> selectPropNames) throws SQLException;

        /**
         *
         * @param id
         * @param includeAllJoinEntities
         * @return
         * @throws SQLException the SQL exception
         */
        default T gett(final ID id, final boolean includeAllJoinEntities) throws SQLException {
            final T result = gett(id);

            if (result != null && includeAllJoinEntities) {
                checkJoinEntityHelper(this).loadAllJoinEntities(result);
            }

            return result;
        }

        /**
         * 
         * @param id
         * @param selectPropNames
         * @param includeAllJoinEntities
         * @return
         * @throws SQLException
         */
        default T gett(final ID id, final Collection<String> selectPropNames, final boolean includeAllJoinEntities) throws SQLException {
            final T result = gett(id, selectPropNames);

            if (result != null && includeAllJoinEntities) {
                checkJoinEntityHelper(this).loadAllJoinEntities(result);
            }

            return result;
        }

        /**
         *
         * @param id
         * @param joinEntitiesToLoad
         * @return
         * @throws SQLException the SQL exception
         */
        default T gett(final ID id, final Class<?> joinEntitiesToLoad) throws SQLException {
            final T result = gett(id);

            if (result != null) {
                checkJoinEntityHelper(this).loadJoinEntities(result, joinEntitiesToLoad);
            }

            return result;
        }

        /**
         * 
         * @param id
         * @param selectPropNames
         * @param joinEntitiesToLoad
         * @return
         * @throws SQLException
         */
        default T gett(final ID id, final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad) throws SQLException {
            final T result = gett(id, selectPropNames);

            if (result != null) {
                checkJoinEntityHelper(this).loadJoinEntities(result, joinEntitiesToLoad);
            }

            return result;
        }

        /**
         * 
         * @param id
         * @param selectPropNames
         * @param joinEntitiesToLoad
         * @return
         * @throws SQLException
         */
        default T gett(final ID id, final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad) throws SQLException {
            final T result = gett(id, selectPropNames);

            if (result != null) {
                final JoinEntityHelper<T, SB, TD> joinEntityHelper = checkJoinEntityHelper(this);

                for (Class<?> joinEntityClass : joinEntitiesToLoad) {
                    joinEntityHelper.loadJoinEntities(result, joinEntityClass);
                }
            }

            return result;
        }

        /**
         *
         *
         * @param ids
         * @return
         * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}.
         * @throws SQLException the SQL exception
         */
        default List<T> batchGet(final Collection<? extends ID> ids) throws DuplicatedResultException, SQLException {
            return batchGet(ids, (Collection<String>) null);
        }

        /**
         *
         *
         * @param ids
         * @param selectPropNames
         * @return
         * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}.
         * @throws SQLException the SQL exception
         */
        default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames) throws DuplicatedResultException, SQLException {
            return batchGet(ids, selectPropNames, JdbcUtil.DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param ids
         * @param selectPropNames
         * @param batchSize
         * @return
         * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}.
         * @throws SQLException the SQL exception
         */
        List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final int batchSize)
                throws DuplicatedResultException, SQLException;

        /**
         *
         * @param id
         * @return true, if successful
         * @throws SQLException the SQL exception
         */
        boolean exists(final ID id) throws SQLException;

        /**
         *
         * @param entityToUpdate
         * @return
         * @throws SQLException the SQL exception
         */
        int update(final T entityToUpdate) throws SQLException;

        /**
         *
         * @param entityToUpdate
         * @param propNamesToUpdate
         * @return
         * @throws SQLException the SQL exception
         */
        int update(final T entityToUpdate, final Collection<String> propNamesToUpdate) throws SQLException;

        /**
        *
        * @param propName
        * @param propValue
        * @param id
        * @return
        * @throws SQLException the SQL exception
        */
        default int update(final String propName, final Object propValue, final ID id) throws SQLException {
            final Map<String, Object> updateProps = new HashMap<>();
            updateProps.put(propName, propValue);

            return update(updateProps, id);
        }

        /**
         *
         * @param updateProps
         * @param id
         * @return
         * @throws SQLException the SQL exception
         */
        int update(final Map<String, Object> updateProps, final ID id) throws SQLException;

        /**
         *
         * @param entities
         * @return
         * @throws SQLException the SQL exception
         */
        default int batchUpdate(final Collection<? extends T> entities) throws SQLException {
            return batchUpdate(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param entities
         * @param batchSize
         * @return
         * @throws SQLException the SQL exception
         */
        int batchUpdate(final Collection<? extends T> entities, final int batchSize) throws SQLException;

        /**
         *
         * @param entities
         * @param propNamesToUpdate
         * @return
         * @throws SQLException the SQL exception
         */
        default int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate) throws SQLException {
            return batchUpdate(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param entities
         * @param propNamesToUpdate
         * @param batchSize
         * @return
         * @throws SQLException the SQL exception
         */
        int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate, final int batchSize) throws SQLException;

        /**
         * Execute {@code add} and return the added entity if the record doesn't, otherwise, {@code update} is executed and updated db record is returned.
         *
         * @param entity
         * @param whereCause to verify if the record exists or not.
         * @return
         */
        default T upsert(final T entity, final Condition whereCause) throws SQLException {
            N.checkArgNotNull(whereCause, "whereCause");

            final T dbEntity = findFirst(whereCause).orNull();

            if (dbEntity == null) {
                insert(entity);
                return entity;
            } else {
                @SuppressWarnings("deprecation")
                final List<String> idPropNameList = ClassUtil.getIdFieldNames(targetEntityClass());
                N.merge(entity, dbEntity, false, N.newHashSet(idPropNameList));
                update(dbEntity);
                return dbEntity;
            }
        }

        /**
         * Execute {@code add} and return the added entity if the record doesn't, otherwise, {@code update} is executed and updated db record is returned.
         *
         * @param entity
         * @return
         */
        default T upsert(final T entity) throws SQLException {
            @SuppressWarnings("deprecation")
            final List<String> idPropNameList = ClassUtil.getIdFieldNames(targetEntityClass());
            final T dbEntity = idPropNameList.size() == 1 ? gett((ID) ClassUtil.getPropValue(entity, idPropNameList.get(0))) : gett((ID) entity);

            if (dbEntity == null) {
                insert(entity);
                return entity;
            } else {
                N.merge(entity, dbEntity, false, N.newHashSet(idPropNameList));
                update(dbEntity);
                return dbEntity;
            }
        }

        /**
         *
         * @param entity
         * @return true, if successful
         * @throws SQLException
         */
        default boolean refresh(final T entity) throws SQLException {
            final Class<?> cls = entity.getClass();
            final Collection<String> propNamesToRefresh = DirtyMarkerUtil.isDirtyMarker(cls) ? DirtyMarkerUtil.signedPropNames((DirtyMarker) entity)
                    : SQLBuilder.getSelectPropNamesByClass(cls, false, null);

            return refresh(entity, propNamesToRefresh);
        }

        /**
         *
         * @param entity
         * @param propNamesToRefresh
         * @return {@code false} if no record found by the ids in the specified {@code entity}.
         * @throws SQLException
         */
        @SuppressWarnings("deprecation")
        default boolean refresh(final T entity, Collection<String> propNamesToRefresh) throws SQLException {
            final Class<?> cls = entity.getClass();
            final List<String> idPropNameList = ClassUtil.getIdFieldNames(cls); // must not empty.
            final EntityInfo entityInfo = ParserUtil.getEntityInfo(cls);

            ID id = null;

            if (idPropNameList.size() == 1) {
                id = entityInfo.getPropInfo(idPropNameList.get(0)).getPropValue(entity);

            } else {
                Seid entityId = Seid.of(ClassUtil.getSimpleClassName(cls));

                for (String idPropName : idPropNameList) {
                    entityId.set(idPropName, entityInfo.getPropInfo(idPropName).getPropValue(entity));
                }

                id = (ID) entityId;
            }

            if (N.isNullOrEmpty(propNamesToRefresh)) {
                return exists(id);
            }

            final T dbEntity = gett(id, propNamesToRefresh);

            if (dbEntity == null) {
                return false;
            } else {
                N.merge(dbEntity, entity, propNamesToRefresh);

                if (DirtyMarkerUtil.isDirtyMarker(cls)) {
                    DirtyMarkerUtil.markDirty((DirtyMarker) entity, propNamesToRefresh, false);
                }

                return true;
            }
        }

        /**
         * Delete by id.
         *
         * @param id
         * @return
         * @throws SQLException the SQL exception
         */
        int deleteById(final ID id) throws SQLException;

        /**
         *
         * @param entity
         * @return
         * @throws SQLException the SQL exception
         */
        int delete(final T entity) throws SQLException;
        //
        //    /**
        //     *
        //     * @param entity
        //     * @param onDeleteAction It should be defined and done in DB server side.
        //     * @return
        //     * @throws SQLException the SQL exception
        //     */
        //    @Beta
        //    int delete(final T entity, final OnDeleteAction onDeleteAction) throws SQLException;

        /**
         *
         * @param entities
         * @return
         * @throws SQLException the SQL exception
         */
        default int batchDelete(final Collection<? extends T> entities) throws SQLException {
            return batchDelete(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param entities
         * @param batchSize
         * @return
         * @throws SQLException the SQL exception
         */
        int batchDelete(final Collection<? extends T> entities, final int batchSize) throws SQLException;

        //    /**
        //     *
        //     * @param entities
        //     * @param onDeleteAction It should be defined and done in DB server side.
        //     * @return
        //     * @throws SQLException the SQL exception
        //     */
        //    @Beta
        //    default int batchDelete(final Collection<? extends T> entities, final OnDeleteAction onDeleteAction) throws SQLException {
        //        return batchDelete(entities, onDeleteAction, JdbcUtil.DEFAULT_BATCH_SIZE);
        //    }
        //
        //    /**
        //     *
        //     * @param entities
        //     * @param onDeleteAction It should be defined and done in DB server side.
        //     * @param batchSize
        //     * @return
        //     * @throws SQLException the SQL exception
        //     */
        //    @Beta
        //    int batchDelete(final Collection<? extends T> entities, final OnDeleteAction onDeleteAction, final int batchSize) throws SQLException;

        /**
         *
         * @param ids
         * @return
         * @throws SQLException the SQL exception
         */
        default int batchDeleteByIds(final Collection<? extends ID> ids) throws SQLException {
            return batchDeleteByIds(ids, JdbcUtil.DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param ids
         * @param batchSize
         * @return
         * @throws SQLException the SQL exception
         */
        int batchDeleteByIds(final Collection<? extends ID> ids, final int batchSize) throws SQLException;
    }

    /**
     *  
     *
     * @param <T>
     * @param <SB>
     * @param <TD>
     */
    @Beta
    public static interface CrudDaoL<T, SB extends SQLBuilder, TD extends CrudDaoL<T, SB, TD>> extends CrudDao<T, Long, SB, TD> {

        default Optional<T> get(final long id) throws SQLException {
            return get(Long.valueOf(id));
        }

        default Optional<T> get(final long id, final Collection<String> selectPropNames) throws SQLException {
            return get(Long.valueOf(id), selectPropNames);
        }

        default T gett(final long id) throws SQLException {
            return gett(Long.valueOf(id));
        }

        default T gett(final long id, final Collection<String> selectPropNames) throws SQLException {
            return gett(Long.valueOf(id), selectPropNames);
        }

        default boolean exists(final long id) throws SQLException {
            return exists(Long.valueOf(id));
        }

        default int update(final String propName, final Object propValue, final long id) throws SQLException {
            return update(propName, propValue, Long.valueOf(id));
        }

        default int update(final Map<String, Object> updateProps, final long id) throws SQLException {
            return update(updateProps, Long.valueOf(id));
        }

        default int deleteById(final long id) throws SQLException {
            return deleteById(Long.valueOf(id));
        }
    }

    /**
     * TODO
     *
     * @param <T>
     * @param <SB>
     * @param <TD>
     */
    @Beta
    public static interface NoUpdateDao<T, SB extends SQLBuilder, TD extends NoUpdateDao<T, SB, TD>> extends Dao<T, SB, TD> {

        /**
         *
         * @param propName
         * @param propValue
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         * @deprecated unsupported Operation
         */
        @Override
        @Deprecated
        default int update(final String propName, final Object propValue, final Condition cond) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param updateProps
         * @param cond
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int update(final Map<String, Object> updateProps, final Condition cond) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param cond
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int delete(final Condition cond) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * TODO
     *
     * @param <T>
     * @param <SB>
     * @param <TD>
     */
    @Beta
    public static interface ReadOnlyDao<T, SB extends SQLBuilder, TD extends ReadOnlyDao<T, SB, TD>> extends NoUpdateDao<T, SB, TD> {

        /**
         *
         * @param entityToSave
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default void save(final T entityToSave) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entityToSave
         * @param propNamesToSave
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default void save(final T entityToSave, final Collection<String> propNamesToSave) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param namedInsertSQL
         * @param entityToSave
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default void save(final String namedInsertSQL, final T entityToSave) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Always throws {@code UnsupportedOperationException}.
         *
         * @param entitiesToSave
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default void batchSave(final Collection<? extends T> entitiesToSave) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Always throws {@code UnsupportedOperationException}.
         *
         * @param entitiesToSave
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default void batchSave(final Collection<? extends T> entitiesToSave, final int batchSize) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Always throws {@code UnsupportedOperationException}.
         *
         * @param entitiesToSave
         * @param propNamesToSave
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave)
                throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Always throws {@code UnsupportedOperationException}.
         *
         * @param entitiesToSave
         * @param propNamesToSave
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave, final int batchSize)
                throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Always throws {@code UnsupportedOperationException}.
         *
         * @param namedInsertSQL
         * @param entitiesToSave
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default void batchSave(final String namedInsertSQL, final Collection<? extends T> entitiesToSave) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Always throws {@code UnsupportedOperationException}.
         *
         * @param namedInsertSQL
         * @param entitiesToSave
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default void batchSave(final String namedInsertSQL, final Collection<? extends T> entitiesToSave, final int batchSize)
                throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * TODO
     *
     * @param <T>
     * @param <ID>
     * @param <SB>
     * @param <TD>
     */
    @Beta
    public static interface NoUpdateCrudDao<T, ID, SB extends SQLBuilder, TD extends NoUpdateCrudDao<T, ID, SB, TD>>
            extends NoUpdateDao<T, SB, TD>, CrudDao<T, ID, SB, TD> {

        /**
         *
         * @param entityToUpdate
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int update(final T entityToUpdate) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entityToUpdate
         * @param propNamesToUpdate
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int update(final T entityToUpdate, final Collection<String> propNamesToUpdate) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param propName
         * @param propValue
         * @param id
         * @return
         * @throws SQLException the SQL exception
         * @deprecated unsupported Operation
         */
        @Override
        @Deprecated
        default int update(final String propName, final Object propValue, final ID id) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param updateProps
         * @param id
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int update(final Map<String, Object> updateProps, final ID id) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int batchUpdate(final Collection<? extends T> entities) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int batchUpdate(final Collection<? extends T> entities, final int batchSize) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param propNamesToUpdate
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate)
                throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param propNamesToUpdate
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate, final int batchSize)
                throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Execute {@code add} and return the added entity if the record doesn't, otherwise, {@code update} is executed and updated db record is returned.
         *
         * @param entity
         * @param whereCause to verify if the record exists or not.
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default T upsert(final T entity, final Condition whereCause) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Execute {@code add} and return the added entity if the record doesn't, otherwise, {@code update} is executed and updated db record is returned.
         *
         * @param entity
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default T upsert(final T entity) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Delete by id.
         *
         * @param id
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteById(final ID id) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entity
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int delete(final T entity) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        //    /**
        //     *
        //     * @param entity
        //     * @param onDeleteAction
        //     * @return
        //     * @throws UnsupportedOperationException
        //     * @throws SQLException
        //     * @deprecated unsupported Operation
        //     */
        //    @Deprecated
        //    @Override
        //    default int delete(final T entity, final OnDeleteAction onDeleteAction) throws UnsupportedOperationException, SQLException {
        //        throw new UnsupportedOperationException();
        //    }

        /**
         *
         * @param entities
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int batchDelete(final Collection<? extends T> entities) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int batchDelete(final Collection<? extends T> entities, final int batchSize) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        //    /**
        //     *
        //     * @param entities
        //     * @param onDeleteAction
        //     * @return
        //     * @throws UnsupportedOperationException
        //     * @throws SQLException
        //     * @deprecated unsupported Operation
        //     */
        //    @Deprecated
        //    @Override
        //    default int batchDelete(final Collection<? extends T> entities, final OnDeleteAction onDeleteAction)
        //            throws UnsupportedOperationException, SQLException {
        //        throw new UnsupportedOperationException();
        //    }
        //
        //    /**
        //     *
        //     * @param entities
        //     * @param onDeleteAction
        //     * @param batchSize
        //     * @return
        //     * @throws UnsupportedOperationException
        //     * @throws SQLException
        //     * @deprecated unsupported Operation
        //     */
        //    @Deprecated
        //    @Override
        //    default int batchDelete(final Collection<? extends T> entities, final OnDeleteAction onDeleteAction, final int batchSize)
        //            throws UnsupportedOperationException, SQLException {
        //        throw new UnsupportedOperationException();
        //    }

        /**
         *
         * @param ids
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int batchDeleteByIds(final Collection<? extends ID> ids) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param ids
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int batchDeleteByIds(final Collection<? extends ID> ids, final int batchSize) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * TODO
     *
     * @param <T>
     * @param <ID>
     * @param <SB>
     * @param <TD>
     */
    @Beta
    public static interface ReadOnlyCrudDao<T, ID, SB extends SQLBuilder, TD extends ReadOnlyCrudDao<T, ID, SB, TD>>
            extends ReadOnlyDao<T, SB, TD>, NoUpdateCrudDao<T, ID, SB, TD> {

        /**
         *
         * @param entityToInsert
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default ID insert(final T entityToInsert) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entityToInsert
         * @param propNamesToInsert
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default ID insert(final T entityToInsert, final Collection<String> propNamesToInsert) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param namedInsertSQL
         * @param entityToSave
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default ID insert(final String namedInsertSQL, final T entityToSave) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default List<ID> batchInsert(final Collection<? extends T> entities) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default List<ID> batchInsert(final Collection<? extends T> entities, final int batchSize) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param propNamesToInsert
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert)
                throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param propNamesToInsert
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert, final int batchSize)
                throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param namedInsertSQL
         * @param entities
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default List<ID> batchInsert(final String namedInsertSQL, final Collection<? extends T> entities) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param namedInsertSQL
         * @param entities
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default List<ID> batchInsert(final String namedInsertSQL, final Collection<? extends T> entities, final int batchSize)
                throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }
    }

    static Object[] getParameterArray(final SP sp) {
        return N.isNullOrEmpty(sp.parameters) ? N.EMPTY_OBJECT_ARRAY : sp.parameters.toArray();
    }

    static <R> BiRowMapper<R> toBiRowMapper(final RowMapper<R> rowMapper) {
        return (rs, columnLabels) -> rowMapper.apply(rs);
    }

    static final Throwables.Consumer<? super Exception, SQLException> throwSQLExceptionAction = e -> {
        if (e instanceof SQLException) {
            throw (SQLException) e;
        } else if (e.getCause() != null && e.getCause() instanceof SQLException) {
            throw (SQLException) e.getCause();
        } else {
            throw N.toRuntimeException(e);
        }
    };

    static void complete(final List<ContinuableFuture<Void>> futures) throws SQLException {
        for (ContinuableFuture<Void> f : futures) {
            f.gett().ifFailure(throwSQLExceptionAction);
        }
    }

    static int completeSum(final List<ContinuableFuture<Integer>> futures) throws SQLException {
        int result = 0;
        Result<Integer, Exception> ret = null;

        for (ContinuableFuture<Integer> f : futures) {
            ret = f.gett();

            if (ret.isFailure()) {
                throwSQLExceptionAction.accept(ret.getExceptionIfPresent());
            }

            result += ret.orElse(0);
        }

        return result;
    }

    @SuppressWarnings("rawtypes")
    private static final Map<Class<?>, Map<NamingPolicy, Tuple3<BiRowMapper, Function, BiConsumer>>> idGeneratorGetterSetterPool = new ConcurrentHashMap<>();

    @SuppressWarnings("rawtypes")
    private static final Tuple3<BiRowMapper, Function, BiConsumer> noIdGeneratorGetterSetter = Tuple.of(NO_BI_GENERATED_KEY_EXTRACTOR, entity -> null,
            BiConsumers.doNothing());

    @SuppressWarnings({ "rawtypes", "deprecation" })
    static <ID> Tuple3<BiRowMapper<ID>, Function<Object, ID>, BiConsumer<ID, Object>> getIdGeneratorGetterSetter(final Class<?> entityClass,
            final NamingPolicy namingPolicy) {
        if (entityClass == null || !ClassUtil.isEntity(entityClass)) {
            return (Tuple3) noIdGeneratorGetterSetter;
        }

        Map<NamingPolicy, Tuple3<BiRowMapper, Function, BiConsumer>> map = idGeneratorGetterSetterPool.get(entityClass);

        if (map == null) {
            final List<String> idPropNameList = ClassUtil.getIdFieldNames(entityClass);
            final boolean isNoId = N.isNullOrEmpty(idPropNameList) || ClassUtil.isFakeId(idPropNameList);
            final String oneIdPropName = isNoId ? null : idPropNameList.get(0);
            final EntityInfo entityInfo = isNoId ? null : ParserUtil.getEntityInfo(entityClass);
            final List<PropInfo> idPropInfoList = isNoId ? null : Stream.of(idPropNameList).map(entityInfo::getPropInfo).toList();
            final PropInfo idPropInfo = isNoId ? null : entityInfo.getPropInfo(oneIdPropName);
            final boolean isOneId = isNoId ? false : idPropNameList.size() == 1;

            final Function<Object, ID> idGetter = isNoId ? noIdGeneratorGetterSetter._2 //
                    : (isOneId ? entity -> idPropInfo.getPropValue(entity) //
                            : entity -> {
                                final Seid seid = Seid.of(ClassUtil.getSimpleClassName(entityClass));

                                for (PropInfo propInfo : idPropInfoList) {
                                    seid.set(propInfo.name, propInfo.getPropValue(entity));
                                }

                                return (ID) seid;
                            });

            final BiConsumer<ID, Object> idSetter = isNoId ? noIdGeneratorGetterSetter._3 //
                    : (isOneId ? (id, entity) -> idPropInfo.setPropValue(entity, id) //
                            : (id, entity) -> {
                                if (id instanceof EntityId) {
                                    final EntityId entityId = (EntityId) id;
                                    PropInfo propInfo = null;

                                    for (String propName : entityId.keySet()) {
                                        propInfo = entityInfo.getPropInfo(propName);

                                        if ((propInfo = entityInfo.getPropInfo(propName)) != null) {
                                            propInfo.setPropValue(entity, entityId.get(propName));
                                        }
                                    }
                                } else {
                                    logger.warn("Can't set generated keys by id type: " + ClassUtil.getCanonicalClassName(id.getClass()));
                                }
                            });

            map = new EnumMap<>(NamingPolicy.class);

            for (NamingPolicy np : NamingPolicy.values()) {
                final ImmutableMap<String, String> propColumnNameMap = SQLBuilder.getPropColumnNameMap(entityClass, namingPolicy);

                final ImmutableMap<String, String> columnPropNameMap = EntryStream.of(propColumnNameMap)
                        .inversed()
                        .flattMapKey(e -> N.asList(e, e.toLowerCase(), e.toUpperCase()))
                        .distinctByKey()
                        .toImmutableMap();

                final BiRowMapper<Object> keyExtractor = isNoId ? noIdGeneratorGetterSetter._1 //
                        : (isOneId ? (rs, columnLabels) -> idPropInfo.dbType.get(rs, 1) //
                                : (rs, columnLabels) -> {
                                    if (columnLabels.size() == 1) {
                                        return idPropInfo.dbType.get(rs, 1);
                                    } else {
                                        final int columnCount = columnLabels.size();
                                        final Seid id = Seid.of(ClassUtil.getSimpleClassName(entityClass));
                                        String columnName = null;
                                        String propName = null;
                                        PropInfo propInfo = null;

                                        for (int i = 1; i <= columnCount; i++) {
                                            columnName = columnLabels.get(i - 1);

                                            if ((propName = columnPropNameMap.get(columnName)) == null
                                                    || (propInfo = entityInfo.getPropInfo(propName)) == null) {
                                                id.set(columnName, getColumnValue(rs, i));
                                            } else {
                                                id.set(propInfo.name, propInfo.dbType.get(rs, i));
                                            }
                                        }

                                        return id;
                                    }
                                });

                map.put(np, Tuple.of(keyExtractor, idGetter, idSetter));
            }

            idGeneratorGetterSetterPool.put(entityClass, map);
        }

        return (Tuple3) map.get(namingPolicy);
    }

    public static interface JoinEntityHelper<T, SB extends SQLBuilder, TD extends Dao<T, SB, TD>> extends Dao<T, SB, TD> {

        /**
         *
         * @return
         * @deprecated internal only
         */
        @Deprecated
        @Internal
        @NonDBOperation
        Class<T> targetDaoInterface();

        /**
         *
         * @param entity
         * @param joinEntityClass
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntities(final T entity, final Class<?> joinEntityClass) throws SQLException {
            loadJoinEntities(entity, joinEntityClass, null);
        }

        /**
         *
         * @param entity
         * @param joinEntityClass
         * @param selectPropNames
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntities(final T entity, final Class<?> joinEntityClass, final Collection<String> selectPropNames) throws SQLException {
            final Class<?> targetEntityClass = targetEntityClass();
            final List<String> joinEntityPropNames = getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, joinEntityClass);
            N.checkArgument(N.notNullOrEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

            for (String joinEntityPropName : joinEntityPropNames) {
                loadJoinEntities(entity, joinEntityPropName, selectPropNames);
            }
        }

        /**
         *
         * @param entities
         * @param joinEntityClass
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntities(final Collection<T> entities, final Class<?> joinEntityClass) throws SQLException {
            loadJoinEntities(entities, joinEntityClass, null);
        }

        /**
         *
         * @param entities
         * @param joinEntityClass
         * @param selectPropNames
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntities(final Collection<T> entities, final Class<?> joinEntityClass, final Collection<String> selectPropNames)
                throws SQLException {
            if (N.isNullOrEmpty(entities)) {
                return;
            }

            final Class<?> targetEntityClass = targetEntityClass();
            final List<String> joinEntityPropNames = getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, joinEntityClass);
            N.checkArgument(N.notNullOrEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

            for (String joinEntityPropName : joinEntityPropNames) {
                loadJoinEntities(entities, joinEntityPropName, selectPropNames);
            }
        }

        /**
         *
         * @param entity
         * @param joinEntityPropName
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntities(final T entity, final String joinEntityPropName) throws SQLException {
            loadJoinEntities(entity, joinEntityPropName, null);
        }

        /**
         *
         * @param entity
         * @param joinEntityPropName
         * @param selectPropNames
         * @throws SQLException the SQL exception
         */
        void loadJoinEntities(final T entity, final String joinEntityPropName, final Collection<String> selectPropNames) throws SQLException;

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntities(final Collection<T> entities, final String joinEntityPropName) throws SQLException {
            loadJoinEntities(entities, joinEntityPropName, null);
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param selectPropNames
         * @throws SQLException the SQL exception
         */
        void loadJoinEntities(final Collection<T> entities, final String joinEntityPropName, final Collection<String> selectPropNames) throws SQLException;

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntities(final T entity, final Collection<String> joinEntityPropNames) throws SQLException {
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
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final boolean inParallel) throws SQLException {
            if (inParallel) {
                loadJoinEntities(entity, joinEntityPropNames, executor());
            } else {
                loadJoinEntities(entity, joinEntityPropNames);
            }
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @param executor
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final Executor executor) throws SQLException {
            if (N.isNullOrEmpty(joinEntityPropNames)) {
                return;
            }

            final List<ContinuableFuture<Void>> futures = StreamE.of(joinEntityPropNames, SQLException.class)
                    .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntities(entity, joinEntityPropName), executor))
                    .toList();

            JdbcUtil.complete(futures);
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames) throws SQLException {
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
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final boolean inParallel)
                throws SQLException {
            if (inParallel) {
                loadJoinEntities(entities, joinEntityPropNames, executor());
            } else {
                loadJoinEntities(entities, joinEntityPropNames);
            }
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param executor
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final Executor executor) throws SQLException {
            if (N.isNullOrEmpty(entities) || N.isNullOrEmpty(joinEntityPropNames)) {
                return;
            }

            final List<ContinuableFuture<Void>> futures = StreamE.of(joinEntityPropNames, SQLException.class)
                    .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntities(entities, joinEntityPropName), executor))
                    .toList();

            JdbcUtil.complete(futures);
        }

        /**
         *
         * @param entity
         * @throws SQLException the SQL exception
         */
        default void loadAllJoinEntities(T entity) throws SQLException {
            loadJoinEntities(entity, getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet());
        }

        /**
         *
         * @param entity
         * @param inParallel
         * @throws SQLException the SQL exception
         */
        default void loadAllJoinEntities(final T entity, final boolean inParallel) throws SQLException {
            if (inParallel) {
                loadAllJoinEntities(entity, executor());
            } else {
                loadAllJoinEntities(entity);
            }
        }

        /**
         *
         * @param entity
         * @param executor
         * @throws SQLException the SQL exception
         */
        default void loadAllJoinEntities(final T entity, final Executor executor) throws SQLException {
            loadJoinEntities(entity, getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet(), executor);
        }

        /**
         *
         * @param entities
         * @throws SQLException the SQL exception
         */
        default void loadAllJoinEntities(final Collection<T> entities) throws SQLException {
            if (N.isNullOrEmpty(entities)) {
                return;
            }

            loadJoinEntities(entities, getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet());
        }

        /**
         *
         * @param entities
         * @param inParallel
         * @throws SQLException the SQL exception
         */
        default void loadAllJoinEntities(final Collection<T> entities, final boolean inParallel) throws SQLException {
            if (inParallel) {
                loadAllJoinEntities(entities, executor());
            } else {
                loadAllJoinEntities(entities);
            }
        }

        /**
         *
         * @param entities
         * @param executor
         * @throws SQLException the SQL exception
         */
        default void loadAllJoinEntities(final Collection<T> entities, final Executor executor) throws SQLException {
            if (N.isNullOrEmpty(entities)) {
                return;
            }

            loadJoinEntities(entities, getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet(), executor);
        }

        /**
         *
         * @param entity
         * @param joinEntityClass
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final T entity, final Class<?> joinEntityClass) throws SQLException {
            loadJoinEntitiesIfNull(entity, joinEntityClass, null);
        }

        /**
         *
         * @param entity
         * @param joinEntityClass
         * @param selectPropNames
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final T entity, final Class<?> joinEntityClass, final Collection<String> selectPropNames) throws SQLException {
            final Class<?> targetEntityClass = targetEntityClass();
            final List<String> joinEntityPropNames = getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, joinEntityClass);
            N.checkArgument(N.notNullOrEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

            for (String joinEntityPropName : joinEntityPropNames) {
                loadJoinEntitiesIfNull(entity, joinEntityPropName, selectPropNames);
            }
        }

        /**
         *
         * @param entities
         * @param joinEntityClass
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final Collection<T> entities, final Class<?> joinEntityClass) throws SQLException {
            loadJoinEntitiesIfNull(entities, joinEntityClass, null);
        }

        /**
         *
         * @param entities
         * @param joinEntityClass
         * @param selectPropNames
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final Collection<T> entities, final Class<?> joinEntityClass, final Collection<String> selectPropNames)
                throws SQLException {
            if (N.isNullOrEmpty(entities)) {
                return;
            }

            final Class<?> targetEntityClass = targetEntityClass();
            final List<String> joinEntityPropNames = getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, joinEntityClass);
            N.checkArgument(N.notNullOrEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

            if (joinEntityPropNames.size() == 1) {
                loadJoinEntitiesIfNull(entities, joinEntityPropNames.get(0), selectPropNames);
            } else {
                for (String joinEntityPropName : joinEntityPropNames) {
                    loadJoinEntitiesIfNull(entities, joinEntityPropName, selectPropNames);
                }
            }
        }

        /**
         *
         * @param entity
         * @param joinEntityPropName
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final T entity, final String joinEntityPropName) throws SQLException {
            loadJoinEntitiesIfNull(entity, joinEntityPropName, null);
        }

        /**
         *
         * @param entity
         * ?
         * @param joinEntityPropName
         * @param selectPropNames
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final T entity, final String joinEntityPropName, final Collection<String> selectPropNames) throws SQLException {
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
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final Collection<T> entities, final String joinEntityPropName) throws SQLException {
            loadJoinEntitiesIfNull(entities, joinEntityPropName, null);
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param selectPropNames
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final Collection<T> entities, final String joinEntityPropName, final Collection<String> selectPropNames)
                throws SQLException {
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
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final T entity, final Collection<String> joinEntityPropNames) throws SQLException {
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
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final T entity, final Collection<String> joinEntityPropNames, final boolean inParallel) throws SQLException {
            if (inParallel) {
                loadJoinEntitiesIfNull(entity, joinEntityPropNames, executor());
            } else {
                loadJoinEntitiesIfNull(entity, joinEntityPropNames);
            }
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @param executor
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final T entity, final Collection<String> joinEntityPropNames, final Executor executor) throws SQLException {
            if (N.isNullOrEmpty(joinEntityPropNames)) {
                return;
            }

            final List<ContinuableFuture<Void>> futures = StreamE.of(joinEntityPropNames, SQLException.class)
                    .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntitiesIfNull(entity, joinEntityPropName), executor))
                    .toList();

            JdbcUtil.complete(futures);
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final Collection<T> entities, final Collection<String> joinEntityPropNames) throws SQLException {
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
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final Collection<T> entities, final Collection<String> joinEntityPropNames, final boolean inParallel)
                throws SQLException {
            if (inParallel) {
                loadJoinEntitiesIfNull(entities, joinEntityPropNames, executor());
            } else {
                loadJoinEntitiesIfNull(entities, joinEntityPropNames);
            }
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param executor
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final Collection<T> entities, final Collection<String> joinEntityPropNames, final Executor executor)
                throws SQLException {
            if (N.isNullOrEmpty(entities) || N.isNullOrEmpty(joinEntityPropNames)) {
                return;
            }

            final List<ContinuableFuture<Void>> futures = StreamE.of(joinEntityPropNames, SQLException.class)
                    .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntitiesIfNull(entities, joinEntityPropName), executor))
                    .toList();

            JdbcUtil.complete(futures);
        }

        /**
         *
         * @param entity
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(T entity) throws SQLException {
            loadJoinEntitiesIfNull(entity, getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet());
        }

        /**
         *
         * @param entity
         * @param inParallel
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final T entity, final boolean inParallel) throws SQLException {
            if (inParallel) {
                loadJoinEntitiesIfNull(entity, executor());
            } else {
                loadJoinEntitiesIfNull(entity);
            }
        }

        /**
         *
         * @param entity
         * @param executor
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final T entity, final Executor executor) throws SQLException {
            loadJoinEntitiesIfNull(entity, getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet(), executor);
        }

        /**
         *
         * @param entities
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final Collection<T> entities) throws SQLException {
            if (N.isNullOrEmpty(entities)) {
                return;
            }

            loadJoinEntitiesIfNull(entities, getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet());
        }

        /**
         *
         * @param entities
         * @param inParallel
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final Collection<T> entities, final boolean inParallel) throws SQLException {
            if (inParallel) {
                loadJoinEntitiesIfNull(entities, executor());
            } else {
                loadJoinEntitiesIfNull(entities);
            }
        }

        /**
         *
         * @param entities
         * @param executor
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final Collection<T> entities, final Executor executor) throws SQLException {
            if (N.isNullOrEmpty(entities)) {
                return;
            }

            loadJoinEntitiesIfNull(entities, getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet(), executor);
        }

        /**
         *
         * @param entity
         * @param joinEntityClass
         * @return the total count of updated/deleted records.
         * @throws SQLException the SQL exception
         */
        default int deleteJoinEntities(final T entity, final Class<?> joinEntityClass) throws SQLException {
            final Class<?> targetEntityClass = targetEntityClass();
            final List<String> joinEntityPropNames = getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, joinEntityClass);
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
         * @throws SQLException the SQL exception
         */
        default int deleteJoinEntities(final Collection<T> entities, final Class<?> joinEntityClass) throws SQLException {
            if (N.isNullOrEmpty(entities)) {
                return 0;
            }

            final Class<?> targetEntityClass = targetEntityClass();
            final List<String> joinEntityPropNames = getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, joinEntityClass);
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
         * @throws SQLException the SQL exception
         */
        int deleteJoinEntities(final T entity, final String joinEntityPropName) throws SQLException;

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param selectPropNames
         * @return the total count of updated/deleted records.
         * @throws SQLException the SQL exception
         */
        int deleteJoinEntities(final Collection<T> entities, final String joinEntityPropName) throws SQLException;

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @return the total count of updated/deleted records.
         * @throws SQLException the SQL exception
         */
        default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames) throws SQLException {
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
         * @throws SQLException the SQL exception
         */
        default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final boolean inParallel) throws SQLException {
            if (inParallel) {
                return deleteJoinEntities(entity, joinEntityPropNames, executor());
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
         * @throws SQLException the SQL exception
         */
        default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final Executor executor) throws SQLException {
            if (N.isNullOrEmpty(joinEntityPropNames)) {
                return 0;
            }

            final List<ContinuableFuture<Integer>> futures = StreamE.of(joinEntityPropNames, SQLException.class)
                    .map(joinEntityPropName -> ContinuableFuture.call(() -> deleteJoinEntities(entity, joinEntityPropName), executor))
                    .toList();

            return JdbcUtil.completeSum(futures);
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @return the total count of updated/deleted records.
         * @throws SQLException the SQL exception
         */
        default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames) throws SQLException {
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
         * @throws SQLException the SQL exception
         */
        default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final boolean inParallel)
                throws SQLException {
            if (inParallel) {
                return deleteJoinEntities(entities, joinEntityPropNames, executor());
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
         * @throws SQLException the SQL exception
         */
        default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final Executor executor)
                throws SQLException {
            if (N.isNullOrEmpty(entities) || N.isNullOrEmpty(joinEntityPropNames)) {
                return 0;
            }

            final List<ContinuableFuture<Integer>> futures = StreamE.of(joinEntityPropNames, SQLException.class)
                    .map(joinEntityPropName -> ContinuableFuture.call(() -> deleteJoinEntities(entities, joinEntityPropName), executor))
                    .toList();

            return JdbcUtil.completeSum(futures);
        }

        /**
         *
         * @param entity
         * @return the total count of updated/deleted records.
         * @throws SQLException the SQL exception
         */
        default int deleteAllJoinEntities(T entity) throws SQLException {
            return deleteJoinEntities(entity, getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet());
        }

        /**
         *
         * @param entity
         * @param inParallel
         * @return the total count of updated/deleted records.
         * @throws SQLException the SQL exception
         */
        default int deleteAllJoinEntities(final T entity, final boolean inParallel) throws SQLException {
            if (inParallel) {
                return deleteAllJoinEntities(entity, executor());
            } else {
                return deleteAllJoinEntities(entity);
            }
        }

        /**
         *
         * @param entity
         * @param executor
         * @return the total count of updated/deleted records.
         * @throws SQLException the SQL exception
         */
        default int deleteAllJoinEntities(final T entity, final Executor executor) throws SQLException {
            return deleteJoinEntities(entity, getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet(), executor);
        }

        /**
         *
         * @param entities
         * @return the total count of updated/deleted records.
         * @throws SQLException the SQL exception
         */
        default int deleteAllJoinEntities(final Collection<T> entities) throws SQLException {
            if (N.isNullOrEmpty(entities)) {
                return 0;
            }

            return deleteJoinEntities(entities, getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet());
        }

        /**
         *
         * @param entities
         * @param inParallel
         * @return the total count of updated/deleted records.
         * @throws SQLException the SQL exception
         */
        default int deleteAllJoinEntities(final Collection<T> entities, final boolean inParallel) throws SQLException {
            if (inParallel) {
                return deleteAllJoinEntities(entities, executor());
            } else {
                return deleteAllJoinEntities(entities);
            }
        }

        /**
         *
         * @param entities
         * @param executor
         * @return the total count of updated/deleted records.
         * @throws SQLException the SQL exception
         */
        default int deleteAllJoinEntities(final Collection<T> entities, final Executor executor) throws SQLException {
            if (N.isNullOrEmpty(entities)) {
                return 0;
            }

            return deleteJoinEntities(entities, getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet(), executor);
        }
    }

    public static interface ReadOnlyJoinEntityHelper<T, SB extends SQLBuilder, TD extends ReadOnlyJoinEntityHelper<T, SB, TD>>
            extends JoinEntityHelper<T, SB, TD> {

        /**
         * 
         * @param entity
         * @param joinEntityClass
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteJoinEntities(final T entity, final Class<?> joinEntityClass) throws SQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param joinEntityClass
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteJoinEntities(final Collection<T> entities, final Class<?> joinEntityClass) throws SQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entity
         * @param joinEntityPropName
         * @param selectPropNames
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteJoinEntities(final T entity, final String joinEntityPropName) throws SQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param selectPropNames
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteJoinEntities(final Collection<T> entities, final String joinEntityPropName) throws SQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames) throws SQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @param inParallel
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final boolean inParallel)
                throws SQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @param executor
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final Executor executor)
                throws SQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames)
                throws SQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param inParallel
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final boolean inParallel)
                throws SQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param executor
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final Executor executor)
                throws SQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entity
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteAllJoinEntities(T entity) throws SQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entity
         * @param inParallel
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteAllJoinEntities(final T entity, final boolean inParallel) throws SQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entity
         * @param executor
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteAllJoinEntities(final T entity, final Executor executor) throws SQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteAllJoinEntities(final Collection<T> entities) throws SQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param inParallel
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteAllJoinEntities(final Collection<T> entities, final boolean inParallel) throws SQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param executor
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteAllJoinEntities(final Collection<T> entities, final Executor executor) throws SQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }
    }

    private static <T, SB extends SQLBuilder, TD extends Dao<T, SB, TD>> JoinEntityHelper<T, SB, TD> checkJoinEntityHelper(final Dao<T, SB, TD> dao) {
        if (dao instanceof JoinEntityHelper) {
            return (JoinEntityHelper<T, SB, TD>) dao;
        } else {
            throw new UnsupportedOperationException(ClassUtil.getCanonicalClassName(dao.getClass()) + " doesn't extends interface JoinEntityHelper");
        }
    }

    private static List<String> getJoinEntityPropNamesByType(final Class<?> targetDaoInterface, final Class<?> targetEntityClass,
            final Class<?> joinEntityClass) {
        return JoinInfo.getJoinEntityPropNamesByType(targetDaoInterface, targetEntityClass, joinEntityClass);
    }

    private static Map<String, JoinInfo> getEntityJoinInfo(final Class<?> targetDaoInterface, final Class<?> targetEntityClass) {
        return JoinInfo.getEntityJoinInfo(targetDaoInterface, targetEntityClass);
    }

    /**
     *
     * @param <T>
     * @param <SB>
     * @param <TD>
     * @param daoInterface
     * @param ds
     * @return
     */
    public static <T, SB extends SQLBuilder, TD extends Dao<T, SB, TD>> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds) {
        return createDao(daoInterface, ds, asyncExecutor.getExecutor());
    }

    /**
     *
     * @param <T>
     * @param <SB>
     * @param <TD>
     * @param daoInterface
     * @param ds
     * @param sqlMapper
     * @return
     */
    public static <T, SB extends SQLBuilder, TD extends Dao<T, SB, TD>> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds,
            final SQLMapper sqlMapper) {
        return createDao(daoInterface, ds, sqlMapper, asyncExecutor.getExecutor());
    }

    /**
     * 
     * @param <T>
     * @param <SB>
     * @param <TD>
     * @param daoInterface
     * @param ds
     * @param sqlMapper
     * @param cache Don't share cache between Dao.
     * @return
     * @deprecated
     */
    @Deprecated
    public static <T, SB extends SQLBuilder, TD extends Dao<T, SB, TD>> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds,
            final SQLMapper sqlMapper, final Cache<String, Object> cache) {
        return createDao(daoInterface, ds, sqlMapper, cache, asyncExecutor.getExecutor());
    }

    /**
     *
     * @param <T>
     * @param <SB>
     * @param <TD>
     * @param daoInterface
     * @param ds
     * @param executor
     * @return
     */
    public static <T, SB extends SQLBuilder, TD extends Dao<T, SB, TD>> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds,
            final Executor executor) {
        return createDao(daoInterface, ds, null, executor);
    }

    /**
     *
     * @param <T>
     * @param <SB>
     * @param <TD>
     * @param daoInterface
     * @param ds
     * @param sqlMapper
     * @param executor
     * @return
     */
    public static <T, SB extends SQLBuilder, TD extends Dao<T, SB, TD>> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds,
            final SQLMapper sqlMapper, final Executor executor) {
        return createDao(daoInterface, ds, sqlMapper, null, executor);
    }

    /**
     * 
     * @param <T>
     * @param <SB>
     * @param <TD>
     * @param daoInterface
     * @param ds
     * @param sqlMapper
     * @param cache Don't share cache between Dao.
     * @param executor
     * @return
     * @deprecated
     */
    @Deprecated
    public static <T, SB extends SQLBuilder, TD extends Dao<T, SB, TD>> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds,
            final SQLMapper sqlMapper, final Cache<String, Object> cache, final Executor executor) {
        return DaoUtil.createDao(daoInterface, ds, null, sqlMapper, cache, executor);
    }
}
