import { expect, test } from '@playwright/test';

interface CapturedCancellation {
  sessionId: string;
  requestId: string | null;
}

test('cancel retries keep one requestId and session switches isolate it', async ({ page }) => {
  const cancellations: CapturedCancellation[] = [];
  const pageErrors: string[] = [];
  const now = new Date().toISOString();

  page.on('pageerror', (error) => pageErrors.push(error.message));

  await page.addInitScript(() => {
    window.localStorage.setItem('sf_token', 'browser-test-token');
  });
  await page.route(/^http:\/\/localhost:3000\/api\//, async (route) => {
    const url = new URL(route.request().url());
    const { pathname } = url;

    if (pathname === '/api/agents') {
      await route.fulfill({
        json: [{ id: 1, name: 'Browser Agent', agentType: 'user' }],
      });
      return;
    }
    if (pathname === '/api/chat/sessions') {
      await route.fulfill({
        json: [
          { id: 's1', agentId: 1, title: 'First session', updatedAt: now },
          { id: 's2', agentId: 1, title: 'Second session', updatedAt: now },
        ],
      });
      return;
    }
    if (/^\/api\/chat\/sessions\/s[12]\/messages$/.test(pathname)) {
      await route.fulfill({ json: [] });
      return;
    }
    if (/^\/api\/chat\/sessions\/s[12]\/tasks$/.test(pathname)) {
      const sessionId = pathname.includes('/s1/') ? 's1' : 's2';
      await route.fulfill({
        json: {
          sessionId,
          summary: {
            total: 0,
            pending: 0,
            in_progress: 0,
            completed: 0,
            deleted: 0,
            blocked: 0,
          },
          tasks: [],
          generatedAt: now,
        },
      });
      return;
    }
    if (/^\/api\/chat\/sessions\/s[12]$/.test(pathname)) {
      const sessionId = pathname.endsWith('/s1') ? 's1' : 's2';
      await route.fulfill({
        json: {
          id: sessionId,
          agentId: 1,
          runtimeStatus: 'running',
          runtimeStep: 'Calling tool',
          executionMode: 'ask',
        },
      });
      return;
    }
    if (/^\/api\/chat\/s[12]\/cancel$/.test(pathname)) {
      cancellations.push({
        sessionId: pathname.includes('/s1/') ? 's1' : 's2',
        requestId: url.searchParams.get('requestId'),
      });
      if (pathname.includes('/s1/')) {
        await route.fulfill({
          status: 503,
          json: { error: 'retry with the same requestId' },
        });
      } else {
        await route.fulfill({ status: 200, json: { status: 'cancelling' } });
      }
      return;
    }
    if (pathname === '/api/llm/models') {
      await route.fulfill({ json: [] });
      return;
    }

    await route.fulfill({ json: [] });
  });

  await page.goto('/chat/s1?agent=1');
  const cancelButton = page.getByRole('button', { name: 'Cancel' });
  await expect(page.locator('body')).toContainText('Agent is running');
  expect(pageErrors).toEqual([]);
  await expect(cancelButton).toBeVisible();

  await cancelButton.click();
  await expect(page.getByText('Cancel temporarily unavailable; retry to continue')).toBeVisible();
  await expect(cancelButton).toBeEnabled();
  await cancelButton.click();
  await expect.poll(() => cancellations.length).toBe(2);
  expect(cancellations[0].requestId).toMatch(
    /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
  );
  expect(cancellations[1]).toEqual(cancellations[0]);

  await page.getByRole('button', { name: /Second session/ }).click();
  await expect(page.getByRole('heading', { name: 'Second session' })).toBeVisible();
  await expect(cancelButton).toBeVisible();
  await cancelButton.click();

  await expect.poll(() => cancellations.length).toBe(3);
  expect(cancellations[2].sessionId).toBe('s2');
  expect(cancellations[2].requestId).not.toBe(cancellations[0].requestId);
  await expect(page.getByRole('button', { name: 'Cancel' })).toBeEnabled();
});
