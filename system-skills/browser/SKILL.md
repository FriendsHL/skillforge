---
name: browser
description: "Browse web pages, extract content, click elements, type text, and take screenshots using the agent-browser CLI. Supports persistent login sessions for authenticated sites."
---

# Browser Automation

Use `npx agent-browser` to automate browser interactions.

## Quick Start

Navigate to a URL:
```bash
npx agent-browser goto https://example.com
```

## Core Commands

### Navigate
```bash
npx agent-browser goto <url>
```

### Get Page Snapshot (Recommended for understanding page structure)
```bash
npx agent-browser snapshot -i
```
Returns an accessibility tree with ref IDs (e.g., `[ref=e12]`). Use these refs for click/type actions.

### Get Page Title
```bash
npx agent-browser get title
```

### Get Current URL
```bash
npx agent-browser get url
```

### Click an Element
```bash
npx agent-browser click <ref>
```
Use ref IDs from the snapshot output (e.g., `npx agent-browser click e12`).

### Type Text
```bash
npx agent-browser type <ref> "text to type"
```

### Evaluate JavaScript
```bash
npx agent-browser eval "document.body.innerText"
```
Use this to extract page text content. More reliable than screenshots for data extraction.

### Take Screenshot
```bash
npx agent-browser screenshot
```

## Browser Lifecycle

### Check Browser State
```bash
npx agent-browser get url
```
- Returns a URL → browser is running
- Returns "Browser not launched" → need to open first

### Open Browser (First Time)
```bash
npx agent-browser open <url> --headed --no-sandbox
```
Use `--headed` to show the browser window (useful when user needs to log in manually).

### Close Browser
```bash
npx agent-browser close
```

## Login to Authenticated Sites

For sites requiring login:
1. Open the browser in headed mode: `npx agent-browser open <login-url> --headed --no-sandbox`
2. Tell the user to log in manually in the browser window
3. After login, use `goto` for subsequent navigation — the session is persisted

## Important Notes

- Prefer `snapshot -i` over `screenshot` for understanding page structure — it returns structured text, not an image
- Prefer `eval "document.body.innerText"` for extracting text content — more reliable than screenshots
- Use ref IDs from snapshot for click/type actions — do NOT use CSS selectors
- If browser is not launched, use `open` first, then `goto` for navigation
- If browser seems stuck (command hangs > 10s), close and reopen:
  `npx agent-browser close 2>/dev/null; npx agent-browser open <url> --headed --no-sandbox`
