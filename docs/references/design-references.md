# Design References — AI Agent Platform Dashboard

Consolidated design tokens from 5 developer-tool design systems, filtered for dark-theme / data-dense / developer-tool applicability.

Source: getdesign.md previews for Linear, Cursor, Raycast, Claude, Sentry.

---

## 1. Linear — Ultra-minimal, purple accent

**Best for:** sidebar navigation, issue lists, status badges, data tables.

### Colors (dark mode)

| Token | Value | Usage |
|---|---|---|
| Background page | `#08090a` | Hero / deepest surface |
| Background panel | `#0f1011` | Sidebar, left nav |
| Surface elevated | `#191a1b` | Cards, panels |
| Surface hover | `#28282c` | Hover states |
| Text primary | `#f7f8f8` | Headings |
| Text secondary | `#d0d6e0` | Body |
| Text tertiary | `#8a8f98` | Placeholders, metadata |
| Text disabled | `#62666d` | Timestamps, disabled |
| Brand indigo | `#5e6ad2` | CTA buttons |
| Accent violet | `#7170ff` | Links, active states |
| Accent hover | `#828fff` | Hover on accent |
| Success green | `#27a644` | Active, done |
| Emerald | `#10b981` | Completion badges |
| Border strong | `#23252a` | Card borders |
| Border medium | `#34343a` | Dividers |
| Border subtle | `#3e3e44` | Subtle separators |

### Typography

Font: **Inter Variable** (sans), **Berkeley Mono** (mono)

| Role | Size | Weight | Tracking |
|---|---|---|---|
| Display | 48px | 510 | -1.056px |
| Heading 1 | 32px | 400 | -0.704px |
| Heading 2 | 24px | 400 | -0.288px |
| Heading 3 | 20px | 590 | -0.24px |
| Body large | 18px | 400 | -0.165px |
| Body medium | 16px | 510 | — |
| Small | 15px | 400 | -0.165px |
| Caption | 14px | 510 | -0.182px |
| Meta / timestamp | 13px | 510 | -0.13px |
| Label / tag | 12px | 510 | — |
| Mono body | 14px | 400 | — |
| Mono label | 12px | 400 | uppercase |

Feature settings: `"cv01" 1, "ss03" 1`

### Spacing scale

4 · 8 · 12 · 16 · 20 · 24 · 32 · 40px (base-4)

### Border radius

| Value | Context |
|---|---|
| 2px | Badges |
| 4px | Small elements |
| 6px | Buttons |
| 8px | Cards |
| 12px | Panels |
| 22px | Large cards |
| 9999px | Pills |
| 50% | Avatars |

### Elevation (dark mode)

| Level | Shadow |
|---|---|
| 0 – Flat | `border: 1px solid #23252a` |
| 1 – Subtle | `rgba(0,0,0,0.03) 0 1.2px 0 0` |
| 2 – Card | `rgba(0,0,0,0.08) 0 0 0 1px, rgba(0,0,0,0.04) 0 2px 4px` |
| 3 – Elevated | `rgba(0,0,0,0.08) 0 0 0 1px, rgba(0,0,0,0.06) 0 4px 12px` |
| Focus | `0 0 0 2px #7170ff` |
| Inset (sunken) | `inset rgba(0,0,0,0.1) 0 0 12px` |

### Key patterns

- Negative letter-spacing on all headings (-0.3% to -2.2%)
- Weight 510 used extensively (between regular 400 and medium 500)
- Border-only elevation in dark — no heavy drop shadows
- Status badges: pill shape (`border-radius: 9999px`), 12px/510
- Form focus ring: `box-shadow: 0 0 0 2px rgba(113,112,255,0.2)`

---

## 2. Cursor — AI-first editor, warm light theme with operation color coding

**Best for:** tool-call timelines, agent operation logs, diff views, code panels.

### Colors

Cursor ships **light mode first** (cream background). Extract the dark-mode-applicable tokens:

| Token | Value | Usage |
|---|---|---|
| Cursor Dark | `#26251e` | Primary text / dark surfaces |
| Cursor Cream | `#f2f1ed` | Page background (light) |
| Cursor Light | `#e6e5e0` | Secondary surface (light) |
| Accent orange | `#f54e00` | Brand CTA |
| Gold | `#c08532` | Secondary accent |
| Error | `#cf2d56` | Error state |
| Success | `#1f8a65` | Success |
| — AI operation colors — | | |
| Thinking | `#dfa88f` | Agent "thinking" state |
| Grep | `#9fc9a2` | Search operations |
| Read | `#9fbbe0` | File read operations |
| Edit | `#c0a8dd` | Edit operations |
| Border default | `rgba(38,37,30,0.1)` | Subtle border |
| Border medium | `rgba(38,37,30,0.2)` | Medium border |
| Border strong | `rgba(38,37,30,0.55)` | Strong border |

