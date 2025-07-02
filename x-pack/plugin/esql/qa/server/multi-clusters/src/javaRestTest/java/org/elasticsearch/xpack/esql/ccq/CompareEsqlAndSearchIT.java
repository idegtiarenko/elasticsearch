/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.ccq;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakFilters;

import org.apache.http.HttpHost;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.Strings;
import org.elasticsearch.test.TestClustersThreadFilter;
import org.elasticsearch.test.cluster.ElasticsearchCluster;
import org.elasticsearch.test.rest.ESRestTestCase;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.hamcrest.Matcher;
import org.junit.ClassRule;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static java.util.stream.Collectors.toSet;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@ThreadLeakFilters(filters = TestClustersThreadFilter.class)
public class CompareEsqlAndSearchIT extends ESRestTestCase {

    static ElasticsearchCluster remoteCluster = Clusters.remoteCluster();
    static ElasticsearchCluster localCluster = Clusters.localCluster(remoteCluster, true);

    @ClassRule
    public static TestRule clusterRule = RuleChain.outerRule(remoteCluster).around(localCluster);

    @Override
    protected String getTestRestCluster() {
        return localCluster.getHttpAddresses();
    }

    private RestClient localClient() {
        return client();
    }

    private RestClient remoteClient() throws IOException {
        return buildClient(restClientSettings(), parseClusterHosts(remoteCluster.getHttpAddresses()).toArray(new HttpHost[0]));
    }

    public void testConcreteIndex() throws IOException {
        setUpIndex(client(), "local", "index-1");

        var search = runSearchQuery(client(), "index-1");
        assertThat(search.values(), equalTo(Set.of("local-index-1")));
        var esql = runEsqlQuery(client(), "FROM index-1 | LIMIT 10");
        assertThat(esql.values(), equalTo(Set.of("local-index-1")));
    }

    public void testAlias() throws IOException {
        setUpIndex(client(), "local", "index-1");
        setUpAlias(client(), "alias-1", "index-1");

        var search = runSearchQuery(client(), "alias-1");
        assertThat(search.values(), equalTo(Set.of("local-index-1")));
        var esql = runEsqlQuery(client(), "FROM alias-1 | LIMIT 10");
        assertThat(esql.values(), equalTo(Set.of("local-index-1")));
    }

    public void testPattern() throws IOException {
        setUpIndex(client(), "local", "index-1");
        setUpIndex(client(), "local", "index-2");

        var search = runSearchQuery(client(), "index-*");
        assertThat(search.values(), equalTo(Set.of("local-index-1", "local-index-2")));
        var esql = runEsqlQuery(client(), "FROM index-* | LIMIT 10");
        assertThat(esql.values(), equalTo(Set.of("local-index-1", "local-index-2")));
    }

    public void testEmptyPatternWithAllowNoIndices() throws IOException {
        var search = runSearchQuery(client(), "index-*", r -> { r.addParameter("allow_no_indices", "true"); });
        assertThat(search.values(), equalTo(Set.of()));

        expectFailure(
            equalTo(400),
            equalTo("verification_exception"),
            containsString("Unknown index [index-*]"),
            () -> runEsqlQuery(client(), "FROM index-* | LIMIT 10")
        );
        expectFailure(
            equalTo(400),
            equalTo("verification_exception"),
            containsString("Unknown index [index-1,index-2]"),
            () -> runEsqlQuery(client(), "FROM index-1,index-2 | LIMIT 10")
        );
        // see
        // https://github.com/elastic/elasticsearch/blob/f097818fa5cfde423f6ea4a1baf8090a7bf611ad/x-pack/plugin/esql/src/main/java/org/elasticsearch/xpack/esql/session/IndexResolver.java#L99-L101
        // that prevents empty index resolution

        // There is no way to allow no indices in ESQL with a single expression today. Even with allow _partial_results
        // Related to field caps "unresolved" work
    }

