package com.landawn.abacus.samples.dao;

import com.landawn.abacus.samples.entity.User;
import com.landawn.abacus.util.JdbcUtil;
import com.landawn.abacus.util.JdbcUtil.Dao.Cache;
import com.landawn.abacus.util.JdbcUtil.Dao.CacheResult;
import com.landawn.abacus.util.JdbcUtil.Dao.PerfLog;
import com.landawn.abacus.util.JdbcUtil.Dao.RefreshCache;
import com.landawn.abacus.util.SQLBuilder;

@PerfLog(minExecutionTimeForSql = 101, minExecutionTimeForOperation = 100)
@CacheResult(transfer = "none")
@Cache(capacity = 1000, evictDelay = 6000)
@RefreshCache
public interface UncheckedUserDaoL extends JdbcUtil.UncheckedCrudDaoL<User, SQLBuilder.PSC, UncheckedUserDaoL>,
        JdbcUtil.UnckeckedReadOnlyCrudJoinEntityHelper<User, Long, SQLBuilder.PSC, UncheckedUserDaoL> {
}