### Typography

Fonts: **CursorGothic** (system-ui fallback), **jjannon** (serif for body copy), **Berkeley Mono** (code)

| Role | Size | Weight | Notes |
|---|---|---|---|
| Display hero | 72px | 400 | -2.16px tracking |
| Section heading | 36px | 400 | -0.72px |
| Sub-heading | 26px | 400 | -0.325px |
| Title small | 22px | 400 | -0.11px |
| Body serif | 19.2px | 500 | jjannon, line-height 1.5 |
| Body serif SM | 17.28px | 400 | jjannon, line-height 1.35 |
| Body sans | 16px | 400 | line-height 1.5 |
| Button label | 14px | 400 | — |
| Caption | 11px | 500 | — |
| Mono body | 12px | 400 | Berkeley Mono |
| System micro | 11px | 500 | uppercase, system-ui |

### Spacing scale

2 · 3 · 4 · 5 · 6 · 8 · 10 · 12 · 16 · 24 · 32 · 48px (fine-grained, micro-detail oriented)

### Border radius

1.5px (micro) · 2px (inline) · 3px (small) · 4px (cards/inputs) · 8px (buttons/cards) · 10px (featured) · 9999px (pills)

### Elevation

| Level | Shadow |
|---|---|
| 0 – Flat | `border: 1px solid rgba(38,37,30,0.1)` |
| 1 – Border ring | `rgba(38,37,30,0.1) 0 0 0 1px` |
| 1b – Medium | `rgba(38,37,30,0.2) 0 0 0 1px` |
| 2 – Ambient | `rgba(0,0,0,0.02) 0 0 16px, rgba(0,0,0,0.008) 0 0 8px` |
| 3 – Modal/popover | `rgba(0,0,0,0.14) 0 28px 70px, rgba(0,0,0,0.1) 0 14px 32px, rgba(38,37,30,0.1) 0 0 0 1px` |
| Focus | `rgba(0,0,0,0.1) 0 4px 12px` |

### Key patterns (directly applicable to agent dashboard)

- **Operation color coding on pills:** each agent tool type gets a distinct muted color (thinking/grep/read/edit). Apply to SkillForge tool-call badges.
- Sans for UI chrome; serif for longer narrative/description text
- Weight 400 for most headings — softness, not boldness
- Very fine spacing scale starting at 2px for micro-components

---

## 3. Raycast — Productivity launcher, deep dark chrome + vibrant accents

**Best for:** command palette, keyboard-shortcut UI, quick actions, status indicators.

### Colors (dark mode)

| Token | Value | Usage |
|---|---|---|
| Background | `#07080a` | Page (near-black blue) |
| Card surface | `#1b1c1e` | Elevated containers |
| Surface 100 | `#101111` | Card backgrounds |
| Primary text | `#f9f9f9` | Near-white |
| Secondary text | `#cecece` | Light gray |
| Nav / links | `#9c9c9d` | Medium gray |
| Disabled | `#6a6b6c` | Dim gray |
| Inactive | `#434345` | Dark gray |
| Border | `#252829` | Card borders |
| Dark border | `#2f3031` | Deeper borders |
| Border subtle | `rgba(255,255,255,0.06)` | Ghost border |
| Border medium | `rgba(255,255,255,0.1)` | Mid ghost |
| Raycast Red | `#ff6363` | Brand accent, hero |
| Raycast Blue | `#55b3ff` | Links, interactive |
| Raycast Green | `#5fc992` | Success |
| Raycast Yellow | `#ffbc33` | Warning / highlights |
| Button bg | `hsla(0,0%,100%,0.815)` | Primary CTA (white on dark) |
| Button fg | `#18191a` | Text on white button |

### Typography

Font: **Inter** + **GeistMono**

| Role | Size | Weight | Notes |
|---|---|---|---|
| Hero | 72px | 600 | `"liga" 0, "ss02", "ss08"` |
| H1 (feature) | 44px | 600 | — |
| H2 | 24px | 500 | letter-spacing 0.2px |
| H3 | 22px | 400 | — |
| Body | 16px–18px | 500 | letter-spacing 0.2px |
| Nav label | 16px | 600 | — |
| Card title | 20px | 600 | — |
| Card text | 14px | — | `#9c9c9d` |
| Section label | 12px | 600 | uppercase, blue color |
| Mono | 14px | 500 | GeistMono, blue color |
| Micro | 12px | 600 | NEW EXTENSION label style |

