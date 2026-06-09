const {getDefaultConfig, mergeConfig} = require('@react-native/metro-config');

/** @type {import('@react-native/metro-config').MetroConfig} */
// NOTE: this project lives on a slow external APFS volume (/Volumes/EXT-Home).
// Metro's in-process readdir crawler does ~20k sequential lstat calls and takes
// 10+ min here; the system `find` crawler can deadlock (its stderr pipe isn't
// drained). watchman (brew install watchman) avoids both — it crawls natively
// and caches, so it must be installed for Metro to start in reasonable time.
const config = {};

module.exports = mergeConfig(getDefaultConfig(__dirname), config);
