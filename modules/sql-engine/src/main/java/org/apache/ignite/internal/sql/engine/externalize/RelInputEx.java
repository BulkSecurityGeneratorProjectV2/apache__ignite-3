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

package org.apache.ignite.internal.sql.engine.externalize;

import java.util.List;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelInput;
import org.apache.ignite.internal.sql.engine.prepare.bounds.SearchBounds;

/**
 * Extension to the {@link RelInput} interface.
 *
 * <p>Provides necessary methods to restore relational nodes.
 */
public interface RelInputEx extends RelInput {
    /**
     * Returns collation serialized under given tag.
     *
     * @param tag Tag under which collation is serialised.
     * @return A collation value.
     */
    RelCollation getCollation(String tag);

    /**
     * Returns table by its id.
     *
     * @return A table with given id.
     */
    RelOptTable getTableById();

    /**
     * Returns search bounds.
     *
     * @param tag Tag.
     * @return Search bounds.
     */
    List<SearchBounds> getSearchBounds(String tag);
}