### Spacing scale

4 · 8 · 12 · 16 · 20 · 24 · 32 · 48 · 64px

### Border radius

2px (micro) · 4px (keys) · 6px (buttons/tags) · 8px (inputs) · 12px (cards) · 16px (large cards) · 86px (primary CTA — near-pill)

### Elevation

| Level | Shadow |
|---|---|
| 0 – Flat | `border: 1px solid rgba(255,255,255,0.06)` |
| 1 – Subtle | `rgba(0,0,0,0.28) 0 1.189px 2.377px` |
| 2 – Ring | `rgb(27,28,30) 0 0 0 1px, rgb(7,8,10) 0 0 0 1px inset` |
| 3 – Button | `rgba(255,255,255,0.05) 0 1px 0 inset, rgba(255,255,255,0.25) 0 0 0 1px, rgba(0,0,0,0.2) 0 -1px 0 inset` |
| 4 – Float | `rgba(0,0,0,0.5) 0 0 0 2px, rgba(255,255,255,0.19) 0 0 14px, inset highlight/shadow` |

### Key patterns

- **Near-pill buttons** (`border-radius: 86px`) for primary CTA — distinctive
- Letter-spacing 0.2px (slightly positive) on most UI text — different from Linear's negative tracking
- Section labels in **blue + uppercase monospace** — works well for dashboard section headers
- Subtle white/transparent borders rather than solid borders — depth without weight
- White button on black bg for primary CTA — high contrast, dev-tool standard

---

## 4. Claude — Warm editorial, terracotta accent

**Best for:** chat interfaces, message bubbles, narrative/reasoning display, warm content areas.

### Colors

Claude ships **light mode** (parchment background). Dark-mode equivalents:

| Token | Value | Usage |
|---|---|---|
| Near-black | `#141413` | Primary text / dark surface bg |
| Dark surface | `#30302e` | Dark-theme containers |
| Dark warm | `#3d3d3a` | Elevated dark surface |
| Charcoal warm | `#4d4c48` | Button text on light |
| Olive gray | `#5e5d59` | Secondary body text |
| Stone gray | `#87867f` | Tertiary / metadata |
| Warm silver | `#b0aea5` | Secondary text (mid) |
| Parchment | `#f5f4ed` | Light page background |
| Ivory | `#faf9f5` | Card surfaces (light) |
| Warm sand | `#e8e6dc` | Button backgrounds (light) |
| Terracotta brand | `#c96442` | Core brand, primary CTA |
| Coral accent | `#d97757` | Text accents, links on dark |
| Error crimson | `#b53333` | Error (warm red, not orange) |
| Focus blue | `#3898ec` | Input focus rings (only cool color) |
| Border cream | `#f0eee6` | Light borders |
| Border warm | `#e8e6dc` | Standard borders |
| Border dark | `#30302e` | Dark borders |
| Ring warm | `#d1cfc5` | Focus ring (light) |

### Typography

Fonts: **Georgia/serif** (headings, editorial), **Arial/system-ui** (UI), **SFMono/Menlo** (code)

| Role | Size | Weight | Font |
|---|---|---|---|
| Display | 64px | 500 | Serif |
| Section heading | 36px | 500 | Serif |
| Card heading | 25px | 500 | Serif |
| Body large | 20px | 500 | Serif |
| Body small serif | 16px | 500 | Serif |
| UI / nav | 15–16px | 400–500 | Sans |
| Button | 16px | 500 | Sans |
| Caption/label | 11px | 500 | Sans/Mono |

### Elevation

| Level | Shadow |
|---|---|
| Flat | no shadow |
| Contained | `border: 1px solid #f0eee6` |
| Ring | `box-shadow: 0 0 0 1px #d1cfc5` |
| Whisper | `border + rgba(0,0,0,0.05) 0 4px 24px` |
| Inset | `inset 0 0 0 1px rgba(0,0,0,0.15)` |

### Key patterns (applicable to AI agent context)

- Serif for content/reasoning; sans for chrome/controls — strong hierarchy signal
- All-warm palette: even "neutral" grays have warm undertones (`#87867f`, `#5e5d59`)
- Focus blue `#3898ec` as the only cool color — clear interactive signal
- Error color is warm crimson (`#b53333`) not standard red — less alarming, editorial
- Good for: agent response text areas, explanation panels, "thinking" narrative sections

