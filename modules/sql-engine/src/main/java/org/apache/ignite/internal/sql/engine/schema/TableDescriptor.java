/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.sql.engine.schema;

import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelProtoDataType;
import org.apache.calcite.sql2rel.InitializerExpressionFactory;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.ignite.internal.sql.engine.trait.IgniteDistribution;
import org.apache.ignite.internal.sql.engine.type.IgniteTypeFactory;

/**
 * TableDescriptor interface.
 * TODO Documentation https://issues.apache.org/jira/browse/IGNITE-15859
 */
public interface TableDescriptor extends RelProtoDataType, InitializerExpressionFactory {
    /** Returns distribution of the table. */
    IgniteDistribution distribution();

    /** {@inheritDoc} */
    @Override
    default RelDataType apply(RelDataTypeFactory factory) {
        return rowType((IgniteTypeFactory) factory, null);
    }

    /**
     * Returns row type excluding effectively virtual fields.
     *
     * @param factory Type factory.
     * @return Row type for INSERT operation.
     */
    default RelDataType insertRowType(IgniteTypeFactory factory) {
        return rowType(factory, null);
    }

    /**
     * Returns row type containing only key fields.
     *
     * @param factory Type factory.
     * @return Row type for DELETE operation.
     */
    default RelDataType deleteRowType(IgniteTypeFactory factory) {
        return rowType(factory, null);
    }

    /**
     * Returns row type including effectively virtual fields.
     *
     * @param factory Type factory.
     * @return Row type for SELECT operation.
     */
    default RelDataType selectForUpdateRowType(IgniteTypeFactory factory) {
        return rowType(factory, null);
    }

    /**
     * Returns row type.
     *
     * @param factory     Type factory.
     * @param usedColumns Participating columns numeration.
     * @return Row type.
     */
    RelDataType rowType(IgniteTypeFactory factory, ImmutableBitSet usedColumns);

    /**
     * Checks whether is possible to update a column with a given index.
     *
     * @param tbl    Parent table.
     * @param colIdx Column index.
     * @return {@code True} if update operation is allowed for a column with a given index.
     */
    boolean isUpdateAllowed(RelOptTable tbl, int colIdx);

    /**
     * Returns column descriptor for given field name.
     *
     * @return Column descriptor
     */
    ColumnDescriptor columnDescriptor(String fieldName);

    /**
     * Returns column descriptor for column of given index.
     *
     * @return Column descriptor or null if there is no column with given index.
     */
    ColumnDescriptor columnDescriptor(int idx);

    /**
     * Returns count of columns in the table.
     *
     * @return Actual count of columns.
     */
    int columnsCount();
}
