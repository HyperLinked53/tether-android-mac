#!/usr/bin/env node
/**
 * Starts the Metro bundler directly via Metro's JS API.
 *
 * Why this exists (this machine is unusual — the project lives on a slow external
 * APFS volume mounted `noowners,nosuid` at /Volumes/EXT-Home):
 *
 *  1. `@react-native-community/cli` (`react-native start`) hangs during
 *     loadConfigAsync on the external-volume home, so we drive Metro directly.
 *  2. Metro's file crawl is unusably slow without watchman here (~20k sequential
 *     lstat calls over the slow volume = 10+ min); the system `find` fallback can
 *     deadlock. So watchman is required.
 *  3. watchman's *default* socket lives under $HOME (the external volume), and the
 *     `noowners` mount makes that unix socket unreliable ("No such file or
 *     directory"). So we run our own watchman daemon with its socket on /tmp
 *     (internal volume) and point Metro's fb-watchman client at it via
 *     WATCHMAN_SOCK (which fb-watchman honors directly).
 *
 * Usage: node start-metro.js [--port 8081] [--reset-cache]
 */
'use strict';

const path = require('path');
const fs = require('fs');
const {spawn, spawnSync} = require('child_process');

/**
 * Redirects `react-native` (and `react-native/…`) imports to an out-of-tree
 * platform's package (here: macos → react-native-macos). Inlined from
 * @react-native/community-cli-plugin (its package `exports` blocks deep import).
 */
function reactNativePlatformResolver(platformImplementations, customResolver) {
  return (context, moduleName, platform) => {
    let modifiedModuleName = moduleName;
    if (platform != null && platformImplementations[platform]) {
      if (moduleName === 'react-native') {
        modifiedModuleName = platformImplementations[platform];
      } else if (moduleName.startsWith('react-native/')) {
        modifiedModuleName =
          `${platformImplementations[platform]}/${modifiedModuleName.slice('react-native/'.length)}`;
      }
    }
    if (customResolver) {
      return customResolver(context, modifiedModuleName, platform);
    }
    return context.resolveRequest(context, modifiedModuleName, platform);
  };
}

// --- watchman on a fast-volume socket --------------------------------------
const WM_SOCK = '/tmp/conduit-watchman.sock';
const WM_LOG = '/tmp/conduit-watchman.log';
const WM_PID = '/tmp/conduit-watchman.pid';
const WM_STATE = '/tmp/conduit-watchman.state';

function findWatchmanBinary() {
  for (const p of ['/opt/homebrew/bin/watchman', '/usr/local/bin/watchman', 'watchman']) {
    const r = spawnSync(p, ['--version'], {encoding: 'utf8'});
    if (!r.error && r.status === 0) return p;
  }
  return null;
}

async function ensureWatchman() {
  const bin = findWatchmanBinary();
  if (!bin) {
    console.warn('[metro] watchman not found — Metro will fall back to the (very slow) JS crawler.');
    return false;
  }
  // Point every client (including Metro's fb-watchman) at our /tmp socket.
  process.env.WATCHMAN_SOCK = WM_SOCK;

  // Already up? (socket exists and daemon responds)
  if (fs.existsSync(WM_SOCK)) {
    const r = spawnSync(bin, ['--no-spawn', 'get-sockname'], {env: process.env, encoding: 'utf8'});
    if (r.status === 0) {
      console.log('[metro] watchman daemon already running on', WM_SOCK);
      return;
    }
  }

  console.log('[metro] starting watchman daemon on', WM_SOCK);
  for (const f of [WM_SOCK, WM_PID]) { try { fs.unlinkSync(f); } catch {} }
  const child = spawn(
    bin,
    [`--unix-listener-path=${WM_SOCK}`, `--logfile=${WM_LOG}`, `--pidfile=${WM_PID}`,
     `--statefile=${WM_STATE}`, '--foreground', '--log-level=1'],
    {detached: true, stdio: 'ignore', env: process.env},
  );
  child.unref();

  // Wait (up to 15s) for the daemon to bind its socket.
  const deadline = Date.now() + 15000;
  while (Date.now() < deadline) {
    if (fs.existsSync(WM_SOCK)) {
      const r = spawnSync(bin, ['--no-spawn', 'get-sockname'], {env: process.env, encoding: 'utf8'});
      if (r.status === 0) { console.log('[metro] watchman daemon ready.'); return true; }
    }
    await new Promise(res => setTimeout(res, 300));
  }
  console.warn('[metro] watchman did not come up in time — falling back to node file watcher.');
  delete process.env.WATCHMAN_SOCK;
  return false;
}

