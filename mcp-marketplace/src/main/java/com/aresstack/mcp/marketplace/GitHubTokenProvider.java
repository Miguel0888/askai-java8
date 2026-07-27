package com.aresstack.mcp.marketplace;

/** Supplies an optional GitHub token for GitHub-owned marketplace endpoints. */
public interface GitHubTokenProvider {
    String getToken();

    GitHubTokenProvider ENVIRONMENT = new GitHubTokenProvider() {
        @Override
        public String getToken() {
            return System.getenv("GITHUB_TOKEN");
        }
    };
}
