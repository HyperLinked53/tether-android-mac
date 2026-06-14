#!/usr/bin/env node
/**
 * Generates a production JS bundle without going through @react-native-community/cli,
 * which hangs on this machine (external APFS volume). Uses the same metro-config loader
 * and platform resolver that start-metro.js uses for the dev server.
 */
'use strict';

const path = require('path');

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
    if (customResolver) return customResolver(context, modifiedModuleName, platform);
    return context.resolveRequest(context, modifiedModuleName, platform);
  };
}

const projectRoot = __dirname;
const OUT = path.join(__dirname, 'macos/conduit-mac-macOS/main.jsbundle');

async function main() {
  const {loadConfig, mergeConfig} = require('metro-config');
  const Metro = require('metro');

  console.log('[bundle] loading config…');
  const baseConfig = await loadConfig({cwd: projectRoot});

  const rnPath = path.dirname(require.resolve('react-native/package.json'));
  const override = {
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
  };

  const config = mergeConfig(baseConfig, override);

  console.log('[bundle] bundling (dev=false, platform=macos)…');
  await Metro.runBuild(config, {
    entry: 'index.js',
    out: OUT,
    platform: 'macos',
    dev: false,
    minify: true,
    sourceMap: false,
  });

  console.log('[bundle] done →', OUT);
}

main().catch(e => { console.error('[bundle] fatal:', e); process.exit(1); });
