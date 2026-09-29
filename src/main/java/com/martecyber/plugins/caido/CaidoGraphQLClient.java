package com.martecyber.plugins.caido;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.martecyber.ares.plugins.PluginComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.*;

/**
 * Thin wrapper over Caido's GraphQL endpoint ({@code /graphql}). All methods take
 * {@code baseUrl} and {@code accessToken} explicitly so a single bean can serve
 * multiple {@link CaidoApiIntegration} instances.
 */
public class CaidoGraphQLClient implements PluginComponent {

    private static final Logger log = LoggerFactory.getLogger(CaidoGraphQLClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestTemplate http;

    public CaidoGraphQLClient(RestTemplateBuilder builder) {
        this.http = builder
            .setConnectTimeout(Duration.ofSeconds(10))
            .setReadTimeout(Duration.ofSeconds(60))
            .build();
    }

    // ── Projects ─────────────────────────────────────────────────────────────────

    public List<Map<String, String>> listProjects(String baseUrl, String token) {
        String query = "{ projects { id name } }";
        JsonNode data = execute(baseUrl, token, query, null);
        var out = new ArrayList<Map<String, String>>();
        for (JsonNode p : data.path("projects")) {
            out.add(Map.of("id", p.path("id").asText(), "name", p.path("name").asText()));
        }
        return out;
    }

    public Map<String, String> getCurrentProject(String baseUrl, String token) {
        String query = "{ currentProject { project { id name } } }";
        JsonNode data = execute(baseUrl, token, query, null);
        JsonNode p = data.path("currentProject").path("project");
        if (p.isMissingNode() || p.isNull()) return null;
        return Map.of("id", p.path("id").asText(), "name", p.path("name").asText());
    }

    /**
     * Switches the active project on this Caido instance and returns the selected project id,
     * or null if the mutation failed (unknown id).
     */
    public String selectProject(String baseUrl, String token, String projectId) {
        String mutation = "mutation SelectProject($id: ID!) { selectProject(id: $id) { currentProject { project { id name } } error { ... on ProjectUserError { code } ... on UnknownIdUserError { id code } ... on OtherUserError { code } } } }";
        ObjectNode vars = MAPPER.createObjectNode();
        vars.put("id", projectId);
        JsonNode data = execute(baseUrl, token, mutation, vars);
        JsonNode payload = data.path("selectProject");
        JsonNode error = payload.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            String msg = error.path("code").asText(error.path("message").asText("unknown error"));
            throw new CaidoApiException("selectProject failed: " + msg);
        }
        return payload.path("currentProject").path("project").path("id").asText(null);
    }

    // ── Scopes ────────────────────────────────────────────────────────────────────

    public List<CaidoScope> listScopes(String baseUrl, String token) {
        String query = "{ scopes { id name allowlist denylist } }";
        JsonNode data = execute(baseUrl, token, query, null);
        var out = new ArrayList<CaidoScope>();
        for (JsonNode s : data.path("scopes")) {
            out.add(new CaidoScope(
                s.path("id").asText(),
                s.path("name").asText(),
                toStringList(s.path("allowlist")),
                toStringList(s.path("denylist"))
            ));
        }
        return out;
    }

    public String createScope(String baseUrl, String token, String name, List<String> allowlist, List<String> denylist) {
        String mutation = "mutation CreateScope($input: CreateScopeInput!) { createScope(input: $input) { scope { id } error { __typename } } }";
        ObjectNode input = MAPPER.createObjectNode();
        input.put("name", name);
        input.putPOJO("allowlist", allowlist);
        input.putPOJO("denylist", denylist);
        ObjectNode vars = MAPPER.createObjectNode();
        vars.set("input", input);
        JsonNode data = execute(baseUrl, token, mutation, vars);
        JsonNode payload = data.path("createScope");
        checkError(payload, "createScope");
        return payload.path("scope").path("id").asText();
    }

    public void updateScope(String baseUrl, String token, String scopeId, String name, List<String> allowlist, List<String> denylist) {
        String mutation = "mutation UpdateScope($id: ID!, $input: UpdateScopeInput!) { updateScope(id: $id, input: $input) { scope { id } error { __typename } } }";
        ObjectNode input = MAPPER.createObjectNode();
        input.put("name", name);
        input.putPOJO("allowlist", allowlist);
        input.putPOJO("denylist", denylist);
        ObjectNode vars = MAPPER.createObjectNode();
        vars.put("id", scopeId);
        vars.set("input", input);
        JsonNode data = execute(baseUrl, token, mutation, vars);
        checkError(data.path("updateScope"), "updateScope");
    }