    public void testConcreteIndexAndEmptyPatternWithAllowNoIndices() throws IOException {
        setUpIndex(client(), "local", "data");

        var search = runSearchQuery(client(), "data,index-*", r -> { r.addParameter("allow_no_indices", "true"); });
        assertThat(search.values(), equalTo(Set.of("local-data")));
        var esql1 = runEsqlQuery(client(), "FROM data,index-* | LIMIT 10", r -> { r.addParameter("allow_partial_results", "true"); });
        assertThat(esql1.values(), equalTo(Set.of("local-data")));
        var esql2 = runEsqlQuery(client(), "FROM data,index-* | LIMIT 10", r -> { r.addParameter("allow_partial_results", "false"); });
        assertThat(esql2.values(), equalTo(Set.of("local-data")));
        // Today esql silently ignores empty patterns as long as something is resolved.
        // Instead, we should check each pattern individually.
        // Related to field caps "unresolved" work
    }

    public void testConcreteIndexAndMissingIndex() throws IOException {
        setUpIndex(client(), "local", "data-1");

        expectFailure(
            equalTo(404),
            equalTo("index_not_found_exception"),
            equalTo("no such index [data-2]"),
            () -> runSearchQuery(client(), "data-1,data-2")
        );
        expectFailure(
            equalTo(404),
            equalTo("index_not_found_exception"),
            containsString("no such index [data-2]"),
            () -> runEsqlQuery(client(), "FROM data-1,data-2 | LIMIT 10", r -> {
                r.addParameter("allow_partial_results", "true");
            })
        );
        expectFailure(
            equalTo(404),
            equalTo("index_not_found_exception"),
            containsString("no such index [data-2]"),
            () -> runEsqlQuery(client(), "FROM data-1,data-2 | LIMIT 10", r -> {
                r.addParameter("allow_partial_results", "false");
            })
        );
    }

    public void testConcreteIndexAndMissingIndexNoDocs() throws IOException {
        setUpIndex(client(), "local", "data-1", false);

        expectFailure(
            equalTo(404),
            equalTo("index_not_found_exception"),
            equalTo("no such index [data-2]"),
            () -> runSearchQuery(client(), "data-1,data-2")
        );
        var esql1 = runEsqlQuery(client(), "FROM data-1,data-2 | LIMIT 10", r -> { r.addParameter("allow_partial_results", "true"); });
        assertThat(esql1.values(), equalTo(Set.of()));
        var esql2 = runEsqlQuery(client(), "FROM data-1,data-2 | LIMIT 10", r -> { r.addParameter("allow_partial_results", "false"); });
        assertThat(esql2.values(), equalTo(Set.of()));
    }

    public void testMathcClosedIndex() throws IOException {
        setUpIndex(client(), "local", "index-1");
        closeIndex(client(), "index-1");

        expectFailure(equalTo(400), equalTo("index_closed_exception"), equalTo("closed"), () -> runSearchQuery(client(), "index-1"));
        expectFailure(
            equalTo(403),
            equalTo("cluster_block_exception"),
            equalTo("index [index-1] blocked by: [FORBIDDEN/4/index closed];"),
            () -> runEsqlQuery(client(), "FROM index-1 | LIMIT 10")
        );
        // Possibly we need to change ESQL exception here when targeting closed index
    }

    public void testMatchNonExistingIndex() throws IOException {
        var search = runSearchQuery(client(), "index-1", r -> r.addParameter("ignore_unavailable", "true"));
        assertThat(search.values(), equalTo(Set.of()));

        expectFailure(
            equalTo(404),
            equalTo("index_not_found_exception"),
            equalTo("no such index [index-1]"),
            () -> runSearchQuery(client(), "index-1")
        );
        expectFailure(
            equalTo(400),
            equalTo("verification_exception"),
            containsString("Unknown index [index-1]"),
            () -> runEsqlQuery(client(), "FROM index-1 | LIMIT 10")
        );
        // There are no way in esql to query empty index set today
    }

