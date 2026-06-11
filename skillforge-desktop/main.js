'use strict';

/**
 * SkillForge desktop launcher (Electron main process).
 *
 * Lifecycle (DESKTOP-MACOS-PACKAGE D4/D5):
 *   single-instance lock → probe desktop PG port 15433 → pick a free HTTP port →
 *   spawn bundled JRE running the fat jar with --server.port / --skillforge.spa.root →
 *   show loading window while health-polling http://127.0.0.1:N/ → loadURL on ready.
 *   On quit: SIGTERM the child, wait ≤20s for embedded PG to release epg-lock, else SIGKILL.
 */

const { app, BrowserWindow, dialog } = require('electron');
const { spawn, execFile } = require('child_process');
const net = require('net');
const fs = require('fs');
const path = require('path');
const os = require('os');
const http = require('http');

// ── Constants ─────────────────────────────────────────────────────────────
// Desktop uses an INDEPENDENT embedded PG (port 15433 + ~/.skillforge-desktop) so it
// never contends with a dev server (which uses 15432 + ~/.skillforge). On first run
// the desktop DB is seeded once from the dev data dir, then the two are fully separate.
const DESKTOP_PG_PORT = 15433;
const HEALTH_TIMEOUT_MS = 120_000;
const HEALTH_INTERVAL_MS = 1_000;
const READY_MARKER = '<div id="root">';   // SPA shell marker — proves it's OUR server, not a squatter
// Server graceful shutdown (close PG + full pgdata backup copy + scheduler drain)
// measured at ~30-45s+ (Hikari drain alone seen at 43s), so 60s before falling back
// to SIGKILL. Too short aborts the @PreDestroy backup mid-flight; the residual-PG
// sweep below is the real 0-orphan guarantee regardless of this value.
const SHUTDOWN_GRACE_MS = 60_000;
const SHUTDOWN_POLL_MS = 500;

// After the java child exits, PostgresBackupService's @PreDestroy may have spun up a
// transient backup PG (zonky) under <desktop>/pgrun that orphans (PPID→1) and keeps
// holding port 15433 → next launch falsely reports "port in use". We sweep it with
// staged signals. Only ever target a postgres whose command line contains the desktop
// zonky run dir — never a random :15433 holder.
//
// PostgreSQL postmaster signal semantics (why NOT SIGTERM):
//   SIGTERM  = smart shutdown   → waits for ALL clients to disconnect; with a lingering
//                                 connection it hangs forever (verified live: still up
//                                 after 4s) → useless for a sweep.
//   SIGINT   = fast shutdown    → disconnects clients, rolls back open txns, clean exit
//                                 (≈ pg_ctl stop -m fast). Verified live: exits at once.
//   SIGQUIT  = immediate shutdown → abrupt but WAL crash-safe (recovery on next boot).
//   SIGKILL  = last resort        → also WAL crash-safe; no data loss.
const PG_RUN_DIR_MARKER = path.join(os.homedir(), '.skillforge-desktop', 'pgrun');
const PG_SWEEP_POLL_MS = 500;
const PG_SIGINT_WAIT_MS = 6_000;   // fast shutdown grace
const PG_SIGQUIT_WAIT_MS = 3_000;  // immediate shutdown grace
const PG_SIGKILL_WAIT_MS = 1_000;  // confirm after last resort

const APP_SUPPORT_DIR = path.join(os.homedir(), 'Library', 'Application Support', 'SkillForge');
const LOG_DIR = path.join(os.homedir(), 'Library', 'Logs', 'SkillForge');
const LOG_FILE = path.join(LOG_DIR, 'server.log');

// Desktop's isolated data root — separate from dev's ~/.skillforge so the desktop app
// and a dev server never share PG data, locks, home, or skills.
const SF_DOT_DIR = path.join(os.homedir(), '.skillforge-desktop');

