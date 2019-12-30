/*
 *  Copyright 1999-2019 Seata.io Group.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package io.seata.rm.datasource.exec;

import com.alibaba.druid.mock.MockStatement;
import com.alibaba.druid.mock.MockStatementBase;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.util.JdbcConstants;
import com.google.common.collect.Lists;
import io.seata.rm.datasource.ConnectionProxy;
import io.seata.rm.datasource.DataSourceProxy;
import io.seata.rm.datasource.StatementProxy;
import io.seata.rm.datasource.mock.MockDriver;
import io.seata.rm.datasource.sql.SQLRecognizer;
import io.seata.rm.datasource.sql.SQLVisitorFactory;
import io.seata.rm.datasource.sql.druid.MySQLDeleteRecognizer;
import io.seata.rm.datasource.sql.struct.TableRecords;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

public class MulitExecutorTest {

    private static MulitExecutor executor;

    private static StatementProxy statementProxy;

    @BeforeAll
    public static void init() throws Throwable {
        List<String> returnValueColumnLabels = Lists.newArrayList("id", "name");
        Object[][] returnValue = new Object[][]{
                new Object[]{1, "Tom"},
                new Object[]{2, "Jack"},
                new Object[]{2, "Jack"},
        };
        Object[][] columnMetas = new Object[][]{
                new Object[]{"", "", "table_update_executor_test", "id", Types.INTEGER, "INTEGER", 64, 0, 10, 1, "", "", 0, 0, 64, 1, "NO", "YES"},
                new Object[]{"", "", "table_update_executor_test", "name", Types.VARCHAR, "VARCHAR", 64, 0, 10, 0, "", "", 0, 0, 64, 2, "YES", "NO"},
        };
        Object[][] indexMetas = new Object[][]{
                new Object[]{"PRIMARY", "id", false, "", 3, 1, "A", 34},
        };

        MockDriver mockDriver = new MockDriver(returnValueColumnLabels, returnValue, columnMetas, indexMetas);
        DruidDataSource dataSource = new DruidDataSource();
        dataSource.setUrl("jdbc:mock:xxx");
        dataSource.setDriver(mockDriver);

        DataSourceProxy dataSourceProxy = new DataSourceProxy(dataSource);
        try {
            Field field = dataSourceProxy.getClass().getDeclaredField("dbType");
            field.setAccessible(true);
            field.set(dataSourceProxy, "mysql");
            ConnectionProxy connectionProxy = new ConnectionProxy(dataSourceProxy, dataSource.getConnection().getConnection());
            MockStatementBase mockStatement = new MockStatement(dataSource.getConnection().getConnection());
            statementProxy = new StatementProxy(connectionProxy, mockStatement);
        } catch (Exception e) {
            throw new RuntimeException("init failed");
        }
        String sql = "update table_update_executor_test set name = 'WILL' where id = 1;update table_update_executor_test set name = 'WILL2' where id = 2";
        List<SQLRecognizer> multi = SQLVisitorFactory.getMulti(sql, JdbcConstants.MYSQL);
        executor = new MulitExecutor(statementProxy, (statement, args) -> {
            return null;
        }, multi);

    }

    @Test
    public void testBeforeImage() throws SQLException {
        executor.beforeImage();
        Assertions.assertNotNull(executor.getUpdateBeforeImages());
        Assertions.assertEquals(executor.getUpdateBeforeImages().size(), 2);
    }

    @Test
    public void testAfterImage() throws SQLException {
        TableRecords tableRecords = executor.beforeImage();
        executor.afterImage(tableRecords);
        List<TableRecords> images = executor.getUpdateAfterImages();
        Assertions.assertTrue(images.stream().allMatch(t -> t.size() == 3));

    }

    @Test
    public void prepareUndoLogTest() throws Throwable {

        String sql = "update table_update_executor_test set name = 'WILL' where id = 1;update table_update_executor_test set name = 'WILL2' where id = 1";
        List<SQLRecognizer> multi = SQLVisitorFactory.getMulti(sql, JdbcConstants.MYSQL);
        MulitExecutor executor = new MulitExecutor(statementProxy, (statement, args) -> {
            return null;
        }, multi);

        TableRecords beforeImage = executor.beforeImage();
        TableRecords afterImage = executor.afterImage(beforeImage);
        executor.prepareUndoLog(beforeImage, afterImage);
        List<TableRecords> beforeImages = executor.getUpdateBeforeImages();
        List<TableRecords> afterImages = executor.getUpdateAfterImages();
        Assertions.assertTrue(beforeImages.size() == 1 && afterImages.size() == 1);


    }

    @Test
    public void mulitTest() throws Throwable {
        String sql = "update table_update_executor_test set name = 'WILL' where id = 1;update table_update_executor_test set name = 'WILL2' where id = 2";
        List<SQLRecognizer> multi = SQLVisitorFactory.getMulti(sql, JdbcConstants.MYSQL);
        MulitExecutor executor = new MulitExecutor(statementProxy, (statement, args) -> {
            return null;
        }, multi);
        executor.execute(null);

        sql = "update table_update_executor_test set name = 'WILL' where id = 1;update table_update_executor_test set name = 'WILL2' where id = 1";
        multi = SQLVisitorFactory.getMulti(sql, JdbcConstants.MYSQL);
        executor = new MulitExecutor(statementProxy, (statement, args) -> {
            return null;
        }, multi);
        executor.execute(null);


    }
}