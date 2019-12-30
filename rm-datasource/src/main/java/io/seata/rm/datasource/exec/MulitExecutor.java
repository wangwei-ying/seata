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

import io.seata.rm.datasource.ConnectionProxy;
import io.seata.rm.datasource.StatementProxy;
import io.seata.rm.datasource.sql.SQLRecognizer;
import io.seata.rm.datasource.sql.SQLType;
import io.seata.rm.datasource.sql.struct.Row;
import io.seata.rm.datasource.sql.struct.TableRecords;
import io.seata.rm.datasource.undo.SQLUndoLog;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Collection;
import java.util.function.Function;
import java.util.stream.Collectors;

public class MulitExecutor<T, S extends Statement> extends AbstractDMLBaseExecutor<T, S> {


    private List<TableRecords> updateBeforeImages = new ArrayList<>();
    private List<TableRecords> deleteBeforeImages = new ArrayList<>();

    private List<TableRecords> updateAfterImages = new ArrayList<>();
    private List<TableRecords> deleteAfterImages = new ArrayList<>();
    private Map<SQLRecognizer, TableRecords> tableRecordsMap = new HashMap<>(4);

    /**
     * Instantiates a new Abstract dml base executor.
     *
     * @param statementProxy    the statement proxy
     * @param statementCallback the statement callback
     * @param sqlRecognizer     the sql recognizer
     */
    public MulitExecutor(StatementProxy<S> statementProxy, StatementCallback<T, S> statementCallback, List<SQLRecognizer> sqlRecognizers) {
        super(statementProxy, statementCallback, sqlRecognizers);
    }

    /**
     * Before image table records.  only support update or deleted
     *
     * @return the table records
     * @throws SQLException the sql exception
     * @see io.seata.rm.datasource.sql.SQLVisitorFactory#getMulti(String, String) validate sqlType
     */
    @Override
    protected TableRecords beforeImage() throws SQLException {
        for (SQLRecognizer recognizer : sqlRecognizers) {
            AbstractDMLBaseExecutor<T, S> executor = null;
            switch (recognizer.getSQLType()) {
                case UPDATE:
                    executor = new UpdateExecutor<T, S>(statementProxy, statementCallback, recognizer);
                    TableRecords updateRecords = executor.beforeImage();
                    updateBeforeImages.add(updateRecords);
                    tableRecordsMap.put(recognizer, updateRecords);
                    break;
                case DELETE:
                    executor = new DeleteExecutor<T, S>(statementProxy, statementCallback, recognizer);
                    TableRecords deleteRecords = executor.beforeImage();
                    deleteBeforeImages.add(deleteRecords);
                    tableRecordsMap.put(recognizer, deleteRecords);
                    break;
                default:
                    break;
            }
        }

        return null;
    }

    @Override
    protected TableRecords afterImage(TableRecords beforeImage) throws SQLException {
        AbstractDMLBaseExecutor<T, S> executor = null;
        for (int i = 0; i < sqlRecognizers.size(); i++) {
            sqlRecognizer = sqlRecognizers.get(i);
            switch (sqlRecognizer.getSQLType()) {
                case UPDATE:
                    executor = new UpdateExecutor<T, S>(statementProxy, statementCallback, sqlRecognizer);
                    updateAfterImages.add(executor.afterImage(tableRecordsMap.get(sqlRecognizer)));
                    break;
                case DELETE:
                    executor = new DeleteExecutor<T, S>(statementProxy, statementCallback, sqlRecognizer);
                    deleteAfterImages.add(executor.afterImage(tableRecordsMap.get(sqlRecognizer)));
                    break;
                default:
                    break;
            }

        }
        return null;
    }


    @Override
    protected void prepareUndoLog(TableRecords beforeImage, TableRecords afterImage) throws SQLException {
        Map<String, TableRecords> updateBeforeImagesMap = getTableMap(updateBeforeImages);
        Map<String, TableRecords> deleteBeforeImagesMap = getTableMap(deleteBeforeImages);
        Map<String, TableRecords> updateAfterImagesMap = getTableMap(updateAfterImages);
        Map<String, TableRecords> deleteAfterImagesMap = getTableMap(deleteAfterImages);
        updateBeforeImages = new ArrayList<>(updateBeforeImagesMap.values());
        deleteBeforeImages = new ArrayList<>(deleteBeforeImagesMap.values());
        updateAfterImages = new ArrayList<>(updateAfterImagesMap.values());
        deleteAfterImages = new ArrayList<>(deleteAfterImagesMap.values());
        //update
        buildUndo(updateBeforeImagesMap, updateAfterImagesMap, SQLType.UPDATE);
        //delete
        buildUndo(deleteBeforeImagesMap, deleteAfterImagesMap, SQLType.DELETE);
    }

    private void buildUndo(Map<String, TableRecords> deleteBeforeImagesMap, Map<String, TableRecords> deleteAfterImagesMap, SQLType sqlType) {
        TableRecords beforeImage;
        TableRecords afterImage;
        for (String table : deleteAfterImagesMap.keySet()) {
            beforeImage = deleteBeforeImagesMap.get(table);
            afterImage = deleteAfterImagesMap.get(table);
            ConnectionProxy connectionProxy = statementProxy.getConnectionProxy();
            String lockKeys = buildLockKey(beforeImage);
            connectionProxy.appendLockKey(lockKeys);
            SQLUndoLog sqlUndoLog = new SQLUndoLog();
            sqlUndoLog.setSqlType(sqlType);
            sqlUndoLog.setTableName(table);
            sqlUndoLog.setBeforeImage(beforeImage);
            sqlUndoLog.setAfterImage(afterImage);
            connectionProxy.appendUndoLog(sqlUndoLog);
        }
    }


    private Map<String, TableRecords> getTableMap(List<TableRecords> updateBeforeImages) {
        //merge tables
        Map<String, TableRecords> tableRecordsMap = updateBeforeImages.stream().collect(Collectors.toMap(TableRecords::getTableName, Function.identity(), (n, o) -> {
            o.getRows().addAll(n.getRows());
            return o;
        }));
        //merge rows
        for (TableRecords value : tableRecordsMap.values()) {
            List<Row> rows = value.getRows();
            Collection<Row> meragedRows = rows.stream().collect(Collectors.toMap(t -> t.primaryKeys().get(0).getValue(), Function.identity(), (n, o) -> o)).values();
            value.setRows(new ArrayList<>(meragedRows));
        }

        return tableRecordsMap;
    }

    public List<TableRecords> getUpdateBeforeImages() {
        return updateBeforeImages;
    }

    public List<TableRecords> getDeleteBeforeImages() {
        return deleteBeforeImages;
    }

    public List<TableRecords> getUpdateAfterImages() {
        return updateAfterImages;
    }

    public List<TableRecords> getDeleteAfterImages() {
        return deleteAfterImages;
    }
}
