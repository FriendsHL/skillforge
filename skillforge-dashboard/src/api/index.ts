/**
 * API barrel — re-exports every legacy-domain module so existing
 * `import { x } from '../api'` / `import api from '../api'` call sites keep
 * working unchanged. The axios instance + interceptors live in `./client`;
 * each domain's endpoints + wire types live in their own file.
 *
 * New domains (evolve / flywheel / canary / channels / ...) already follow
 * the one-file-per-domain pattern and are imported directly by consumers —
 * they are deliberately NOT re-exported here. New code should import from
 * the domain file, not this barrel.
 */
export { default } from './client';
export * from './client';

export * from './agents';
export * from './chat';
export * from './observability';
export * from './skills';
export * from './memory';
export * from './userConfig';
export * from './collab';
export * from './dashboard';
export * from './behaviorRuleCatalog';
export * from './lifecycleHooks';
export * from './evals';
export * from './improve';
export * from './methods';
export * from './drafts';
