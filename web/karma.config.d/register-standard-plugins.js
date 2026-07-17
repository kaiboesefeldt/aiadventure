// Kotlin writes kotlin-web-helpers to build/js/node_modules but Karma itself
// is installed in the generated test package. Give the helper access to that
// package's dependencies before registering it as a Karma plugin.
const path = require('path');
const Module = require('module');
const testNodeModules = path.resolve(__dirname, 'node_modules');
process.env.NODE_PATH = [testNodeModules, process.env.NODE_PATH].filter(Boolean).join(path.delimiter);
Module._initPaths();
const kotlinHelpers = path.resolve(__dirname, '../../node_modules/kotlin-web-helpers/dist');

config.plugins = config.plugins.filter((plugin) => ![
  'kotlin-web-helpers/dist/karma-kotlin-reporter.js',
  'kotlin-web-helpers/dist/karma-webpack-output.js',
].includes(plugin));
config.plugins.push(
  require(path.join(kotlinHelpers, 'karma-kotlin-reporter.js')),
  require(path.join(kotlinHelpers, 'karma-webpack-output.js')),
  require('karma-chrome-launcher'),
  require('karma-mocha'),
  require('karma-sourcemap-loader'),
  require('karma-webpack'),
);
