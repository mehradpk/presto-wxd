/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.tests;

import com.facebook.presto.Session;
import com.facebook.presto.SystemSessionProperties;
import com.facebook.presto.testing.QueryRunner;
import com.facebook.presto.tpch.TpchPlugin;
import com.google.common.collect.ImmutableMap;
import org.testng.annotations.Test;

import java.nio.file.Paths;

import static com.facebook.presto.SystemSessionProperties.QUERY_MAX_TOTAL_MEMORY_PER_NODE;
import static com.facebook.presto.sessionpropertyproviders.JavaWorkerSessionPropertyProvider.TOPN_OPERATOR_UNSPILL_MEMORY_LIMIT;
import static com.facebook.presto.sessionpropertyproviders.JavaWorkerSessionPropertyProvider.TOPN_SPILL_ENABLED;
import static com.facebook.presto.testing.TestingSession.testSessionBuilder;
import static com.facebook.presto.tpch.TpchMetadata.TINY_SCHEMA_NAME;

public class TestSpilledTopNQueries
        extends AbstractTestTopNQueries

{
    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        Session defaultSession = testSessionBuilder()
                .setCatalog("tpch")
                .setSchema(TINY_SCHEMA_NAME)
                .setSystemProperty(SystemSessionProperties.TASK_CONCURRENCY, "2")
                .setSystemProperty(TOPN_OPERATOR_UNSPILL_MEMORY_LIMIT, "120kB")
                .setSystemProperty(SystemSessionProperties.QUERY_MAX_MEMORY_PER_NODE, "1500kB")
                .build();

        ImmutableMap<String, String> extraProperties = ImmutableMap.<String, String>builder()
                .put("experimental.spill-enabled", "true")
                .put("experimental.topn-spill-enabled", "true")
                .put("experimental.spiller-spill-path", Paths.get(System.getProperty("java.io.tmpdir"), "presto", "spills").toString())
                .put("experimental.spiller-max-used-space-threshold", "1.0")
                .put("experimental.memory-revoking-threshold", "0.001") // revoke always
                .put("experimental.memory-revoking-target", "0.0")
                .build();

        DistributedQueryRunner queryRunner = new DistributedQueryRunner(defaultSession, 2, extraProperties);

        try {
            queryRunner.installPlugin(new TpchPlugin());
            queryRunner.createCatalog("tpch", "tpch");
            return queryRunner;
        }
        catch (Exception e) {
            queryRunner.close();
            throw e;
        }
    }

    @Test
    public void testDoesNotSpillTopNWhenDisabled()
    {
        Session session = Session.builder(getSession())
                .setSystemProperty(TOPN_SPILL_ENABLED, "false")
                // set this low so that if we ran without spill the query would fail
                .setSystemProperty(QUERY_MAX_TOTAL_MEMORY_PER_NODE, "50kB")
                .build();
        assertQueryFails(session,
                "SELECT orderpriority, custkey FROM orders ORDER BY orderpriority  LIMIT 1000", "Query exceeded.*memory limit.*");
    }
}
