#!/usr/bin/env node
/**
 * Produces a production JS bundle for the macOS app without going through
 * the Xcode build phase (which breaks on the space in this project's folder
 * name due to the --config-cmd quoting issue in react-native-xcode.sh).
 *
 * Usage:
 *   node bundle-release.js [--out /path/to/main.jsbundle]
 *
 * Outputs main.jsbundle (and assets/) to --out path, defaulting to
 * ./macos/conduit-mac-macOS/main.jsbundle so xcodebuild can pick it up
 * when built with SKIP_BUNDLING=1.
 */
'use strict';

const path = require('path');
const fs   = require('fs');

// ── helpers ────────────────────────────────────────────────────────────────

function reactNativePlatformResolver(platformImplementations, customResolver) {
  return (context, moduleName, platform) => {
    let mod = moduleName;
    if (platform != null && platformImplementations[platform]) {
      if (moduleName === 'react-native') {
        mod = platformImplementations[platform];
      } else if (moduleName.startsWith('react-native/')) {
        mod = `${platformImplementations[platform]}/${mod.slice('react-native/'.length)}`;
      }
    }
    return customResolver
      ? customResolver(context, mod, platform)
      : context.resolveRequest(context, mod, platform);
  };
}

// ── args ───────────────────────────────────────────────────────────────────

const outArgIdx = process.argv.indexOf('--out');
const bundleOut = outArgIdx >= 0
  ? path.resolve(process.argv[outArgIdx + 1])
  : path.join(__dirname, 'macos', 'conduit-mac-macOS', 'main.jsbundle');

fs.mkdirSync(path.dirname(bundleOut), {recursive: true});

const projectRoot = __dirname;

// ── bundle ─────────────────────────────────────────────────────────────────

async function main() {
  const {loadConfig, mergeConfig} = require('metro-config');
  const Metro = require('metro');

  console.log('[bundle] loading Metro config…');
  const baseConfig = await loadConfig({cwd: projectRoot});

  const rnPath = path.dirname(require.resolve('react-native/package.json'));

  const config = mergeConfig(baseConfig, {
    resetCache: true,
    resolver: {
      platforms: ['macos', 'ios', 'android', 'native'],
      resolveRequest: reactNativePlatformResolver(
        {macos: 'react-native-macos'},
        baseConfig.resolver && baseConfig.resolver.resolveRequest,
      ),
      useWatchman: false,
    },
    serializer: {
      getModulesRunBeforeMainModule: () => [
        require.resolve(path.join(rnPath, 'Libraries/Core/InitializeCore'), {paths: [projectRoot]}),
        require.resolve('react-native-macos/Libraries/Core/InitializeCore', {paths: [projectRoot]}),
      ],
    },
  });

  console.log('[bundle] bundling index.js for macos (dev=false)…');
  await Metro.runBuild(config, {
    entry:     'index.js',
    out:       bundleOut,
    dev:       false,
    minify:    false,   // Hermes compiles; minification done at Hermes level
    platform:  'macos',
    sourceMap: false,
  });

  // Metro appends '.js' to the --out path; rename it back so Xcode finds 'main.jsbundle'.
  const metroOut = bundleOut + '.js';
  if (fs.existsSync(metroOut)) {
    fs.renameSync(metroOut, bundleOut);
  }

  console.log('[bundle] ✓ bundle written to', bundleOut);
}

main().catch(e => { console.error('[bundle] fatal:', e); process.exit(1); });
