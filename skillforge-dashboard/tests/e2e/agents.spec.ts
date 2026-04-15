import { test, expect } from '@playwright/test';

test('agents page loads and shows Create Agent button', async ({ page }) => {
  await page.goto('/agents');
  await expect(page.getByRole('button', { name: 'Create Agent' })).toBeVisible({ timeout: 10000 });
});

test('create agent form shows validation errors on empty submit', async ({ page }) => {
  await page.goto('/agents');
  await page.getByRole('button', { name: 'Create Agent' }).click();
  // Wait for modal
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.getByRole('button', { name: 'OK' }).click();
  await expect(page.getByText('Please enter agent name')).toBeVisible();
  await expect(page.getByText('Please select a model')).toBeVisible();
});
