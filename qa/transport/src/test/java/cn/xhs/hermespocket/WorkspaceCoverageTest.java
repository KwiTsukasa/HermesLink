package cn.xhs.hermespocket;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public final class WorkspaceCoverageTest {
    @Test public void classifyBundledOperationsWithTheShippingPolicy() throws Exception {
        WorkspaceCatalog catalog;
        try (InputStream input = getClass().getResourceAsStream("/webapp-contract.json")) {
            assertNotNull(input);
            catalog = WorkspaceCatalog.load(input);
        }
        assertNotNull(catalog.find("rest:GET:/api/model/info"));
        assertNull(WorkspaceClient.unsupportedReason(catalog.find("rest:GET:/api/model/info")));
        Map<String, Integer> available = new TreeMap<>();
        Map<String, Integer> other = new TreeMap<>();
        JSONArray partial = new JSONArray();
        for (WorkspaceCatalog.Entry entry : catalog.entries) {
            String exclusion = WorkspaceClient.unsupportedReason(entry);
            Map<String, Integer> bucket = exclusion == null ? available : other;
            bucket.merge(entry.kind, 1, Integer::sum);
            if (exclusion == null) {
                assertFalse(entry.sensitiveResponse);
                assertTrue("remote".equals(entry.access) || WorkspaceClient.isBundledPlugin(entry)
                        || WorkspaceClient.isSessionBound(entry) || WorkspaceClient.isOAuthManagement(entry));
                if (WorkspaceClient.hasPartialInput(entry)) {
                    partial.put(entry.id);
                }
            }
        }
        assertEquals(43L, catalog.entries.stream().filter(WorkspaceClient::isBundledPlugin)
                .filter(entry -> WorkspaceClient.unsupportedReason(entry) == null).count());
        assertEquals(2L, catalog.entries.stream().filter(WorkspaceClient::isOAuthManagement)
                .filter(entry -> WorkspaceClient.unsupportedReason(entry) == null).count());
        assertEquals(19L, catalog.entries.stream().filter(WorkspaceClient::isSessionBound)
                .filter(entry -> WorkspaceClient.unsupportedReason(entry) == null).count());
        assertNotNull(WorkspaceClient.unsupportedReason(catalog.find("rest:GET:/api/config/raw")));
        assertNotNull(WorkspaceClient.unsupportedReason(catalog.find("rest:POST:/api/files/ticket")));
        assertNotNull(WorkspaceClient.unsupportedReason(catalog.find("rest:POST:/api/profiles/import")));
        assertNotNull(WorkspaceClient.unsupportedReason(catalog.find("rest:POST:/api/profiles/{name}/export")));
        assertNull(WorkspaceClient.unsupportedReason(catalog.find("rest:GET:/api/gateway-connections")));
        assertNotNull(WorkspaceClient.unsupportedReason(catalog.find("rest:POST:/api/gateway-connections")));
        assertNotNull(WorkspaceClient.unsupportedReason(
                catalog.find("rest:GET:/api/gateway-connections/{connection_id}/oauth")));
        Path out = Path.of("build", "reports", "workspace-capabilities.json");
        Files.createDirectories(out.getParent());
        JSONObject report = new JSONObject().put("version",catalog.version)
                .put("catalogTotal",catalog.entries.size())
                .put("genericTransportEligible",available)
                .put("partialInput",partial)
                .put("outsideGenericTransport",other);
        Files.writeString(out, report.toString(2));
    }
}
