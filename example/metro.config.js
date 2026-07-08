// Metro config so the example can import the module directly from the repo root
// (../src) without publishing to npm first.
const path = require('path');
const { getDefaultConfig } = require('expo/metro-config');

const projectRoot = __dirname;
const moduleRoot = path.resolve(projectRoot, '..');

const config = getDefaultConfig(projectRoot);

// Watch the parent module source for live changes.
config.watchFolders = [moduleRoot];

// Resolve React / React Native from the example's own node_modules to avoid
// duplicate-copy errors, but let the module resolve from the root.
config.resolver.nodeModulesPaths = [
  path.resolve(projectRoot, 'node_modules'),
  path.resolve(moduleRoot, 'node_modules'),
];
config.resolver.disableHierarchicalLookup = true;

module.exports = config;