// --- Metro -----------------------------------------------------------------
const PORT = (() => {
  const i = process.argv.indexOf('--port');
  return i >= 0 ? parseInt(process.argv[i + 1], 10) : 8081;
})();
const resetCache = process.argv.includes('--reset-cache');
const projectRoot = __dirname;

async function main() {
  const watchmanReady = await ensureWatchman();

  // Require Metro only after WATCHMAN_SOCK is set so the client picks it up.
  const {loadConfig, mergeConfig} = require('metro-config');
  const Metro = require('metro');

  console.log('[metro] loading config…');
  const baseConfig = await loadConfig({cwd: projectRoot});

  // Replicate what @react-native-community/cli's loadMetroConfig does for
  // out-of-tree platforms. `macos` is served by the `react-native-macos`
  // package: requests for `react-native` / `react-native/…` must be redirected
  // there (vanilla react-native has no Image.macos.js etc.), and the macOS
  // InitializeCore must run before the entrypoint. Without this the bundle fails
  // with "Unable to resolve module ./Libraries/Image/Image".
  const rnPath = path.dirname(require.resolve('react-native/package.json'));
  const override = {
    resolver: {
      platforms: ['macos', 'ios', 'android', 'native'],
      resolveRequest: reactNativePlatformResolver(
        {macos: 'react-native-macos'},
        baseConfig.resolver && baseConfig.resolver.resolveRequest,
      ),
      // When watchman isn't available, disable it here (config.resolver.useWatchman
      // is where Metro's createFileMap actually reads this flag).
      ...(watchmanReady ? {} : {useWatchman: false}),
    },
    serializer: {
      getModulesRunBeforeMainModule: () => [
        require.resolve(path.join(rnPath, 'Libraries/Core/InitializeCore'), {paths: [projectRoot]}),
        require.resolve('react-native-macos/Libraries/Core/InitializeCore', {paths: [projectRoot]}),
      ],
    },
  };

  const config = mergeConfig(baseConfig, override);
  console.log(`[metro] starting server on :${PORT}…`);

  // The React Native app probes http://localhost:PORT/status and only treats the
  // packager as running when it returns "packager-status:running". Metro core
  // doesn't serve that — it (plus /symbolicate, the reload + HMR websockets) comes
  // from @react-native-community/cli-server-api + @react-native/dev-middleware,
  // which `react-native start` normally wires in. Without it the app's bundle URL
  // resolves to nil → "No bundle URL present". Replicate that wiring here.
  const {createDevServerMiddleware, indexPageMiddleware} =
    require('@react-native-community/cli-server-api');
  const {createDevMiddleware} = require('@react-native/dev-middleware');

  const host = '127.0.0.1';
  const devServerUrl = `http://${host}:${PORT}`;
  const {
    middleware: communityMiddleware,
    websocketEndpoints: communityWebsocketEndpoints,
    eventsSocketEndpoint,
  } = createDevServerMiddleware({host, port: PORT, watchFolders: [projectRoot]});
  const {middleware: devMiddleware, websocketEndpoints: devWebsocketEndpoints} =
    createDevMiddleware({projectRoot, serverBaseUrl: devServerUrl, logger: undefined});

  // Forward Metro reporter events to the events socket so Fast Refresh works.
  let reportEvent;
  const baseReporter = config.reporter;
  config.reporter = {
    update(event) {
      if (baseReporter && baseReporter.update) baseReporter.update(event);
      if (reportEvent) reportEvent(event);
    },
  };

  const httpServer = await Metro.runServer(config, {
    host,
    resetCache,
    unstable_extraMiddleware: [communityMiddleware, indexPageMiddleware, devMiddleware],
    websocketEndpoints: {...communityWebsocketEndpoints, ...devWebsocketEndpoints},
    onReady: () => console.log(`[metro] Metro bundler ready — ${devServerUrl}`),
  });
  reportEvent = eventsSocketEndpoint.reportEvent;
  httpServer.keepAliveTimeout = 30000;

  process.on('SIGINT', () => { httpServer.close(); process.exit(0); });
  process.on('SIGTERM', () => { httpServer.close(); process.exit(0); });
}

main().catch(e => { console.error('[metro] fatal:', e); process.exit(1); });