    // ── Findings ─────────────────────────────────────────────────────────────────

    public record CaidoFindingPage(List<CaidoFindingNode> nodes, boolean hasMore) {}

    public record CaidoFindingNode(
        String id, String title, String description,
        String host, int port, String path, String query, boolean isTls,
        /** Raw HTTP request/response bytes (decoded from Caido's base64 Blob scalar), for
         *  storage as a DetectionHttpSample — null when Caido has no response captured yet. */
        String requestRaw, String responseRaw
    ) {}

    public CaidoFindingPage getFindingsByOffset(String baseUrl, String token, int offset, int limit) {
        String query = """
            query FindingsByOffset($offset: Int!, $limit: Int!, $filter: FilterClauseFindingInput!, $order: FindingOrderInput!) {
              findingsByOffset(offset: $offset, limit: $limit, filter: $filter, order: $order) {
                edges { node {
                  id title description
                  request { host port path query isTls raw response { raw } }
                } }
              }
            }""";
        ObjectNode vars = MAPPER.createObjectNode();
        vars.put("offset", offset);
        vars.put("limit", limit);
        vars.putObject("filter");
        ObjectNode order = vars.putObject("order");
        order.put("by", "ID");
        order.put("ordering", "ASC");
        JsonNode data = execute(baseUrl, token, query, vars);
        var edges = data.path("findingsByOffset").path("edges");
        var nodes = new ArrayList<CaidoFindingNode>();
        for (JsonNode edge : edges) {
            JsonNode n = edge.path("node");
            JsonNode req = n.path("request");
            nodes.add(new CaidoFindingNode(
                n.path("id").asText(),
                n.path("title").asText(""),
                n.path("description").asText(null),
                req.path("host").asText(""),
                req.path("port").asInt(80),
                req.path("path").asText("/"),
                req.path("query").asText(""),
                req.path("isTls").asBoolean(false),
                decodeBlob(req.path("raw")),
                decodeBlob(req.path("response").path("raw"))
            ));
        }
        return new CaidoFindingPage(nodes, nodes.size() >= limit);
    }

    /** Caido's "Blob" scalar is a base64-encoded byte string; decodes to UTF-8 text for storage
     *  as a DetectionHttpSample (binary bodies survive as best-effort mangled text, same tradeoff
     *  Burp's importer makes). Returns null for a missing/empty field. */
    private static String decodeBlob(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String b64 = node.asText(null);
        if (b64 == null || b64.isBlank()) return null;
        try {
            return new String(java.util.Base64.getDecoder().decode(b64), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to decode Caido Blob field: {}", e.getMessage());
            return null;
        }
    }

    // ── Core ──────────────────────────────────────────────────────────────────────

    private JsonNode execute(String baseUrl, String token, String query, ObjectNode variables) {
        String url = normalizeBaseUrl(baseUrl) + "/graphql";
        ObjectNode body = MAPPER.createObjectNode();
        body.put("query", query);
        if (variables != null) body.set("variables", variables);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);

        try {
            ResponseEntity<String> resp = http.exchange(
                url, HttpMethod.POST, new HttpEntity<>(body.toString(), headers), String.class);
            JsonNode root = MAPPER.readTree(resp.getBody());
            if (root.has("errors")) {
                String msg = root.path("errors").path(0).path("message").asText("GraphQL error");
                throw new CaidoApiException(msg);
            }
            return root.path("data");
        } catch (CaidoApiException e) {
            throw e;
        } catch (HttpClientErrorException e) {
            throw new CaidoApiException("HTTP " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString());
        } catch (Exception e) {
            throw new CaidoApiException("Request failed: " + e.getMessage());
        }
    }

    private static String normalizeBaseUrl(String url) {
        if (url == null) return "";
        String s = url.trim();
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static void checkError(JsonNode payload, String op) {
        JsonNode error = payload.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new CaidoApiException(op + " error: " + error.path("message").asText("unknown"));
        }
    }

    private static List<String> toStringList(JsonNode node) {
        var out = new ArrayList<String>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) out.add(item.asText());
        }
        return out;
    }

    public record CaidoScope(String id, String name, List<String> allowlist, List<String> denylist) {}

    public static class CaidoApiException extends RuntimeException {
        public CaidoApiException(String message) { super(message); }
    }
}
