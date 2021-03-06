/*
 * Copyright (c) 2019, Haiyang Li.
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

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

import com.landawn.abacus.DataSet;
import com.landawn.abacus.DirtyMarker;
import com.landawn.abacus.EntityId;
import com.landawn.abacus.cache.Cache;
import com.landawn.abacus.cache.CacheFactory;
import com.landawn.abacus.condition.Condition;
import com.landawn.abacus.condition.ConditionFactory.CF;
import com.landawn.abacus.condition.Criteria;
import com.landawn.abacus.core.DirtyMarkerUtil;
import com.landawn.abacus.exception.DuplicatedResultException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.logging.Logger;
import com.landawn.abacus.logging.LoggerFactory;
import com.landawn.abacus.parser.KryoParser;
import com.landawn.abacus.parser.ParserFactory;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.EntityInfo;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.Columns.ColumnOne;
import com.landawn.abacus.util.Fn.IntFunctions;
import com.landawn.abacus.util.JdbcUtil.BiResultExtractor;
import com.landawn.abacus.util.JdbcUtil.BiRowFilter;
import com.landawn.abacus.util.JdbcUtil.BiRowMapper;
import com.landawn.abacus.util.JdbcUtil.Dao;
import com.landawn.abacus.util.JdbcUtil.Dao.OP;
import com.landawn.abacus.util.JdbcUtil.Dao.OutParameter;
import com.landawn.abacus.util.JdbcUtil.ResultExtractor;
import com.landawn.abacus.util.JdbcUtil.RowFilter;
import com.landawn.abacus.util.JdbcUtil.RowMapper;
import com.landawn.abacus.util.SQLBuilder.NAC;
import com.landawn.abacus.util.SQLBuilder.NLC;
import com.landawn.abacus.util.SQLBuilder.NSC;
import com.landawn.abacus.util.SQLBuilder.PAC;
import com.landawn.abacus.util.SQLBuilder.PLC;
import com.landawn.abacus.util.SQLBuilder.PSC;
import com.landawn.abacus.util.SQLBuilder.SP;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.Tuple.Tuple3;
import com.landawn.abacus.util.u.Nullable;
import com.landawn.abacus.util.u.Optional;
import com.landawn.abacus.util.u.OptionalBoolean;
import com.landawn.abacus.util.u.OptionalByte;
import com.landawn.abacus.util.u.OptionalChar;
import com.landawn.abacus.util.u.OptionalFloat;
import com.landawn.abacus.util.u.OptionalShort;
import com.landawn.abacus.util.function.BiConsumer;
import com.landawn.abacus.util.function.BiFunction;
import com.landawn.abacus.util.function.Function;
import com.landawn.abacus.util.function.LongFunction;
import com.landawn.abacus.util.function.Predicate;
import com.landawn.abacus.util.function.Supplier;
import com.landawn.abacus.util.function.TriFunction;
import com.landawn.abacus.util.stream.BaseStream;
import com.landawn.abacus.util.stream.EntryStream;
import com.landawn.abacus.util.stream.IntStream.IntStreamEx;
import com.landawn.abacus.util.stream.Stream;
import com.landawn.abacus.util.stream.Stream.StreamEx;

@SuppressWarnings("deprecation")
final class DaoUtil {

    private DaoUtil() {
        // singleton for utility class.
    }

    private static final KryoParser kryoParser = ParserFactory.isKryoAvailable() ? ParserFactory.createKryoParser() : null;

    @SuppressWarnings("rawtypes")
    private static final Map<String, JdbcUtil.Dao> daoPool = new ConcurrentHashMap<>();

    private static final Map<Class<? extends Annotation>, BiFunction<Annotation, SQLMapper, QueryInfo>> sqlAnnoMap = new HashMap<>();

    static {
        sqlAnnoMap.put(Dao.Select.class, (Annotation anno, SQLMapper sqlMapper) -> {
            final Dao.Select tmp = (Dao.Select) anno;
            int queryTimeout = tmp.queryTimeout();
            int fetchSize = tmp.fetchSize();
            final boolean isBatch = false;
            final int batchSize = -1;
            final OP op = tmp.op() == null ? OP.DEFAULT : tmp.op();
            final boolean isSingleParameter = tmp.isSingleParameter();

            String sql = StringUtil.trim(tmp.sql());

            if (N.isNullOrEmpty(sql)) {
                sql = StringUtil.trim(tmp.value());
            }

            if (N.notNullOrEmpty(tmp.id())) {
                final String id = tmp.id();

                if (sqlMapper == null) {
                    throw new IllegalArgumentException("No SQLMapper is defined or passed for id: " + id);
                }

                sql = sqlMapper.get(id).getParameterizedSql();

                if (N.isNullOrEmpty(sql)) {
                    throw new IllegalArgumentException("No sql is found in SQLMapper by id: " + id);
                }

                final Map<String, String> attrs = sqlMapper.getAttrs(id);

                if (N.notNullOrEmpty(attrs)) {
                    if (attrs.containsKey(SQLMapper.TIMEOUT)) {
                        queryTimeout = N.parseInt(attrs.get(SQLMapper.TIMEOUT));
                    }

                    if (attrs.containsKey(SQLMapper.FETCH_SIZE)) {
                        fetchSize = N.parseInt(attrs.get(SQLMapper.FETCH_SIZE));
                    }
                }
            }

            return new QueryInfo(sql, queryTimeout, fetchSize, isBatch, batchSize, op, isSingleParameter);
        });

        sqlAnnoMap.put(Dao.Insert.class, (Annotation anno, SQLMapper sqlMapper) -> {
            final Dao.Insert tmp = (Dao.Insert) anno;
            int queryTimeout = tmp.queryTimeout();
            final int fetchSize = -1;
            final boolean isBatch = tmp.isBatch();
            int batchSize = tmp.batchSize();
            final OP op = OP.DEFAULT;
            final boolean isSingleParameter = tmp.isSingleParameter();

            String sql = StringUtil.trim(tmp.sql());

            if (N.isNullOrEmpty(sql)) {
                sql = StringUtil.trim(tmp.value());
            }

            if (N.notNullOrEmpty(tmp.id())) {
                final String id = tmp.id();

                if (sqlMapper == null) {
                    throw new IllegalArgumentException("No SQLMapper is defined or passed for id: " + id);
                }

                sql = sqlMapper.get(id).getParameterizedSql();

                if (N.isNullOrEmpty(sql)) {
                    throw new IllegalArgumentException("No sql is found in SQLMapper by id: " + id);
                }

                final Map<String, String> attrs = sqlMapper.getAttrs(id);

                if (N.notNullOrEmpty(attrs)) {
                    if (attrs.containsKey(SQLMapper.TIMEOUT)) {
                        queryTimeout = N.parseInt(attrs.get(SQLMapper.TIMEOUT));
                    }

                    if (attrs.containsKey(SQLMapper.BATCH_SIZE)) {
                        batchSize = N.parseInt(attrs.get(SQLMapper.BATCH_SIZE));
                    }
                }
            }

            return new QueryInfo(sql, queryTimeout, fetchSize, isBatch, batchSize, op, isSingleParameter);
        });

        sqlAnnoMap.put(Dao.Update.class, (Annotation anno, SQLMapper sqlMapper) -> {
            final Dao.Update tmp = (Dao.Update) anno;
            int queryTimeout = tmp.queryTimeout();
            final int fetchSize = -1;
            final boolean isBatch = tmp.isBatch();
            int batchSize = tmp.batchSize();
            final OP op = tmp.op() == null ? OP.DEFAULT : tmp.op();
            final boolean isSingleParameter = tmp.isSingleParameter();

            String sql = StringUtil.trim(tmp.sql());

            if (N.isNullOrEmpty(sql)) {
                sql = StringUtil.trim(tmp.value());
            }

            if (N.notNullOrEmpty(tmp.id())) {
                final String id = tmp.id();

                if (sqlMapper == null) {
                    throw new IllegalArgumentException("No SQLMapper is defined or passed for id: " + id);
                }

                sql = sqlMapper.get(id).getParameterizedSql();

                if (N.isNullOrEmpty(sql)) {
                    throw new IllegalArgumentException("No sql is found in SQLMapper by id: " + id);
                }

                final Map<String, String> attrs = sqlMapper.getAttrs(id);

                if (N.notNullOrEmpty(attrs)) {
                    if (attrs.containsKey(SQLMapper.TIMEOUT)) {
                        queryTimeout = N.parseInt(attrs.get(SQLMapper.TIMEOUT));
                    }

                    if (attrs.containsKey(SQLMapper.BATCH_SIZE)) {
                        batchSize = N.parseInt(attrs.get(SQLMapper.BATCH_SIZE));
                    }
                }
            }

            return new QueryInfo(sql, queryTimeout, fetchSize, isBatch, batchSize, op, isSingleParameter);
        });

        sqlAnnoMap.put(Dao.Delete.class, (Annotation anno, SQLMapper sqlMapper) -> {
            final Dao.Delete tmp = (Dao.Delete) anno;
            int queryTimeout = tmp.queryTimeout();
            final int fetchSize = -1;
            final boolean isBatch = tmp.isBatch();
            int batchSize = tmp.batchSize();
            final OP op = tmp.op() == null ? OP.DEFAULT : tmp.op();
            final boolean isSingleParameter = tmp.isSingleParameter();

            String sql = StringUtil.trim(tmp.sql());

            if (N.isNullOrEmpty(sql)) {
                sql = StringUtil.trim(tmp.value());
            }

            if (N.notNullOrEmpty(tmp.id())) {
                final String id = tmp.id();

                if (sqlMapper == null) {
                    throw new IllegalArgumentException("No SQLMapper is defined or passed for id: " + id);
                }

                sql = sqlMapper.get(id).getParameterizedSql();

                if (N.isNullOrEmpty(sql)) {
                    throw new IllegalArgumentException("No sql is found in SQLMapper by id: " + id);
                }

                final Map<String, String> attrs = sqlMapper.getAttrs(id);

                if (N.notNullOrEmpty(attrs)) {
                    if (attrs.containsKey(SQLMapper.TIMEOUT)) {
                        queryTimeout = N.parseInt(attrs.get(SQLMapper.TIMEOUT));
                    }

                    if (attrs.containsKey(SQLMapper.BATCH_SIZE)) {
                        batchSize = N.parseInt(attrs.get(SQLMapper.BATCH_SIZE));
                    }
                }
            }

            return new QueryInfo(sql, queryTimeout, fetchSize, isBatch, batchSize, op, isSingleParameter);
        });

        sqlAnnoMap.put(Dao.NamedSelect.class, (Annotation anno, SQLMapper sqlMapper) -> {
            final Dao.NamedSelect tmp = (Dao.NamedSelect) anno;
            int queryTimeout = tmp.queryTimeout();
            int fetchSize = tmp.fetchSize();
            final boolean isBatch = false;
            final int batchSize = -1;
            final OP op = tmp.op() == null ? OP.DEFAULT : tmp.op();
            final boolean isSingleParameter = false;

            String sql = StringUtil.trim(tmp.sql());

            if (N.isNullOrEmpty(sql)) {
                sql = StringUtil.trim(tmp.value());
            }

            if (N.notNullOrEmpty(tmp.id())) {
                final String id = tmp.id();

                if (sqlMapper == null) {
                    throw new IllegalArgumentException("No SQLMapper is defined or passed for id: " + id);
                }

                sql = sqlMapper.get(id).sql();

                if (N.isNullOrEmpty(sql)) {
                    throw new IllegalArgumentException("No sql is found in SQLMapper by id: " + id);
                }

                final Map<String, String> attrs = sqlMapper.getAttrs(id);

                if (N.notNullOrEmpty(attrs)) {
                    if (attrs.containsKey(SQLMapper.TIMEOUT)) {
                        queryTimeout = N.parseInt(attrs.get(SQLMapper.TIMEOUT));
                    }

                    if (attrs.containsKey(SQLMapper.FETCH_SIZE)) {
                        fetchSize = N.parseInt(attrs.get(SQLMapper.FETCH_SIZE));
                    }
                }
            }

            return new QueryInfo(sql, queryTimeout, fetchSize, isBatch, batchSize, op, isSingleParameter);
        });

        sqlAnnoMap.put(Dao.NamedInsert.class, (Annotation anno, SQLMapper sqlMapper) -> {
            final Dao.NamedInsert tmp = (Dao.NamedInsert) anno;
            int queryTimeout = tmp.queryTimeout();
            final int fetchSize = -1;
            final boolean isBatch = tmp.isBatch();
            int batchSize = tmp.batchSize();
            final OP op = OP.DEFAULT;
            final boolean isSingleParameter = false;

            String sql = StringUtil.trim(tmp.sql());

            if (N.isNullOrEmpty(sql)) {
                sql = StringUtil.trim(tmp.value());
            }

            if (N.notNullOrEmpty(tmp.id())) {
                final String id = tmp.id();

                if (sqlMapper == null) {
                    throw new IllegalArgumentException("No SQLMapper is defined or passed for id: " + id);
                }

                sql = sqlMapper.get(id).sql();

                if (N.isNullOrEmpty(sql)) {
                    throw new IllegalArgumentException("No sql is found in SQLMapper by id: " + id);
                }

                final Map<String, String> attrs = sqlMapper.getAttrs(id);

                if (N.notNullOrEmpty(attrs)) {
                    if (attrs.containsKey(SQLMapper.TIMEOUT)) {
                        queryTimeout = N.parseInt(attrs.get(SQLMapper.TIMEOUT));
                    }

                    if (attrs.containsKey(SQLMapper.BATCH_SIZE)) {
                        batchSize = N.parseInt(attrs.get(SQLMapper.BATCH_SIZE));
                    }
                }
            }

            return new QueryInfo(sql, queryTimeout, fetchSize, isBatch, batchSize, op, isSingleParameter);
        });

        sqlAnnoMap.put(Dao.NamedUpdate.class, (Annotation anno, SQLMapper sqlMapper) -> {
            final Dao.NamedUpdate tmp = (Dao.NamedUpdate) anno;
            int queryTimeout = tmp.queryTimeout();
            final int fetchSize = -1;
            final boolean isBatch = tmp.isBatch();
            int batchSize = tmp.batchSize();
            final OP op = tmp.op() == null ? OP.DEFAULT : tmp.op();
            final boolean isSingleParameter = false;

            String sql = StringUtil.trim(tmp.sql());

            if (N.isNullOrEmpty(sql)) {
                sql = StringUtil.trim(tmp.value());
            }

            if (N.notNullOrEmpty(tmp.id())) {
                final String id = tmp.id();

                if (sqlMapper == null) {
                    throw new IllegalArgumentException("No SQLMapper is defined or passed for id: " + id);
                }

                sql = sqlMapper.get(id).sql();

                if (N.isNullOrEmpty(sql)) {
                    throw new IllegalArgumentException("No sql is found in SQLMapper by id: " + id);
                }

                final Map<String, String> attrs = sqlMapper.getAttrs(id);

                if (N.notNullOrEmpty(attrs)) {
                    if (attrs.containsKey(SQLMapper.TIMEOUT)) {
                        queryTimeout = N.parseInt(attrs.get(SQLMapper.TIMEOUT));
                    }

                    if (attrs.containsKey(SQLMapper.BATCH_SIZE)) {
                        batchSize = N.parseInt(attrs.get(SQLMapper.BATCH_SIZE));
                    }
                }
            }

            return new QueryInfo(sql, queryTimeout, fetchSize, isBatch, batchSize, op, isSingleParameter);
        });

        sqlAnnoMap.put(Dao.NamedDelete.class, (Annotation anno, SQLMapper sqlMapper) -> {
            final Dao.NamedDelete tmp = (Dao.NamedDelete) anno;
            int queryTimeout = tmp.queryTimeout();
            final int fetchSize = -1;
            final boolean isBatch = tmp.isBatch();
            int batchSize = tmp.batchSize();
            final OP op = tmp.op() == null ? OP.DEFAULT : tmp.op();
            final boolean isSingleParameter = false;

            String sql = StringUtil.trim(tmp.sql());

            if (N.isNullOrEmpty(sql)) {
                sql = StringUtil.trim(tmp.value());
            }

            if (N.notNullOrEmpty(tmp.id())) {
                final String id = tmp.id();

                if (sqlMapper == null) {
                    throw new IllegalArgumentException("No SQLMapper is defined or passed for id: " + id);
                }

                sql = sqlMapper.get(id).sql();

                if (N.isNullOrEmpty(sql)) {
                    throw new IllegalArgumentException("No sql is found in SQLMapper by id: " + id);
                }

                final Map<String, String> attrs = sqlMapper.getAttrs(id);

                if (N.notNullOrEmpty(attrs)) {
                    if (attrs.containsKey(SQLMapper.TIMEOUT)) {
                        queryTimeout = N.parseInt(attrs.get(SQLMapper.TIMEOUT));
                    }

                    if (attrs.containsKey(SQLMapper.BATCH_SIZE)) {
                        batchSize = N.parseInt(attrs.get(SQLMapper.BATCH_SIZE));
                    }
                }
            }

            return new QueryInfo(sql, queryTimeout, fetchSize, isBatch, batchSize, op, isSingleParameter);
        });

        sqlAnnoMap.put(Dao.Call.class, (Annotation anno, SQLMapper sqlMapper) -> {
            final Dao.Call tmp = (Dao.Call) anno;
            int queryTimeout = tmp.queryTimeout();
            final int fetchSize = -1;
            final boolean isBatch = false;
            final int batchSize = -1;
            final OP op = OP.DEFAULT;
            final boolean isSingleParameter = tmp.isSingleParameter();

            String sql = StringUtil.trim(tmp.sql());

            if (N.isNullOrEmpty(sql)) {
                sql = StringUtil.trim(tmp.value());
            }

            if (N.notNullOrEmpty(tmp.id())) {
                final String id = tmp.id();

                if (sqlMapper == null) {
                    throw new IllegalArgumentException("No SQLMapper is defined or passed for id: " + id);
                }

                sql = sqlMapper.get(id).getParameterizedSql();

                if (N.isNullOrEmpty(sql)) {
                    throw new IllegalArgumentException("No sql is found in SQLMapper by id: " + id);
                }

                final Map<String, String> attrs = sqlMapper.getAttrs(id);

                if (N.notNullOrEmpty(attrs)) {
                    if (attrs.containsKey(SQLMapper.TIMEOUT)) {
                        queryTimeout = N.parseInt(attrs.get(SQLMapper.TIMEOUT));
                    }
                }
            }

            return new QueryInfo(sql, queryTimeout, fetchSize, isBatch, batchSize, op, isSingleParameter);
        });
    }

    @SuppressWarnings("rawtypes")
    private static final Map<Class<?>, Predicate> isValuePresentMap = N.newHashMap(20);

    static {
        final Map<Class<?>, Predicate<?>> tmp = N.newHashMap(20);

        tmp.put(u.Nullable.class, (u.Nullable<?> t) -> t.isPresent());
        tmp.put(u.Optional.class, (u.Optional<?> t) -> t.isPresent());
        tmp.put(u.OptionalBoolean.class, (u.OptionalBoolean t) -> t.isPresent());
        tmp.put(u.OptionalChar.class, (u.OptionalChar t) -> t.isPresent());
        tmp.put(u.OptionalByte.class, (u.OptionalByte t) -> t.isPresent());
        tmp.put(u.OptionalShort.class, (u.OptionalShort t) -> t.isPresent());
        tmp.put(u.OptionalInt.class, (u.OptionalInt t) -> t.isPresent());
        tmp.put(u.OptionalLong.class, (u.OptionalLong t) -> t.isPresent());
        tmp.put(u.OptionalFloat.class, (u.OptionalFloat t) -> t.isPresent());
        tmp.put(u.OptionalDouble.class, (u.OptionalDouble t) -> t.isPresent());

        tmp.put(java.util.Optional.class, (java.util.Optional<?> t) -> t.isPresent());
        tmp.put(java.util.OptionalInt.class, (java.util.OptionalInt t) -> t.isPresent());
        tmp.put(java.util.OptionalLong.class, (java.util.OptionalLong t) -> t.isPresent());
        tmp.put(java.util.OptionalDouble.class, (java.util.OptionalDouble t) -> t.isPresent());

        isValuePresentMap.putAll(tmp);
    }

    private static Set<Class<?>> notCacheableTypes = N.asSet(void.class, Void.class, Iterator.class, java.util.stream.BaseStream.class, BaseStream.class,
            EntryStream.class, ExceptionalStream.class);

    /**
     * Creates the method handle.
     *
     * @param method
     * @return
     */
    private static MethodHandle createMethodHandle(final Method method) {
        final Class<?> declaringClass = method.getDeclaringClass();

        try {
            return MethodHandles.lookup().in(declaringClass).unreflectSpecial(method, declaringClass);
        } catch (Exception e) {
            try {
                final Constructor<MethodHandles.Lookup> constructor = MethodHandles.Lookup.class.getDeclaredConstructor(Class.class);
                ClassUtil.setAccessible(constructor, true);

                return constructor.newInstance(declaringClass).in(declaringClass).unreflectSpecial(method, declaringClass);
            } catch (Exception ex) {
                try {
                    return MethodHandles.lookup()
                            .findSpecial(declaringClass, method.getName(), MethodType.methodType(method.getReturnType(), method.getParameterTypes()),
                                    declaringClass);
                } catch (Exception exx) {
                    throw new UnsupportedOperationException(exx);
                }
            }
        }
    }

    /**
     * Checks if is list query.
     *
     * @param method
     * @return true, if is list query
     */
    private static boolean isListQuery(final Method method, final OP op, final String fullClassMethodName) {
        final Class<?>[] paramTypes = method.getParameterTypes();
        final Class<?> returnType = method.getReturnType();
        final int paramLen = paramTypes.length;

        if (op == OP.list) {
            if (Collection.class.equals(returnType) || !(Collection.class.isAssignableFrom(returnType))) {
                throw new UnsupportedOperationException(
                        "The result type of list OP must be sub type of Collection, can't be: " + returnType + " in method: " + fullClassMethodName);
            }

            return true;
        } else if (op != OP.DEFAULT) {
            return false;
        }

        if (Collection.class.isAssignableFrom(returnType)) {
            // Check if return type is generic List type.
            if (method.getGenericReturnType() instanceof ParameterizedType) {
                final ParameterizedType parameterizedReturnType = (ParameterizedType) method.getGenericReturnType();
                final Class<?> paramClassInReturnType = parameterizedReturnType.getActualTypeArguments()[0] instanceof Class
                        ? (Class<?>) parameterizedReturnType.getActualTypeArguments()[0]
                        : (Class<?>) ((ParameterizedType) parameterizedReturnType.getActualTypeArguments()[0]).getRawType();

                if (paramLen > 0 && (RowMapper.class.isAssignableFrom(paramTypes[paramLen - 1]) || BiRowMapper.class.isAssignableFrom(paramTypes[paramLen - 1]))
                        && method.getGenericParameterTypes()[paramLen - 1] instanceof ParameterizedType) {

                    final ParameterizedType rowMapperType = (ParameterizedType) method.getGenericParameterTypes()[paramLen - 1];
                    final Class<?> paramClassInRowMapper = rowMapperType.getActualTypeArguments()[0] instanceof Class
                            ? (Class<?>) rowMapperType.getActualTypeArguments()[0]
                            : (Class<?>) ((ParameterizedType) rowMapperType.getActualTypeArguments()[0]).getRawType();

                    if (paramClassInReturnType != null && paramClassInRowMapper != null && paramClassInReturnType.isAssignableFrom(paramClassInRowMapper)) {
                        return true;
                    } else {
                        // if the return type of the method is same as the return type of RowMapper/BiRowMapper parameter, return false;
                        return !parameterizedReturnType.equals(rowMapperType.getActualTypeArguments()[0]);
                    }
                }
            }

            if (paramLen > 0 && (ResultExtractor.class.isAssignableFrom(paramTypes[paramLen - 1])
                    || BiResultExtractor.class.isAssignableFrom(paramTypes[paramLen - 1]))) {
                return false;
            }

            return !(method.getName().startsWith("get") || method.getName().startsWith("findFirst") || method.getName().startsWith("findOne"));
        } else {
            return false;
        }
    }

    private static boolean isExistsQuery(final Method method, final OP op, final String fullClassMethodName) {
        final String methodName = method.getName();
        final Class<?>[] paramTypes = method.getParameterTypes();
        final Class<?> returnType = method.getReturnType();
        final int paramLen = paramTypes.length;

        if (op == OP.exists) {
            if (!(boolean.class.equals(returnType) || Boolean.class.equals(returnType))) {
                throw new UnsupportedOperationException(
                        "The result type of exists OP must be boolean or Boolean, can't be: " + returnType + " in method: " + fullClassMethodName);
            }

            return true;
        } else if (op != OP.DEFAULT) {
            return false;
        }

        if (paramLen > 0 //
                && (ResultExtractor.class.isAssignableFrom(paramTypes[paramLen - 1]) //
                        || BiResultExtractor.class.isAssignableFrom(paramTypes[paramLen - 1]) //
                        || RowMapper.class.isAssignableFrom(paramTypes[paramLen - 1]) //
                        || BiRowMapper.class.isAssignableFrom(paramTypes[paramLen - 1]))) {
            return false;
        }

        return (boolean.class.equals(returnType) || Boolean.class.equals(returnType))
                && ((methodName.startsWith("exists") && (methodName.length() == 6 || Character.isUpperCase(methodName.charAt(6))))
                        || (methodName.startsWith("exist") && (methodName.length() == 5 || Character.isUpperCase(methodName.charAt(5))))
                        || (methodName.startsWith("has") && (methodName.length() == 3 || Character.isUpperCase(methodName.charAt(3)))));
    }

    @SuppressWarnings("rawtypes")
    private static <R> Throwables.BiFunction<AbstractPreparedQuery, Object[], R, Exception> createSingleQueryFunction(final Class<?> returnType) {
        if (OptionalBoolean.class.isAssignableFrom(returnType)) {
            return (preparedQuery, args) -> (R) preparedQuery.queryForBoolean();
        } else if (OptionalChar.class.isAssignableFrom(returnType)) {
            return (preparedQuery, args) -> (R) preparedQuery.queryForChar();
        } else if (OptionalByte.class.isAssignableFrom(returnType)) {
            return (preparedQuery, args) -> (R) preparedQuery.queryForByte();
        } else if (OptionalShort.class.isAssignableFrom(returnType)) {
            return (preparedQuery, args) -> (R) preparedQuery.queryForShort();
        } else if (OptionalInt.class.isAssignableFrom(returnType)) {
            return (preparedQuery, args) -> (R) preparedQuery.queryForInt();
        } else if (OptionalLong.class.isAssignableFrom(returnType)) {
            return (preparedQuery, args) -> (R) preparedQuery.queryForLong();
        } else if (OptionalFloat.class.isAssignableFrom(returnType)) {
            return (preparedQuery, args) -> (R) preparedQuery.queryForFloat();
        } else if (OptionalDouble.class.isAssignableFrom(returnType)) {
            return (preparedQuery, args) -> (R) preparedQuery.queryForDouble();
        } else {
            return (preparedQuery, args) -> (R) preparedQuery.queryForSingleResult(returnType).orElse(N.defaultValueOf(returnType));
        }
    }

    @SuppressWarnings("rawtypes")
    private static <R> Throwables.BiFunction<AbstractPreparedQuery, Object[], R, Exception> createQueryFunctionByMethod(final Method method,
            final boolean hasRowMapperOrExtractor, final boolean hasRowFilter, final OP op, final String fullClassMethodName) {
        method.getName();
        final Class<?>[] paramTypes = method.getParameterTypes();
        final Class<?> returnType = method.getReturnType();
        final int paramLen = paramTypes.length;
        final Class<?> lastParamType = paramLen == 0 ? null : paramTypes[paramLen - 1];
        final boolean isListQuery = isListQuery(method, op, fullClassMethodName);
        final boolean isExists = isExistsQuery(method, op, fullClassMethodName);

        if ((op == OP.stream && !(Stream.class.isAssignableFrom(returnType) || ExceptionalStream.class.isAssignableFrom(returnType)))
                || (op == OP.query && !(DataSet.class.isAssignableFrom(returnType) || lastParamType == null
                        || ResultExtractor.class.isAssignableFrom(lastParamType) || BiResultExtractor.class.isAssignableFrom(lastParamType)))) {
            throw new UnsupportedOperationException(
                    "The return type: " + returnType + " of method: " + fullClassMethodName + " is not supported the specified op: " + op);
        }

        if ((op == OP.get || op == OP.findFirst) && Nullable.class.isAssignableFrom(returnType)) {
            throw new UnsupportedOperationException(
                    "The return type: " + returnType + " of method: " + fullClassMethodName + " is not supported the specified op: " + op);
        }

        //    if ((op == OP.queryForSingle || op == OP.queryForUnique)
        //            && !(Optional.class.isAssignableFrom(returnType) || Nullable.class.isAssignableFrom(returnType))) {
        //        throw new UnsupportedOperationException(
        //                "The return type: " + returnType + " of method: " + fullClassMethodName + " is not supported the specified op: " + op);
        //    }

        if ((Stream.class.isAssignableFrom(returnType) || ExceptionalStream.class.isAssignableFrom(returnType)) && !(op == OP.stream || op == OP.DEFAULT)) {
            throw new UnsupportedOperationException(
                    "The return type: " + returnType + " of method: " + fullClassMethodName + " is not supported the specified op: " + op);
        }

        if (DataSet.class.isAssignableFrom(returnType) && !(op == OP.query || op == OP.DEFAULT)) {
            throw new UnsupportedOperationException(
                    "The return type: " + returnType + " of method: " + fullClassMethodName + " is not supported the specified op: " + op);
        }

        if (Optional.class.isAssignableFrom(returnType)
                && !(op == OP.get || op == OP.findFirst || op == OP.queryForSingle || op == OP.queryForUnique || op == OP.DEFAULT)) {
            throw new UnsupportedOperationException(
                    "The return type: " + returnType + " of method: " + fullClassMethodName + " is not supported the specified op: " + op);
        }

        if (Nullable.class.isAssignableFrom(returnType) && !(op == OP.queryForSingle || op == OP.queryForUnique || op == OP.DEFAULT)) {
            throw new UnsupportedOperationException(
                    "The return type: " + returnType + " of method: " + fullClassMethodName + " is not supported the specified op: " + op);
        }

        if (hasRowMapperOrExtractor) {
            if (!(op == OP.get || op == OP.findFirst || op == OP.list || op == OP.stream)) {
                throw new UnsupportedOperationException("RowMapper/ResultExtractor is not supported by OP: " + op + " in method: " + fullClassMethodName);
            }

            if (hasRowFilter && (op == OP.get || op == OP.query)) {
                throw new UnsupportedOperationException("RowFilter is not supported by OP: " + op + " in method: " + fullClassMethodName);
            }

            if (RowMapper.class.isAssignableFrom(lastParamType)) {
                if (isListQuery) {
                    if (returnType.equals(List.class)) {
                        if (hasRowFilter) {
                            return (preparedQuery, args) -> (R) preparedQuery.list((RowFilter) args[paramLen - 2], (RowMapper) args[paramLen - 1]);
                        } else {
                            return (preparedQuery, args) -> (R) preparedQuery.list((RowMapper) args[paramLen - 1]);
                        }
                    } else {
                        if (hasRowFilter) {
                            return (preparedQuery, args) -> (R) preparedQuery.stream((RowFilter) args[paramLen - 2], (RowMapper) args[paramLen - 1])
                                    .toCollection(() -> N.newInstance(returnType));
                        } else {
                            return (preparedQuery,
                                    args) -> (R) preparedQuery.stream((RowMapper) args[paramLen - 1]).toCollection(() -> N.newInstance(returnType));
                        }
                    }
                } else if (Optional.class.isAssignableFrom(returnType)) {
                    if (op == OP.get) {
                        return (preparedQuery, args) -> (R) preparedQuery.get((RowMapper) args[paramLen - 1]);
                    } else {
                        if (hasRowFilter) {
                            return (preparedQuery, args) -> (R) preparedQuery.findFirst((RowFilter) args[paramLen - 2], (RowMapper) args[paramLen - 1]);
                        } else {
                            return (preparedQuery, args) -> (R) preparedQuery.findFirst((RowMapper) args[paramLen - 1]);
                        }
                    }
                } else if (ExceptionalStream.class.isAssignableFrom(returnType)) {
                    if (hasRowFilter) {
                        return (preparedQuery, args) -> (R) preparedQuery.stream((RowFilter) args[paramLen - 2], (RowMapper) args[paramLen - 1]);
                    } else {
                        return (preparedQuery, args) -> (R) preparedQuery.stream((RowMapper) args[paramLen - 1]);
                    }
                } else if (Stream.class.isAssignableFrom(returnType)) {
                    if (hasRowFilter) {
                        return (preparedQuery, args) -> (R) preparedQuery.stream((RowFilter) args[paramLen - 2], (RowMapper) args[paramLen - 1]).unchecked();
                    } else {
                        return (preparedQuery, args) -> (R) preparedQuery.stream((RowMapper) args[paramLen - 1]).unchecked();
                    }
                } else {
                    if (Nullable.class.isAssignableFrom(returnType)) {
                        throw new UnsupportedOperationException(
                                "The return type of method: " + method.getName() + " can't be: " + returnType + " when RowMapper/BiRowMapper is specified");
                    }

                    if (op == OP.get) {
                        return (preparedQuery, args) -> (R) preparedQuery.gett((RowMapper) args[paramLen - 1]);
                    } else {
                        if (hasRowFilter) {
                            return (preparedQuery, args) -> (R) preparedQuery.findFirst((RowFilter) args[paramLen - 2], (RowMapper) args[paramLen - 1])
                                    .orElse(N.defaultValueOf(returnType));
                        } else {
                            return (preparedQuery, args) -> (R) preparedQuery.findFirst((RowMapper) args[paramLen - 1]).orElse(N.defaultValueOf(returnType));
                        }
                    }
                }
            } else if (BiRowMapper.class.isAssignableFrom(lastParamType)) {
                if (isListQuery) {
                    if (returnType.equals(List.class)) {
                        if (hasRowFilter) {
                            return (preparedQuery, args) -> (R) preparedQuery.list((BiRowFilter) args[paramLen - 2], (BiRowMapper) args[paramLen - 1]);
                        } else {
                            return (preparedQuery, args) -> (R) preparedQuery.list((BiRowMapper) args[paramLen - 1]);
                        }
                    } else {
                        if (hasRowFilter) {
                            return (preparedQuery, args) -> (R) preparedQuery.stream((BiRowFilter) args[paramLen - 2], (BiRowMapper) args[paramLen - 1])
                                    .toCollection(() -> N.newInstance(returnType));
                        } else {
                            return (preparedQuery,
                                    args) -> (R) preparedQuery.stream((BiRowMapper) args[paramLen - 1]).toCollection(() -> N.newInstance(returnType));
                        }
                    }
                } else if (Optional.class.isAssignableFrom(returnType)) {
                    if (op == OP.get) {
                        return (preparedQuery, args) -> (R) preparedQuery.get((BiRowMapper) args[paramLen - 1]);
                    } else {
                        if (hasRowFilter) {
                            return (preparedQuery, args) -> (R) preparedQuery.findFirst((BiRowFilter) args[paramLen - 2], (BiRowMapper) args[paramLen - 1]);
                        } else {
                            return (preparedQuery, args) -> (R) preparedQuery.findFirst((BiRowMapper) args[paramLen - 1]);
                        }
                    }
                } else if (ExceptionalStream.class.isAssignableFrom(returnType)) {
                    if (hasRowFilter) {
                        return (preparedQuery, args) -> (R) preparedQuery.stream((BiRowFilter) args[paramLen - 2], (BiRowMapper) args[paramLen - 1]);
                    } else {
                        return (preparedQuery, args) -> (R) preparedQuery.stream((BiRowMapper) args[paramLen - 1]);
                    }
                } else if (Stream.class.isAssignableFrom(returnType)) {
                    if (hasRowFilter) {
                        return (preparedQuery,
                                args) -> (R) preparedQuery.stream((BiRowFilter) args[paramLen - 2], (BiRowMapper) args[paramLen - 1]).unchecked();
                    } else {
                        return (preparedQuery, args) -> (R) preparedQuery.stream((BiRowMapper) args[paramLen - 1]).unchecked();
                    }
                } else {
                    if (Nullable.class.isAssignableFrom(returnType)) {
                        throw new UnsupportedOperationException(
                                "The return type of method: " + method.getName() + " can't be: " + returnType + " when RowMapper/BiRowMapper is specified");
                    }

                    if (op == OP.get) {
                        return (preparedQuery, args) -> (R) preparedQuery.gett((BiRowMapper) args[paramLen - 1]);
                    } else {
                        if (hasRowFilter) {
                            return (preparedQuery, args) -> (R) preparedQuery.findFirst((BiRowFilter) args[paramLen - 2], (BiRowMapper) args[paramLen - 1])
                                    .orElse(N.defaultValueOf(returnType));
                        } else {
                            return (preparedQuery, args) -> (R) preparedQuery.findFirst((BiRowMapper) args[paramLen - 1]).orElse(N.defaultValueOf(returnType));
                        }
                    }
                }
            } else {
                if (!(op == OP.query || op == OP.DEFAULT)) {
                    throw new UnsupportedOperationException(
                            "ResultExtractor/BiResultExtractor is not supported by OP: " + op + " in method: " + fullClassMethodName);
                }

                if (method.getGenericParameterTypes()[paramLen - 1] instanceof ParameterizedType) {
                    final java.lang.reflect.Type resultExtractorReturnType = ((ParameterizedType) method.getGenericParameterTypes()[paramLen - 1])
                            .getActualTypeArguments()[0];
                    final Class<?> resultExtractorReturnClass = resultExtractorReturnType instanceof Class ? (Class<?>) resultExtractorReturnType
                            : (Class<?>) ((ParameterizedType) resultExtractorReturnType).getRawType();

                    if (returnType.isAssignableFrom(resultExtractorReturnClass)) {
                        throw new UnsupportedOperationException("The return type: " + returnType + " of method: " + method.getName()
                                + " is not assignable from the return type of ResultExtractor: " + resultExtractorReturnClass);
                    }
                }

                if (ResultExtractor.class.isAssignableFrom(lastParamType)) {
                    return (preparedQuery, args) -> (R) preparedQuery.query((ResultExtractor) args[paramLen - 1]);
                } else {
                    return (preparedQuery, args) -> (R) preparedQuery.query((BiResultExtractor) args[paramLen - 1]);
                }
            }
        } else if (isExists) {
            return (preparedQuery, args) -> (R) (Boolean) preparedQuery.exists();
        } else if (isListQuery) {
            final Class<?> eleType = getReturnEleType(method);

            if (returnType.equals(List.class)) {
                return (preparedQuery, args) -> (R) preparedQuery.list(eleType);
            } else {
                return (preparedQuery, args) -> (R) preparedQuery.stream(eleType).toCollection(() -> N.newInstance(returnType));
            }
        } else if (DataSet.class.isAssignableFrom(returnType)) {
            return (preparedQuery, args) -> (R) preparedQuery.query();
        } else if (Optional.class.isAssignableFrom(returnType) || Nullable.class.isAssignableFrom(returnType)) {
            final Class<?> eleType = getReturnEleType(method);

            if (Nullable.class.isAssignableFrom(returnType)) {
                if (op == OP.queryForSingle) {
                    return (preparedQuery, args) -> (R) preparedQuery.queryForSingleResult(eleType);
                } else if (op == OP.queryForUnique) {
                    return (preparedQuery, args) -> (R) preparedQuery.queryForUniqueResult(eleType);
                } else {
                    return (preparedQuery, args) -> (R) preparedQuery.queryForSingleResult(eleType);
                }
            } else {
                if (op == OP.get) {
                    return (preparedQuery, args) -> (R) preparedQuery.get(BiRowMapper.to(eleType));
                } else if (op == OP.findFirst) {
                    return (preparedQuery, args) -> (R) preparedQuery.findFirst(BiRowMapper.to(eleType));
                } else if (op == OP.queryForSingle) {
                    return (preparedQuery, args) -> (R) preparedQuery.queryForSingleNonNull(eleType);
                } else if (op == OP.queryForUnique) {
                    return (preparedQuery, args) -> (R) preparedQuery.queryForUniqueNonNull(eleType);
                } else {
                    if (ClassUtil.isEntity(eleType) || Map.class.isAssignableFrom(eleType) || List.class.isAssignableFrom(eleType)
                            || Object[].class.isAssignableFrom(eleType)) {
                        return (preparedQuery, args) -> (R) preparedQuery.findFirst(BiRowMapper.to(eleType));
                    } else {
                        return (preparedQuery, args) -> (R) preparedQuery.queryForSingleNonNull(eleType);
                    }
                }
            }
        } else if (ExceptionalStream.class.isAssignableFrom(returnType) || Stream.class.isAssignableFrom(returnType)) {
            final Class<?> eleType = getReturnEleType(method);

            if (ExceptionalStream.class.isAssignableFrom(returnType)) {
                return (preparedQuery, args) -> (R) preparedQuery.stream(eleType);
            } else {
                return (preparedQuery, args) -> (R) preparedQuery.stream(eleType).unchecked();
            }
        } else if (op == OP.get) {
            return (preparedQuery, args) -> (R) preparedQuery.gett(BiRowMapper.to(returnType));
        } else if (op == OP.findFirst) {
            return (preparedQuery, args) -> (R) preparedQuery.findFirst(BiRowMapper.to(returnType)).orNull();
        } else if (op == OP.queryForSingle) {
            return createSingleQueryFunction(returnType);
        } else if (op == OP.queryForUnique) {
            return (preparedQuery, args) -> (R) preparedQuery.queryForUniqueResult(returnType).orElse(N.defaultValueOf(returnType));
        } else {
            if (ClassUtil.isEntity(returnType) || Map.class.isAssignableFrom(returnType) || List.class.isAssignableFrom(returnType)
                    || Object[].class.isAssignableFrom(returnType)) {
                return (preparedQuery, args) -> (R) preparedQuery.findFirst(BiRowMapper.to(returnType)).orNull();
            } else {
                return createSingleQueryFunction(returnType);
            }
        }
    }

    private static Class<?> getReturnEleType(final Method method) {
        final ParameterizedType parameterizedReturnType = (ParameterizedType) method.getGenericReturnType();

        final Class<?> eleType = parameterizedReturnType.getActualTypeArguments()[0] instanceof Class
                ? (Class<?>) parameterizedReturnType.getActualTypeArguments()[0]
                : (Class<?>) ((ParameterizedType) parameterizedReturnType.getActualTypeArguments()[0]).getRawType();
        return eleType;
    }

    @SuppressWarnings("rawtypes")
    private static JdbcUtil.BiParametersSetter<AbstractPreparedQuery, Object[]> createParametersSetter(Method m, final String fullClassMethodName,
            final Class<?>[] paramTypes, final int paramLen, final boolean isBatch, final boolean isSingleParameter, final boolean isCall,
            final boolean isNamedQuery, final ParsedSql namedSql, final int defineParamLen, final int[] stmtParamIndexes, final int stmtParamLen) {

        JdbcUtil.BiParametersSetter<AbstractPreparedQuery, Object[]> parametersSetter = null;

        boolean hasParameterSetter = false;

        if (hasParameterSetter = paramLen - defineParamLen > 0 && JdbcUtil.ParametersSetter.class.isAssignableFrom(paramTypes[defineParamLen])) {
            parametersSetter = (preparedQuery, args) -> preparedQuery.settParameters((JdbcUtil.ParametersSetter) args[defineParamLen]);
        } else if (hasParameterSetter = paramLen - defineParamLen > 1 && JdbcUtil.BiParametersSetter.class.isAssignableFrom(paramTypes[defineParamLen + 1])) {
            parametersSetter = (preparedQuery, args) -> preparedQuery.settParameters(args[defineParamLen],
                    (JdbcUtil.BiParametersSetter) args[defineParamLen + 1]);
        } else if (hasParameterSetter = paramLen - defineParamLen > 1 && JdbcUtil.TriParametersSetter.class.isAssignableFrom(paramTypes[defineParamLen + 1])) {
            parametersSetter = (preparedQuery, args) -> ((NamedQuery) preparedQuery).setParameters(args[defineParamLen],
                    (JdbcUtil.TriParametersSetter) args[defineParamLen + 1]);
        }

        if (hasParameterSetter) {
            throw new UnsupportedOperationException(
                    "Setting parameters by 'ParametersSetter/BiParametersSetter/TriParametersSetter' is not enabled at present. Can't use it in method: "
                            + fullClassMethodName);
        }

        if (parametersSetter != null || stmtParamLen == 0 || isBatch) {
            // ignore
        } else if (stmtParamLen == 1) {
            final Class<?> paramTypeOne = paramTypes[stmtParamIndexes[0]];

            if (isCall) {
                final String paramName = StreamEx.of(m.getParameterAnnotations()[stmtParamIndexes[0]])
                        .select(Dao.Bind.class)
                        .map(b -> b.value())
                        .first()
                        .orNull();

                if (N.notNullOrEmpty(paramName)) {
                    parametersSetter = (preparedQuery, args) -> ((PreparedCallableQuery) preparedQuery).setObject(paramName, args[stmtParamIndexes[0]]);
                } else if (isSingleParameter) {
                    parametersSetter = (preparedQuery, args) -> preparedQuery.setObject(1, args[stmtParamIndexes[0]]);
                } else if (Map.class.isAssignableFrom(paramTypeOne)) {
                    parametersSetter = (preparedQuery, args) -> ((PreparedCallableQuery) preparedQuery)
                            .setParameters((Map<String, ?>) args[stmtParamIndexes[0]]);
                } else if (ClassUtil.isEntity(paramTypeOne) || EntityId.class.isAssignableFrom(paramTypeOne)) {
                    throw new UnsupportedOperationException("In method: " + fullClassMethodName
                            + ", parameters for call(procedure) have to be binded with names through annotation @Bind, or Map. Entity/EntityId type parameter are not supported");
                } else if (Collection.class.isAssignableFrom(paramTypeOne)) {
                    parametersSetter = (preparedQuery, args) -> preparedQuery.setParameters((Collection) args[stmtParamIndexes[0]]);
                } else if (Object[].class.isAssignableFrom(paramTypeOne)) {
                    parametersSetter = (preparedQuery, args) -> preparedQuery.setParameters((Object[]) args[stmtParamIndexes[0]]);
                } else {
                    parametersSetter = (preparedQuery, args) -> preparedQuery.setObject(1, args[stmtParamIndexes[0]]);
                }
            } else if (isNamedQuery) {
                final String paramName = StreamEx.of(m.getParameterAnnotations()[stmtParamIndexes[0]])
                        .select(Dao.Bind.class)
                        .map(b -> b.value())
                        .first()
                        .orNull();

                if (N.notNullOrEmpty(paramName)) {
                    parametersSetter = (preparedQuery, args) -> ((NamedQuery) preparedQuery).setObject(paramName, args[stmtParamIndexes[0]]);
                } else if (isSingleParameter) {
                    parametersSetter = (preparedQuery, args) -> preparedQuery.setObject(1, args[stmtParamIndexes[0]]);
                } else if (ClassUtil.isEntity(paramTypeOne)) {
                    parametersSetter = (preparedQuery, args) -> ((NamedQuery) preparedQuery).setParameters(args[stmtParamIndexes[0]]);
                } else if (Map.class.isAssignableFrom(paramTypeOne)) {
                    parametersSetter = (preparedQuery, args) -> ((NamedQuery) preparedQuery).setParameters((Map<String, ?>) args[stmtParamIndexes[0]]);
                } else if (EntityId.class.isAssignableFrom(paramTypeOne)) {
                    parametersSetter = (preparedQuery, args) -> ((NamedQuery) preparedQuery).setParameters(args[stmtParamIndexes[0]]);
                } else {
                    throw new UnsupportedOperationException("In method: " + fullClassMethodName
                            + ", parameters for named query have to be binded with names through annotation @Bind, or Map/Entity with getter/setter methods. Can not be: "
                            + ClassUtil.getSimpleClassName(paramTypeOne));
                }
            } else {
                if (isSingleParameter) {
                    parametersSetter = (preparedQuery, args) -> preparedQuery.setObject(1, args[stmtParamIndexes[0]]);
                } else if (Collection.class.isAssignableFrom(paramTypeOne)) {
                    parametersSetter = (preparedQuery, args) -> preparedQuery.setParameters((Collection) args[stmtParamIndexes[0]]);
                } else if (Object[].class.isAssignableFrom(paramTypeOne)) {
                    parametersSetter = (preparedQuery, args) -> preparedQuery.setParameters((Object[]) args[stmtParamIndexes[0]]);
                } else {
                    parametersSetter = (preparedQuery, args) -> preparedQuery.setObject(1, args[stmtParamIndexes[0]]);
                }
            }
        } else {
            if (isCall) {
                final String[] paramNames = IntStreamEx.of(stmtParamIndexes)
                        .mapToObj(i -> StreamEx.of(m.getParameterAnnotations()[i]).select(Dao.Bind.class).first().orElse(null))
                        .skipNull()
                        .map(b -> b.value())
                        .toArray(IntFunctions.ofStringArray());

                if (N.notNullOrEmpty(paramNames)) {
                    parametersSetter = (preparedQuery, args) -> {
                        final PreparedCallableQuery namedQuery = ((PreparedCallableQuery) preparedQuery);

                        for (int i = 0; i < stmtParamLen; i++) {
                            namedQuery.setObject(paramNames[i], args[stmtParamIndexes[i]]);
                        }
                    };
                } else {
                    if (stmtParamLen == paramLen) {
                        parametersSetter = (preparedQuery, args) -> preparedQuery.setParameters(args);
                    } else {
                        parametersSetter = (preparedQuery, args) -> {
                            for (int i = 0; i < stmtParamLen; i++) {
                                preparedQuery.setObject(i + 1, args[stmtParamIndexes[i]]);
                            }
                        };
                    }
                }
            } else if (isNamedQuery) {
                final String[] paramNames = IntStreamEx.of(stmtParamIndexes)
                        .mapToObj(i -> StreamEx.of(m.getParameterAnnotations()[i])
                                .select(Dao.Bind.class)
                                .first()
                                .orElseThrow(() -> new UnsupportedOperationException("In method: " + fullClassMethodName + ", parameters[" + i + "]: "
                                        + ClassUtil.getSimpleClassName(m.getParameterTypes()[i])
                                        + " is not binded with parameter named through annotation @Bind")))
                        .map(b -> b.value())
                        .toArray(IntFunctions.ofStringArray());

                if (namedSql != null) {
                    final List<String> diffParamNames = N.difference(namedSql.getNamedParameters(), N.asList(paramNames));

                    if (N.notNullOrEmpty(diffParamNames)) {
                        throw new UnsupportedOperationException("In method: " + fullClassMethodName
                                + ", The named parameters in sql are different from the names binded by method parameters: " + diffParamNames);
                    }
                }

                parametersSetter = (preparedQuery, args) -> {
                    final NamedQuery namedQuery = ((NamedQuery) preparedQuery);

                    for (int i = 0; i < stmtParamLen; i++) {
                        namedQuery.setObject(paramNames[i], args[stmtParamIndexes[i]]);
                    }
                };
            } else {
                if (stmtParamLen == paramLen) {
                    parametersSetter = (preparedQuery, args) -> preparedQuery.setParameters(args);
                } else {
                    parametersSetter = (preparedQuery, args) -> {
                        for (int i = 0; i < stmtParamLen; i++) {
                            preparedQuery.setObject(i + 1, args[stmtParamIndexes[i]]);
                        }
                    };
                }
            }
        }

        return parametersSetter == null ? JdbcUtil.BiParametersSetter.DO_NOTHING : parametersSetter;
    }

    @SuppressWarnings("rawtypes")
    private static AbstractPreparedQuery prepareQuery(final Dao proxy, final Method method, final Object[] args, final int[] defineParamIndexes,
            final String[] defines, final boolean isNamedQuery, String query, ParsedSql namedSql, final boolean isBatch, final int batchSize,
            final int fetchSize, final FetchDirection fetchDirection, final int queryTimeout, final boolean returnGeneratedKeys,
            final String[] returnColumnNames, final boolean isCall, final List<OutParameter> outParameterList) throws SQLException, Exception {

        if (N.notNullOrEmpty(defines)) {
            for (int i = 0, len = defines.length; i < len; i++) {
                query = StringUtil.replaceAll(query, defines[i], N.stringOf(args[defineParamIndexes[i]]));
            }

            if (isNamedQuery) {
                namedSql = ParsedSql.parse(query);
            }
        }

        final AbstractPreparedQuery preparedQuery = isCall ? proxy.prepareCallableQuery(query)
                : (isNamedQuery ? (returnGeneratedKeys ? proxy.prepareNamedQuery(namedSql, returnColumnNames) : proxy.prepareNamedQuery(namedSql))
                        : (returnGeneratedKeys ? proxy.prepareQuery(query, returnColumnNames) : proxy.prepareQuery(query)));

        if (isCall && N.notNullOrEmpty(outParameterList)) {
            final PreparedCallableQuery callableQuery = ((PreparedCallableQuery) preparedQuery);

            for (OutParameter op : outParameterList) {
                if (N.isNullOrEmpty(op.name())) {
                    callableQuery.registerOutParameter(op.position(), op.sqlType());
                } else {
                    callableQuery.registerOutParameter(op.name(), op.sqlType());
                }
            }
        }

        if (fetchSize > 0) {
            preparedQuery.setFetchSize(fetchSize);
        }

        if (fetchDirection != null) {
            preparedQuery.setFetchDirection(fetchDirection);
        }

        if (queryTimeout >= 0) {
            preparedQuery.setQueryTimeout(queryTimeout);
        }

        return preparedQuery;
    }

    private static Condition appendLimit(final Condition cond, final int count, final DBVersion dbVersion) {
        if (cond instanceof Criteria && ((Criteria) cond).getLimit() != null) {
            return cond;
        }

        final Criteria result = CF.criteria().where(cond);

        switch (dbVersion) {
            case ORACLE:
            case SQL_SERVER:
            case DB2:
                result.limit("FETCH FIRST " + count + " ROWS ONLY");
                break;

            default:
                result.limit(count);
        }

        return result;
    }

    private static String createCacheKey(final Method method, final String fullClassMethodName, final Object[] args, final Logger daoLogger) {
        String cachekey = null;

        if (kryoParser != null) {
            try {
                cachekey = kryoParser.serialize(N.toJSON(N.asMap(fullClassMethodName, args)));
            } catch (Exception e) {
                // ignore;
                daoLogger.warn("Failed to generated cache key and not able cache the result for method: " + fullClassMethodName);
            }
        } else {
            final List<Object> newArgs = Stream.of(args).map(it -> {
                if (it == null) {
                    return "null";
                }

                final Type<?> type = N.typeOf(it.getClass());

                if (type.isSerializable() || type.isCollection() || type.isMap() || type.isArray() || type.isEntity() || type.isEntityId()) {
                    return it;
                } else {
                    return it.toString();
                }
            }).toList();

            try {
                cachekey = N.toJSON(N.asMap(fullClassMethodName, newArgs));
            } catch (Exception e) {
                // ignore;
                daoLogger.warn("Failed to generated cache key and not able cache the result for method: " + fullClassMethodName);
            }
        }
        return cachekey;
    }

    @SuppressWarnings({ "rawtypes" })
    static <T, SB extends SQLBuilder, TD extends JdbcUtil.Dao<T, SB, TD>> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds,
            final SQLMapper sqlMapper, final Cache<String, Object> daoCache, final Executor executor) {
        N.checkArgNotNull(daoInterface, "daoInterface");
        N.checkArgNotNull(ds, "dataSource");

        final String key = ClassUtil.getCanonicalClassName(daoInterface) + "_" + System.identityHashCode(ds) + "_"
                + (sqlMapper == null ? "null" : System.identityHashCode(sqlMapper)) + "_" + (executor == null ? "null" : System.identityHashCode(executor));

        TD daoInstance = (TD) daoPool.get(key);

        if (daoInstance != null) {
            return daoInstance;
        }

        final Logger daoLogger = LoggerFactory.getLogger(daoInterface);

        final javax.sql.DataSource primaryDataSource = ds;
        final SQLMapper nonNullSQLMapper = sqlMapper == null ? new SQLMapper() : sqlMapper;
        final Executor nonNullExecutor = executor == null ? JdbcUtil.asyncExecutor.getExecutor() : executor;
        final AsyncExecutor asyncExecutor = new AsyncExecutor(nonNullExecutor);
        final boolean isUnchecked = JdbcUtil.UncheckedDao.class.isAssignableFrom(daoInterface);
        final boolean isCrudDao = JdbcUtil.CrudDao.class.isAssignableFrom(daoInterface) || JdbcUtil.UncheckedCrudDao.class.isAssignableFrom(daoInterface);
        final boolean isCrudDaoL = JdbcUtil.CrudDaoL.class.isAssignableFrom(daoInterface) || JdbcUtil.UncheckedCrudDaoL.class.isAssignableFrom(daoInterface);

        final List<Class<?>> allInterfaces = StreamEx.of(ClassUtil.getAllInterfaces(daoInterface)).prepend(daoInterface).toList();

        final DBVersion dbVersion = JdbcUtil.getDBVersion(ds);
        final boolean addLimitForSingleQuery = StreamEx.of(allInterfaces)
                .flatMapp(cls -> cls.getAnnotations())
                .select(Dao.Config.class)
                .map(it -> it.addLimitForSingleQuery())
                .first()
                .orElse(false);

        java.lang.reflect.Type[] typeArguments = null;

        if (N.notNullOrEmpty(daoInterface.getGenericInterfaces()) && daoInterface.getGenericInterfaces()[0] instanceof ParameterizedType) {
            final ParameterizedType parameterizedType = (ParameterizedType) daoInterface.getGenericInterfaces()[0];
            typeArguments = parameterizedType.getActualTypeArguments();

            if (typeArguments.length >= 1 && typeArguments[0] instanceof Class) {
                if (!ClassUtil.isEntity((Class) typeArguments[0])) {
                    throw new IllegalArgumentException(
                            "Entity Type parameter must be: Object.class or entity class with getter/setter methods. Can't be: " + typeArguments[0]);
                }
            }

            if (typeArguments.length >= 2 && SQLBuilder.class.isAssignableFrom((Class) typeArguments[1])) {
                if (!(typeArguments[1].equals(PSC.class) || typeArguments[1].equals(PAC.class) || typeArguments[1].equals(PLC.class))) {
                    throw new IllegalArgumentException("SQLBuilder Type parameter must be: SQLBuilder.PSC/PAC/PLC. Can't be: " + typeArguments[1]);
                }
            } else if (typeArguments.length >= 3 && SQLBuilder.class.isAssignableFrom((Class) typeArguments[2])) {
                if (!(typeArguments[2].equals(PSC.class) || typeArguments[2].equals(PAC.class) || typeArguments[2].equals(PLC.class))) {
                    throw new IllegalArgumentException("SQLBuilder Type parameter must be: SQLBuilder.PSC/PAC/PLC. Can't be: " + typeArguments[2]);
                }
            }

            if (isCrudDao) {
                final List<String> idFieldNames = ClassUtil.getIdFieldNames((Class) typeArguments[0]);

                if (idFieldNames.size() == 0) {
                    throw new IllegalArgumentException("To support CRUD operations by extending CrudDao interface, the entity class: " + typeArguments[0]
                            + " must have at least one field annotated with @Id");
                } else if (idFieldNames.size() == 1 && !SQLBuilder.class.isAssignableFrom((Class) typeArguments[1])) {
                    if (!(Primitives.wrap((Class) typeArguments[1]))
                            .isAssignableFrom(Primitives.wrap(ClassUtil.getPropGetMethod((Class) typeArguments[0], idFieldNames.get(0)).getReturnType()))) {
                        throw new IllegalArgumentException("The 'ID' type declared in Dao type parameters: " + typeArguments[1]
                                + " is not assignable from the id property type in the entity class: "
                                + ClassUtil.getPropGetMethod((Class) typeArguments[0], idFieldNames.get(0)).getReturnType());
                    }
                } else if (idFieldNames.size() > 1 && !(EntityId.class.equals(typeArguments[1]) || ClassUtil.isEntity((Class) typeArguments[1]))) {
                    throw new IllegalArgumentException("To support multiple ids, the 'ID' type type must be EntityId. It can't be: " + typeArguments[1]);
                }
            }
        }

        final Map<Method, Throwables.BiFunction<JdbcUtil.Dao, Object[], ?, Throwable>> methodInvokerMap = new HashMap<>();

        final List<Method> sqlMethods = StreamEx.of(allInterfaces)
                .reversed()
                .distinct()
                .flatMapp(clazz -> clazz.getDeclaredMethods())
                .filter(m -> !Modifier.isStatic(m.getModifiers()))
                .toList();

        final Class<T> entityClass = N.isNullOrEmpty(typeArguments) ? null : (Class) typeArguments[0];
        final boolean isDirtyMarker = entityClass == null ? false : ClassUtil.isDirtyMarker(entityClass);
        final Class<?> idClass = isCrudDao ? (isCrudDaoL ? Long.class : (Class) typeArguments[1]) : null;
        final boolean isEntityId = idClass != null && EntityId.class.isAssignableFrom(idClass);

        final Class<? extends SQLBuilder> sbc = N.isNullOrEmpty(typeArguments) ? PSC.class
                : (typeArguments.length >= 2 && SQLBuilder.class.isAssignableFrom((Class) typeArguments[1]) ? (Class) typeArguments[1]
                        : (typeArguments.length >= 3 && SQLBuilder.class.isAssignableFrom((Class) typeArguments[2]) ? (Class) typeArguments[2] : PSC.class));

        final NamingPolicy namingPolicy = sbc.equals(PSC.class) ? NamingPolicy.LOWER_CASE_WITH_UNDERSCORE
                : (sbc.equals(PAC.class) ? NamingPolicy.UPPER_CASE_WITH_UNDERSCORE : NamingPolicy.LOWER_CAMEL_CASE);

        final Function<Condition, SQLBuilder.SP> selectFromSQLBuilderFunc = sbc.equals(PSC.class) ? cond -> PSC.selectFrom(entityClass).append(cond).pair()
                : (sbc.equals(PAC.class) ? cond -> PAC.selectFrom(entityClass).append(cond).pair() : cond -> PLC.selectFrom(entityClass).append(cond).pair());

        final BiFunction<String, Condition, SQLBuilder.SP> singleQuerySQLBuilderFunc = sbc.equals(PSC.class)
                ? (selectPropName, cond) -> PSC.select(selectPropName).from(entityClass).append(cond).pair()
                : (sbc.equals(PAC.class) ? (selectPropName, cond) -> PAC.select(selectPropName).from(entityClass).append(cond).pair()
                        : (selectPropName, cond) -> PLC.select(selectPropName).from(entityClass).append(cond).pair());

        final BiFunction<Collection<String>, Condition, SQLBuilder> selectSQLBuilderFunc = sbc.equals(PSC.class)
                ? ((selectPropNames, cond) -> N.isNullOrEmpty(selectPropNames) ? PSC.selectFrom(entityClass).append(cond)
                        : PSC.select(selectPropNames).from(entityClass).append(cond))
                : (sbc.equals(PAC.class)
                        ? ((selectPropNames, cond) -> N.isNullOrEmpty(selectPropNames) ? PAC.selectFrom(entityClass).append(cond)
                                : PAC.select(selectPropNames).from(entityClass).append(cond))
                        : (selectPropNames, cond) -> (N.isNullOrEmpty(selectPropNames) ? PLC.selectFrom(entityClass).append(cond)
                                : PLC.select(selectPropNames).from(entityClass).append(cond)));

        final BiFunction<Collection<String>, Condition, SQLBuilder> namedSelectSQLBuilderFunc = sbc.equals(PSC.class)
                ? ((selectPropNames, cond) -> N.isNullOrEmpty(selectPropNames) ? NSC.selectFrom(entityClass).append(cond)
                        : NSC.select(selectPropNames).from(entityClass).append(cond))
                : (sbc.equals(PAC.class)
                        ? ((selectPropNames, cond) -> N.isNullOrEmpty(selectPropNames) ? NAC.selectFrom(entityClass).append(cond)
                                : NAC.select(selectPropNames).from(entityClass).append(cond))
                        : (selectPropNames, cond) -> (N.isNullOrEmpty(selectPropNames) ? NLC.selectFrom(entityClass).append(cond)
                                : NLC.select(selectPropNames).from(entityClass).append(cond)));

        final Function<Collection<String>, SQLBuilder> namedInsertSQLBuilderFunc = sbc.equals(PSC.class)
                ? (propNamesToInsert -> N.isNullOrEmpty(propNamesToInsert) ? NSC.insertInto(entityClass) : NSC.insert(propNamesToInsert).into(entityClass))
                : (sbc.equals(PAC.class)
                        ? (propNamesToInsert -> N.isNullOrEmpty(propNamesToInsert) ? NAC.insertInto(entityClass)
                                : NAC.insert(propNamesToInsert).into(entityClass))
                        : (propNamesToInsert -> N.isNullOrEmpty(propNamesToInsert) ? NLC.insertInto(entityClass)
                                : NLC.insert(propNamesToInsert).into(entityClass)));

        final Function<Class<?>, SQLBuilder> parameterizedUpdateFunc = sbc.equals(PSC.class) ? clazz -> PSC.update(clazz)
                : (sbc.equals(PAC.class) ? clazz -> PAC.update(clazz) : clazz -> PLC.update(clazz));

        final Function<Class<?>, SQLBuilder> parameterizedDeleteFromFunc = sbc.equals(PSC.class) ? clazz -> PSC.deleteFrom(clazz)
                : (sbc.equals(PAC.class) ? clazz -> PAC.deleteFrom(clazz) : clazz -> PLC.deleteFrom(clazz));

        final Function<Class<?>, SQLBuilder> namedUpdateFunc = sbc.equals(PSC.class) ? clazz -> NSC.update(clazz)
                : (sbc.equals(PAC.class) ? clazz -> NAC.update(clazz) : clazz -> NLC.update(clazz));

        final List<String> idPropNameList = entityClass == null ? N.emptyList() : ClassUtil.getIdFieldNames(entityClass);
        final boolean isNoId = entityClass == null || N.isNullOrEmpty(idPropNameList) || ClassUtil.isFakeId(idPropNameList);
        final Set<String> idPropNameSet = N.newHashSet(idPropNameList);
        final String oneIdPropName = isNoId ? null : idPropNameList.get(0);
        final EntityInfo entityInfo = entityClass == null ? null : ParserUtil.getEntityInfo(entityClass);
        final PropInfo idPropInfo = isNoId ? null : entityInfo.getPropInfo(oneIdPropName);
        final boolean isOneId = isNoId ? false : idPropNameList.size() == 1;
        final Condition idCond = isNoId ? null : isOneId ? CF.eq(oneIdPropName) : CF.and(StreamEx.of(idPropNameList).map(CF::eq).toList());

        String sql_getById = null;
        String sql_existsById = null;
        String sql_insertWithId = null;
        String sql_insertWithoutId = null;
        String sql_updateById = null;
        String sql_deleteById = null;

        if (sbc.equals(PSC.class)) {
            sql_getById = isNoId ? null : NSC.selectFrom(entityClass).where(idCond).sql();
            sql_existsById = isNoId ? null : NSC.select(SQLBuilder._1).from(entityClass).where(idCond).sql();
            sql_insertWithId = entityClass == null ? null : NSC.insertInto(entityClass).sql();
            sql_insertWithoutId = entityClass == null ? null
                    : (idPropNameSet.containsAll(ClassUtil.getPropNameList(entityClass)) ? sql_insertWithId : NSC.insertInto(entityClass, idPropNameSet).sql());
            sql_updateById = isNoId ? null : NSC.update(entityClass, idPropNameSet).where(idCond).sql();
            sql_deleteById = isNoId ? null : NSC.deleteFrom(entityClass).where(idCond).sql();
        } else if (sbc.equals(PAC.class)) {
            sql_getById = isNoId ? null : NAC.selectFrom(entityClass).where(idCond).sql();
            sql_existsById = isNoId ? null : NAC.select(SQLBuilder._1).from(entityClass).where(idCond).sql();
            sql_updateById = isNoId ? null : NAC.update(entityClass, idPropNameSet).where(idCond).sql();
            sql_insertWithId = entityClass == null ? null : NAC.insertInto(entityClass).sql();
            sql_insertWithoutId = entityClass == null ? null
                    : (idPropNameSet.containsAll(ClassUtil.getPropNameList(entityClass)) ? sql_insertWithId : NAC.insertInto(entityClass, idPropNameSet).sql());
            sql_deleteById = isNoId ? null : NAC.deleteFrom(entityClass).where(idCond).sql();
        } else {
            sql_getById = isNoId ? null : NLC.selectFrom(entityClass).where(idCond).sql();
            sql_existsById = isNoId ? null : NLC.select(SQLBuilder._1).from(entityClass).where(idCond).sql();
            sql_insertWithId = entityClass == null ? null : NLC.insertInto(entityClass).sql();
            sql_insertWithoutId = entityClass == null ? null
                    : (idPropNameSet.containsAll(ClassUtil.getPropNameList(entityClass)) ? sql_insertWithId : NLC.insertInto(entityClass, idPropNameSet).sql());
            sql_updateById = isNoId ? null : NLC.update(entityClass, idPropNameSet).where(idCond).sql();
            sql_deleteById = isNoId ? null : NLC.deleteFrom(entityClass).where(idCond).sql();
        }

        final ParsedSql namedGetByIdSQL = N.isNullOrEmpty(sql_getById) ? null : ParsedSql.parse(sql_getById);
        final ParsedSql namedExistsByIdSQL = N.isNullOrEmpty(sql_existsById) ? null : ParsedSql.parse(sql_existsById);
        final ParsedSql namedInsertWithIdSQL = N.isNullOrEmpty(sql_insertWithId) ? null : ParsedSql.parse(sql_insertWithId);
        final ParsedSql namedInsertWithoutIdSQL = N.isNullOrEmpty(sql_insertWithoutId) ? null : ParsedSql.parse(sql_insertWithoutId);
        final ParsedSql namedUpdateByIdSQL = N.isNullOrEmpty(sql_updateById) ? null : ParsedSql.parse(sql_updateById);
        final ParsedSql namedDeleteByIdSQL = N.isNullOrEmpty(sql_deleteById) ? null : ParsedSql.parse(sql_deleteById);

        final ImmutableMap<String, String> propColumnNameMap = ClassUtil.getProp2ColumnNameMap(entityClass, namingPolicy);

        final String[] returnColumnNames = isNoId ? N.EMPTY_STRING_ARRAY
                : (isOneId ? Array.of(propColumnNameMap.get(oneIdPropName))
                        : Stream.of(idPropNameList).map(idName -> propColumnNameMap.get(idName)).toArray(IntFunctions.ofStringArray()));

        final Tuple3<BiRowMapper<Object>, Function<Object, Object>, BiConsumer<Object, Object>> tp3 = JdbcUtil.getIdGeneratorGetterSetter(daoInterface,
                entityClass, namingPolicy, idClass);

        final BiRowMapper<Object> keyExtractor = tp3._1;
        final Function<Object, Object> idGetter = tp3._2;
        final BiConsumer<Object, Object> idSetter = tp3._3;

        final EntityInfo idEntityInfo = idClass != null && ClassUtil.isEntity(idClass) ? ParserUtil.getEntityInfo(idClass) : null;

        final Predicate<Object> isDefaultIdTester = isNoId ? id -> true
                : (isOneId ? id -> JdbcUtil.isDefaultIdPropValue(id)
                        : (isEntityId ? id -> Stream.of(((EntityId) id).entrySet()).allMatch(it -> JdbcUtil.isDefaultIdPropValue(it.getValue()))
                                : id -> Stream.of(idPropNameList).allMatch(idName -> JdbcUtil.isDefaultIdPropValue(idEntityInfo.getPropValue(id, idName)))));

        final JdbcUtil.BiParametersSetter<NamedQuery, Object> idParamSetter = isOneId ? (pq, id) -> pq.setObject(oneIdPropName, id, idPropInfo.dbType)
                : (isEntityId ? (pq, id) -> {
                    final EntityId entityId = (EntityId) id;
                    PropInfo propInfo = null;

                    for (String idName : idPropNameList) {
                        propInfo = entityInfo.getPropInfo(idName);
                        pq.setObject(idName, entityId.get(idName), propInfo.dbType);
                    }
                } : (pq, id) -> {
                    PropInfo propInfo = null;

                    for (String idName : idPropNameList) {
                        propInfo = idEntityInfo.getPropInfo(idName);
                        pq.setObject(idName, propInfo.getPropValue(id), propInfo.dbType);
                    }
                });

        final JdbcUtil.BiParametersSetter<NamedQuery, Object> idParamSetterByEntity = isOneId
                ? (pq, entity) -> pq.setObject(oneIdPropName, idPropInfo.getPropValue(entity), idPropInfo.dbType)
                : (pq, entity) -> pq.setParameters(entity);

        final Dao.SqlLogEnabled daoClassSqlLogAnno = StreamEx.of(allInterfaces)
                .flatMapp(cls -> cls.getAnnotations())
                .select(Dao.SqlLogEnabled.class)
                .first()
                .orNull();

        final Dao.PerfLog daoClassPerfLogAnno = StreamEx.of(allInterfaces).flatMapp(cls -> cls.getAnnotations()).select(Dao.PerfLog.class).first().orNull();

        final Dao.CacheResult daoClassCacheResultAnno = StreamEx.of(allInterfaces)
                .flatMapp(cls -> cls.getAnnotations())
                .select(Dao.CacheResult.class)
                .first()
                .orNull();

        final Dao.RefreshCache daoClassRefreshCacheAnno = StreamEx.of(allInterfaces)
                .flatMapp(cls -> cls.getAnnotations())
                .select(Dao.RefreshCache.class)
                .first()
                .orNull();

        final MutableBoolean hasCacheResult = MutableBoolean.of(false);
        final MutableBoolean hasRefreshCache = MutableBoolean.of(false);

        final List<Dao.Handler> daoClassHandlerList = StreamEx.of(allInterfaces)
                .reversed()
                .flatMapp(cls -> cls.getAnnotations())
                .filter(anno -> anno.annotationType().equals(Dao.Handler.class) || anno.annotationType().equals(DaoUtil.HandlerList.class))
                .flattMap(
                        anno -> anno.annotationType().equals(Dao.Handler.class) ? N.asList((Dao.Handler) anno) : N.asList(((DaoUtil.HandlerList) anno).value()))
                .toList();

        final Dao.Cache daoClassCacheAnno = StreamEx.of(allInterfaces).flatMapp(cls -> cls.getAnnotations()).select(Dao.Cache.class).first().orNull();

        final int capacity = daoClassCacheAnno == null ? 1000 : daoClassCacheAnno.capacity();
        final long evictDelay = daoClassCacheAnno == null ? 3000 : daoClassCacheAnno.evictDelay();

        final Cache<String, Object> cache = daoCache == null ? CacheFactory.createLocalCache(capacity, evictDelay) : daoCache;
        final Set<Method> nonDBOperationSet = new HashSet<>();

        final Map<String, String> sqlCache = new ConcurrentHashMap<>(0);
        final Map<String, ImmutableList<String>> sqlsCache = new ConcurrentHashMap<>(0);

        for (Method m : sqlMethods) {
            if (!Modifier.isPublic(m.getModifiers())) {
                continue;
            }

            final Class<?> declaringClass = m.getDeclaringClass();
            final String methodName = m.getName();
            final String simpleClassMethodName = declaringClass.getSimpleName() + "." + methodName;
            final String fullClassMethodName = ClassUtil.getCanonicalClassName(declaringClass) + "." + methodName;
            final Class<?>[] paramTypes = m.getParameterTypes();
            final Class<?> returnType = m.getReturnType();
            final int paramLen = paramTypes.length;

            final Dao.Sqls sqlsAnno = StreamEx.of(m.getAnnotations()).select(Dao.Sqls.class).onlyOne().orNull();
            List<String> sqlList = null;

            if (sqlsAnno != null) {
                if (Modifier.isAbstract(m.getModifiers())) {
                    throw new UnsupportedOperationException(
                            "Annotation @Sqls is only supported by interface methods with default implementation: default xxx dbOperationABC(someParameters, String ... sqls), not supported by abstract method: "
                                    + fullClassMethodName);
                }

                if (paramLen == 0 || !paramTypes[paramLen - 1].equals(String[].class)) {
                    throw new UnsupportedOperationException(
                            "To support sqls binding by @Sqls, the type of last parameter must be: String... sqls. It can't be : " + paramTypes[paramLen - 1]
                                    + " on method: " + fullClassMethodName);
                }

                if (sqlMapper == null) {
                    sqlList = Stream.of(sqlsAnno.value()).filter(Fn.notNullOrEmpty()).toList();
                } else {
                    sqlList = Stream.of(sqlsAnno.value())
                            .map(it -> sqlMapper.get(it) == null ? it : sqlMapper.get(it).sql())
                            .filter(Fn.notNullOrEmpty())
                            .toList();
                }
            }

            final String[] sqls = sqlList == null ? N.EMPTY_STRING_ARRAY : sqlList.toArray(new String[sqlList.size()]);

            Throwables.BiFunction<JdbcUtil.Dao, Object[], ?, Throwable> call = null;

            if (!Modifier.isAbstract(m.getModifiers())) {
                final MethodHandle methodHandle = createMethodHandle(m);

                call = (proxy, args) -> {
                    if (sqlsAnno != null) {
                        if (N.notNullOrEmpty((String[]) args[paramLen - 1])) {
                            throw new IllegalArgumentException(
                                    "The last parameter(String[]) of method annotated by @Sqls must be null, don't specify it. It will auto-filled by sqls from annotation @Sqls on the method: "
                                            + fullClassMethodName);
                        }

                        args[paramLen - 1] = sqls;
                    }

                    return methodHandle.bindTo(proxy).invokeWithArguments(args);
                };

            } else if (methodName.equals("executor") && Executor.class.isAssignableFrom(returnType) && paramLen == 0) {
                call = (proxy, args) -> nonNullExecutor;
            } else if (methodName.equals("asyncExecutor") && AsyncExecutor.class.isAssignableFrom(returnType) && paramLen == 0) {
                call = (proxy, args) -> asyncExecutor;
            } else if (m.getName().equals("targetEntityClass") && Class.class.isAssignableFrom(returnType) && paramLen == 0) {
                call = (proxy, args) -> entityClass;
            } else if (m.getName().equals("targetDaoInterface") && Class.class.isAssignableFrom(returnType) && paramLen == 0) {
                call = (proxy, args) -> daoInterface;
            } else if (methodName.equals("dataSource") && javax.sql.DataSource.class.isAssignableFrom(returnType) && paramLen == 0) {
                call = (proxy, args) -> primaryDataSource;
            } else if (methodName.equals("sqlMapper") && SQLMapper.class.isAssignableFrom(returnType) && paramLen == 0) {
                call = (proxy, args) -> nonNullSQLMapper;
            } else if (methodName.equals("cacheSql") && void.class.isAssignableFrom(returnType) && paramLen == 2 && paramTypes[0].equals(String.class)
                    && paramTypes[1].equals(String.class)) {
                call = (proxy, args) -> {
                    sqlCache.put(N.checkArgNotNullOrEmpty((String) args[0], "key"), N.checkArgNotNullOrEmpty((String) args[1], "sql"));
                    return null;
                };
            } else if (methodName.equals("cacheSqls") && void.class.isAssignableFrom(returnType) && paramLen == 2 && paramTypes[0].equals(String.class)
                    && paramTypes[1].equals(Collection.class)) {
                call = (proxy, args) -> {
                    sqlsCache.put(N.checkArgNotNullOrEmpty((String) args[0], "key"),
                            ImmutableList.copyOf(N.checkArgNotNullOrEmpty((Collection<String>) args[1], "sqls")));
                    return null;
                };
            } else if (methodName.equals("getCachedSql") && String.class.isAssignableFrom(returnType) && paramLen == 1 && paramTypes[0].equals(String.class)) {
                call = (proxy, args) -> sqlCache.get(args[0]);
            } else if (methodName.equals("getCachedSqls") && ImmutableList.class.isAssignableFrom(returnType) && paramLen == 1
                    && paramTypes[0].equals(String.class)) {
                call = (proxy, args) -> sqlsCache.get(args[0]);
            } else {
                final boolean isStreamReturn = Stream.class.isAssignableFrom(returnType) || ExceptionalStream.class.isAssignableFrom(returnType);
                final boolean throwsSQLException = StreamEx.of(m.getExceptionTypes()).anyMatch(e -> SQLException.class.equals(e));
                final Annotation sqlAnno = StreamEx.of(m.getAnnotations()).filter(anno -> sqlAnnoMap.containsKey(anno.annotationType())).first().orNull();

                if (declaringClass.equals(JdbcUtil.Dao.class) || declaringClass.equals(JdbcUtil.UncheckedDao.class)) {
                    if (methodName.equals("save") && paramLen == 1) {
                        call = (proxy, args) -> {
                            final Object entity = args[0];

                            if (entity instanceof DirtyMarker) {
                                final Collection<String> propNamesToSave = SQLBuilder.getInsertPropNames(entity, null);

                                N.checkArgNotNullOrEmpty(propNamesToSave, "propNamesToSave");

                                final String namedInsertSQL = namedInsertSQLBuilderFunc.apply(propNamesToSave).sql();

                                proxy.prepareNamedQuery(namedInsertSQL).setParameters(entity).update();

                                DirtyMarkerUtil.markDirty((DirtyMarker) entity, false);
                            } else {
                                final ParsedSql namedInsertSQL = isNoId || isDefaultIdTester.test(idGetter.apply(entity)) ? namedInsertWithoutIdSQL
                                        : namedInsertWithIdSQL;

                                proxy.prepareNamedQuery(namedInsertSQL).setParameters(entity).update();
                            }

                            return null;
                        };
                    } else if (methodName.equals("save") && paramLen == 2 && Collection.class.isAssignableFrom(paramTypes[1])) {
                        call = (proxy, args) -> {
                            final Object entity = args[0];
                            final Collection<String> propNamesToSave = (Collection<String>) args[1];

                            N.checkArgNotNullOrEmpty(propNamesToSave, "propNamesToSave");

                            final String namedInsertSQL = namedInsertSQLBuilderFunc.apply(propNamesToSave).sql();

                            proxy.prepareNamedQuery(namedInsertSQL).setParameters(entity).update();

                            if (entity instanceof DirtyMarker) {
                                DirtyMarkerUtil.markDirty((DirtyMarker) entity, propNamesToSave, false);
                            }

                            return null;
                        };
                    } else if (methodName.equals("save") && paramLen == 2 && String.class.equals(paramTypes[0])) {
                        call = (proxy, args) -> {
                            final String namedInsertSQL = (String) args[0];
                            final Object entity = args[1];

                            proxy.prepareNamedQuery(namedInsertSQL).setParameters(entity).update();

                            if (entity instanceof DirtyMarker) {
                                DirtyMarkerUtil.markDirty((DirtyMarker) entity, false);
                            }

                            return null;
                        };
                    } else if (methodName.equals("batchSave") && paramLen == 2 && Collection.class.isAssignableFrom(paramTypes[0])
                            && int.class.equals(paramTypes[1])) {
                        call = (proxy, args) -> {
                            final Collection<?> entities = (Collection<Object>) args[0];
                            final int batchSize = (Integer) args[1];
                            N.checkArgPositive(batchSize, "batchSize");

                            if (N.isNullOrEmpty(entities)) {
                                return 0;
                            }

                            final ParsedSql namedInsertSQL = isNoId || isDefaultIdTester.test(idGetter.apply(N.firstOrNullIfEmpty(entities)))
                                    ? namedInsertWithoutIdSQL
                                    : namedInsertWithIdSQL;

                            if (entities.size() <= batchSize) {
                                proxy.prepareNamedQuery(namedInsertSQL).addBatchParameters(entities).batchUpdate();
                            } else {
                                final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());

                                try {
                                    try (NamedQuery nameQuery = proxy.prepareNamedQuery(namedInsertSQL).closeAfterExecution(false)) {
                                        ExceptionalStream.of(entities)
                                                .splitToList(batchSize) //
                                                .forEach(bp -> nameQuery.addBatchParameters(bp).batchUpdate());
                                    }

                                    tran.commit();
                                } finally {
                                    tran.rollbackIfNotCommitted();
                                }
                            }

                            if (isDirtyMarker) {
                                for (Object e : entities) {
                                    DirtyMarkerUtil.markDirty((DirtyMarker) e, false);
                                }
                            }

                            return null;
                        };
                    } else if (methodName.equals("batchSave") && paramLen == 3 && Collection.class.isAssignableFrom(paramTypes[0])
                            && Collection.class.isAssignableFrom(paramTypes[1]) && int.class.equals(paramTypes[2])) {
                        call = (proxy, args) -> {
                            final Collection<?> entities = (Collection<Object>) args[0];

                            final Collection<String> propNamesToSave = (Collection<String>) args[1];
                            N.checkArgNotNullOrEmpty(propNamesToSave, "propNamesToSave");

                            final int batchSize = (Integer) args[2];
                            N.checkArgPositive(batchSize, "batchSize");

                            if (N.isNullOrEmpty(entities)) {
                                return 0;
                            }

                            final String namedInsertSQL = namedInsertSQLBuilderFunc.apply(propNamesToSave).sql();

                            if (entities.size() <= batchSize) {
                                proxy.prepareNamedQuery(namedInsertSQL).addBatchParameters(entities).batchUpdate();
                            } else {
                                final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());

                                try {
                                    try (NamedQuery nameQuery = proxy.prepareNamedQuery(namedInsertSQL).closeAfterExecution(false)) {
                                        ExceptionalStream.of(entities)
                                                .splitToList(batchSize) //
                                                .forEach(bp -> nameQuery.addBatchParameters(bp).batchUpdate());
                                    }

                                    tran.commit();
                                } finally {
                                    tran.rollbackIfNotCommitted();
                                }
                            }

                            if (isDirtyMarker) {
                                for (Object e : entities) {
                                    DirtyMarkerUtil.markDirty((DirtyMarker) e, false);
                                }
                            }

                            return null;
                        };
                    } else if (methodName.equals("batchSave") && paramLen == 3 && String.class.equals(paramTypes[0])
                            && Collection.class.isAssignableFrom(paramTypes[1]) && int.class.equals(paramTypes[2])) {
                        call = (proxy, args) -> {
                            final String namedInsertSQL = (String) args[0];
                            final Collection<?> entities = (Collection<Object>) args[1];
                            final int batchSize = (Integer) args[2];
                            N.checkArgPositive(batchSize, "batchSize");

                            if (N.isNullOrEmpty(entities)) {
                                return 0;
                            }

                            if (entities.size() <= batchSize) {
                                proxy.prepareNamedQuery(namedInsertSQL).addBatchParameters(entities).batchUpdate();
                            } else {
                                final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());

                                try {
                                    try (NamedQuery nameQuery = proxy.prepareNamedQuery(namedInsertSQL).closeAfterExecution(false)) {
                                        ExceptionalStream.of(entities)
                                                .splitToList(batchSize) //
                                                .forEach(bp -> nameQuery.addBatchParameters(bp).batchUpdate());
                                    }

                                    tran.commit();
                                } finally {
                                    tran.rollbackIfNotCommitted();
                                }
                            }

                            if (isDirtyMarker) {
                                for (Object e : entities) {
                                    DirtyMarkerUtil.markDirty((DirtyMarker) e, false);
                                }
                            }

                            return null;
                        };
                    } else if (methodName.equals("exists") && paramLen == 1 && Condition.class.isAssignableFrom(paramTypes[0])) {
                        call = (proxy, args) -> {
                            final Condition condArg = (Condition) args[0];
                            final Condition cond = addLimitForSingleQuery ? appendLimit(condArg, 1, dbVersion) : condArg;
                            final SP sp = singleQuerySQLBuilderFunc.apply(SQLBuilder._1, cond);
                            return proxy.prepareQuery(sp.sql).setFetchSize(1).setParameters(sp.parameters).exists();
                        };
                    } else if (methodName.equals("count") && paramLen == 1 && Condition.class.isAssignableFrom(paramTypes[0])) {
                        call = (proxy, args) -> {
                            final Condition condArg = (Condition) args[0];
                            final Condition cond = addLimitForSingleQuery ? appendLimit(condArg, 1, dbVersion) : condArg;
                            final SP sp = singleQuerySQLBuilderFunc.apply(SQLBuilder.COUNT_ALL, cond);
                            return proxy.prepareQuery(sp.sql).setFetchSize(1).setParameters(sp.parameters).queryForInt().orZero();
                        };
                    } else if (methodName.equals("findFirst") && paramLen == 1 && paramTypes[0].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Condition condArg = (Condition) args[0];
                            final Condition cond = addLimitForSingleQuery ? appendLimit(condArg, 1, dbVersion) : condArg;
                            final SP sp = selectFromSQLBuilderFunc.apply(cond);
                            return proxy.prepareQuery(sp.sql).setFetchSize(1).setParameters(sp.parameters).findFirst(entityClass);
                        };
                    } else if (methodName.equals("findFirst") && paramLen == 2 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(RowMapper.class)) {
                        call = (proxy, args) -> {
                            final Condition condArg = (Condition) args[0];
                            final Condition cond = addLimitForSingleQuery ? appendLimit(condArg, 1, dbVersion) : condArg;
                            final SP sp = selectFromSQLBuilderFunc.apply(cond);
                            return proxy.prepareQuery(sp.sql).setFetchSize(1).setParameters(sp.parameters).findFirst((RowMapper) args[1]);
                        };
                    } else if (methodName.equals("findFirst") && paramLen == 2 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final Condition condArg = (Condition) args[0];
                            final Condition cond = addLimitForSingleQuery ? appendLimit(condArg, 1, dbVersion) : condArg;
                            final SP sp = selectFromSQLBuilderFunc.apply(cond);
                            return proxy.prepareQuery(sp.sql).setFetchSize(1).setParameters(sp.parameters).findFirst((BiRowMapper) args[1]);
                        };
                    } else if (methodName.equals("findFirst") && paramLen == 2 && paramTypes[0].equals(Collection.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Condition condArg = (Condition) args[1];
                            final Condition cond = addLimitForSingleQuery ? appendLimit(condArg, 1, dbVersion) : condArg;
                            final SP sp = selectSQLBuilderFunc.apply((Collection<String>) args[0], cond).pair();
                            return proxy.prepareQuery(sp.sql).setFetchSize(1).setParameters(sp.parameters).findFirst(entityClass);
                        };
                    } else if (methodName.equals("findFirst") && paramLen == 3 && paramTypes[0].equals(Collection.class)
                            && paramTypes[1].equals(Condition.class) && paramTypes[2].equals(RowMapper.class)) {
                        call = (proxy, args) -> {
                            final Condition condArg = (Condition) args[1];
                            final Condition cond = addLimitForSingleQuery ? appendLimit(condArg, 1, dbVersion) : condArg;
                            final SP sp = selectSQLBuilderFunc.apply((Collection<String>) args[0], cond).pair();
                            return proxy.prepareQuery(sp.sql).setFetchSize(1).setParameters(sp.parameters).findFirst((RowMapper) args[2]);
                        };
                    } else if (methodName.equals("findFirst") && paramLen == 3 && paramTypes[0].equals(Collection.class)
                            && paramTypes[1].equals(Condition.class) && paramTypes[2].equals(BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final Condition condArg = (Condition) args[1];
                            final Condition cond = addLimitForSingleQuery ? appendLimit(condArg, 1, dbVersion) : condArg;
                            final SP sp = selectSQLBuilderFunc.apply((Collection<String>) args[0], cond).pair();
                            return proxy.prepareQuery(sp.sql).setFetchSize(1).setParameters(sp.parameters).findFirst((BiRowMapper) args[2]);
                        };
                    } else if (methodName.equals("queryForBoolean") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Condition condArg = (Condition) args[1];
                            final Condition cond = addLimitForSingleQuery ? appendLimit(condArg, 1, dbVersion) : condArg;
                            final SP sp = singleQuerySQLBuilderFunc.apply((String) args[0], cond);
                            return proxy.prepareQuery(sp.sql).setFetchSize(1).setParameters(sp.parameters).queryForBoolean();
                        };
                    } else if (methodName.equals("queryForChar") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Condition condArg = (Condition) args[1];
                            final Condition cond = addLimitForSingleQuery ? appendLimit(condArg, 1, dbVersion) : condArg;
                            final SP sp = singleQuerySQLBuilderFunc.apply((String) args[0], cond);
                            return proxy.prepareQuery(sp.sql).setFetchSize(1).setParameters(sp.parameters).queryForChar();
                        };
                    } else if (methodName.equals("queryForByte") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Condition condArg = (Condition) args[1];
                            final Condition cond = addLimitForSingleQuery ? appendLimit(condArg, 1, dbVersion) : condArg;
                            final SP sp = singleQuerySQLBuilderFunc.apply((String) args[0], cond);
                            return proxy.prepareQuery(sp.sql).setFetchSize(1).setParameters(sp.parameters).queryForByte();
                        };
                    } else if (methodName.equals("queryForShort") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Condition condArg = (Condition) args[1];
                            final Condition cond = addLimitForSingleQuery ? appendLimit(condArg, 1, dbVersion) : condArg;
                            final SP sp = singleQuerySQLBuilderFunc.apply((String) args[0], cond);
                            return proxy.prepareQuery(sp.sql).setFetchSize(1).setParameters(sp.parameters).queryForShort();
                        };
                    } else if (methodName.equals("queryForInt") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Condition condArg = (Condition) args[1];
                            final Condition cond = addLimitForSingleQuery ? appendLimit(condArg, 1, dbVersion) : condArg;
                            final SP sp = singleQuerySQLBuilderFunc.apply((String) args[0], cond);
                            return proxy.prepareQuery(sp.sql).setFetchSize(1).setParameters(sp.parameters).queryForInt();
                        };
                    } else if (methodName.equals("queryForLong") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Condition condArg = (Condition) args[1];
                            final Condition cond = addLimitForSingleQuery ? appendLimit(condArg, 1, dbVersion) : condArg;
                            final SP sp = singleQuerySQLBuilderFunc.apply((String) args[0], cond);
                            return proxy.prepareQuery(sp.sql).setFetchSize(1).setParameters(sp.parameters).queryForLong();
                        };
                    } else if (methodName.equals("queryForFloat") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Condition condArg = (Condition) args[1];
                            final Condition cond = addLimitForSingleQuery ? appendLimit(condArg, 1, dbVersion) : condArg;
                            final SP sp = singleQuerySQLBuilderFunc.apply((String) args[0], cond);
                            return proxy.prepareQuery(sp.sql).setFetchSize(1).setParameters(sp.parameters).queryForFloat();
                        };
                    } else if (methodName.equals("queryForDouble") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Condition condArg = (Condition) args[1];
                            final Condition cond = addLimitForSingleQuery ? appendLimit(condArg, 1, dbVersion) : condArg;
                            final SP sp = singleQuerySQLBuilderFunc.apply((String) args[0], cond);
                            return proxy.prepareQuery(sp.sql).setFetchSize(1).setParameters(sp.parameters).queryForDouble();
                        };
                    } else if (methodName.equals("queryForString") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Condition condArg = (Condition) args[1];
                            final Condition cond = addLimitForSingleQuery ? appendLimit(condArg, 1, dbVersion) : condArg;
                            final SP sp = singleQuerySQLBuilderFunc.apply((String) args[0], cond);
                            return proxy.prepareQuery(sp.sql).setFetchSize(1).setParameters(sp.parameters).queryForString();
                        };
                    } else if (methodName.equals("queryForDate") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Condition condArg = (Condition) args[1];
                            final Condition cond = addLimitForSingleQuery ? appendLimit(condArg, 1, dbVersion) : condArg;
                            final SP sp = singleQuerySQLBuilderFunc.apply((String) args[0], cond);
                            return proxy.prepareQuery(sp.sql).setFetchSize(1).setParameters(sp.parameters).queryForDate();
                        };
                    } else if (methodName.equals("queryForTime") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Condition condArg = (Condition) args[1];
                            final Condition cond = addLimitForSingleQuery ? appendLimit(condArg, 1, dbVersion) : condArg;
                            final SP sp = singleQuerySQLBuilderFunc.apply((String) args[0], cond);
                            return proxy.prepareQuery(sp.sql).setFetchSize(1).setParameters(sp.parameters).queryForTime();
                        };
                    } else if (methodName.equals("queryForTimestamp") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Condition condArg = (Condition) args[1];
                            final Condition cond = addLimitForSingleQuery ? appendLimit(condArg, 1, dbVersion) : condArg;
                            final SP sp = singleQuerySQLBuilderFunc.apply((String) args[0], cond);
                            return proxy.prepareQuery(sp.sql).setFetchSize(1).setParameters(sp.parameters).queryForTimestamp();
                        };
                    } else if (methodName.equals("queryForSingleResult") && paramLen == 3 && paramTypes[0].equals(Class.class)
                            && paramTypes[1].equals(String.class) && paramTypes[2].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Condition condArg = (Condition) args[2];
                            final Condition cond = addLimitForSingleQuery ? appendLimit(condArg, 1, dbVersion) : condArg;
                            final SP sp = singleQuerySQLBuilderFunc.apply((String) args[1], cond);
                            return proxy.prepareQuery(sp.sql).setFetchSize(1).setParameters(sp.parameters).queryForSingleResult((Class) args[0]);
                        };
                    } else if (methodName.equals("queryForSingleNonNull") && paramLen == 3 && paramTypes[0].equals(Class.class)
                            && paramTypes[1].equals(String.class) && paramTypes[2].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Condition condArg = (Condition) args[2];
                            final Condition cond = addLimitForSingleQuery ? appendLimit(condArg, 1, dbVersion) : condArg;
                            final SP sp = singleQuerySQLBuilderFunc.apply((String) args[1], cond);
                            return proxy.prepareQuery(sp.sql).setFetchSize(1).setParameters(sp.parameters).queryForSingleNonNull((Class) args[0]);
                        };
                    } else if (methodName.equals("queryForUniqueResult") && paramLen == 3 && paramTypes[0].equals(Class.class)
                            && paramTypes[1].equals(String.class) && paramTypes[2].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Condition condArg = (Condition) args[2];
                            final Condition cond = addLimitForSingleQuery ? appendLimit(condArg, 2, dbVersion) : condArg;
                            final SP sp = singleQuerySQLBuilderFunc.apply((String) args[1], cond);
                            return proxy.prepareQuery(sp.sql).setFetchSize(2).setParameters(sp.parameters).queryForUniqueResult((Class) args[0]);
                        };
                    } else if (methodName.equals("queryForUniqueNonNull") && paramLen == 3 && paramTypes[0].equals(Class.class)
                            && paramTypes[1].equals(String.class) && paramTypes[2].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Condition condArg = (Condition) args[2];
                            final Condition cond = addLimitForSingleQuery ? appendLimit(condArg, 2, dbVersion) : condArg;
                            final SP sp = singleQuerySQLBuilderFunc.apply((String) args[1], cond);
                            return proxy.prepareQuery(sp.sql).setFetchSize(2).setParameters(sp.parameters).queryForUniqueNonNull((Class) args[0]);
                        };
                    } else if (methodName.equals("query") && paramLen == 1 && paramTypes[0].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectFromSQLBuilderFunc.apply((Condition) args[0]);
                            return proxy.prepareQuery(sp.sql).setFetchDirection(FetchDirection.FORWARD).setParameters(sp.parameters).query();
                        };
                    } else if (methodName.equals("query") && paramLen == 2 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectSQLBuilderFunc.apply((Collection<String>) args[0], (Condition) args[1]).pair();
                            return proxy.prepareQuery(sp.sql).setFetchDirection(FetchDirection.FORWARD).setParameters(sp.parameters).query();
                        };
                    } else if (methodName.equals("query") && paramLen == 2 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(JdbcUtil.ResultExtractor.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectFromSQLBuilderFunc.apply((Condition) args[0]);
                            return proxy.prepareQuery(sp.sql)
                                    .setFetchDirection(FetchDirection.FORWARD)
                                    .setParameters(sp.parameters)
                                    .query((JdbcUtil.ResultExtractor) args[1]);
                        };
                    } else if (methodName.equals("query") && paramLen == 3 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(JdbcUtil.ResultExtractor.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectSQLBuilderFunc.apply((Collection<String>) args[0], (Condition) args[1]).pair();
                            return proxy.prepareQuery(sp.sql)
                                    .setFetchDirection(FetchDirection.FORWARD)
                                    .setParameters(sp.parameters)
                                    .query((JdbcUtil.ResultExtractor) args[2]);
                        };
                    } else if (methodName.equals("query") && paramLen == 2 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(BiResultExtractor.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectFromSQLBuilderFunc.apply((Condition) args[0]);
                            return proxy.prepareQuery(sp.sql)
                                    .setFetchDirection(FetchDirection.FORWARD)
                                    .setParameters(sp.parameters)
                                    .query((BiResultExtractor) args[1]);
                        };
                    } else if (methodName.equals("query") && paramLen == 3 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(BiResultExtractor.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectSQLBuilderFunc.apply((Collection<String>) args[0], (Condition) args[1]).pair();
                            return proxy.prepareQuery(sp.sql)
                                    .setFetchDirection(FetchDirection.FORWARD)
                                    .setParameters(sp.parameters)
                                    .query((BiResultExtractor) args[2]);
                        };
                    } else if (methodName.equals("list") && paramLen == 1 && paramTypes[0].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectFromSQLBuilderFunc.apply((Condition) args[0]);
                            return proxy.prepareQuery(sp.sql).setFetchDirection(FetchDirection.FORWARD).setParameters(sp.parameters).list(entityClass);
                        };
                    } else if (methodName.equals("list") && paramLen == 2 && paramTypes[0].equals(Condition.class) && paramTypes[1].equals(RowMapper.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectFromSQLBuilderFunc.apply((Condition) args[0]);
                            return proxy.prepareQuery(sp.sql).setFetchDirection(FetchDirection.FORWARD).setParameters(sp.parameters).list((RowMapper) args[1]);
                        };
                    } else if (methodName.equals("list") && paramLen == 2 && paramTypes[0].equals(Condition.class) && paramTypes[1].equals(BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectFromSQLBuilderFunc.apply((Condition) args[0]);
                            return proxy.prepareQuery(sp.sql)
                                    .setFetchDirection(FetchDirection.FORWARD)
                                    .setParameters(sp.parameters)
                                    .list((BiRowMapper) args[1]);
                        };
                    } else if (methodName.equals("list") && paramLen == 3 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(JdbcUtil.RowFilter.class) && paramTypes[2].equals(RowMapper.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectFromSQLBuilderFunc.apply((Condition) args[0]);
                            return proxy.prepareQuery(sp.sql)
                                    .setFetchDirection(FetchDirection.FORWARD)
                                    .setParameters(sp.parameters)
                                    .list((JdbcUtil.RowFilter) args[1], (RowMapper) args[2]);
                        };
                    } else if (methodName.equals("list") && paramLen == 3 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(JdbcUtil.BiRowFilter.class) && paramTypes[2].equals(BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectFromSQLBuilderFunc.apply((Condition) args[0]);
                            return proxy.prepareQuery(sp.sql)
                                    .setFetchDirection(FetchDirection.FORWARD)
                                    .setParameters(sp.parameters)
                                    .list((JdbcUtil.BiRowFilter) args[1], (BiRowMapper) args[2]);
                        };
                    } else if (methodName.equals("list") && paramLen == 2 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectSQLBuilderFunc.apply((Collection<String>) args[0], (Condition) args[1]).pair();
                            return proxy.prepareQuery(sp.sql).setFetchDirection(FetchDirection.FORWARD).setParameters(sp.parameters).list(entityClass);
                        };
                    } else if (methodName.equals("list") && paramLen == 3 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(RowMapper.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectSQLBuilderFunc.apply((Collection<String>) args[0], (Condition) args[1]).pair();
                            return proxy.prepareQuery(sp.sql).setFetchDirection(FetchDirection.FORWARD).setParameters(sp.parameters).list((RowMapper) args[2]);
                        };
                    } else if (methodName.equals("list") && paramLen == 3 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectSQLBuilderFunc.apply((Collection<String>) args[0], (Condition) args[1]).pair();
                            return proxy.prepareQuery(sp.sql)
                                    .setFetchDirection(FetchDirection.FORWARD)
                                    .setParameters(sp.parameters)
                                    .list((BiRowMapper) args[2]);
                        };
                    } else if (methodName.equals("list") && paramLen == 4 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(JdbcUtil.RowFilter.class) && paramTypes[3].equals(RowMapper.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectSQLBuilderFunc.apply((Collection<String>) args[0], (Condition) args[1]).pair();
                            return proxy.prepareQuery(sp.sql)
                                    .setFetchDirection(FetchDirection.FORWARD)
                                    .setParameters(sp.parameters)
                                    .list((JdbcUtil.RowFilter) args[2], (RowMapper) args[3]);
                        };
                    } else if (methodName.equals("list") && paramLen == 4 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(JdbcUtil.BiRowFilter.class) && paramTypes[3].equals(BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectSQLBuilderFunc.apply((Collection<String>) args[0], (Condition) args[1]).pair();
                            return proxy.prepareQuery(sp.sql)
                                    .setFetchDirection(FetchDirection.FORWARD)
                                    .setParameters(sp.parameters)
                                    .list((JdbcUtil.BiRowFilter) args[2], (BiRowMapper) args[3]);
                        };
                    } else if (methodName.equals("stream") && paramLen == 1 && paramTypes[0].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectFromSQLBuilderFunc.apply((Condition) args[0]);

                            final Throwables.Supplier<ExceptionalStream, SQLException> supplier = () -> proxy.prepareQuery(sp.sql)
                                    .setFetchDirection(FetchDirection.FORWARD)
                                    .setParameters(sp.parameters)
                                    .stream(entityClass);

                            return ExceptionalStream.of(supplier).flatMap(it -> it.get());
                        };
                    } else if (methodName.equals("stream") && paramLen == 2 && paramTypes[0].equals(Condition.class) && paramTypes[1].equals(RowMapper.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectFromSQLBuilderFunc.apply((Condition) args[0]);

                            final Throwables.Supplier<ExceptionalStream, SQLException> supplier = () -> proxy.prepareQuery(sp.sql)
                                    .setFetchDirection(FetchDirection.FORWARD)
                                    .setParameters(sp.parameters)
                                    .stream((RowMapper) args[1]);

                            return ExceptionalStream.of(supplier).flatMap(it -> it.get());
                        };
                    } else if (methodName.equals("stream") && paramLen == 2 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectFromSQLBuilderFunc.apply((Condition) args[0]);

                            final Throwables.Supplier<ExceptionalStream, SQLException> supplier = () -> proxy.prepareQuery(sp.sql)
                                    .setFetchDirection(FetchDirection.FORWARD)
                                    .setParameters(sp.parameters)
                                    .stream((BiRowMapper) args[1]);

                            return ExceptionalStream.of(supplier).flatMap(it -> it.get());
                        };
                    } else if (methodName.equals("stream") && paramLen == 3 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(JdbcUtil.RowFilter.class) && paramTypes[2].equals(RowMapper.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectFromSQLBuilderFunc.apply((Condition) args[0]);

                            final Throwables.Supplier<ExceptionalStream, SQLException> supplier = () -> proxy.prepareQuery(sp.sql)
                                    .setFetchDirection(FetchDirection.FORWARD)
                                    .setParameters(sp.parameters)
                                    .stream((JdbcUtil.RowFilter) args[1], (RowMapper) args[2]);

                            return ExceptionalStream.of(supplier).flatMap(it -> it.get());
                        };
                    } else if (methodName.equals("stream") && paramLen == 3 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(JdbcUtil.BiRowFilter.class) && paramTypes[2].equals(BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectFromSQLBuilderFunc.apply((Condition) args[0]);

                            final Throwables.Supplier<ExceptionalStream, SQLException> supplier = () -> proxy.prepareQuery(sp.sql)
                                    .setFetchDirection(FetchDirection.FORWARD)
                                    .setParameters(sp.parameters)
                                    .stream((JdbcUtil.BiRowFilter) args[1], (BiRowMapper) args[2]);

                            return ExceptionalStream.of(supplier).flatMap(it -> it.get());
                        };
                    } else if (methodName.equals("stream") && paramLen == 2 && paramTypes[0].equals(Collection.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectSQLBuilderFunc.apply((Collection<String>) args[0], (Condition) args[1]).pair();

                            final Throwables.Supplier<ExceptionalStream, SQLException> supplier = () -> proxy.prepareQuery(sp.sql)
                                    .setFetchDirection(FetchDirection.FORWARD)
                                    .setParameters(sp.parameters)
                                    .stream(entityClass);

                            return ExceptionalStream.of(supplier).flatMap(it -> it.get());
                        };
                    } else if (methodName.equals("stream") && paramLen == 3 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(RowMapper.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectSQLBuilderFunc.apply((Collection<String>) args[0], (Condition) args[1]).pair();

                            final Throwables.Supplier<ExceptionalStream, SQLException> supplier = () -> proxy.prepareQuery(sp.sql)
                                    .setFetchDirection(FetchDirection.FORWARD)
                                    .setParameters(sp.parameters)
                                    .stream((RowMapper) args[2]);

                            return ExceptionalStream.of(supplier).flatMap(it -> it.get());
                        };
                    } else if (methodName.equals("stream") && paramLen == 3 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectSQLBuilderFunc.apply((Collection<String>) args[0], (Condition) args[1]).pair();

                            final Throwables.Supplier<ExceptionalStream, SQLException> supplier = () -> proxy.prepareQuery(sp.sql)
                                    .setFetchDirection(FetchDirection.FORWARD)
                                    .setParameters(sp.parameters)
                                    .stream((BiRowMapper) args[2]);

                            return ExceptionalStream.of(supplier).flatMap(it -> it.get());
                        };
                    } else if (methodName.equals("stream") && paramLen == 4 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(JdbcUtil.RowFilter.class) && paramTypes[3].equals(RowMapper.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectSQLBuilderFunc.apply((Collection<String>) args[0], (Condition) args[1]).pair();

                            final Throwables.Supplier<ExceptionalStream, SQLException> supplier = () -> proxy.prepareQuery(sp.sql)
                                    .setFetchDirection(FetchDirection.FORWARD)
                                    .setParameters(sp.parameters)
                                    .stream((JdbcUtil.RowFilter) args[2], (RowMapper) args[3]);

                            return ExceptionalStream.of(supplier).flatMap(it -> it.get());
                        };
                    } else if (methodName.equals("stream") && paramLen == 4 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(JdbcUtil.BiRowFilter.class) && paramTypes[3].equals(BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectSQLBuilderFunc.apply((Collection<String>) args[0], (Condition) args[1]).pair();

                            final Throwables.Supplier<ExceptionalStream, SQLException> supplier = () -> proxy.prepareQuery(sp.sql)
                                    .setFetchDirection(FetchDirection.FORWARD)
                                    .setParameters(sp.parameters)
                                    .stream((JdbcUtil.BiRowFilter) args[2], (BiRowMapper) args[3]);

                            return ExceptionalStream.of(supplier).flatMap(it -> it.get());
                        };
                    } else if (methodName.equals("update") && paramLen == 2 && Map.class.equals(paramTypes[0])
                            && Condition.class.isAssignableFrom(paramTypes[1])) {
                        call = (proxy, args) -> {
                            final Map<String, Object> props = (Map<String, Object>) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotNullOrEmpty(props, "updateProps");

                            final SP sp = parameterizedUpdateFunc.apply(entityClass).set(props).append(cond).pair();
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).update();
                        };
                    } else if (methodName.equals("delete") && paramLen == 1 && Condition.class.isAssignableFrom(paramTypes[0])) {
                        call = (proxy, args) -> {
                            final SP sp = parameterizedDeleteFromFunc.apply(entityClass).append((Condition) args[0]).pair();
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).update();
                        };
                    } else {
                        call = (proxy, args) -> {
                            throw new UnsupportedOperationException("Unsupported operation: " + m);
                        };
                    }
                } else if (declaringClass.equals(JdbcUtil.CrudDao.class) || declaringClass.equals(JdbcUtil.UncheckedCrudDao.class)) {
                    if (methodName.equals("insert") && paramLen == 1) {
                        call = (proxy, args) -> {
                            final Object entity = args[0];

                            ParsedSql namedInsertSQL = null;

                            if (isDirtyMarker) {
                                final Collection<String> propNamesToInsert = SQLBuilder.getInsertPropNames(entity, null);
                                N.checkArgNotNullOrEmpty(propNamesToInsert, "propNamesToInsert");

                                namedInsertSQL = ParsedSql.parse(namedInsertSQLBuilderFunc.apply(propNamesToInsert).sql());
                            } else {
                                if (isDefaultIdTester.test(idGetter.apply(entity))) {
                                    namedInsertSQL = namedInsertWithoutIdSQL;
                                } else {
                                    namedInsertSQL = namedInsertWithIdSQL;
                                }
                            }

                            Object newId = proxy.prepareNamedQuery(namedInsertSQL, returnColumnNames)
                                    .setParameters(entity)
                                    .insert(keyExtractor)
                                    .ifPresent(ret -> idSetter.accept(ret, entity))
                                    .orElse(idGetter.apply(entity));

                            if (isDirtyMarker) {
                                DirtyMarkerUtil.markDirty((DirtyMarker) entity, false);
                            }

                            return newId;
                        };
                    } else if (methodName.equals("insert") && paramLen == 2 && Collection.class.isAssignableFrom(paramTypes[1])) {
                        call = (proxy, args) -> {
                            final Object entity = args[0];
                            final Collection<String> propNamesToInsert = (Collection<String>) args[1];
                            N.checkArgNotNullOrEmpty(propNamesToInsert, "propNamesToInsert");

                            final String namedInsertSQL = namedInsertSQLBuilderFunc.apply(propNamesToInsert).sql();

                            final Object id = proxy.prepareNamedQuery(namedInsertSQL, returnColumnNames)
                                    .setParameters(entity)
                                    .insert(keyExtractor)
                                    .ifPresent(ret -> idSetter.accept(ret, entity))
                                    .orElse(idGetter.apply(entity));

                            if (isDirtyMarker) {
                                DirtyMarkerUtil.markDirty((DirtyMarker) entity, propNamesToInsert, false);
                            }

                            return id;
                        };
                    } else if (methodName.equals("insert") && paramLen == 2 && String.class.equals(paramTypes[0])) {
                        call = (proxy, args) -> {
                            final String namedInsertSQL = (String) args[0];
                            final Object entity = args[1];

                            final Object id = proxy.prepareNamedQuery(namedInsertSQL, returnColumnNames)
                                    .setParameters(entity)
                                    .insert(keyExtractor)
                                    .ifPresent(ret -> idSetter.accept(ret, entity))
                                    .orElse(idGetter.apply(entity));

                            if (isDirtyMarker) {
                                DirtyMarkerUtil.markDirty((DirtyMarker) entity, false);
                            }

                            return id;
                        };
                    } else if (methodName.equals("batchInsert") && paramLen == 2 && Collection.class.isAssignableFrom(paramTypes[0])
                            && int.class.equals(paramTypes[1])) {
                        call = (proxy, args) -> {
                            final Collection<?> entities = (Collection<Object>) args[0];
                            final int batchSize = (Integer) args[1];
                            N.checkArgPositive(batchSize, "batchSize");

                            if (N.isNullOrEmpty(entities)) {
                                return 0;
                            }

                            final boolean isDefaultIdPropValue = isDefaultIdTester.test(idGetter.apply(N.firstOrNullIfEmpty(entities)));
                            final ParsedSql namedInsertSQL = isDefaultIdPropValue ? namedInsertWithoutIdSQL : namedInsertWithIdSQL;
                            List<Object> ids = null;

                            if (entities.size() <= batchSize) {
                                ids = proxy.prepareNamedQuery(namedInsertSQL, returnColumnNames).addBatchParameters(entities).batchInsert(keyExtractor);
                            } else {
                                final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());

                                try {
                                    try (NamedQuery nameQuery = proxy.prepareNamedQuery(namedInsertSQL, returnColumnNames).closeAfterExecution(false)) {
                                        ids = ExceptionalStream.of(entities)
                                                .splitToList(batchSize)
                                                .flattMap(bp -> nameQuery.addBatchParameters(bp).batchInsert(keyExtractor))
                                                .toList();
                                    }

                                    tran.commit();
                                } finally {
                                    tran.rollbackIfNotCommitted();
                                }
                            }

                            if (N.notNullOrEmpty(ids) && Stream.of(ids).allMatch(Fn.isNull())) {
                                ids = new ArrayList<>();
                            }

                            if (N.notNullOrEmpty(ids) && N.notNullOrEmpty(entities) && ids.size() == N.size(entities)) {
                                int idx = 0;

                                for (Object e : entities) {
                                    idSetter.accept(ids.get(idx++), e);
                                }
                            }

                            if (isDirtyMarker) {
                                for (Object e : entities) {
                                    DirtyMarkerUtil.markDirty((DirtyMarker) e, false);
                                }
                            }

                            if (N.notNullOrEmpty(ids) && ids.size() != entities.size()) {
                                if (daoLogger.isWarnEnabled()) {
                                    daoLogger.warn("The size of returned id list: {} is different from the size of input entity list: {}", ids.size(),
                                            entities.size());
                                }
                            }

                            if (N.isNullOrEmpty(ids)) {
                                ids = Stream.of(entities).map(idGetter).toList();
                            }

                            return ids;
                        };
                    } else if (methodName.equals("batchInsert") && paramLen == 3 && Collection.class.isAssignableFrom(paramTypes[0])
                            && Collection.class.isAssignableFrom(paramTypes[1]) && int.class.equals(paramTypes[2])) {
                        call = (proxy, args) -> {
                            final Collection<?> entities = (Collection<Object>) args[0];

                            final Collection<String> propNamesToInsert = (Collection<String>) args[1];
                            N.checkArgNotNullOrEmpty(propNamesToInsert, "propNamesToInsert");

                            final int batchSize = (Integer) args[1];
                            N.checkArgPositive(batchSize, "batchSize");

                            if (N.isNullOrEmpty(entities)) {
                                return 0;
                            }

                            final String namedInsertSQL = namedInsertSQLBuilderFunc.apply(propNamesToInsert).sql();
                            List<Object> ids = null;

                            if (entities.size() <= batchSize) {
                                ids = proxy.prepareNamedQuery(namedInsertSQL, returnColumnNames).addBatchParameters(entities).batchInsert(keyExtractor);
                            } else {
                                final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());

                                try {
                                    try (NamedQuery nameQuery = proxy.prepareNamedQuery(namedInsertSQL, returnColumnNames).closeAfterExecution(false)) {
                                        ids = ExceptionalStream.of(entities)
                                                .splitToList(batchSize)
                                                .flattMap(bp -> nameQuery.addBatchParameters(bp).batchInsert(keyExtractor))
                                                .toList();
                                    }

                                    tran.commit();
                                } finally {
                                    tran.rollbackIfNotCommitted();
                                }
                            }

                            if (N.notNullOrEmpty(ids) && Stream.of(ids).allMatch(Fn.isNull())) {
                                ids = new ArrayList<>();
                            }

                            if (N.notNullOrEmpty(ids) && N.notNullOrEmpty(entities) && ids.size() == N.size(entities)) {
                                int idx = 0;

                                for (Object e : entities) {
                                    idSetter.accept(ids.get(idx++), e);
                                }
                            }

                            if (isDirtyMarker) {
                                for (Object e : entities) {
                                    DirtyMarkerUtil.markDirty((DirtyMarker) e, false);
                                }
                            }

                            if (N.notNullOrEmpty(ids) && ids.size() != entities.size()) {
                                if (daoLogger.isWarnEnabled()) {
                                    daoLogger.warn("The size of returned id list: {} is different from the size of input entity list: {}", ids.size(),
                                            entities.size());
                                }
                            }

                            if (N.isNullOrEmpty(ids)) {
                                ids = Stream.of(entities).map(idGetter).toList();
                            }

                            return ids;
                        };
                    } else if (methodName.equals("batchInsert") && paramLen == 3 && String.class.equals(paramTypes[0])
                            && Collection.class.isAssignableFrom(paramTypes[1]) && int.class.equals(paramTypes[2])) {
                        call = (proxy, args) -> {
                            final String namedInsertSQL = (String) args[0];
                            final Collection<?> entities = (Collection<Object>) args[1];
                            final int batchSize = (Integer) args[2];
                            N.checkArgPositive(batchSize, "batchSize");

                            if (N.isNullOrEmpty(entities)) {
                                return 0;
                            }

                            List<Object> ids = null;

                            if (entities.size() <= batchSize) {
                                ids = proxy.prepareNamedQuery(namedInsertSQL, returnColumnNames).addBatchParameters(entities).batchInsert(keyExtractor);
                            } else {
                                final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());

                                try {
                                    try (NamedQuery nameQuery = proxy.prepareNamedQuery(namedInsertSQL, returnColumnNames).closeAfterExecution(false)) {
                                        ids = ExceptionalStream.of(entities)
                                                .splitToList(batchSize)
                                                .flattMap(bp -> nameQuery.addBatchParameters(bp).batchInsert(keyExtractor))
                                                .toList();
                                    }

                                    tran.commit();
                                } finally {
                                    tran.rollbackIfNotCommitted();
                                }
                            }

                            if (N.notNullOrEmpty(ids) && Stream.of(ids).allMatch(Fn.isNull())) {
                                ids = new ArrayList<>();
                            }

                            if (N.notNullOrEmpty(ids) && N.notNullOrEmpty(entities) && ids.size() == N.size(entities)) {
                                int idx = 0;

                                for (Object e : entities) {
                                    idSetter.accept(ids.get(idx++), e);
                                }
                            }

                            if (isDirtyMarker) {
                                for (Object e : entities) {
                                    DirtyMarkerUtil.markDirty((DirtyMarker) e, false);
                                }
                            }

                            if (N.notNullOrEmpty(ids) && ids.size() != entities.size()) {
                                if (daoLogger.isWarnEnabled()) {
                                    daoLogger.warn("The size of returned id list: {} is different from the size of input entity list: {}", ids.size(),
                                            entities.size());
                                }
                            }

                            if (N.isNullOrEmpty(ids)) {
                                ids = Stream.of(entities).map(idGetter).toList();
                            }

                            return ids;
                        };
                    } else if (methodName.equals("gett")) {
                        if (paramLen == 1) {
                            call = (proxy,
                                    args) -> proxy.prepareNamedQuery(namedGetByIdSQL).setFetchSize(2).settParameters(args[0], idParamSetter).gett(entityClass);
                        } else {
                            call = (proxy, args) -> {
                                final Collection<String> selectPropNames = (Collection<String>) args[1];

                                if (N.isNullOrEmpty(selectPropNames)) {
                                    return proxy.prepareNamedQuery(namedGetByIdSQL).setFetchSize(2).settParameters(args[0], idParamSetter).gett(entityClass);

                                } else {
                                    return proxy.prepareNamedQuery(namedSelectSQLBuilderFunc.apply(selectPropNames, idCond).sql())
                                            .setFetchSize(2)
                                            .settParameters(args[0], idParamSetter)
                                            .gett(entityClass);
                                }
                            };
                        }
                    } else if (methodName.equals("batchGet") && paramLen == 3 && Collection.class.equals(paramTypes[0])
                            && Collection.class.equals(paramTypes[1]) && int.class.equals(paramTypes[2])) {
                        call = (proxy, args) -> {
                            final Collection<Object> ids = (Collection<Object>) args[0];
                            final Collection<String> selectPropNames = (Collection<String>) args[1];
                            final int batchSize = (Integer) args[2];

                            N.checkArgPositive(batchSize, "batchSize");

                            if (N.isNullOrEmpty(ids)) {
                                return new ArrayList<>();
                            }

                            final Object firstId = N.first(ids).get();
                            final boolean isMap = firstId instanceof Map;
                            final boolean isEntity = firstId != null && ClassUtil.isEntity(firstId.getClass());

                            N.checkArgument(idPropNameList.size() > 1 || !(isEntity || isMap || isEntityId),
                                    "Input 'ids' can not be EntityIds/Maps or entities for single id ");

                            final List idList = ids instanceof List ? (List) ids : new ArrayList(ids);
                            final List<T> resultList = new ArrayList<>(idList.size());

                            if (idPropNameList.size() == 1) {
                                String sql_selectPart = selectSQLBuilderFunc.apply(selectPropNames, idCond).sql();
                                sql_selectPart = sql_selectPart.substring(0, sql_selectPart.lastIndexOf('=')) + "IN ";

                                if (ids.size() >= batchSize) {
                                    final Joiner joiner = Joiner.with(", ", "(", ")").reuseCachedBuffer(true);

                                    for (int i = 0; i < batchSize; i++) {
                                        joiner.append('?');
                                    }

                                    final String qery = sql_selectPart + joiner.toString();

                                    try (PreparedQuery preparedQuery = proxy.prepareQuery(qery)
                                            .setFetchDirection(FetchDirection.FORWARD)
                                            .setFetchSize(batchSize)
                                            .closeAfterExecution(false)) {
                                        for (int i = 0, to = ids.size() - batchSize; i <= to; i += batchSize) {
                                            resultList.addAll(preparedQuery.setParameters(idList.subList(i, i + batchSize)).list(entityClass));
                                        }
                                    }
                                }

                                if (ids.size() % batchSize != 0) {
                                    final int remaining = ids.size() % batchSize;
                                    final Joiner joiner = Joiner.with(", ", "(", ")").reuseCachedBuffer(true);

                                    for (int i = 0; i < remaining; i++) {
                                        joiner.append('?');
                                    }

                                    final String qery = sql_selectPart + joiner.toString();
                                    resultList.addAll(proxy.prepareQuery(qery)
                                            .setFetchDirection(FetchDirection.FORWARD)
                                            .setFetchSize(batchSize)
                                            .setParameters(idList.subList(ids.size() - remaining, ids.size()))
                                            .list(entityClass));
                                }
                            } else {
                                if (ids.size() >= batchSize) {
                                    for (int i = 0, to = ids.size() - batchSize; i <= to; i += batchSize) {
                                        if (isEntityId) {
                                            resultList.addAll(proxy.list(CF.id2Cond(idList.subList(i, i + batchSize))));
                                        } else if (isMap) {
                                            resultList.addAll(proxy.list(CF.eqAndOr(idList.subList(i, i + batchSize))));
                                        } else {
                                            resultList.addAll(proxy.list(CF.eqAndOr(idList.subList(i, i + batchSize), idPropNameList)));
                                        }
                                    }
                                }

                                if (ids.size() % batchSize != 0) {
                                    final int remaining = ids.size() % batchSize;

                                    if (isEntityId) {
                                        resultList.addAll(proxy.list(CF.id2Cond(idList.subList(idList.size() - remaining, ids.size()))));
                                    } else if (isMap) {
                                        resultList.addAll(proxy.list(CF.eqAndOr(idList.subList(ids.size() - remaining, ids.size()))));
                                    } else {
                                        resultList.addAll(proxy.list(CF.eqAndOr(idList.subList(ids.size() - remaining, ids.size()), idPropNameList)));
                                    }
                                }
                            }

                            if (resultList.size() > ids.size()) {
                                throw new DuplicatedResultException(
                                        "The size of result: " + resultList.size() + " is bigger than the size of input ids: " + ids.size());
                            }

                            return resultList;
                        };
                    } else if (methodName.equals("exists") && paramLen == 1 && !Condition.class.isAssignableFrom(paramTypes[0])) {
                        call = (proxy, args) -> proxy.prepareNamedQuery(namedExistsByIdSQL).setFetchSize(1).settParameters(args[0], idParamSetter).exists();
                    } else if (methodName.equals("update") && paramLen == 1) {
                        if (isDirtyMarker) {
                            call = (proxy, args) -> {
                                final DirtyMarker entity = (DirtyMarker) args[0];
                                final String query = namedUpdateFunc.apply(entityClass).set(entity, idPropNameSet).where(idCond).sql();
                                final int result = proxy.prepareNamedQuery(query).setParameters(entity).update();

                                DirtyMarkerUtil.markDirty(entity, false);

                                return result;
                            };
                        } else {
                            call = (proxy, args) -> {
                                return proxy.prepareNamedQuery(namedUpdateByIdSQL).setParameters(args[0]).update();
                            };
                        }
                    } else if (methodName.equals("update") && paramLen == 2 && !Map.class.equals(paramTypes[0]) && Collection.class.equals(paramTypes[1])) {
                        call = (proxy, args) -> {
                            final Collection<String> propNamesToUpdate = (Collection<String>) args[1];
                            N.checkArgNotNullOrEmpty(propNamesToUpdate, "propNamesToUpdate");

                            final String query = namedUpdateFunc.apply(entityClass).set(propNamesToUpdate).where(idCond).sql();

                            final int result = proxy.prepareNamedQuery(query).setParameters(args[0]).update();

                            if (isDirtyMarker) {
                                DirtyMarkerUtil.markDirty((DirtyMarker) args[0], propNamesToUpdate, false);
                            }

                            return result;
                        };
                    } else if (methodName.equals("update") && paramLen == 2 && Map.class.equals(paramTypes[0])) {
                        call = (proxy, args) -> {
                            final Map<String, Object> props = (Map<String, Object>) args[0];
                            final Object id = args[1];
                            N.checkArgNotNullOrEmpty(props, "updateProps");

                            final String query = namedUpdateFunc.apply(entityClass).set(props.keySet()).where(idCond).sql();

                            return proxy.prepareNamedQuery(query).setParameters(props).settParameters(id, idParamSetter).update();
                        };
                    } else if (methodName.equals("batchUpdate") && paramLen == 2 && int.class.equals(paramTypes[1])) {
                        call = (proxy, args) -> {
                            final Collection<Object> entities = (Collection<Object>) args[0];
                            final int batchSize = (Integer) args[1];
                            N.checkArgPositive(batchSize, "batchSize");

                            if (N.isNullOrEmpty(entities)) {
                                return 0;
                            }

                            long result = 0;

                            if (entities.size() <= batchSize) {
                                result = N.sum(proxy.prepareNamedQuery(namedUpdateByIdSQL).addBatchParameters(entities).batchUpdate());
                            } else {
                                final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());

                                try {
                                    try (NamedQuery nameQuery = proxy.prepareNamedQuery(namedUpdateByIdSQL).closeAfterExecution(false)) {
                                        result = ExceptionalStream.of(entities)
                                                .splitToList(batchSize) //
                                                .sumInt(bp -> N.sum(nameQuery.addBatchParameters(bp).batchUpdate()))
                                                .orZero();
                                    }

                                    tran.commit();
                                } finally {
                                    tran.rollbackIfNotCommitted();
                                }
                            }

                            if (isDirtyMarker) {
                                for (Object e : entities) {
                                    DirtyMarkerUtil.markDirty((DirtyMarker) e, false);
                                }
                            }

                            return N.toIntExact(result);
                        };
                    } else if (methodName.equals("batchUpdate") && paramLen == 3 && int.class.equals(paramTypes[2])) {
                        call = (proxy, args) -> {
                            final Collection<Object> entities = (Collection<Object>) args[0];
                            final Collection<String> propNamesToUpdate = (Collection<String>) args[1];
                            final int batchSize = (Integer) args[2];
                            N.checkArgPositive(batchSize, "batchSize");

                            N.checkArgNotNullOrEmpty(propNamesToUpdate, "propNamesToUpdate");

                            if (N.isNullOrEmpty(entities)) {
                                return 0;
                            }

                            final String query = namedUpdateFunc.apply(entityClass).set(propNamesToUpdate).where(idCond).sql();
                            long result = 0;

                            if (entities.size() <= batchSize) {
                                result = N.sum(proxy.prepareNamedQuery(query).addBatchParameters(entities).batchUpdate());
                            } else {
                                final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());

                                try {
                                    try (NamedQuery nameQuery = proxy.prepareNamedQuery(query).closeAfterExecution(false)) {
                                        result = ExceptionalStream.of(entities)
                                                .splitToList(batchSize) //
                                                .sumInt(bp -> N.sum(nameQuery.addBatchParameters(bp).batchUpdate()))
                                                .orZero();
                                    }

                                    tran.commit();
                                } finally {
                                    tran.rollbackIfNotCommitted();
                                }
                            }

                            if (isDirtyMarker) {
                                for (Object e : entities) {
                                    DirtyMarkerUtil.markDirty((DirtyMarker) e, propNamesToUpdate, false);
                                }
                            }

                            return N.toIntExact(result);
                        };
                    } else if (methodName.equals("deleteById")) {
                        call = (proxy, args) -> proxy.prepareNamedQuery(namedDeleteByIdSQL).settParameters(args[0], idParamSetter).update();
                    } else if (methodName.equals("delete") && paramLen == 1 && !Condition.class.isAssignableFrom(paramTypes[0])) {
                        call = (proxy, args) -> proxy.prepareNamedQuery(namedDeleteByIdSQL).settParameters(args[0], idParamSetterByEntity).update();

                        //} else if (methodName.equals("delete") && paramLen == 2 && !Condition.class.isAssignableFrom(paramTypes[0])
                        //        && OnDeleteAction.class.equals(paramTypes[1])) {
                        //    call = (proxy, args) -> {
                        //        final Object entity = (args[0]);
                        //        final OnDeleteAction onDeleteAction = (OnDeleteAction) args[1];
                        //
                        //        if (onDeleteAction == null || onDeleteAction == OnDeleteAction.NO_ACTION) {
                        //            return proxy.prepareNamedQuery(namedDeleteByIdSQL).settParameters(entity, idParamSetterByEntity).update();
                        //        }
                        //
                        //        final Map<String, JoinInfo> entityJoinInfo = JoinInfo.getEntityJoinInfo(entityClass);
                        //
                        //        if (N.isNullOrEmpty(entityJoinInfo)) {
                        //            return proxy.prepareNamedQuery(namedDeleteByIdSQL).settParameters(entity, idParamSetterByEntity).update();
                        //        } else {
                        //            final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());
                        //            long result = 0;
                        //
                        //            try {
                        //                Tuple2<String, JdbcUtil.BiParametersSetter<PreparedStatement, Object>> tp = null;
                        //
                        //                for (JoinInfo propJoinInfo : entityJoinInfo.values()) {
                        //                    tp = onDeleteAction == OnDeleteAction.SET_NULL ? propJoinInfo.getSetNullSqlAndParamSetter(sbc)
                        //                            : propJoinInfo.getDeleteSqlAndParamSetter(sbc);
                        //
                        //                    result += proxy.prepareQuery(tp._1).setParameters(entity, tp._2).update();
                        //                }
                        //
                        //                result += proxy.prepareNamedQuery(namedDeleteByIdSQL).settParameters(entity, idParamSetterByEntity).update();
                        //
                        //                tran.commit();
                        //            } finally {
                        //                tran.rollbackIfNotCommitted();
                        //            }
                        //
                        //            return N.toIntExact(result);
                        //        }
                        //    };
                    } else if ((methodName.equals("batchDelete") || methodName.equals("batchDeleteByIds")) && paramLen == 2
                            && int.class.equals(paramTypes[1])) {

                        final JdbcUtil.BiParametersSetter<NamedQuery, Object> paramSetter = methodName.equals("batchDeleteByIds") ? idParamSetter
                                : idParamSetterByEntity;

                        call = (proxy, args) -> {
                            final Collection<Object> idsOrEntities = (Collection) args[0];
                            final int batchSize = (Integer) args[1];
                            N.checkArgPositive(batchSize, "batchSize");

                            if (N.isNullOrEmpty(idsOrEntities)) {
                                return 0;
                            }

                            if (idsOrEntities.size() <= batchSize) {
                                return N.sum(proxy.prepareNamedQuery(namedDeleteByIdSQL).addBatchParameters(idsOrEntities, paramSetter).batchUpdate());
                            } else {
                                final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());
                                long result = 0;

                                try {
                                    try (NamedQuery nameQuery = proxy.prepareNamedQuery(namedDeleteByIdSQL).closeAfterExecution(false)) {
                                        result = ExceptionalStream.of(idsOrEntities)
                                                .splitToList(batchSize)
                                                .sumInt(bp -> N.sum(nameQuery.addBatchParameters(bp, paramSetter).batchUpdate()))
                                                .orZero();
                                    }

                                    tran.commit();
                                } finally {
                                    tran.rollbackIfNotCommitted();
                                }

                                return N.toIntExact(result);
                            }
                        };
                        //    } else if (methodName.equals("batchDelete") && paramLen == 3 && OnDeleteAction.class.equals(paramTypes[1])
                        //        && int.class.equals(paramTypes[2])) {
                        //    final JdbcUtil.BiParametersSetter<NamedQuery, Object> paramSetter = idParamSetterByEntity;
                        //    
                        //    call = (proxy, args) -> {
                        //        final Collection<Object> entities = (Collection) args[0];
                        //        final OnDeleteAction onDeleteAction = (OnDeleteAction) args[1];
                        //        final int batchSize = (Integer) args[2];
                        //        N.checkArgPositive(batchSize, "batchSize");
                        //    
                        //        if (N.isNullOrEmpty(entities)) {
                        //            return 0;
                        //        } else if (onDeleteAction == null || onDeleteAction == OnDeleteAction.NO_ACTION) {
                        //            return ((JdbcUtil.CrudDao) proxy).batchDelete(entities, batchSize);
                        //        }
                        //    
                        //        final Map<String, JoinInfo> entityJoinInfo = JoinInfo.getEntityJoinInfo(entityClass);
                        //    
                        //        if (N.isNullOrEmpty(entityJoinInfo)) {
                        //            return ((JdbcUtil.CrudDao) proxy).batchDelete(entities, batchSize);
                        //        }
                        //    
                        //        final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());
                        //        long result = 0;
                        //    
                        //        try {
                        //            try (NamedQuery nameQuery = proxy.prepareNamedQuery(namedDeleteByIdSQL).closeAfterExecution(false)) {
                        //                result = ExceptionalStream.of(entities) //
                        //                        .splitToList(batchSize) //
                        //                        .sumLong(bp -> {
                        //                            long tmpResult = 0;
                        //    
                        //                            Tuple2<String, JdbcUtil.BiParametersSetter<PreparedStatement, Object>> tp = null;
                        //    
                        //                            for (JoinInfo propJoinInfo : entityJoinInfo.values()) {
                        //                                tp = onDeleteAction == OnDeleteAction.SET_NULL ? propJoinInfo.getSetNullSqlAndParamSetter(sbc)
                        //                                        : propJoinInfo.getDeleteSqlAndParamSetter(sbc);
                        //    
                        //                                tmpResult += N.sum(proxy.prepareQuery(tp._1).addBatchParameters2(bp, tp._2).batchUpdate());
                        //                            }
                        //    
                        //                            tmpResult += N.sum(nameQuery.addBatchParameters(bp, paramSetter).batchUpdate());
                        //    
                        //                            return tmpResult;
                        //                        })
                        //                        .orZero();
                        //            }
                        //    
                        //            tran.commit();
                        //        } finally {
                        //            tran.rollbackIfNotCommitted();
                        //        }
                        //    
                        //        return N.toIntExact(result);
                        //    };
                    } else {
                        call = (proxy, args) -> {
                            throw new UnsupportedOperationException("Unsupported operation: " + m);
                        };
                    }
                } else if (declaringClass.equals(JdbcUtil.JoinEntityHelper.class) || declaringClass.equals(JdbcUtil.UncheckedJoinEntityHelper.class)) {
                    if (methodName.equals("loadJoinEntities") && paramLen == 3 && !Collection.class.isAssignableFrom(paramTypes[0])
                            && String.class.isAssignableFrom(paramTypes[1]) && Collection.class.isAssignableFrom(paramTypes[2])) {
                        call = (proxy, args) -> {
                            final Object entity = args[0];
                            final String joinEntityPropName = (String) args[1];
                            final Collection<String> selectPropNames = (Collection<String>) args[2];
                            final JoinInfo propJoinInfo = JoinInfo.getPropJoinInfo(daoInterface, entityClass, joinEntityPropName);
                            final Tuple2<Function<Collection<String>, String>, JdbcUtil.BiParametersSetter<PreparedStatement, Object>> tp = propJoinInfo
                                    .getSelectSQLBuilderAndParamSetter(sbc);

                            final PreparedQuery preparedQuery = proxy.prepareQuery(tp._1.apply(selectPropNames)).setParameters(entity, tp._2);

                            if (propJoinInfo.joinPropInfo.type.isCollection()) {
                                final List<?> propEntities = preparedQuery.list(propJoinInfo.referencedEntityClass);

                                if (propJoinInfo.joinPropInfo.clazz.isAssignableFrom(propEntities.getClass())) {
                                    propJoinInfo.joinPropInfo.setPropValue(entity, propEntities);
                                } else {
                                    final Collection<Object> c = (Collection) N.newInstance(propJoinInfo.joinPropInfo.clazz);
                                    c.addAll(propEntities);
                                    propJoinInfo.joinPropInfo.setPropValue(entity, c);
                                }
                            } else {
                                propJoinInfo.joinPropInfo.setPropValue(entity, preparedQuery.findFirst(propJoinInfo.referencedEntityClass).orNull());
                            }

                            if (isDirtyMarker) {
                                DirtyMarkerUtil.markDirty((DirtyMarker) entity, propJoinInfo.joinPropInfo.name, false);
                            }

                            return null;
                        };
                    } else if (methodName.equals("loadJoinEntities") && paramLen == 3 && Collection.class.isAssignableFrom(paramTypes[0])
                            && String.class.isAssignableFrom(paramTypes[1]) && Collection.class.isAssignableFrom(paramTypes[2])) {
                        call = (proxy, args) -> {
                            final Collection<Object> entities = (Collection) args[0];
                            final String joinEntityPropName = (String) args[1];
                            final Collection<String> selectPropNames = (Collection<String>) args[2];
                            final JoinInfo propJoinInfo = JoinInfo.getPropJoinInfo(daoInterface, entityClass, joinEntityPropName);

                            if (N.isNullOrEmpty(entities)) {
                                // Do nothing.
                            } else if (entities.size() == 1) {
                                final Object entity = N.firstOrNullIfEmpty(entities);
                                final Tuple2<Function<Collection<String>, String>, JdbcUtil.BiParametersSetter<PreparedStatement, Object>> tp = propJoinInfo
                                        .getSelectSQLBuilderAndParamSetter(sbc);

                                final PreparedQuery preparedQuery = proxy.prepareQuery(tp._1.apply(selectPropNames)).setParameters(entity, tp._2);

                                if (propJoinInfo.joinPropInfo.type.isCollection()) {
                                    final List<?> propEntities = preparedQuery.list(propJoinInfo.referencedEntityClass);

                                    if (propJoinInfo.joinPropInfo.clazz.isAssignableFrom(propEntities.getClass())) {
                                        propJoinInfo.joinPropInfo.setPropValue(entity, propEntities);
                                    } else {
                                        final Collection<Object> c = (Collection) N.newInstance(propJoinInfo.joinPropInfo.clazz);
                                        c.addAll(propEntities);
                                        propJoinInfo.joinPropInfo.setPropValue(entity, c);
                                    }
                                } else {
                                    propJoinInfo.joinPropInfo.setPropValue(entity, preparedQuery.findFirst(propJoinInfo.referencedEntityClass).orNull());
                                }

                                if (isDirtyMarker) {
                                    DirtyMarkerUtil.markDirty((DirtyMarker) entity, propJoinInfo.joinPropInfo.name, false);
                                }
                            } else {
                                final Tuple2<BiFunction<Collection<String>, Integer, String>, JdbcUtil.BiParametersSetter<PreparedStatement, Collection<?>>> tp = propJoinInfo
                                        .getSelectSQLBuilderAndParamSetterForBatch(sbc);

                                if (propJoinInfo.isManyToManyJoin()) {
                                    final BiRowMapper<Object> biRowMapper = BiRowMapper.to(propJoinInfo.referencedEntityClass, true);
                                    final BiRowMapper<Pair<Object, Object>> pairBiRowMapper = (rs, cls) -> Pair.of(rs.getObject(1), biRowMapper.apply(rs, cls));

                                    final List<Pair<Object, Object>> joinPropEntities = proxy.prepareQuery(tp._1.apply(selectPropNames, entities.size()))
                                            .setParameters(entities, tp._2)
                                            .list(pairBiRowMapper);

                                    propJoinInfo.setJoinPropEntities(entities, Stream.of(joinPropEntities).groupTo(it -> it.left, it -> it.right));
                                } else {
                                    final List<?> joinPropEntities = proxy.prepareQuery(tp._1.apply(selectPropNames, entities.size()))
                                            .setParameters(entities, tp._2)
                                            .list(propJoinInfo.referencedEntityClass);

                                    propJoinInfo.setJoinPropEntities(entities, joinPropEntities);
                                }
                            }

                            return null;
                        };
                    } else if (methodName.equals("deleteJoinEntities") && paramLen == 2 && !Collection.class.isAssignableFrom(paramTypes[0])
                            && String.class.isAssignableFrom(paramTypes[1])) {
                        call = (proxy, args) -> {
                            final Object entity = args[0];
                            final String joinEntityPropName = (String) args[1];
                            final JoinInfo propJoinInfo = JoinInfo.getPropJoinInfo(daoInterface, entityClass, joinEntityPropName);
                            final Tuple2<String, JdbcUtil.BiParametersSetter<PreparedStatement, Object>> tp = propJoinInfo.getDeleteSqlAndParamSetter(sbc);

                            return proxy.prepareQuery(tp._1).setParameters(entity, tp._2).update();
                        };
                    } else if (methodName.equals("deleteJoinEntities") && paramLen == 2 && Collection.class.isAssignableFrom(paramTypes[0])
                            && String.class.isAssignableFrom(paramTypes[1])) {
                        call = (proxy, args) -> {
                            final Collection<Object> entities = (Collection) args[0];
                            final String joinEntityPropName = (String) args[1];
                            final JoinInfo propJoinInfo = JoinInfo.getPropJoinInfo(daoInterface, entityClass, joinEntityPropName);
                            final Tuple2<String, JdbcUtil.BiParametersSetter<PreparedStatement, Object>> tp = propJoinInfo.getDeleteSqlAndParamSetter(sbc);

                            if (N.isNullOrEmpty(entities)) {
                                return 0;
                            } else if (entities.size() == 1) {
                                return proxy.prepareQuery(tp._1).setParameters(N.firstOrNullIfEmpty(entities), tp._2).update();
                            } else {
                                return proxy.prepareQuery(tp._1).addBatchParametters(entities, tp._2).update();
                            }
                        };
                    } else {
                        call = (proxy, args) -> {
                            throw new UnsupportedOperationException("Unsupported operation: " + m);
                        };
                    }
                } else {
                    if (java.util.Optional.class.isAssignableFrom(returnType) || java.util.OptionalInt.class.isAssignableFrom(returnType)
                            || java.util.OptionalLong.class.isAssignableFrom(returnType) || java.util.OptionalDouble.class.isAssignableFrom(returnType)) {
                        throw new UnsupportedOperationException("the return type of the method: " + fullClassMethodName + " can't be: " + returnType
                                + ". Please use the OptionalXXX classes defined in com.landawn.abacus.util.u");
                    }

                    if (!(isUnchecked || throwsSQLException || isStreamReturn)) {
                        throw new UnsupportedOperationException("'throws SQLException' is not declared in method: " + fullClassMethodName
                                + ". It's required for Dao interface extends Dao. Don't want to throw SQLException? extends UncheckedDao");
                    }

                    if (isStreamReturn && throwsSQLException) {
                        throw new UnsupportedOperationException("'throws SQLException' is not allowed in method: " + fullClassMethodName
                                + " because its return type is Stream/ExceptionalStream which will be lazy evaluation");
                    }

                    final Class<?> lastParamType = paramLen == 0 ? null : paramTypes[paramLen - 1];

                    final QueryInfo queryInfo = sqlAnnoMap.get(sqlAnno.annotationType()).apply(sqlAnno, sqlMapper);
                    final String query = N.checkArgNotNullOrEmpty(queryInfo.sql, "sql can't be null or empty");
                    final int queryTimeout = queryInfo.queryTimeout;
                    final int fetchSize = queryInfo.fetchSize;
                    final boolean isBatch = queryInfo.isBatch;
                    final int tmpBatchSize = queryInfo.batchSize;
                    final OP op = queryInfo.op;
                    final boolean isSingleParameter = queryInfo.isSingleParameter;

                    final boolean returnGeneratedKeys = isNoId == false
                            && (sqlAnno.annotationType().equals(Dao.Insert.class) || sqlAnno.annotationType().equals(Dao.NamedInsert.class));

                    final boolean isCall = sqlAnno.annotationType().getSimpleName().endsWith("Call");
                    final boolean isNamedQuery = sqlAnno.annotationType().getSimpleName().startsWith("Named");

                    final Predicate<Class<?>> isRowMapperOrResultExtractor = it -> JdbcUtil.ResultExtractor.class.isAssignableFrom(it)
                            || BiResultExtractor.class.isAssignableFrom(it) || RowMapper.class.isAssignableFrom(it) || BiRowMapper.class.isAssignableFrom(it);

                    if (isNamedQuery) {
                        // @Bind parameters are not always required for named query. It's not required if parameter is Entity/Map/EnityId/...
                        //    if (IntStreamEx.range(0, paramLen)
                        //            .noneMatch(i -> StreamEx.of(m.getParameterAnnotations()[i]).anyMatch(it -> it.annotationType().equals(Dao.Bind.class)))) {
                        //        throw new UnsupportedOperationException(
                        //                "@Bind parameters are required for named query but none is defined in method: " + fullClassMethodName);
                        //    }
                    } else {
                        if (IntStreamEx.range(0, paramLen)
                                .anyMatch(i -> StreamEx.of(m.getParameterAnnotations()[i]).anyMatch(it -> it.annotationType().equals(Dao.Bind.class)))) {
                            throw new UnsupportedOperationException("@Bind parameters are defined for non-named query in method: " + fullClassMethodName);
                        }
                    }

                    final int[] tmp = IntStreamEx.range(0, paramLen).filter(i -> !isRowMapperOrResultExtractor.test(paramTypes[i])).toArray();

                    if (N.notNullOrEmpty(tmp) && tmp[tmp.length - 1] != tmp.length - 1) {
                        throw new UnsupportedOperationException(
                                "RowMapper/ResultExtractor must be the last parameter but not in method: " + fullClassMethodName);
                    }

                    final Predicate<Class<?>> isRowFilter = it -> JdbcUtil.RowFilter.class.isAssignableFrom(it)
                            || JdbcUtil.BiRowFilter.class.isAssignableFrom(it);

                    final int[] tmp2 = IntStreamEx.of(tmp).filter(i -> !isRowFilter.test(paramTypes[i])).toArray();

                    if (N.notNullOrEmpty(tmp2) && tmp2[tmp2.length - 1] != tmp2.length - 1) {
                        throw new UnsupportedOperationException(
                                "RowFilter/BiRowFilter must be the last parameter or just before RowMapper/ResultExtractor but not in method: "
                                        + fullClassMethodName);
                    }

                    final Predicate<Class<?>> isParameterSetter = it -> JdbcUtil.ParametersSetter.class.isAssignableFrom(it)
                            || JdbcUtil.BiParametersSetter.class.isAssignableFrom(it) || JdbcUtil.TriParametersSetter.class.isAssignableFrom(it);

                    final int[] tmp3 = IntStreamEx.of(tmp2).filter(i -> !isParameterSetter.test(paramTypes[i])).toArray();

                    if (N.notNullOrEmpty(tmp3) && tmp3[tmp3.length - 1] != tmp3.length - 1) {
                        throw new UnsupportedOperationException(
                                "ParametersSetter/BiParametersSetter/TriParametersSetter must be the last parameter or just before RowFilter/BiRowFilter/RowMapper/ResultExtractor but not in method: "
                                        + fullClassMethodName);
                    }

                    final boolean hasRowMapperOrResultExtractor = paramLen > 0 && isRowMapperOrResultExtractor.test(lastParamType);

                    final boolean hasRowFilter = paramLen >= 2 && (JdbcUtil.RowFilter.class.isAssignableFrom(paramTypes[paramLen - 2])
                            || JdbcUtil.BiRowFilter.class.isAssignableFrom(paramTypes[paramLen - 2]));

                    if (hasRowFilter && !hasRowMapperOrResultExtractor) {
                        throw new UnsupportedOperationException(
                                "Parameter 'RowFilter/BiRowFilter' is not supported without last paramere to be 'RowMapper/BiRowMapper' in method: "
                                        + fullClassMethodName);
                    }

                    if (hasRowMapperOrResultExtractor) {
                        throw new UnsupportedOperationException(
                                "Retrieving result/record by 'ResultExtractor/BiResultExtractor/RowMapper/BiRowMapper' is not enabled at present. Can't use it in method: "
                                        + fullClassMethodName);
                    }

                    final int[] defineParamIndexes = IntStreamEx.of(tmp3)
                            .filter(i -> StreamEx.of(m.getParameterAnnotations()[i]).anyMatch(it -> it.annotationType().equals(Dao.Define.class)))
                            .toArray();

                    final int defineParamLen = N.len(defineParamIndexes);

                    if (defineParamLen > 0) {
                        //    if (defineParamIndexes[0] != 0 || defineParamIndexes[defineParamLen - 1] - defineParamIndexes[0] + 1 != defineParamLen) {
                        //        throw new UnsupportedOperationException(
                        //                "Parameters annotated with @Define must be at the head of the parameter list of method: " + fullClassMethodName);
                        //    }

                        //    for (int i = 0; i < defineParamLen; i++) {
                        //        if (!paramTypes[defineParamIndexes[i]].equals(String.class)) {
                        //            throw new UnsupportedOperationException("The type of parameter[" + i + "] annotated with @Define can't be: " + paramTypes[i]
                        //                    + " in method: " + fullClassMethodName + ". It must be String");
                        //        }
                        //    }
                    }

                    final String[] defines = defineParamLen == 0 ? N.EMPTY_STRING_ARRAY
                            : IntStreamEx.of(defineParamIndexes)
                                    .mapToObj(i -> StreamEx.of(m.getParameterAnnotations()[i]).select(Dao.Define.class).first().get().value())
                                    .map(it -> it.charAt(0) == '{' && it.charAt(it.length() - 1) == '}' ? it : "{" + it + "}")
                                    .toArray(IntFunctions.ofStringArray());

                    final int[] stmtParamIndexes = IntStreamEx.of(tmp3)
                            .filter(i -> StreamEx.of(m.getParameterAnnotations()[i]).noneMatch(it -> it.annotationType().equals(Dao.Define.class)))
                            .toArray();

                    final int stmtParamLen = stmtParamIndexes.length;

                    if (stmtParamLen == 1 && (ClassUtil.isEntity(paramTypes[stmtParamIndexes[0]]) || Map.class.isAssignableFrom(paramTypes[stmtParamIndexes[0]])
                            || EntityId.class.isAssignableFrom(paramTypes[stmtParamIndexes[0]])) && isNamedQuery == false) {
                        throw new UnsupportedOperationException(
                                "Using named query: @NamedSelect/NamedUpdate/NamedInsert/NamedDelete when parameter type is Entity/Map/EntityId in method: "
                                        + fullClassMethodName);
                    }

                    if (isSingleParameter && stmtParamLen != 1) {
                        throw new UnsupportedOperationException(
                                "Don't set 'isSingleParameter' to true if the count of statement/query parameter is not one in method: " + fullClassMethodName);
                    }

                    final List<Dao.OutParameter> outParameterList = StreamEx.of(m.getAnnotations())
                            .select(Dao.OutParameter.class)
                            .append(StreamEx.of(m.getAnnotations()).select(DaoUtil.OutParameterList.class).flatMapp(e -> e.value()))
                            .toList();

                    if (N.notNullOrEmpty(outParameterList)) {
                        if (!isCall) {
                            throw new UnsupportedOperationException(
                                    "@OutParameter annotations are only supported by method annotated by @Call, not supported in method: "
                                            + fullClassMethodName);
                        }

                        if (StreamEx.of(outParameterList).anyMatch(it -> N.isNullOrEmpty(it.name()) && it.position() < 0)) {
                            throw new UnsupportedOperationException(
                                    "One of the attribute: (name, position) of @OutParameter must be set in method: " + fullClassMethodName);
                        }
                    }

                    if (isBatch) {
                        if (!((stmtParamLen == 1 && Collection.class.isAssignableFrom(paramTypes[stmtParamIndexes[0]])) || (stmtParamLen == 2
                                && Collection.class.isAssignableFrom(paramTypes[stmtParamIndexes[0]]) && int.class.equals(paramTypes[stmtParamIndexes[1]])))) {
                            throw new UnsupportedOperationException("For batch operations(" + fullClassMethodName
                                    + "), the first parameter must be Collection. The second parameter is optional, it only can be int if it's set");
                        }

                        //    if (isNamedQuery == false) {
                        //        throw new UnsupportedOperationException(
                        //                "Only named query/sql is supported for batch operations(" + fullClassMethodName + ") at present");
                        //    }

                        //    if (isNamedQuery == false) {
                        //        final java.lang.reflect.Type[] genericParameterTypes = m.getGenericParameterTypes();
                        //        final java.lang.reflect.Type genericBatchParametersType = genericParameterTypes[stmtParamIndexes[0]];
                        //
                        //        boolean isArrayBatchParameters = List.class.isAssignableFrom(paramTypes[stmtParamIndexes[0]])
                        //                && genericBatchParametersType instanceof ParameterizedType
                        //                && Object[].class.isAssignableFrom((Class) ((ParameterizedType) genericBatchParametersType).getActualTypeArguments()[0]);
                        //
                        //        final boolean isListBatchParameters = Collection.class.isAssignableFrom(paramTypes[stmtParamIndexes[0]])
                        //                && genericBatchParametersType instanceof ParameterizedType
                        //                && List.class.isAssignableFrom((Class) ((ParameterizedType) genericBatchParametersType).getActualTypeArguments()[0]);
                        //
                        //        if (!(isArrayBatchParameters || isListBatchParameters)) {
                        //            throw new UnsupportedOperationException("For non-named batch operations(" + fullClassMethodName
                        //                    + "), the batch parameter type must be: List<? extends Object[]) or Collection<? extends List<?>>. The second parameter is optional, it only can be int if it's set");
                        //        }
                        //    }
                    }

                    final ParsedSql namedSql = isNamedQuery && defineParamLen == 0 ? ParsedSql.parse(query) : null;

                    final JdbcUtil.BiParametersSetter<AbstractPreparedQuery, Object[]> parametersSetter = createParametersSetter(m, fullClassMethodName,
                            paramTypes, paramLen, isBatch, isSingleParameter, isCall, isNamedQuery, namedSql, stmtParamLen, stmtParamIndexes, stmtParamLen);

                    //    final boolean isUpdateReturnType = returnType.equals(int.class) || returnType.equals(Integer.class) || returnType.equals(long.class)
                    //            || returnType.equals(Long.class) || returnType.equals(boolean.class) || returnType.equals(Boolean.class)
                    //            || returnType.equals(void.class);

                    final boolean isUpdateReturnType = returnType.equals(int.class) || returnType.equals(long.class) || returnType.equals(boolean.class)
                            || returnType.equals(void.class);

                    final boolean idDirtyMarkerReturnType = ClassUtil.isEntity(returnType) && DirtyMarker.class.isAssignableFrom(returnType);

                    if (sqlAnno.annotationType().equals(Dao.Select.class) || sqlAnno.annotationType().equals(Dao.NamedSelect.class)
                            || (isCall && !isUpdateReturnType)) {

                        final Throwables.BiFunction<AbstractPreparedQuery, Object[], T, Exception> queryFunc = createQueryFunctionByMethod(m,
                                hasRowMapperOrResultExtractor, hasRowFilter, op, fullClassMethodName);

                        // Getting ClassCastException. Not sure why query result is being casted Dao. It seems there is a bug in JDk compiler.
                        //   call = (proxy, args) -> queryFunc.apply(JdbcUtil.prepareQuery(proxy, ds, query, isNamedQuery, fetchSize, queryTimeout, returnGeneratedKeys, args, paramSetter), args);

                        int tmpFetchSize = fetchSize;

                        if (fetchSize <= 0) {
                            if (op == OP.exists || isExistsQuery(m, op, fullClassMethodName) || op == OP.findFirst || op == OP.queryForSingle) {
                                tmpFetchSize = 1;
                            } else if (op == OP.get || op == OP.queryForUnique) {
                                tmpFetchSize = 2;
                            } else if (op == OP.list || isListQuery(m, op, fullClassMethodName) || op == OP.query || op == OP.stream) {
                                // skip.
                            } else if (lastParamType != null
                                    && (ResultExtractor.class.isAssignableFrom(lastParamType) || BiResultExtractor.class.isAssignableFrom(lastParamType))) {
                                // skip.
                            } else if (Stream.class.isAssignableFrom(returnType) || ExceptionalStream.class.isAssignableFrom(returnType)
                                    || DataSet.class.isAssignableFrom(returnType)) {
                                // skip.
                            } else {
                                tmpFetchSize = 1;
                            }
                        }

                        final int finalFetchSize = tmpFetchSize;

                        call = (proxy, args) -> {
                            Object result = queryFunc.apply(prepareQuery(proxy, m, args, defineParamIndexes, defines, isNamedQuery, query, namedSql, isBatch,
                                    -1, finalFetchSize, FetchDirection.FORWARD, queryTimeout, returnGeneratedKeys, returnColumnNames, isCall, outParameterList)
                                            .settParameters(args, parametersSetter),
                                    args);

                            if (idDirtyMarkerReturnType) {
                                ((DirtyMarker) result).markDirty(false);
                            }

                            return result;
                        };
                    } else if (sqlAnno.annotationType().equals(Dao.Insert.class) || sqlAnno.annotationType().equals(Dao.NamedInsert.class)) {
                        if (isNoId) {
                            if (!returnType.isAssignableFrom(void.class)) {
                                throw new UnsupportedOperationException("The return type of insert operations(" + fullClassMethodName
                                        + ") for no id entities only can be: void. It can't be: " + returnType);
                            }
                        }

                        final TriFunction<Optional<Object>, Object, Boolean, ?> insertResultConvertor = void.class.equals(returnType)
                                ? (ret, entity, isEntity) -> null
                                : (Optional.class.equals(returnType) ? (ret, entity, isEntity) -> ret
                                        : (ret, entity, isEntity) -> ret.orElse(isEntity ? idGetter.apply(entity) : N.defaultValueOf(returnType)));

                        if (isBatch == false) {
                            if (!(returnType.isAssignableFrom(void.class) || idClass == null
                                    || Primitives.wrap(idClass).isAssignableFrom(Primitives.wrap(returnType)) || returnType.isAssignableFrom(Optional.class))) {
                                throw new UnsupportedOperationException("The return type of insert operations(" + fullClassMethodName
                                        + ") only can be: void or 'ID' type. It can't be: " + returnType);
                            }

                            call = (proxy, args) -> {
                                final boolean isEntity = stmtParamLen == 1 && args[stmtParamIndexes[0]] != null
                                        && ClassUtil.isEntity(args[stmtParamIndexes[0]].getClass());
                                final Object entity = isEntity ? args[stmtParamIndexes[0]] : null;

                                final Optional<Object> id = prepareQuery(proxy, m, args, defineParamIndexes, defines, isNamedQuery, query, namedSql, isBatch,
                                        -1, fetchSize, null, queryTimeout, returnGeneratedKeys, returnColumnNames, isCall, outParameterList)
                                                .settParameters(args, parametersSetter)
                                                .insert(keyExtractor);

                                if (isEntity && id.isPresent()) {
                                    id.ifPresent(ret -> idSetter.accept(ret, entity));
                                }

                                if (isEntity && entity instanceof DirtyMarker) {
                                    DirtyMarkerUtil.markDirty((DirtyMarker) entity, false);
                                }

                                return insertResultConvertor.apply(id, entity, isEntity);
                            };
                        } else {
                            if (!(returnType.equals(void.class) || returnType.isAssignableFrom(List.class))) {
                                throw new UnsupportedOperationException("The return type of batch insert operations(" + fullClassMethodName
                                        + ")  only can be: void/List<ID>/Collection<ID>. It can't be: " + returnType);
                            }

                            call = (proxy, args) -> {
                                final Collection<Object> batchParameters = (Collection) args[stmtParamIndexes[0]];
                                int batchSize = tmpBatchSize;

                                if (stmtParamLen == 2) {
                                    batchSize = (Integer) args[stmtParamIndexes[1]];
                                }

                                if (batchSize == 0) {
                                    batchSize = JdbcUtil.DEFAULT_BATCH_SIZE;
                                }

                                N.checkArgPositive(batchSize, "batchSize");

                                List<Object> ids = null;

                                if (N.isNullOrEmpty(batchParameters)) {
                                    ids = new ArrayList<>(0);
                                } else if (batchParameters.size() < batchSize) {
                                    AbstractPreparedQuery preparedQuery = null;

                                    if (isSingleParameter) {
                                        preparedQuery = prepareQuery(proxy, m, args, defineParamIndexes, defines, isNamedQuery, query, namedSql, isBatch,
                                                batchSize, fetchSize, null, queryTimeout, returnGeneratedKeys, returnColumnNames, isCall, outParameterList)
                                                        .addBatchParameters(batchParameters, ColumnOne.SET_OBJECT);
                                    } else {
                                        preparedQuery = prepareQuery(proxy, m, args, defineParamIndexes, defines, isNamedQuery, query, namedSql, isBatch,
                                                batchSize, fetchSize, null, queryTimeout, returnGeneratedKeys, returnColumnNames, isCall, outParameterList)
                                                        .addBatchParameters(batchParameters);
                                    }

                                    ids = preparedQuery.batchInsert();
                                } else {
                                    final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());

                                    try {
                                        try (AbstractPreparedQuery preparedQuery = prepareQuery(proxy, m, args, defineParamIndexes, defines, isNamedQuery,
                                                query, namedSql, isBatch, batchSize, fetchSize, null, queryTimeout, returnGeneratedKeys, returnColumnNames,
                                                isCall, outParameterList).closeAfterExecution(false)) {

                                            if (isSingleParameter) {
                                                ids = ExceptionalStream.of(batchParameters)
                                                        .splitToList(batchSize) //
                                                        .flattMap(bp -> preparedQuery.addBatchParameters(bp, ColumnOne.SET_OBJECT).batchInsert())
                                                        .toList();
                                            } else {
                                                ids = ExceptionalStream.of((Collection<List<?>>) (Collection) batchParameters)
                                                        .splitToList(batchSize) //
                                                        .flattMap(bp -> preparedQuery.addBatchParameters(bp).batchInsert())
                                                        .toList();
                                            }
                                        }

                                        tran.commit();
                                    } finally {
                                        tran.rollbackIfNotCommitted();
                                    }
                                }

                                final boolean isEntity = ClassUtil.isEntity(N.firstOrNullIfEmpty(batchParameters).getClass());

                                if (N.notNullOrEmpty(ids) && Stream.of(ids).allMatch(Fn.isNull())) {
                                    ids = new ArrayList<>();
                                }

                                if (isEntity) {
                                    final Collection<Object> entities = batchParameters;

                                    if (N.notNullOrEmpty(ids) && N.notNullOrEmpty(entities) && ids.size() == N.size(entities)) {
                                        int idx = 0;

                                        for (Object e : entities) {
                                            idSetter.accept(ids.get(idx++), e);
                                        }
                                    }

                                    if (N.firstOrNullIfEmpty(entities) instanceof DirtyMarker) {
                                        for (Object e : entities) {
                                            DirtyMarkerUtil.markDirty((DirtyMarker) e, false);
                                        }
                                    }

                                    if (N.isNullOrEmpty(ids)) {
                                        ids = Stream.of(entities).map(idGetter).toList();
                                    }
                                }

                                if (N.notNullOrEmpty(ids) && ids.size() != batchParameters.size()) {
                                    if (daoLogger.isWarnEnabled()) {
                                        daoLogger.warn("The size of returned id list: {} is different from the size of input entity list: {}", ids.size(),
                                                batchParameters.size());
                                    }
                                }

                                return void.class.equals(returnType) ? null : ids;
                            };
                        }
                    } else if (sqlAnno.annotationType().equals(Dao.Update.class) || sqlAnno.annotationType().equals(Dao.Delete.class)
                            || sqlAnno.annotationType().equals(Dao.NamedUpdate.class) || sqlAnno.annotationType().equals(Dao.NamedDelete.class)
                            || (sqlAnno.annotationType().equals(Dao.Call.class) && isUpdateReturnType)) {
                        if (!isUpdateReturnType) {
                            throw new UnsupportedOperationException("The return type of update/delete operations(" + fullClassMethodName
                                    + ") only can be: int/Integer/long/Long/boolean/Boolean/void. It can't be: " + returnType);
                        }

                        final LongFunction<?> updateResultConvertor = void.class.equals(returnType) ? updatedRecordCount -> null
                                : (Boolean.class.equals(Primitives.wrap(returnType)) ? updatedRecordCount -> updatedRecordCount > 0
                                        : (Integer.class.equals(Primitives.wrap(returnType)) ? updatedRecordCount -> N.toIntExact(updatedRecordCount)
                                                : LongFunction.identity()));

                        final boolean isLargeUpdate = op == OP.largeUpdate
                                || (op == OP.DEFAULT && (returnType.equals(long.class) || returnType.equals(Long.class)));

                        if (isBatch == false) {
                            final boolean idDirtyMarker = stmtParamLen == 1 && ClassUtil.isEntity(paramTypes[stmtParamIndexes[0]])
                                    && DirtyMarker.class.isAssignableFrom(paramTypes[stmtParamIndexes[0]]);

                            call = (proxy, args) -> {
                                final AbstractPreparedQuery preparedQuery = prepareQuery(proxy, m, args, defineParamIndexes, defines, isNamedQuery, query,
                                        namedSql, isBatch, -1, fetchSize, null, queryTimeout, returnGeneratedKeys, returnColumnNames, isCall, outParameterList)
                                                .settParameters(args, parametersSetter);

                                final long updatedRecordCount = isLargeUpdate ? preparedQuery.largeUpdate() : preparedQuery.update();

                                if (idDirtyMarker) {
                                    if (sqlAnno.annotationType().equals(Dao.NamedUpdate.class)) {
                                        ((DirtyMarker) args[stmtParamIndexes[0]]).markDirty(namedSql.getNamedParameters(), false);
                                    } else {
                                        ((DirtyMarker) args[stmtParamIndexes[0]]).markDirty(false);
                                    }
                                }

                                return updateResultConvertor.apply(updatedRecordCount);
                            };

                        } else {
                            call = (proxy, args) -> {
                                final Collection<Object> batchParameters = (Collection) args[stmtParamIndexes[0]];
                                int batchSize = tmpBatchSize;

                                if (stmtParamLen == 2) {
                                    batchSize = (Integer) args[stmtParamIndexes[1]];
                                }

                                if (batchSize == 0) {
                                    batchSize = JdbcUtil.DEFAULT_BATCH_SIZE;
                                }

                                N.checkArgPositive(batchSize, "batchSize");

                                long updatedRecordCount = 0;

                                if (N.isNullOrEmpty(batchParameters)) {
                                    updatedRecordCount = 0;
                                } else if (batchParameters.size() < batchSize) {
                                    AbstractPreparedQuery preparedQuery = null;

                                    if (isSingleParameter) {
                                        preparedQuery = prepareQuery(proxy, m, args, defineParamIndexes, defines, isNamedQuery, query, namedSql, isBatch,
                                                batchSize, fetchSize, null, queryTimeout, returnGeneratedKeys, returnColumnNames, isCall, outParameterList)
                                                        .addBatchParameters(batchParameters, ColumnOne.SET_OBJECT);
                                    } else {
                                        preparedQuery = prepareQuery(proxy, m, args, defineParamIndexes, defines, isNamedQuery, query, namedSql, isBatch,
                                                batchSize, fetchSize, null, queryTimeout, returnGeneratedKeys, returnColumnNames, isCall, outParameterList)
                                                        .addBatchParameters(batchParameters);
                                    }

                                    if (isLargeUpdate) {
                                        updatedRecordCount = N.sum(preparedQuery.largeBatchUpdate());
                                    } else {
                                        updatedRecordCount = N.sum(preparedQuery.batchUpdate());
                                    }
                                } else {
                                    final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());

                                    try {
                                        try (AbstractPreparedQuery preparedQuery = prepareQuery(proxy, m, args, defineParamIndexes, defines, isNamedQuery,
                                                query, namedSql, isBatch, batchSize, fetchSize, null, queryTimeout, returnGeneratedKeys, returnColumnNames,
                                                isCall, outParameterList).closeAfterExecution(false)) {

                                            if (isSingleParameter) {
                                                updatedRecordCount = ExceptionalStream.of(batchParameters)
                                                        .splitToList(batchSize) //
                                                        .sumLong(bp -> isLargeUpdate
                                                                ? N.sum(preparedQuery.addBatchParameters(bp, ColumnOne.SET_OBJECT).largeBatchUpdate())
                                                                : N.sum(preparedQuery.addBatchParameters(bp, ColumnOne.SET_OBJECT).batchUpdate()))
                                                        .orZero();
                                            } else {
                                                updatedRecordCount = ExceptionalStream.of((Collection<List<?>>) (Collection) batchParameters)
                                                        .splitToList(batchSize) //
                                                        .sumLong(bp -> isLargeUpdate //
                                                                ? N.sum(preparedQuery.addBatchParameters(bp).largeBatchUpdate())
                                                                : N.sum(preparedQuery.addBatchParameters(bp).batchUpdate()))
                                                        .orZero();
                                            }
                                        }

                                        tran.commit();
                                    } finally {
                                        tran.rollbackIfNotCommitted();
                                    }
                                }

                                if (N.firstOrNullIfEmpty(batchParameters) instanceof DirtyMarker) {
                                    if (sqlAnno.annotationType().equals(Dao.NamedUpdate.class)) {
                                        for (Object e : batchParameters) {
                                            ((DirtyMarker) e).markDirty(namedSql.getNamedParameters(), false);
                                        }
                                    } else {
                                        for (Object e : batchParameters) {
                                            ((DirtyMarker) e).markDirty(false);
                                        }
                                    }
                                }

                                return updateResultConvertor.apply(updatedRecordCount);
                            };
                        }
                    } else {
                        throw new UnsupportedOperationException(
                                "Unsupported sql annotation: " + sqlAnno.annotationType() + " in method: " + fullClassMethodName);
                    }
                }

                if (isStreamReturn) {
                    if (ExceptionalStream.class.isAssignableFrom(returnType)) {
                        final Throwables.BiFunction<JdbcUtil.Dao, Object[], ExceptionalStream, Exception> tmp = (Throwables.BiFunction) call;

                        call = (proxy, args) -> {
                            final Throwables.Supplier<ExceptionalStream, Exception> supplier = () -> tmp.apply(proxy, args);

                            return ExceptionalStream.of(supplier).flatMap(it -> it.get());
                        };
                    } else {
                        final Throwables.BiFunction<JdbcUtil.Dao, Object[], Stream, Exception> tmp = (Throwables.BiFunction) call;

                        call = (proxy, args) -> {
                            final Supplier<Stream> supplier = () -> Throwables.call(() -> tmp.apply(proxy, args));

                            return Stream.of(supplier).flatMap(it -> it.get());
                        };
                    }
                } else if (throwsSQLException == false) {
                    final Throwables.BiFunction<JdbcUtil.Dao, Object[], ?, Throwable> tmp = call;

                    call = (proxy, args) -> {
                        try {
                            return tmp.apply(proxy, args);
                        } catch (SQLException e) {
                            throw new UncheckedSQLException(e);
                        }
                    };

                    call = tmp;
                }
            }

            final boolean isNonDBOperation = StreamEx.of(m.getAnnotations()).anyMatch(anno -> anno.annotationType().equals(DaoUtil.NonDBOperation.class));

            if (isNonDBOperation) {
                nonDBOperationSet.add(m);

                if (daoLogger.isDebugEnabled()) {
                    daoLogger.debug("Non-DB operation method: " + simpleClassMethodName);
                }

                // ignore
            } else {
                final Dao.Transactional transactionalAnno = StreamEx.of(m.getAnnotations()).select(Dao.Transactional.class).last().orNull();

                //    if (transactionalAnno != null && Modifier.isAbstract(m.getModifiers())) {
                //        throw new UnsupportedOperationException(
                //                "Annotation @Transactional is only supported by interface methods with default implementation: default xxx dbOperationABC(someParameters, String ... sqls), not supported by abstract method: "
                //                       + fullClassMethodName);
                //    }

                final Dao.SqlLogEnabled sqlLogAnno = StreamEx.of(m.getAnnotations()).select(Dao.SqlLogEnabled.class).last().orElse(daoClassSqlLogAnno);
                final Dao.PerfLog perfLogAnno = StreamEx.of(m.getAnnotations()).select(Dao.PerfLog.class).last().orElse(daoClassPerfLogAnno);
                final boolean hasSqlLogAnno = sqlLogAnno != null;
                final boolean hasPerfLogAnno = perfLogAnno != null;

                final Throwables.BiFunction<JdbcUtil.Dao, Object[], ?, Throwable> tmp = call;

                if (transactionalAnno == null || transactionalAnno.propagation() == Propagation.SUPPORTS) {
                    if (hasSqlLogAnno || hasPerfLogAnno) {
                        call = (proxy, args) -> {
                            final boolean prevSqlLogEnabled = hasSqlLogAnno ? JdbcUtil.isSQLLogEnabled() : false;
                            final long prevMinExecutionTimeForSQLPerfLog = hasPerfLogAnno ? JdbcUtil.getMinExecutionTimeForSQLPerfLog() : -1;

                            if (hasSqlLogAnno) {
                                JdbcUtil.enableSQLLog(sqlLogAnno.value());
                            }

                            if (hasPerfLogAnno) {
                                JdbcUtil.setMinExecutionTimeForSQLPerfLog(perfLogAnno.minExecutionTimeForSql());
                            }

                            final long startTime = hasPerfLogAnno ? System.currentTimeMillis() : -1;

                            try {
                                return tmp.apply(proxy, args);
                            } finally {
                                if (hasPerfLogAnno) {
                                    if (perfLogAnno.minExecutionTimeForOperation() >= 0 && daoLogger.isInfoEnabled()) {
                                        final long elapsedTime = System.currentTimeMillis() - startTime;

                                        if (elapsedTime >= perfLogAnno.minExecutionTimeForOperation()) {
                                            daoLogger.info("[DAO-OP-PERF]: " + elapsedTime + ", " + simpleClassMethodName);
                                        }
                                    }

                                    JdbcUtil.setMinExecutionTimeForSQLPerfLog(prevMinExecutionTimeForSQLPerfLog);
                                }

                                if (hasSqlLogAnno) {
                                    JdbcUtil.enableSQLLog(prevSqlLogEnabled);
                                }
                            }
                        };

                    } else {
                        // Do not need to do anything.
                    }
                } else if (transactionalAnno.propagation() == Propagation.REQUIRED) {
                    call = (proxy, args) -> {
                        final boolean prevSqlLogEnabled = hasSqlLogAnno ? JdbcUtil.isSQLLogEnabled() : false;
                        final long prevMinExecutionTimeForSQLPerfLog = hasPerfLogAnno ? JdbcUtil.getMinExecutionTimeForSQLPerfLog() : -1;

                        if (hasSqlLogAnno) {
                            JdbcUtil.enableSQLLog(sqlLogAnno.value());
                        }

                        if (hasPerfLogAnno) {
                            JdbcUtil.setMinExecutionTimeForSQLPerfLog(perfLogAnno.minExecutionTimeForSql());
                        }

                        final long startTime = hasPerfLogAnno ? System.currentTimeMillis() : -1;

                        final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());
                        Object result = null;

                        try {
                            result = tmp.apply(proxy, args);

                            tran.commit();
                        } finally {
                            if (hasSqlLogAnno || hasPerfLogAnno) {
                                try {
                                    tran.rollbackIfNotCommitted();
                                } finally {
                                    if (hasPerfLogAnno) {
                                        if (perfLogAnno.minExecutionTimeForOperation() >= 0 && daoLogger.isInfoEnabled()) {
                                            final long elapsedTime = System.currentTimeMillis() - startTime;

                                            if (elapsedTime >= perfLogAnno.minExecutionTimeForOperation()) {
                                                daoLogger.info("[DAO-OP-PERF]: " + elapsedTime + ", " + simpleClassMethodName);
                                            }
                                        }

                                        JdbcUtil.setMinExecutionTimeForSQLPerfLog(prevMinExecutionTimeForSQLPerfLog);
                                    }

                                    if (hasSqlLogAnno) {
                                        JdbcUtil.enableSQLLog(prevSqlLogEnabled);
                                    }
                                }
                            } else {
                                tran.rollbackIfNotCommitted();
                            }
                        }

                        return result;
                    };
                } else if (transactionalAnno.propagation() == Propagation.REQUIRES_NEW) {
                    call = (proxy, args) -> {
                        final javax.sql.DataSource dataSource = proxy.dataSource();

                        return JdbcUtil.callNotInStartedTransaction(dataSource, () -> {
                            final boolean prevSqlLogEnabled = hasSqlLogAnno ? JdbcUtil.isSQLLogEnabled() : false;
                            final long prevMinExecutionTimeForSQLPerfLog = hasPerfLogAnno ? JdbcUtil.getMinExecutionTimeForSQLPerfLog() : -1;

                            if (hasSqlLogAnno) {
                                JdbcUtil.enableSQLLog(sqlLogAnno.value());
                            }

                            if (hasPerfLogAnno) {
                                JdbcUtil.setMinExecutionTimeForSQLPerfLog(perfLogAnno.minExecutionTimeForSql());
                            }

                            final long startTime = hasPerfLogAnno ? System.currentTimeMillis() : -1;

                            final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());
                            Object result = null;

                            try {
                                result = tmp.apply(proxy, args);

                                tran.commit();
                            } finally {
                                if (hasSqlLogAnno || hasPerfLogAnno) {
                                    try {
                                        tran.rollbackIfNotCommitted();
                                    } finally {
                                        if (hasPerfLogAnno) {
                                            if (perfLogAnno.minExecutionTimeForOperation() >= 0 && daoLogger.isInfoEnabled()) {
                                                final long elapsedTime = System.currentTimeMillis() - startTime;

                                                if (elapsedTime >= perfLogAnno.minExecutionTimeForOperation()) {
                                                    daoLogger.info("[DAO-OP-PERF]: " + elapsedTime + ", " + simpleClassMethodName);
                                                }
                                            }

                                            JdbcUtil.setMinExecutionTimeForSQLPerfLog(prevMinExecutionTimeForSQLPerfLog);
                                        }

                                        if (hasSqlLogAnno) {
                                            JdbcUtil.enableSQLLog(prevSqlLogEnabled);
                                        }
                                    }
                                } else {
                                    tran.rollbackIfNotCommitted();
                                }
                            }

                            return result;
                        });
                    };
                } else if (transactionalAnno.propagation() == Propagation.NOT_SUPPORTED) {
                    call = (proxy, args) -> {
                        final javax.sql.DataSource dataSource = proxy.dataSource();

                        if (hasSqlLogAnno || hasPerfLogAnno) {
                            return JdbcUtil.callNotInStartedTransaction(dataSource, () -> {
                                final boolean prevSqlLogEnabled = hasSqlLogAnno ? JdbcUtil.isSQLLogEnabled() : false;
                                final long prevMinExecutionTimeForSQLPerfLog = hasPerfLogAnno ? JdbcUtil.getMinExecutionTimeForSQLPerfLog() : -1;

                                if (hasSqlLogAnno) {
                                    JdbcUtil.enableSQLLog(sqlLogAnno.value());
                                }

                                if (hasPerfLogAnno) {
                                    JdbcUtil.setMinExecutionTimeForSQLPerfLog(perfLogAnno.minExecutionTimeForSql());
                                }

                                final long startTime = hasPerfLogAnno ? System.currentTimeMillis() : -1;

                                try {
                                    return tmp.apply(proxy, args);
                                } finally {
                                    if (hasPerfLogAnno) {
                                        if (perfLogAnno.minExecutionTimeForOperation() >= 0 && daoLogger.isInfoEnabled()) {
                                            final long elapsedTime = System.currentTimeMillis() - startTime;

                                            if (elapsedTime >= perfLogAnno.minExecutionTimeForOperation()) {
                                                daoLogger.info("[DAO-OP-PERF]: " + elapsedTime + ", " + simpleClassMethodName);
                                            }
                                        }

                                        JdbcUtil.setMinExecutionTimeForSQLPerfLog(prevMinExecutionTimeForSQLPerfLog);
                                    }

                                    if (hasSqlLogAnno) {
                                        JdbcUtil.enableSQLLog(prevSqlLogEnabled);
                                    }
                                }
                            });
                        } else {
                            return JdbcUtil.callNotInStartedTransaction(dataSource, () -> tmp.apply(proxy, args));
                        }
                    };
                }

                final Predicate<String> filterByMethodName = it -> N.notNullOrEmpty(it)
                        && (StringUtil.containsIgnoreCase(m.getName(), it) || Pattern.matches(it, m.getName()));

                final Dao.CacheResult cacheResultAnno = StreamEx.of(m.getAnnotations())
                        .select(Dao.CacheResult.class)
                        .last()
                        .orElse((daoClassCacheResultAnno != null && N.anyMatch(daoClassCacheResultAnno.filter(), filterByMethodName)) ? daoClassCacheResultAnno
                                : null);

                final Dao.RefreshCache refreshResultAnno = StreamEx.of(m.getAnnotations())
                        .select(Dao.RefreshCache.class)
                        .last()
                        .orElse((daoClassRefreshCacheAnno != null && N.anyMatch(daoClassRefreshCacheAnno.filter(), filterByMethodName))
                                ? daoClassRefreshCacheAnno
                                : null);

                if (cacheResultAnno != null && cacheResultAnno.disabled() == false) {
                    if (daoLogger.isDebugEnabled()) {
                        daoLogger.debug("Add CacheResult method: " + m);
                    }

                    if (Stream.of(notCacheableTypes).anyMatch(it -> it.isAssignableFrom(returnType))) {
                        throw new UnsupportedOperationException(
                                "The return type of method: " + simpleClassMethodName + " is not cacheable: " + m.getReturnType());
                    }

                    final String transferAttr = cacheResultAnno.transfer();

                    if (!(N.isNullOrEmpty(transferAttr) || N.asSet("none", "kryo").contains(transferAttr.toLowerCase()))) {
                        throw new UnsupportedOperationException(
                                "Unsupported 'cloneWhenReadFromCache' : " + transferAttr + " in annotation 'CacheResult' on method: " + simpleClassMethodName);
                    }

                    final Function<Object, Object> cloneFunc = N.isNullOrEmpty(transferAttr) || "none".equalsIgnoreCase(transferAttr) ? Fn.identity() : r -> {
                        if (r == null) {
                            return r;
                        } else if (isValuePresentMap.getOrDefault(r.getClass(), Fn.alwaysFalse()).test(r) == false) {
                            return r;
                        } else {
                            return kryoParser.clone(r);
                        }
                    };

                    final Throwables.BiFunction<JdbcUtil.Dao, Object[], ?, Throwable> temp = call;

                    call = (proxy, args) -> {
                        final String cachekey = createCacheKey(m, fullClassMethodName, args, daoLogger);

                        Object result = N.isNullOrEmpty(cachekey) ? null : cache.gett(cachekey);

                        if (result != null) {
                            return cloneFunc.apply(result);
                        }

                        result = temp.apply(proxy, args);

                        if (N.notNullOrEmpty(cachekey) && result != null) {
                            if (result instanceof DataSet) {
                                final DataSet dataSet = (DataSet) result;

                                if (dataSet.size() >= cacheResultAnno.minSize() && dataSet.size() <= cacheResultAnno.maxSize()) {
                                    cache.put(cachekey, cloneFunc.apply(result), cacheResultAnno.liveTime(), cacheResultAnno.idleTime());
                                }
                            } else if (result instanceof Collection) {
                                final Collection<Object> c = (Collection<Object>) result;

                                if (c.size() >= cacheResultAnno.minSize() && c.size() <= cacheResultAnno.maxSize()) {
                                    cache.put(cachekey, cloneFunc.apply(result), cacheResultAnno.liveTime(), cacheResultAnno.idleTime());
                                }
                            } else {
                                cache.put(cachekey, cloneFunc.apply(result), cacheResultAnno.liveTime(), cacheResultAnno.idleTime());
                            }
                        }

                        return result;
                    };

                    hasCacheResult.setTrue();
                }

                if (refreshResultAnno != null && refreshResultAnno.disabled() == false) {
                    if (daoLogger.isDebugEnabled()) {
                        daoLogger.debug("Add RefreshCache method: " + m);
                    }

                    final Throwables.BiFunction<JdbcUtil.Dao, Object[], ?, Throwable> temp = call;

                    call = (proxy, args) -> {
                        cache.clear();

                        return temp.apply(proxy, args);
                    };

                    hasRefreshCache.setTrue();
                }

                final List<JdbcUtil.Handler<?>> handlerList = StreamEx.of(m.getAnnotations())
                        .filter(anno -> anno.annotationType().equals(Dao.Handler.class) || anno.annotationType().equals(DaoUtil.HandlerList.class))
                        .flattMap(anno -> anno.annotationType().equals(Dao.Handler.class) ? N.asList((Dao.Handler) anno)
                                : N.asList(((DaoUtil.HandlerList) anno).value()))
                        .prepend(StreamEx.of(daoClassHandlerList).filter(h -> StreamEx.of(h.filter()).anyMatch(filterByMethodName)))
                        .map(handlerAnno -> N.notNullOrEmpty(handlerAnno.qualifier()) ? HandlerFactory.get(handlerAnno.qualifier())
                                : HandlerFactory.getOrCreate(handlerAnno.type()))
                        .onEach(handler -> N.checkArgNotNull(handler,
                                "No handler found/registered with qualifier or type in class/method: " + fullClassMethodName))
                        .reversed()
                        .toList();

                if (N.notNullOrEmpty(handlerList)) {
                    final Throwables.BiFunction<JdbcUtil.Dao, Object[], ?, Throwable> temp = call;
                    final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(m, ImmutableList.of(m.getParameterTypes()),
                            m.getReturnType());

                    call = (proxy, args) -> {
                        for (JdbcUtil.Handler handler : handlerList) {
                            handler.beforeInvoke(proxy, args, methodSignature);
                        }

                        Result<?, Exception> result = null;
                        Exception ex = null;

                        try {
                            result = Result.of(temp.apply(proxy, args), null);
                        } catch (Exception e) {
                            result = Result.of(null, e);
                            ex = e;
                        } finally {
                            try {
                                for (JdbcUtil.Handler handler : handlerList) {
                                    handler.afterInvoke(result, proxy, args, methodSignature);
                                }
                            } catch (Exception e) {
                                if (ex != null) {
                                    ex.addSuppressed(e);
                                } else {
                                    result = Result.of(null, e);
                                    ex = e;
                                }
                            }
                        }

                        return result.orElseThrow();
                    };
                }
            }

            methodInvokerMap.put(m, call);
        }

        if (hasRefreshCache.isTrue() && hasCacheResult.isFalse()) {
            throw new UnsupportedOperationException("Class: " + daoInterface
                    + " or its super interfaces or methods are annotated by @RefreshCache, but none of them is annotated with @CacheResult. "
                    + "Please remove the unnecessary @RefreshCache annotations or Add @CacheResult annotation if it's really needed.");
        }

        final Throwables.TriFunction<JdbcUtil.Dao, Method, Object[], ?, Throwable> proxyInvoker = (proxy, method, args) -> methodInvokerMap.get(method)
                .apply(proxy, args);
        final Class<TD>[] interfaceClasses = N.asArray(daoInterface);

        final InvocationHandler h = (proxy, method, args) -> {
            if (daoLogger.isDebugEnabled() && !nonDBOperationSet.contains(method)) {
                daoLogger.debug("Invoking Dao method: {} with args: {}", method.getName(), args);
            }

            return proxyInvoker.apply((JdbcUtil.Dao) proxy, method, args);
        };

        daoInstance = N.newProxyInstance(interfaceClasses, h);

        daoPool.put(key, daoInstance);

        return daoInstance;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    static @interface OutParameterList {
        Dao.OutParameter[] value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(value = { ElementType.METHOD, ElementType.TYPE })
    static @interface HandlerList {
        Dao.Handler[] value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(value = { ElementType.METHOD })
    static @interface NonDBOperation {

    }

    static final class QueryInfo {

        final String sql;
        final int queryTimeout;
        final int fetchSize;
        final boolean isBatch;
        final int batchSize;
        final OP op;
        final boolean isSingleParameter;

        QueryInfo(final String sql, final int queryTimeout, final int fetchSize, final boolean isBatch, final int batchSize, final OP op,
                final boolean isSingleParameter) {
            this.sql = sql;
            this.queryTimeout = queryTimeout;
            this.fetchSize = fetchSize;
            this.isBatch = isBatch;
            this.batchSize = batchSize;
            this.op = op;
            this.isSingleParameter = isSingleParameter;
        }
    }
}
