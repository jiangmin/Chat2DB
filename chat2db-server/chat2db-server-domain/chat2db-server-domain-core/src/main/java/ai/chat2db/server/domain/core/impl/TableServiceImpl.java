package ai.chat2db.server.domain.core.impl;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import ai.chat2db.server.domain.api.param.*;
import ai.chat2db.server.domain.api.service.PinService;
import ai.chat2db.server.domain.api.service.TableService;
import ai.chat2db.server.domain.core.cache.CacheManage;
import ai.chat2db.server.domain.core.converter.PinTableConverter;
import ai.chat2db.server.domain.repository.entity.TableCacheDO;
import ai.chat2db.server.domain.repository.entity.TableCacheVersionDO;
import ai.chat2db.server.domain.repository.entity.TeamDO;
import ai.chat2db.server.domain.repository.entity.TeamUserDO;
import ai.chat2db.server.domain.repository.mapper.TableCacheMapper;
import ai.chat2db.server.domain.repository.mapper.TableCacheVersionMapper;
import ai.chat2db.server.tools.base.wrapper.result.ActionResult;
import ai.chat2db.server.tools.base.wrapper.result.DataResult;
import ai.chat2db.server.tools.base.wrapper.result.ListResult;
import ai.chat2db.server.tools.base.wrapper.result.PageResult;
import ai.chat2db.server.tools.common.util.ContextUtils;
import ai.chat2db.spi.DBManage;
import ai.chat2db.spi.MetaData;
import ai.chat2db.spi.SqlBuilder;
import ai.chat2db.spi.model.*;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.util.ResultSetUtils;
import ai.chat2db.spi.util.SqlUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static ai.chat2db.server.domain.core.cache.CacheKey.getColumnKey;
import static ai.chat2db.server.domain.core.cache.CacheKey.getTableKey;

/**
 * @author moji
 * @version DataSourceCoreServiceImpl.java, v 0.1 2022年09月23日 15:51 moji Exp $
 * @date 2022/09/23
 */
@Service
@Slf4j
public class TableServiceImpl implements TableService {

    @Autowired
    private PinService pinService;

    @Autowired
    private PinTableConverter pinTableConverter;

    @Autowired
    private TableCacheMapper tableCacheMapper;

    @Autowired
    private TableCacheVersionMapper tableCacheVersionMapper;

    @Override
    public DataResult<String> showCreateTable(ShowCreateTableParam param) {
        MetaData metaSchema = Chat2DBContext.getMetaData();
        String ddl = metaSchema.tableDDL(Chat2DBContext.getConnection(), param.getDatabaseName(), param.getSchemaName(), param.getTableName());
        return DataResult.of(ddl);
    }

    @Override
    public ActionResult drop(DropParam param) {
        DBManage metaSchema = Chat2DBContext.getDBManage();
        metaSchema.dropTable(Chat2DBContext.getConnection(), param.getDatabaseName(), param.getTableSchema(), param.getTableName());
        return ActionResult.isSuccess();
    }

    @Override
    public DataResult<String> createTableExample(String dbType) {
        String sql = Chat2DBContext.getDBConfig().getSimpleCreateTable();
        return DataResult.of(sql);
    }

    @Override
    public DataResult<String> alterTableExample(String dbType) {
        String sql = Chat2DBContext.getDBConfig().getSimpleAlterTable();
        return DataResult.of(sql);
    }

    @Override
    public DataResult<Table> query(TableQueryParam param, TableSelector selector) {
        MetaData metaSchema = Chat2DBContext.getMetaData();
        List<Table> tables = metaSchema.tables(Chat2DBContext.getConnection(), param.getDatabaseName(), param.getSchemaName(), param.getTableName());
        if (!CollectionUtils.isEmpty(tables)) {
            Table table = tables.get(0);
            table.setIndexList(
                    metaSchema.indexes(Chat2DBContext.getConnection(), param.getDatabaseName(), param.getSchemaName(), param.getTableName()));
            table.setColumnList(
                    metaSchema.columns(Chat2DBContext.getConnection(), param.getDatabaseName(), param.getSchemaName(), param.getTableName()));
            return DataResult.of(table);
        }
        return DataResult.of(null);
    }

