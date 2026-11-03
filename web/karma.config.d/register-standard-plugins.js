// Kotlin writes kotlin-web-helpers to build/js/node_modules but Karma itself
// is installed in the generated test package. Give the helper access to that
// package's dependencies before registering it as a Karma plugin.
const path = require('path');
const fs = require('fs');
const Module = require('module');
const testNodeModules = path.resolve(__dirname, 'node_modules');
process.env.NODE_PATH = [testNodeModules, process.env.NODE_PATH].filter(Boolean).join(path.delimiter);
Module._initPaths();
const kotlinHelperPaths = [
  path.resolve(__dirname, '../../node_modules/kotlin-web-helpers/dist'),
  path.resolve(testNodeModules, 'kotlin-web-helpers/dist'),
];
const kotlinHelpers = kotlinHelperPaths.find(fs.existsSync);
if (!kotlinHelpers) {
  throw new Error('Cannot find kotlin-web-helpers in: ' + kotlinHelperPaths.join(', '));
}

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

const appStylesheetPath = path.resolve(__dirname, '../../../../web/src/jsMain/resources/app.css');
const lighthouseExamplePaths = {
  '/examples/nebelhafen-lighthouse.md': path.resolve(__dirname, '../../../../examples/nebelhafen-lighthouse.md'),
  '/fixtures/signal-test-game.md': path.resolve(__dirname, '../../../../web/src/jsTest/resources/signal-test-game.md'),
};
function createLighthouseExampleMiddleware() {
  return (request, response, next) => {
    if (request.url.split('?')[0] == '/app.css') {
      response.setHeader('Content-Type', 'text/css');
      response.end(fs.readFileSync(appStylesheetPath));
      return;
    }
    const examplePath = lighthouseExamplePaths[request.url.split('?')[0]];
    if (!examplePath) return next();
    response.setHeader('Content-Type', 'text/markdown; charset=utf-8');
    response.end(fs.readFileSync(examplePath));
  };
}
createLighthouseExampleMiddleware.$inject = [];
config.plugins.push({
  'middleware:lighthouse-example': ['factory', createLighthouseExampleMiddleware],
});
config.set({ beforeMiddleware: ['lighthouse-example'] });
