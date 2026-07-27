package com.aresstack.mcp.marketplace;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/** Holds all infrastructure choices required by the reusable marketplace backend. */
public final class MarketplaceConfiguration {
    private final Path cacheDirectory;
    private final List<McpMarketplaceSource> sources;
    private final GitHubTokenProvider tokenProvider;
    private final MarketplaceLog log;

    public MarketplaceConfiguration(Path cacheDirectory, List<McpMarketplaceSource> sources,
                                    GitHubTokenProvider tokenProvider, MarketplaceLog log) {
        if (cacheDirectory == null) {
            throw new IllegalArgumentException("cacheDirectory must not be null");
        }
        if (sources == null) {
            throw new IllegalArgumentException("sources must not be null");
        }
        this.cacheDirectory = cacheDirectory;
        this.sources = sources;
        this.tokenProvider = tokenProvider != null ? tokenProvider : GitHubTokenProvider.ENVIRONMENT;
        this.log = log != null ? log : MarketplaceLog.NO_OP;
    }

    public static MarketplaceConfiguration createDefault() {
        Path cache = Paths.get(System.getProperty("user.home"), ".aresstack", "mcp-marketplace-cache");
        return new MarketplaceConfiguration(cache, McpMarketplaceSource.defaults(),
                GitHubTokenProvider.ENVIRONMENT, MarketplaceLog.NO_OP);
    }

    public Path getCacheDirectory() {
        return cacheDirectory;
    }

    public List<McpMarketplaceSource> getSources() {
        return sources;
    }

    public GitHubTokenProvider getTokenProvider() {
        return tokenProvider;
    }

    public MarketplaceLog getLog() {
        return log;
    }
}