// SkillForgeHomeResolver anchor (env-var path). home=<SF_DOT_DIR/home> →
// runtimeRoot=<home>/data/skills, systemSkillsDir=<home>/../system-skills
// (= <SF_DOT_DIR>/system-skills). All siblings under ~/.skillforge-desktop together
// with the embedded PG data dir (pgdata) / run dir (pgrun) / backups.
const SF_HOME = path.join(SF_DOT_DIR, 'home');
const SF_SYSTEM_SKILLS_DEST = path.join(SF_DOT_DIR, 'system-skills');

// Embedded-PG: desktop overrides EmbeddedPostgresConfig's defaults via spawn args.
//   base-dir → ~/.skillforge-desktop (pgdata/pgrun/backups live here)
//   seed-from → the DEV data dir (~/.skillforge/pgdata): copied once on first run.
const PG_BASE_DIR = SF_DOT_DIR;
const PG_SEED_FROM = path.join(os.homedir(), '.skillforge', 'pgdata');

// ── State ─────────────────────────────────────────────────────────────────
let mainWindow = null;
let serverChild = null;
let serverExited = false;
let httpPort = 0;
let shuttingDown = false;
let teardownStarted = false;   // one-shot guard: fatal() can be reached from several paths

// ── Path resolution (packaged vs dev) ───────────────────────────────────────
function resolvePaths() {
  if (app.isPackaged) {
    const res = process.resourcesPath; // .app/Contents/Resources
    // build-dist.sh emits a FLAT jlink image, so java lives at jre/bin/java.
    return {
      javaBin: path.join(res, 'jre', 'bin', 'java'),
      jar: path.join(res, 'server', 'skillforge-server.jar'),
      webRoot: path.join(res, 'web'),
      systemSkillsSrc: path.join(res, 'system-skills'),
    };
  }
  // Dev mode: run against repo build outputs (run `mvn package` + `npm run build` first).
  const repoRoot = path.resolve(__dirname, '..');
  const targetDir = path.join(repoRoot, 'skillforge-server', 'target');
  let jar = path.join(targetDir, 'skillforge-server.jar');
  try {
    const candidates = fs.readdirSync(targetDir)
      .filter((f) => f.startsWith('skillforge-server') && f.endsWith('.jar') && !f.endsWith('.original'));
    if (candidates.length > 0) {
      jar = path.join(targetDir, candidates[0]);
    }
  } catch (_) {
    // fall through to default jar path; spawn will surface a clear error
  }
  return {
    // dev: rely on system `java` (jre staging not built in dev).
    javaBin: process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', 'java') : 'java',
    jar,
    webRoot: path.join(repoRoot, 'skillforge-dashboard', 'dist'),
    systemSkillsSrc: path.join(repoRoot, 'system-skills'),
  };
}

// ── Helpers ─────────────────────────────────────────────────────────────────
function ensureDir(dir) {
  fs.mkdirSync(dir, { recursive: true });
}

/** Resolve true if `port` already has a listener (i.e. occupied). */
function isPortOccupied(port) {
  return new Promise((resolve) => {
    const socket = new net.Socket();
    const done = (occupied) => {
      socket.destroy();
      resolve(occupied);
    };
    socket.setTimeout(800);
    socket.once('connect', () => done(true));
    socket.once('timeout', () => done(false));
    socket.once('error', () => done(false));
    socket.connect(port, '127.0.0.1');
  });
}

/** Ask the OS for a free TCP port by binding to 0. */
function findFreePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.unref();
    srv.on('error', reject);
    srv.listen(0, '127.0.0.1', () => {
      const port = srv.address().port;
      // Known TOCTOU window: between this close() and the JVM's bind(), another
      // process could grab the port. On loopback this is very unlikely; if it
      // happens the JVM logs a BindException to server.log and health-poll fails
      // (fatal after timeout). A bind-failure fast-exit + start retry is a
      // follow-up (see /tmp/nits-followup-desktop-macos-package.md).
      srv.close(() => resolve(port));
    });
  });
}

/** Rotate server.log → server.log.1 on each launch (keep one previous run). */
function rotateLog() {
  ensureDir(LOG_DIR);
  try {
    if (fs.existsSync(LOG_FILE)) {
      fs.renameSync(LOG_FILE, LOG_FILE + '.1');
    }
  } catch (_) {
    // non-fatal: a locked/old log shouldn't block startup
  }
}

