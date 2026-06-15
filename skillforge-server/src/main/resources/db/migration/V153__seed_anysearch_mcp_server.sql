-- Seed the AnySearch MCP server (HTTP transport, added in V152) and bind it to the
-- Main Assistant (id=3) and Research Agent (id=5), plus tool-routing guidance.
--
-- AnySearch is a hosted MCP server reachable only over remote HTTP (JSON-RPC 2.0,
-- Bearer auth) — verified to support initialize / tools/list / tools/call and to
-- return application/json. It exposes structured vertical data (finance quotes &
-- fundamentals, academic citations via Crossref, CVEs, patents, ...).
--
-- IMPORTANT (Flyway placeholder trap): Flyway expands the dollar-brace token even
-- inside string literals, so the header value must NOT contain a literal
-- dollar-brace sequence in this file. We build it with chr(36) (the dollar sign)
-- concatenated to the rest, so the stored value becomes the literal placeholder
-- "Bearer <dollar-brace>ANY_SEARCH_API_KEY<brace>" which McpServerLifecycle.resolveHeaders
-- substitutes from System.getenv at spawn time (the resolved secret is never persisted).

INSERT INTO t_mcp_server (name, transport, command, url, args, env, headers, description, enabled)
VALUES (
    'anysearch',
    'http',
    NULL,
    'https://api.anysearch.com/mcp',
    '[]',
    '{}',
    '{"Authorization":"Bearer ' || chr(36) || '{ANY_SEARCH_API_KEY}"}',
    'AnySearch — hosted MCP for structured vertical data (finance quotes/fundamentals/macro, academic citations via Crossref, CVE, patents, travel, ...). Tools: search / batch_search / get_sub_domains / extract.',
    TRUE
) ON CONFLICT (name) DO NOTHING;

-- Bind to Main Assistant (id=3) and Research Agent (id=5) — append 'anysearch' to the
-- comma-list, guarding against an existing reference (idempotent token append).
UPDATE t_agent
SET mcp_server_ids = CASE
        WHEN mcp_server_ids IS NULL OR mcp_server_ids = '' THEN 'anysearch'
        WHEN ',' || mcp_server_ids || ',' LIKE '%,anysearch,%' THEN mcp_server_ids
        ELSE mcp_server_ids || ',anysearch'
    END
WHERE id IN (3, 5);

-- Tool-routing guidance → tools_prompt column (the runtime system prompt reads
-- t_agent.tools_prompt; this column is not churned by auto_improve prompt-version
-- promotion, which targets system_prompt). Append to any existing tools_prompt.
UPDATE t_agent
SET tools_prompt = COALESCE(NULLIF(tools_prompt, ''), '')
    || CASE WHEN COALESCE(tools_prompt, '') = '' THEN '' ELSE E'\n\n' END
    || E'## Search tool routing\n'
    || E'Two search capabilities are available — choose deliberately:\n'
    || E'- AnySearch (mcp_anysearch_*) — structured, authoritative vertical data. PREFER it for: stock/forex/crypto/commodity quotes, financial statements & macro indicators, academic papers & citations (DOI/Crossref), CVEs, patents, and other structured domain lookups. For a vertical query, FIRST call mcp_anysearch_get_sub_domains for the target domain to discover the right sub_domain and its required params, THEN call mcp_anysearch_search with domain + sub_domain + sub_domain_params. Use mcp_anysearch_batch_search for 2–5 independent queries at once.\n'
    || E'- WebSearch — general open-web search (news, blogs, docs, how-tos, trending topics). Use it for anything not covered by an AnySearch structured vertical.\n'
    || E'Default: for structured/quantitative facts (prices, financials, papers, vulnerabilities) try AnySearch first; otherwise use WebSearch.'
WHERE id IN (3, 5);
