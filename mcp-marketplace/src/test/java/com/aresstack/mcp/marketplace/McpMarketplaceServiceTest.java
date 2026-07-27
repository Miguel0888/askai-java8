package com.aresstack.mcp.marketplace;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class McpMarketplaceServiceTest {

    @Test
    public void returnFilteredBuiltInEntriesWithoutNetworkAccess() {
        MarketplaceConfiguration configuration = new MarketplaceConfiguration(
                Paths.get("build", "test-marketplace-cache"),
                Collections.singletonList(McpMarketplaceSource.defaults().get(0)),
                GitHubTokenProvider.ENVIRONMENT,
                MarketplaceLog.NO_OP
        );

        McpMarketplaceService service = new McpMarketplaceService(configuration);
        List<McpMarketplaceEntry> result = service.search("memory graph", "built-in", false);

        assertEquals(1, result.size());
        assertEquals("memory", result.get(0).getName());
        assertTrue(result.get(0).isInstallable());
        assertFalse(result.get(0).getInstallOptions().isEmpty());
    }
}