function pollHealth(port, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  return new Promise((resolve) => {
    const tick = () => {
      if (serverExited) {
        resolve(false);
        return;
      }
      const req = http.get(
        { host: '127.0.0.1', port, path: '/', timeout: 2000 },
        (res) => {
          // Only accept a 2xx that actually serves our SPA shell. A bare status
          // check would treat a port squatter's response as "ready" (W-2).
          if (!res.statusCode || res.statusCode < 200 || res.statusCode >= 300) {
            res.resume();
            retry();
            return;
          }
          let body = '';
          let matched = false;
          res.setEncoding('utf8');
          res.on('data', (chunk) => {
            if (matched) return;
            body += chunk;
            if (body.includes(READY_MARKER)) {
              matched = true;
              res.destroy();        // marker found — stop reading
              resolve(true);
            } else if (body.length > 65536) {
              body = body.slice(-32);   // keep a small overlap so a split marker still matches
            }
          });
          res.on('end', () => {
            if (!matched) retry();
          });
          res.on('error', () => {
            if (!matched) retry();
          });
        },
      );
      req.on('error', retry);
      req.on('timeout', () => {
        req.destroy();
        retry();
      });
    };
    const retry = () => {
      if (Date.now() >= deadline) {
        resolve(false);
      } else {
        setTimeout(tick, HEALTH_INTERVAL_MS);
      }
    };
    tick();
  });
}

function fatal(title, message) {
  if (teardownStarted) return;   // a teardown is already in flight — don't stack dialogs
  teardownStarted = true;
  // Show synchronously, then tear down.
  try {
    dialog.showErrorBox(title, message);
  } catch (_) {
    // ignore
  }
  shutdownAndQuit();
}

// ── Server spawn ─────────────────────────────────────────────────────────────
function startServer() {
  rotateLog();
  ensureDir(APP_SUPPORT_DIR);

  const paths = resolvePaths();
  const javaBin = paths.javaBin;

  if (app.isPackaged && !fs.existsSync(paths.jar)) {
    fatal('SkillForge', 'Bundled server jar not found:\n' + paths.jar);
    return;
  }

  // SkillForgeHomeResolver only reads the SKILLFORGE_HOME env var (not a system
  // property) and otherwise walks up from cwd for a repo root — which never exists
  // for the packaged app (cwd is App Support). So we MUST pass SKILLFORGE_HOME and
  // provision its anchor dirs. runtimeRoot (<home>/data/skills) is created by the
  // resolver; system-skills (<home>/../system-skills) must exist before boot.
  ensureDir(path.join(SF_HOME, 'data', 'skills'));
  try {
    // Overwrite-sync so a newer app version ships updated system-skills.
    fs.cpSync(paths.systemSkillsSrc, SF_SYSTEM_SKILLS_DEST, { recursive: true, force: true });
  } catch (err) {
    fatal('SkillForge', 'Failed to provision system-skills from\n' + paths.systemSkillsSrc + '\n→ ' + SF_SYSTEM_SKILLS_DEST + '\n' + err.message);
    return;
  }

  const args = [
    '-Duser.timezone=UTC',
    '-jar',
    paths.jar,
    '--server.port=' + httpPort,
    '--skillforge.spa.root=' + paths.webRoot,
    // Independent embedded PG (D4): own port + data dir, seeded once from dev data.
    '--skillforge.embedded-postgres.port=' + DESKTOP_PG_PORT,
    '--skillforge.embedded-postgres.base-dir=' + PG_BASE_DIR,
    '--skillforge.embedded-postgres.seed-from=' + PG_SEED_FROM,
  ];

  const logFd = fs.openSync(LOG_FILE, 'a');
  serverChild = spawn(javaBin, args, {
    cwd: APP_SUPPORT_DIR,
    env: { ...process.env, SKILLFORGE_HOME: SF_HOME },
    stdio: ['ignore', logFd, logFd],
  });

  serverChild.on('exit', (code, signal) => {
    serverExited = true;
    if (!shuttingDown) {
      // Unexpected death before/after ready.
      fatal('SkillForge', 'Server process exited unexpectedly (code=' + code + ', signal=' + signal + ').\nSee ' + LOG_FILE);
    }
  });
  serverChild.on('error', (err) => {
    serverExited = true;
    fatal('SkillForge', 'Failed to launch server runtime (' + javaBin + '):\n' + err.message);
  });
}

