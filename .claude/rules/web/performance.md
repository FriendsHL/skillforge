> This file extends [common/performance.md](../common/performance.md) with web-specific performance content.

> **SkillForge override (2026-04-30)**：本文件主要面向 marketing 公网站。SkillForge 是后台 dashboard（用户登录后才用），**仅以下章节适用**：
> - ✅ Core Web Vitals（INP / CLS 通用，但 LCP/FCP 目标值不严格按 marketing 标准卡）
> - ✅ Bundle Budget 中 **App page** 行（< 300kb gzipped）—— Landing / Microsite 行不适用
> - ✅ Image Optimization（图片尺寸 / lazy loading 通用）
> - ✅ Animation Performance（compositor-friendly props 通用）
> - ✅ Performance Checklist（通用）
> - ❌ Loading Strategy 第 2 条 "Preload hero image" —— dashboard 没 hero
> - ❌ Font Loading "Max two font families" —— dashboard 走 Ant Design 默认字体栈，无需 custom fonts

# Web Performance Rules

## Core Web Vitals Targets

| Metric | Target |
|--------|--------|
| LCP | < 2.5s |
| INP | < 200ms |
| CLS | < 0.1 |
| FCP | < 1.5s |
| TBT | < 200ms |

## Bundle Budget

| Page Type | JS Budget (gzipped) | CSS Budget |
|-----------|---------------------|------------|
| Landing page | < 150kb | < 30kb |
| App page | < 300kb | < 50kb |
| Microsite | < 80kb | < 15kb |

## Loading Strategy

1. Inline critical above-the-fold CSS where justified
2. Preload the hero image and primary font only
3. Defer non-critical CSS or JS
4. Dynamically import heavy libraries

```js
const gsapModule = await import('gsap');
const { ScrollTrigger } = await import('gsap/ScrollTrigger');
```

## Image Optimization

- Explicit `width` and `height`
- `loading="eager"` plus `fetchpriority="high"` for hero media only
- `loading="lazy"` for below-the-fold assets
- Prefer AVIF or WebP with fallbacks
- Never ship source images far beyond rendered size

## Font Loading

- Max two font families unless there is a clear exception
- `font-display: swap`
- Subset where possible
- Preload only the truly critical weight/style

## Animation Performance

- Animate compositor-friendly properties only
- Use `will-change` narrowly and remove it when done
- Prefer CSS for simple transitions
- Use `requestAnimationFrame` or established animation libraries for JS motion
- Avoid scroll handler churn; use IntersectionObserver or well-behaved libraries

## Performance Checklist

- [ ] All images have explicit dimensions
- [ ] No accidental render-blocking resources
- [ ] No layout shifts from dynamic content
- [ ] Motion stays on compositor-friendly properties
- [ ] Third-party scripts load async/defer and only when needed
