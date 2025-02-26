/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql;

import org.apache.lucene.tests.mockfile.HandleLimitFS;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.junit.annotations.TestLogging;
import org.elasticsearch.xpack.esql.action.AbstractEsqlIntegTestCase;
import org.elasticsearch.xpack.esql.action.EsqlQueryAction;
import org.elasticsearch.xpack.esql.action.EsqlQueryRequest;
import org.elasticsearch.xpack.esql.plugin.QueryPragmas;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@ESIntegTestCase.ClusterScope(numDataNodes = 3)
@HandleLimitFS.MaxOpenHandles(limit = Integer.MAX_VALUE)
public class QueryManyShardsRequestIT extends AbstractEsqlIntegTestCase {

    @TestLogging(value = "org.elasticsearch.xpack.esql.session:DEBUG", reason = "to better understand planning")
    public void testPlay() {

        int fields = 10;
        for (int index = 0; index < 50; index++) {
            var request = client().prepareBulk("events-" + index);
            for (int document = 0; document < 12; document++) {
                var data = new Object[fields * 2];
                for (int f = 0; f < data.length; f += 2) {
                    data[f] = "field-" + f;
                    data[f + 1] = UUID.randomUUID().toString();
                }
                request.add(new IndexRequest().source(data));
            }
            request.get();
        }

        // var settings = Settings.builder().put(QueryPragmas.NODE_LEVEL_REDUCTION.getKey(), false).build();
        var settings = Settings.EMPTY;

        var request = EsqlQueryRequest.syncEsqlQueryRequest();
        request.query("FROM events-* | LIMIT 10");
        request.profile(true);
        request.pragmas(new QueryPragmas(settings));

        logger.info("Running query: {}", request.query());
        try {
            var result = client().execute(EsqlQueryAction.INSTANCE, request).actionGet(30, TimeUnit.SECONDS);
            logger.info("Query finished: {}", result);
            result.close();
        } catch (Exception e) {
            logger.error("Failed to execute query", e);
        }
    }
}