// ── Shutdown ─────────────────────────────────────────────────────────────────
let quitRequested = false;
function shutdownAndQuit() {
  if (quitRequested) return;     // idempotent: reachable from window-all-closed + before-quit
  quitRequested = true;
  stopServer().finally(() => {
    app.exit(0);
  });
}

function stopServer() {
  return new Promise((resolve) => {
    let settled = false;
    const finish = () => {
      if (settled) return;   // W-4: poll timer and exit event can both reach here
      settled = true;
      // The java child is now gone (graceful exit or SIGKILL fallback). ONLY now is
      // it safe to sweep a residual backup PG — never while the JVM (and its
      // @PreDestroy backup) is still running. sweepResidualPg has its own timeout so
      // it can't hang app exit.
      sweepResidualPg().finally(resolve);
    };

    if (!serverChild || serverExited) {
      finish();
      return;
    }
    shuttingDown = true;
    try {
      serverChild.kill('SIGTERM');
    } catch (_) {
      finish();
      return;
    }

    const deadline = Date.now() + SHUTDOWN_GRACE_MS;
    const poll = () => {
      if (settled) return;
      if (serverExited) {
        finish();
        return;
      }
      if (Date.now() >= deadline) {
        try {
          serverChild.kill('SIGKILL');
        } catch (_) {
          // ignore
        }
        finish();
        return;
      }
      setTimeout(poll, SHUTDOWN_POLL_MS);
    };
    serverChild.once('exit', finish);
    poll();
  });
}

// ── Residual backup-PG sweep (runs only after the java child has exited) ───────

/** Run a system binary, resolving its stdout best-effort (non-zero exit → ""). */
function execFileText(cmd, fileArgs, timeoutMs) {
  return new Promise((resolve) => {
    execFile(cmd, fileArgs, { timeout: timeoutMs, windowsHide: true }, (_err, stdout) => {
      resolve((stdout || '').toString());
    });
  });
}

/**
 * PIDs LISTENING on the embedded-PG port whose command line is one of OUR zonky
 * postgres processes (command contains the ~/.skillforge/pgrun run dir). The dual
 * check (port + pgrun marker) ensures we never signal an unrelated :15433 holder.
 * Absolute tool paths because a GUI-launched .app has a minimal PATH.
 */
async function findOurResidualPgPids() {
  const out = await execFileText(
    '/usr/sbin/lsof',
    ['-nP', '-iTCP:' + DESKTOP_PG_PORT, '-sTCP:LISTEN', '-t'],
    3000,
  );
  const pids = out.split('\n').map((s) => s.trim()).filter((s) => /^\d+$/.test(s));
  const ours = [];
  for (const pid of pids) {
    const cmd = await execFileText('/bin/ps', ['-o', 'command=', '-p', pid], 3000);
    if (cmd.includes(PG_RUN_DIR_MARKER)) {
      ours.push(Number(pid));
    }
  }
  return ours;
}

/** Signal `pids` with `signal`, ignoring already-gone / not-permitted errors. */
function signalPids(pids, signal) {
  for (const pid of pids) {
    try {
      process.kill(pid, signal);
    } catch (_) {
      // already gone / not permitted — ignore
    }
  }
}

