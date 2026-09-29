package com.martecyber.plugins.caido;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.integrations.CredentialCryptoFacade;
import com.martecyber.ares.plugins.PluginComponent;
import com.martecyber.plugins.caido.dto.CaidoDtos.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

public class CaidoApiIntegrationService implements PluginComponent {

    private static final Logger log = LoggerFactory.getLogger(CaidoApiIntegrationService.class);

    private final CaidoApiIntegrationRepository repo;
    private final CaidoApiIntegrationGrantRepository grantRepo;
    private final CaidoGraphQLClient graphql;
    private final CredentialCryptoFacade crypto;
    private final ObjectMapper json;

    public CaidoApiIntegrationService(
        CaidoApiIntegrationRepository repo,
        CaidoApiIntegrationGrantRepository grantRepo,
        CaidoGraphQLClient graphql,
        CredentialCryptoFacade crypto,
        ObjectMapper json
    ) {
        this.repo = repo;
        this.grantRepo = grantRepo;
        this.graphql = graphql;
        this.crypto = crypto;
        this.json = json;
    }

    public List<CaidoApiIntegrationDto> list() {
        return repo.findAllByOrderByLabelAsc().stream().map(CaidoApiIntegrationDto::from).toList();
    }

    public CaidoApiIntegrationDto get(Long id) {
        return CaidoApiIntegrationDto.from(find(id));
    }

    public CaidoApiIntegrationDto create(CreateCaidoApiIntegrationRequest req) {
        var i = new CaidoApiIntegration();
        i.setLabel(req.label());
        i.setBaseUrl(req.baseUrl().trim());
        if (req.accessToken() != null && !req.accessToken().isBlank()) {
            var enc = crypto.encrypt(req.accessToken());
            i.setAccessTokenCiphertext(enc.ciphertext());
            i.setAccessTokenIv(enc.iv());
        }
        if (req.enabled() != null) i.setEnabled(req.enabled());
        return CaidoApiIntegrationDto.from(repo.save(i));
    }

    public CaidoApiIntegrationDto update(Long id, UpdateCaidoApiIntegrationRequest req) {
        var i = find(id);
        if (req.label() != null) i.setLabel(req.label());
        if (req.baseUrl() != null) i.setBaseUrl(req.baseUrl().trim());
        if (req.accessToken() != null && !req.accessToken().isBlank()) {
            var enc = crypto.encrypt(req.accessToken());
            i.setAccessTokenCiphertext(enc.ciphertext());
            i.setAccessTokenIv(enc.iv());
            i.setConnectionStatus("unknown");
        }
        if (req.enabled() != null) i.setEnabled(req.enabled());
        i.setUpdatedAt(OffsetDateTime.now());
        return CaidoApiIntegrationDto.from(repo.save(i));
    }

    public void delete(Long id) {
        repo.delete(find(id));
    }

    public CaidoApiIntegrationDto testConnection(Long id) {
        var i = find(id);
        String token = decryptToken(i);
        try {
            var projects = graphql.listProjects(i.getBaseUrl(), token);
            i.setConnectionStatus("ok");
            i.setConnectionError(null);
            // Persist known projects for the task-creation dropdown.
            var known = projects.stream()
                .map(p -> new CaidoKnownProjectDto(p.get("id"), p.get("name")))
                .toList();
            i.setKnownProjects(json.writeValueAsString(known));
        } catch (Exception e) {
            log.warn("Caido API test failed for integration {}: {}", id, e.getMessage());
            i.setConnectionStatus("error");
            i.setConnectionError(e.getMessage());
        }
        i.setLastTestedAt(OffsetDateTime.now());
        i.setUpdatedAt(OffsetDateTime.now());
        return CaidoApiIntegrationDto.from(repo.save(i));
    }

    public List<CaidoKnownProjectDto> knownProjects(Long id) {
        var i = find(id);
        try {
            String raw = i.getKnownProjects();
            if (raw == null || raw.isBlank() || "[]".equals(raw.strip())) {
                // No cached projects — fetch live.
                String token = decryptToken(i);
                var projects = graphql.listProjects(i.getBaseUrl(), token);
                var known = projects.stream()
                    .map(p -> new CaidoKnownProjectDto(p.get("id"), p.get("name")))
                    .toList();
                i.setKnownProjects(json.writeValueAsString(known));
                i.setUpdatedAt(OffsetDateTime.now());
                repo.save(i);
                return known;
            }
            return json.readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<List<CaidoKnownProjectDto>>() {});
        } catch (Exception e) {
            log.warn("Failed to retrieve known projects for API integration {}: {}", id, e.getMessage());
            return List.of();
        }
    }

    // ── Grants ────────────────────────────────────────────────────────────────────

    public List<CaidoApiGrantDto> listGrants(Long integrationId) {
        find(integrationId);
        return grantRepo.findByIntegrationId(integrationId).stream().map(CaidoApiGrantDto::from).toList();
    }

    public CaidoApiGrantDto createGrant(Long integrationId, CreateCaidoApiGrantRequest req) {
        find(integrationId);
        var g = new CaidoApiIntegrationGrant();
        g.setIntegrationId(integrationId);
        g.setOrganizationId(req.organizationId());
        g.setProjectId(req.projectId());
        g.setCapabilities(req.capabilities() != null ? req.capabilities()
            : List.of(com.martecyber.plugins.caido.dto.CaidoDtos.ACTION_PULL_SCOPE, com.martecyber.plugins.caido.dto.CaidoDtos.ACTION_PUSH_FINDINGS));
        return CaidoApiGrantDto.from(grantRepo.save(g));
    }

    public void deleteGrant(Long integrationId, Long grantId) {
        var g = grantRepo.findById(grantId)
            .filter(x -> x.getIntegrationId().equals(integrationId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Grant not found: " + grantId));
        grantRepo.delete(g);
    }

    public List<GrantedCaidoApiIntegrationDto> grantedForProject(Long organizationId, Long projectId) {
        var grants = grantRepo.findVisibleToProject(organizationId, projectId);
        var byIntegration = new LinkedHashMap<Long, LinkedHashSet<String>>();
        for (var g : grants) {
            byIntegration.computeIfAbsent(g.getIntegrationId(), k -> new LinkedHashSet<>())
                .addAll(g.getCapabilities());
        }
        var out = new ArrayList<GrantedCaidoApiIntegrationDto>();
        for (var e : byIntegration.entrySet()) {
            repo.findById(e.getKey()).filter(CaidoApiIntegration::isEnabled).ifPresent(i ->
                out.add(new GrantedCaidoApiIntegrationDto(
                    i.getId(), i.getLabel(), i.isEnabled(), i.getConnectionStatus(),
                    i.getLastTestedAt(), List.copyOf(e.getValue()))));
        }
        return out;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    CaidoApiIntegration find(Long id) {
        return repo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Caido API integration not found: " + id));
    }

    String decryptToken(CaidoApiIntegration i) {
        if (i.getAccessTokenCiphertext() == null || i.getAccessTokenIv() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No access token configured for this integration");
        }
        return crypto.decrypt(i.getAccessTokenCiphertext(), i.getAccessTokenIv());
    }
}