---

## 5. Sentry — Error monitoring, deep purple dashboard, data-dense

**Best for:** error tables, event timelines, status charts, log views, dense data grids.

### Colors (dark mode)

| Token | Value | Usage |
|---|---|---|
| Background primary | `#1f1633` | Page background |
| Background deeper | `#150f23` | Sections, footer |
| Border purple | `#362d59` | Borders, dividers |
| Sentry purple | `#6a5fc1` | Links, hover, focus rings |
| Muted purple | `#79628c` | Button backgrounds |
| Deep violet | `#422082` | Active states, selects |
| Lime green | `#c2ef4e` | High-visibility accent, alerts |
| Coral | `#ffb287` | Focus state backgrounds |
| Pink | `#fa7faa` | Focus outlines, decor |
| Primary text | `#ffffff` | Headings |
| Secondary text | `#e5e7eb` | Body |
| Code yellow | `#dcdcaa` | Syntax highlighting |
| Glass white | `rgba(255,255,255,0.18)` | Frosted glass buttons |
| Glass dark | `rgba(54,22,107,0.14)` | Hover overlay |
| Input border | `#cfcfdb` | Form borders |

### Typography

Font: **Rubik** (UI), **Monaco/Menlo** (mono). Note: "Dammit Sans" referenced for display (approximated with Rubik bold).

| Role | Size | Weight | Notes |
|---|---|---|---|
| Display hero | 80–88px | 700 | — |
| Display secondary | 60px | 500 | — |
| Section heading | 30px | 400 | Rubik |
| Sub-heading | 27px | 500 | — |
| Card title | 24px | 500 | — |
| Feature title | 20px | 600 | — |
| Body | 16px | 400–600 | line-height 1.5 |
| Nav label | 15px | 500 | — |
| Button text | 14px | 700 | uppercase, 0.2px spacing |
| Caption | 14px | 500 | uppercase |
| Small caption | 12px | 600 | line-height 2.0 |
| Micro label | 10px | 600 | uppercase, 0.25px spacing |
| Code / mono | 12px+ | 600 | Monaco |

### Spacing scale

1 · 2 · 4 · 8 · 12 · 16 · 24 · 32 · 40 · 48px

### Border radius

(not fully extracted; estimated from design) 4px (inputs) · 6px (buttons) · 8px (cards) · 12px (panels)

### Elevation

| Level | Shadow |
|---|---|
| 0 – Inset | `rgba(0,0,0,0.1) 0 1px 3px 0 inset` |
| 1 – Flat | none |
| 2 – Glass | `rgba(0,0,0,0.08) 0 2px 8px` |
| 3 – Card | `rgba(0,0,0,0.1) 0 10px 15px -3px` |
| 4 – Hover | `rgba(0,0,0,0.18) 0 0.5rem 1.5rem` |
| 5 – Ambient | `rgba(22,15,36,0.9) 0 4px 4px 9px` (deep purple tint) |

### Key patterns

- **Lime green (`#c2ef4e`) for high-priority alerts** — maximum contrast on purple dark — adopt for critical agent errors
- **Uppercase button text** with letter-spacing — clear action affordance in dense UIs
- Rubik's rounded terminals add friendliness vs. Inter's sharpness — good for error/warning contexts to reduce alarm
- Glass morphism buttons (`rgba(255,255,255,0.18)`) with backdrop-filter — works well for floating panels
- Deep purple tinted backgrounds (not just black) — warmth and brand identity in dark mode
- Code yellow `#dcdcaa` for syntax — standard VSCode palette, familiar to devs

---

## Consolidated Tokens for SkillForge Dashboard

Recommended selections blending the most applicable patterns from all 5 systems.

### Recommended dark-mode color palette