    @Override
    public ListResult<Sql> buildSql(Table oldTable, Table newTable) {
        initOldTable(oldTable, newTable);
        SqlBuilder sqlBuilder = Chat2DBContext.getSqlBuilder();
        List<Sql> sqls = new ArrayList<>();
        if (oldTable == null) {
            sqls.add(Sql.builder().sql(sqlBuilder.buildCreateTableSql(newTable)).build());
        } else {
            sqls.add(Sql.builder().sql(sqlBuilder.buildModifyTaleSql(oldTable, newTable)).build());
        }
        return ListResult.of(sqls);
    }

    private void initOldTable(Table oldTable, Table newTable) {
        if (oldTable == null) {
            return;
        }
        Map<String, TableColumn> columnMap = oldTable.getColumnList().stream().collect(Collectors.toMap(TableColumn::getName, Function.identity()));
        for (TableColumn newColumn : newTable.getColumnList()) {
            TableColumn oldColumn = columnMap.get(newColumn.getName());
            if (oldColumn != null) {
                newColumn.setOldColumn(oldColumn);
            }
        }
    }

    @Override
    public PageResult<Table> pageQuery(TablePageQueryParam param, TableSelector selector) {
        LambdaQueryWrapper<TableCacheVersionDO> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(TableCacheVersionDO::getKey, buildKey(param.getDataSourceId(), param.getDatabaseName(), param.getSchemaName()));
        TableCacheVersionDO versionDO = tableCacheVersionMapper.selectOne(queryWrapper);
        if (param.isRefresh() || versionDO == null) {
            addDBCache(param, versionDO);
        }
        LambdaQueryWrapper<TableCacheDO> query = new LambdaQueryWrapper<>();
        query.eq(TableCacheDO::getVersion, versionDO.getVersion());
        query.eq(TableCacheDO::getDataSourceId, param.getDataSourceId());
        if (StringUtils.isNotBlank(param.getDatabaseName())) {
            query.eq(TableCacheDO::getDatabaseName, param.getDatabaseName());
        }
        if (StringUtils.isNotBlank(param.getSchemaName())) {
            query.eq(TableCacheDO::getSchemaName, param.getSchemaName());
        }
        if (StringUtils.isNotBlank(param.getSearchKey())) {
            query.like(TableCacheDO::getTableName, param.getSearchKey());
        }
        Page<TableCacheDO> page = new Page<>(param.getPageNo(), param.getPageSize());
       // page.setSearchCount(param.getEnableReturnCount());
        IPage<TableCacheDO> iPage = tableCacheMapper.selectPage(page, query);
        List<Table> tables = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(iPage.getRecords())) {
            for (TableCacheDO tableCacheDO : iPage.getRecords()) {
                Table t = new Table();
                t.setName(tableCacheDO.getTableName());
                t.setComment(tableCacheDO.getExtendInfo());
                t.setSchemaName(tableCacheDO.getSchemaName());
                t.setDatabaseName(tableCacheDO.getDatabaseName());
                tables.add(t);
            }
        }
        return PageResult.of(tables, versionDO.getTableCount(), param);
    }

    private void addDBCache(TablePageQueryParam param, TableCacheVersionDO versionDO) {
        if (versionDO == null) {
            versionDO = new TableCacheVersionDO();
            versionDO.setDatabaseName(param.getDatabaseName());
            versionDO.setSchemaName(param.getSchemaName());
            versionDO.setDataSourceId(param.getDataSourceId());
            versionDO.setStatus("2");
            versionDO.setKey(buildKey(param.getDataSourceId(), param.getDatabaseName(), param.getSchemaName()));
            versionDO.setVersion(0L);
            versionDO.setTableCount(0L);
            tableCacheVersionMapper.insert(versionDO);
        } else {
            versionDO.setVersion(versionDO.getVersion() + 1);
            versionDO.setStatus("2");
            tableCacheVersionMapper.updateById(versionDO);
        }
        Connection connection = Chat2DBContext.getConnection();
        long n = 0;
        try (ResultSet resultSet = connection.getMetaData().getTables(param.getDatabaseName(), param.getSchemaName(), param.getTableName(),
                new String[]{"TABLE"})) {
            List<TableCacheDO> cacheDOS = new ArrayList<>();
            while (resultSet.next()) {
                TableCacheDO tableCacheDO = new TableCacheDO();
                tableCacheDO.setDatabaseName(resultSet.getString("TABLE_CAT"));
                tableCacheDO.setSchemaName(resultSet.getString("TABLE_SCHEM"));
                tableCacheDO.setTableName(resultSet.getString("TABLE_NAME"));
                tableCacheDO.setExtendInfo(resultSet.getString("REMARKS"));
                tableCacheDO.setDataSourceId(param.getDataSourceId());
                tableCacheDO.setVersion(versionDO.getVersion());
                tableCacheDO.setKey(buildKey(param.getDataSourceId(), param.getDatabaseName(), param.getSchemaName()));
                cacheDOS.add(tableCacheDO);
                if (cacheDOS.size() >= 500) {
                    tableCacheMapper.batchInsert(cacheDOS);
                    cacheDOS = new ArrayList<>();
                }
                n++;
            }
            if (!CollectionUtils.isEmpty(cacheDOS)) {
                tableCacheMapper.batchInsert(cacheDOS);
            }
            versionDO.setStatus("1");
            versionDO.setTableCount(n);
            tableCacheVersionMapper.updateById(versionDO);
            LambdaQueryWrapper<TableCacheDO> q = new LambdaQueryWrapper();
            q.eq(TableCacheDO::getDataSourceId, param.getDataSourceId());
            q.lt(TableCacheDO::getVersion, versionDO.getVersion());
            if (param.getDatabaseName() != null) {
                q.eq(TableCacheDO::getDatabaseName, param.getDatabaseName());
            }
            if (param.getSchemaName() != null) {
                q.eq(TableCacheDO::getSchemaName, param.getSchemaName());
            }
            tableCacheMapper.delete(q);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

    }


    private String buildKey(Long dataSourceId, String databaseName, String schemaName) {
        StringBuilder stringBuilder = new StringBuilder(dataSourceId.toString());
        if (StringUtils.isNotBlank(databaseName)) {
            stringBuilder.append("_").append(databaseName);
        }
        if (StringUtils.isNotBlank(schemaName)) {
            stringBuilder.append("_").append(schemaName);
        }
        return stringBuilder.toString();
    }

    private List<Table> pinTable(List<Table> list, TablePageQueryParam param) {
        if (CollectionUtils.isEmpty(list)) {
            return Lists.newArrayList();
        }
        PinTableParam pinTableParam = pinTableConverter.toPinTableParam(param);
        pinTableParam.setUserId(ContextUtils.getUserId());
        ListResult<String> listResult = pinService.queryPinTables(pinTableParam);
        if (!listResult.success() || CollectionUtils.isEmpty(listResult.getData())) {
            return list;
        }
        List<Table> tables = new ArrayList<>();
        Map<String, Table> tableMap = list.stream().collect(Collectors.toMap(Table::getName, Function.identity()));
        for (String tableName : listResult.getData()) {
            Table table = tableMap.get(tableName);
            if (table != null) {
                table.setPinned(true);
                tables.add(table);
            }
        }

        for (Table table : list) {
            if (table != null && !tables.contains(table)) {
                tables.add(table);
            }
        }
        return tables;
    }

    @Override
    public List<TableColumn> queryColumns(TableQueryParam param) {
        String tableColumnKey = getColumnKey(param.getDataSourceId(), param.getDatabaseName(), param.getSchemaName(), param.getTableName());
        MetaData metaSchema = Chat2DBContext.getMetaData();
        return CacheManage.getList(tableColumnKey, TableColumn.class,
                (key) -> param.isRefresh(), (key) ->
                        metaSchema.columns(Chat2DBContext.getConnection(), param.getDatabaseName(), param.getSchemaName(), param.getTableName()));
    }

    @Override
    public List<TableIndex> queryIndexes(TableQueryParam param) {
        MetaData metaSchema = Chat2DBContext.getMetaData();
        return metaSchema.indexes(Chat2DBContext.getConnection(), param.getDatabaseName(), param.getSchemaName(), param.getTableName());

    }

    @Override
    public List<Type> queryTypes(TypeQueryParam param) {
        MetaData metaSchema = Chat2DBContext.getMetaData();
        return metaSchema.types(Chat2DBContext.getConnection());
    }

    @Override
    public TableMeta queryTableMeta(TypeQueryParam param) {
        MetaData metaSchema = Chat2DBContext.getMetaData();
        return metaSchema.getTableMeta(null, null, null);
    }
}