    private void setUpAlias(RestClient client, String name, String target) throws IOException {
        var request = new Request("POST", "/_aliases");
        request.setJsonEntity(Strings.format("""
            {
              "actions": [
                {
                  "add": {
                    "index": "%s",
                    "alias": "%s"
                  }
                }
              ]
            }""", target, name));
        assertOK(client.performRequest(request));
    }

    private void setUpIndex(RestClient client, String name, String index) throws IOException {
        setUpIndex(client, name, index, true);
    }

    private void setUpIndex(RestClient client, String name, String index, boolean indexDocs) throws IOException {
        createIndex(client, index, Settings.builder().put("index.number_of_shards", 1).build());
        if (indexDocs) {
            indexDocs(client, name, index);
            refresh(client, index);
        }
    }

    private void closeIndex(RestClient client, String index) throws IOException {
        var request = new Request("POST", "/" + index + "/_close");
        assertOK(client.performRequest(request));
    }

    private void indexDocs(RestClient client, String name, String index) throws IOException {
        var request = new Request("POST", "/" + index + "/_doc");
        request.setJsonEntity(Strings.format("""
            {
              "key": "%s-%s"
            }""", name, index));
        assertOK(client.performRequest(request));
    }

    private SearchResult runSearchQuery(RestClient client, String pattern) throws IOException {
        return runSearchQuery(client, pattern, request -> {});
    }

    private SearchResult runSearchQuery(RestClient client, String pattern, Consumer<Request> params) throws IOException {
        var request = new Request("GET", "/" + pattern + "/_search");
        params.accept(request);
        var response = client.performRequest(request);
        return new SearchResult(EsqlTestUtils.entityToMap(response.getEntity(), XContentType.JSON));
    }

    private record SearchResult(Map<String, Object> result) {

        @SuppressWarnings("unchecked")
        private Set<String> values() {
            var hits = ((Map<String, List<Map<String, Map<String, String>>>>) result.get("hits")).get("hits");
            return hits.stream().map(it -> it.get("_source").get("key")).collect(toSet());
        }
    }

    private EsqlResult runEsqlQuery(RestClient client, String query) throws IOException {
        return runEsqlQuery(client, query, request -> {});
    }

    private EsqlResult runEsqlQuery(RestClient client, String query, Consumer<Request> params) throws IOException {
        var request = new Request("POST", "/_query");
        params.accept(request);
        request.setJsonEntity(Strings.format("""
            {
              "query": "%s"
            }""", query));
        var response = client.performRequest(request);
        return new EsqlResult(EsqlTestUtils.entityToMap(response.getEntity(), XContentType.JSON));
    }

    private record EsqlResult(Map<String, Object> result) {

        @SuppressWarnings("unchecked")
        private Set<String> values() {
            return ((List<List<String>>) result.get("values")).stream().flatMap(Collection::stream).collect(toSet());
        }

        @SuppressWarnings("unchecked")
        private int count() {
            return ((List<List<Integer>>) result.get("values")).get(0).get(0);
        }

        public boolean isPartial() {
            return (boolean) result.get("is_partial");
        }
    }

    @SuppressWarnings("unchecked")
    private static void expectFailure(
        Matcher<Integer> codeMatcher,
        Matcher<String> typeMatcher,
        Matcher<String> reasonMatcher,
        ThrowingRunnable runnable
    ) throws IOException {
        var failure = expectThrows(ResponseException.class, runnable);
        assertThat(failure.getResponse().getStatusLine().getStatusCode(), codeMatcher);
        var entity = EsqlTestUtils.entityToMap(failure.getResponse().getEntity(), XContentType.JSON);
        var error = (Map<String, String>) entity.get("error");
        assertThat(error.get("type"), typeMatcher);
        assertThat(error.get("reason"), reasonMatcher);
    }
}
