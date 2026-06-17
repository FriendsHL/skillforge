-- Strengthen the AnySearch tool-routing guidance for Main Assistant (id=3) and
-- Research Agent (id=5). The softer V153 wording ("PREFER" / "try first") was not
-- decisive: a clear structured query (A-share close price) still routed to WebSearch
-- in smoke testing. This makes the structured-vertical cases imperative and adds the
-- A-share cn_code hint (removes the finance.quote param-retry friction observed in QA).
--
-- Wholesale SET (not append) — for these two agents tools_prompt currently holds only
-- the V153 routing block, so replacing it is clean and idempotent. No dollar-brace
-- tokens here, so no Flyway placeholder handling needed.

UPDATE t_agent
SET tools_prompt =
    E'## Search tool routing (IMPORTANT)\n'
 || E'You have two distinct search capabilities; choosing wrong gives the user worse data.\n\n'
 || E'**AnySearch (the mcp_anysearch_* tools)** returns STRUCTURED, authoritative vertical data. You MUST use it — NOT WebSearch — whenever the user asks about any of:\n'
 || E'- stock / index / forex / crypto / commodity prices or quotes (including A-shares such as 600519.SH, e.g. "茅台收盘价" / "贵州茅台股价")\n'
 || E'- company financial statements, fundamentals, valuation, SEC filings\n'
 || E'- macro indicators (GDP, CPI, interest rates, etc.) or stock screening\n'
 || E'- academic papers & citations (DOI / Crossref), CVE vulnerabilities, or patents\n'
 || E'WebSearch CANNOT return structured quotes or financials — it only returns web-page snippets, so do not use it for the cases above.\n\n'
 || E'Workflow for a vertical query: FIRST call mcp_anysearch_get_sub_domains for the relevant domain to discover the right sub_domain and its required params, THEN call mcp_anysearch_search with domain + sub_domain + sub_domain_params. For finance.quote: A-shares pass cn_code (e.g. "600519.SH") with symbol:""; US stocks pass symbol (e.g. "AAPL"). Use mcp_anysearch_batch_search for 2-5 independent queries at once.\n\n'
 || E'**WebSearch** is for general open-web information — news, blogs, documentation, how-tos, opinions, trending topics — that is NOT one of the structured verticals above.\n\n'
 || E'Decision rule: prices / financials / macro data / academic papers / CVEs / patents -> AnySearch. Everything else -> WebSearch.'
WHERE id IN (3, 5);
