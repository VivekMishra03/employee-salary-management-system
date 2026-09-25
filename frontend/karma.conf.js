// Karma configuration — see https://karma-runner.github.io/6.4/config/configuration-file.html
//
// Angular 21 no longer scaffolds Karma (its default is the Vitest/jsdom unit-test builder), but the
// `@angular-devkit/build-angular:karma` builder is still supported and declares karma ^6.3.0 as an
// optional peer. Karma is a deliberate project choice recorded in docs/adr/0004.
//
// Karma drives a real browser, unlike jsdom. That is the point: component tests execute against a
// genuine DOM, real layout and real CSS. The cost is that a browser must be available, so CI
// installs one explicitly.

module.exports = function (config) {
  config.set({
    basePath: '',
    frameworks: ['jasmine', '@angular-devkit/build-angular'],
    plugins: [
      require('karma-jasmine'),
      require('karma-chrome-launcher'),
      require('karma-jasmine-html-reporter'),
      require('karma-coverage'),
      require('@angular-devkit/build-angular/plugins/karma'),
    ],
    client: {
      jasmine: {
        // NFR-3: tests must pass in any order, so run them in a random order to surface any
        // order dependency immediately rather than months later on a CI machine.
        random: true,
      },
      clearContext: false, // leave the Jasmine HTML reporter visible in the browser
    },
    jasmineHtmlReporter: { suppressAll: true },
    coverageReporter: {
      dir: require('path').join(__dirname, './coverage/frontend'),
      subdir: '.',
      reporters: [{ type: 'html' }, { type: 'text-summary' }, { type: 'lcovonly' }],
    },
    reporters: ['progress', 'kjhtml'],
    browsers: ['Chrome'],
    customLaunchers: {
      // Used by CI, where Chrome runs as root in a container and has no usable sandbox.
      ChromeHeadlessCI: {
        base: 'ChromeHeadless',
        // A fixed 1280px window: the app switches to its phone layout below 768px, and the shell specs must not depend on
        // Chrome's default window size (800px), which sits just above that line.
        flags: ['--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage', '--window-size=1280,900'],
      },
    },
    restartOnFileChange: true,
  });
};
