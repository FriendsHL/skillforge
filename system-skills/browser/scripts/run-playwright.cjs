#!/usr/bin/env node

'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');

const taskArgument = process.argv[2];
if (!taskArgument) {
  console.error('Usage: run-playwright.cjs <absolute-task-file.cjs>');
  process.exit(2);
}

const repoRoot = path.resolve(__dirname, '..', '..', '..');
const taskPath = path.resolve(taskArgument);
if (!fs.existsSync(taskPath) || !fs.statSync(taskPath).isFile()) {
  console.error('Playwright task file not found');
  process.exit(2);
}
const actualTaskPath = fs.realpathSync(taskPath);
const relativeTaskPath = path.relative(fs.realpathSync(repoRoot), actualTaskPath);
if (relativeTaskPath.startsWith('..') || path.isAbsolute(relativeTaskPath)) {
  console.error('Playwright task file must be inside the SkillForge workspace');
  process.exit(2);
}

const dashboardPackage = path.join(repoRoot, 'skillforge-dashboard', 'package.json');
const dashboardRequire = createRequire(dashboardPackage);

async function main() {
  const playwright = dashboardRequire('playwright');
  const task = require(actualTaskPath);
  if (typeof task !== 'function') {
    throw new TypeError('Playwright task must export one async function');
  }
  await task(playwright, { repoRoot });
}

main().catch(error => {
  const message = error instanceof Error ? error.message : String(error);
  console.error(message);
  process.exitCode = 1;
});