/** Poll until no residual PG remains or `waitMs` elapses; resolves true if gone. */
function waitResidualGone(waitMs) {
  const deadline = Date.now() + waitMs;
  return new Promise((resolve) => {
    const tick = async () => {
      let pids;
      try {
        pids = await findOurResidualPgPids();
      } catch (_) {
        resolve(true); // can't probe → stop escalating (treat as done, don't hang)
        return;
      }
      if (pids.length === 0) {
        resolve(true);
        return;
      }
      if (Date.now() >= deadline) {
        resolve(false);
        return;
      }
      setTimeout(tick, PG_SWEEP_POLL_MS);
    };
    tick();
  });
}

/**
 * Sweep a residual backup PG left holding the port after the server exited, using
 * staged signals (all WAL crash-safe — never delete/move pgdata):
 *   SIGINT (fast shutdown) → SIGQUIT (immediate) → SIGKILL (last resort).
 * SIGTERM is deliberately NOT used (smart shutdown hangs on open connections).
 * Always resolves (bounded per-stage waits) so it can't hang app exit.
 */
async function sweepResidualPg() {
  const stages = [
    { signal: 'SIGINT', waitMs: PG_SIGINT_WAIT_MS },
    { signal: 'SIGQUIT', waitMs: PG_SIGQUIT_WAIT_MS },
    { signal: 'SIGKILL', waitMs: PG_SIGKILL_WAIT_MS },
  ];
  for (const stage of stages) {
    let pids;
    try {
      pids = await findOurResidualPgPids();
    } catch (_) {
      return; // can't probe — bail (don't hang quit)
    }
    if (pids.length === 0) {
      return; // nothing residual (or a prior stage finished it off)
    }
    signalPids(pids, stage.signal);
    if (await waitResidualGone(stage.waitMs)) {
      return; // gone — done
    }
  }
  // After SIGKILL it should be gone; if probing still shows it, give up rather than
  // hang quit. PG remains WAL crash-safe for the next launch.
}

// ── Windows ──────────────────────────────────────────────────────────────────
function createLoadingWindow() {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 860,
    show: true,
    title: 'SkillForge',
    backgroundColor: '#0f1115',
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
    },
  });
  mainWindow.loadFile(path.join(__dirname, 'loading.html'));
  mainWindow.on('closed', () => {
    mainWindow = null;
  });
}

// ── Boot sequence ────────────────────────────────────────────────────────────
async function boot() {
  if (await isPortOccupied(DESKTOP_PG_PORT)) {
    fatal(
      'SkillForge',
      'Port ' + DESKTOP_PG_PORT + ' is already in use.\n\n' +
        'Another SkillForge desktop instance appears to be running (it uses its own ' +
        'database on port ' + DESKTOP_PG_PORT + '). Quit it and try again.',
    );
    return;
  }

  try {
    httpPort = await findFreePort();
  } catch (err) {
    fatal('SkillForge', 'Could not allocate a local port:\n' + err.message);
    return;
  }

  createLoadingWindow();
  startServer();

  const ready = await pollHealth(httpPort, HEALTH_TIMEOUT_MS);
  if (!ready) {
    fatal(
      'SkillForge',
      'Server did not become ready within ' + Math.round(HEALTH_TIMEOUT_MS / 1000) +
        's.\nSee ' + LOG_FILE,
    );
    return;
  }

  if (mainWindow) {
    mainWindow.loadURL('http://127.0.0.1:' + httpPort + '/');
  }
}

// ── App wiring ───────────────────────────────────────────────────────────────
const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.focus();
    }
  });

  app.whenReady().then(boot);

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0 && !shuttingDown) {
      createLoadingWindow();
      if (httpPort) {
        mainWindow.loadURL('http://127.0.0.1:' + httpPort + '/');
      }
    }
  });

  app.on('window-all-closed', () => {
    shutdownAndQuit();
  });

  app.on('before-quit', (event) => {
    // BLOCKER-1: as long as the server is alive we MUST block the quit so the
    // child gets a graceful SIGTERM (embedded PG releases epg-lock). Pressing
    // Cmd+Q during the ≤20s shutdown grace window must not abandon the child.
    if (serverChild && !serverExited) {
      event.preventDefault();
      if (!quitRequested) {
        shutdownAndQuit();
      }
    }
  });
}
