# Dashboard Design Rules

Read this before changing `*.tsx`, `*.css`, dashboard layout, or user-facing UI.

- SkillForge dashboard should feel precise, professional, and developer-oriented, with inspiration from Linear, Raycast, Cursor, Vercel, Posthog, and Sentry.
- Avoid template-looking UI: generic card grids, centered generic hero plus gradient, raw Ant Design defaults, flat layouts with no hierarchy, one-size radius/shadow, and default dashboard layouts without a design point of view.
- Meaningful UI surfaces should show several of: scale hierarchy, intentional spacing rhythm, depth/layering, deliberate typography, semantic color, designed interaction states, clarifying motion, and integrated data visualization.
- Preferred visual direction: dark precision with subtle depth, 8px spacing grid, small elements around 4px radius, cards around 8px, large containers around 12px, and layered shadows.
- Use CSS variables/tokens for palette, typography, spacing, timing, and easing.
- Use semantic HTML first. Avoid wrapper `div` stacks when a semantic element fits.
- Animate compositor-friendly properties such as `transform`, `opacity`, `clip-path`, and sparing `filter`. Avoid animating layout-bound properties.
- Web UI verification should include meaningful screenshots at key breakpoints, accessibility/keyboard checks, reduced-motion behavior, contrast, overflow, and deterministic E2E assertions.

## Web Security And Performance

- Configure production CSP; prefer nonce-based scripts over `unsafe-inline`.
- Never inject unsanitized HTML. Avoid `innerHTML`/`dangerouslySetInnerHTML` unless sanitized with a vetted sanitizer.
- Use SRI for CDN scripts and prefer self-hosting critical dependencies where practical.
- State-changing forms need CSRF protection, rate limiting, and client/server validation.
- Target Core Web Vitals: LCP < 2.5s, INP < 200ms, CLS < 0.1, FCP < 1.5s, TBT < 200ms.
- Set explicit image dimensions. Hero media may be eager/high priority; below-fold media should be lazy.
- Prefer AVIF/WebP with fallbacks and avoid shipping images far beyond rendered size.
- Use at most two font families unless there is a clear reason.
- Dynamically import heavy libraries and defer non-critical CSS/JS.