```css
:root {
  /* Backgrounds — Linear-inspired layering */
  --bg-void:    #07080a;   /* Raycast: deepest, sidebar bg */
  --bg-base:    #0f1011;   /* Linear: panel / main canvas */
  --bg-surface: #191a1b;   /* Linear: card surface */
  --bg-hover:   #28282c;   /* Linear: hover state */
  --bg-overlay: #1b1c1e;   /* Raycast: elevated overlay */

  /* Text — Linear scale */
  --text-primary:    #f7f8f8;
  --text-secondary:  #d0d6e0;
  --text-tertiary:   #8a8f98;
  --text-disabled:   #62666d;

  /* Brand accent — choice: pick ONE primary */
  /* Option A: Linear indigo/violet (calm, professional) */
  --accent:       #5e6ad2;
  --accent-hover: #7170ff;
  /* Option B: Sentry purple (data-dense, monitoring-feel) */
  /* --accent: #6a5fc1; */

  /* Status */
  --status-success: #10b981;   /* Linear emerald */
  --status-warning: #ffbc33;   /* Raycast yellow */
  --status-error:   #ff6363;   /* Raycast red (high visibility on dark) */
  --status-info:    #55b3ff;   /* Raycast blue */
  --status-active:  #c2ef4e;   /* Sentry lime (critical/live alerts) */

  /* Agent operation colors — Cursor pattern */
  --op-thinking: #dfa88f;
  --op-search:   #9fc9a2;
  --op-read:     #9fbbe0;
  --op-write:    #c0a8dd;
  --op-execute:  #ffbc33;

  /* Borders */
  --border-strong:  #23252a;
  --border-medium:  #34343a;
  --border-subtle:  rgba(255,255,255,0.06);

  /* Focus */
  --focus-ring: #7170ff;
}
```

### Recommended typography

```css
:root {
  --font-sans: 'Inter', -apple-system, 'Segoe UI', sans-serif;
  --font-mono: 'GeistMono', ui-monospace, 'SF Mono', Menlo, monospace;

  /* Scale */
  --text-hero:    48px; /* weight 510, tracking -1px */
  --text-h1:      32px; /* weight 400, tracking -0.7px */
  --text-h2:      24px; /* weight 400, tracking -0.3px */
  --text-h3:      20px; /* weight 590, tracking -0.24px */
  --text-body-lg: 18px; /* weight 400, line-height 1.6 */
  --text-body:    16px; /* weight 400–510 */
  --text-sm:      14px; /* weight 510, tracking -0.18px */
  --text-caption: 13px; /* weight 510, tracking -0.13px */
  --text-label:   12px; /* weight 510 */
  --text-micro:   11px; /* weight 600, uppercase for Sentry-style labels */
  --text-mono:    13px; /* monospace for code/IDs */
}
```

### Recommended spacing scale

```
4 · 8 · 12 · 16 · 20 · 24 · 32 · 40 · 48 · 64 · 80 · 96px
```

### Recommended border radius

```
2px  — badges, micro chips
4px  — inputs, small interactive
6px  — buttons (default)
8px  — cards, panels
12px — large cards, modals
9999px — pill badges, status tags
50%  — avatars
```

### Recommended elevation (dark mode)

```css
--shadow-flat:    none;                             /* use border instead */
--shadow-card:    0 0 0 1px #23252a;                /* border-ring only */
--shadow-raised:  0 0 0 1px #23252a,
                  0 4px 12px rgba(0,0,0,0.24);      /* card with lift */
--shadow-float:   0 0 0 1px #34343a,
                  0 8px 32px rgba(0,0,0,0.4);       /* modals, tooltips */
--shadow-focus:   0 0 0 2px #7170ff;                /* focus ring */
--shadow-inset:   inset 0 0 0 1px rgba(0,0,0,0.2); /* sunken wells */
```

---

## Do's and Don'ts (synthesized)

### Do

- Use border-ring elevation (`0 0 0 1px`) instead of drop shadows as the primary depth signal — all 5 systems do this
- Color-code agent operation types with muted pastels (Cursor pattern) — immediately scannable in logs
- Negative letter-spacing on headings (Linear) — tighter, more precise feel
- Uppercase + monospace for section labels and micro-metadata (Raycast, Sentry)
- Near-pill or pill border-radius for status badges (`9999px`) — avoids square-badge harshness
- Keep font weight distribution narrow: 400/510 for most text, 590–700 only for CTAs and critical labels
- Use lime/high-chroma accent (`#c2ef4e` style) only for live/critical signals — not as decoration

### Don't

- Don't animate layout properties — keep motion on `transform`/`opacity` only
- Don't use more than 2 accent colors at once in the same surface
- Don't use heavy drop shadows in dark mode — they look muddy; prefer border-ring + subtle spread
- Don't mix warm and cool border colors on the same component
- Don't use bold weight (700+) for body text — reserve for button labels and micro uppercase labels (Sentry pattern)
- Don't use positive letter-spacing on body text larger than 16px — stick to neutral or slightly negative
- Don't make error states alarming orange/red by default — Cursor (`#cf2d56`) and Claude (`#b53333`) both use muted, slightly warm reds
