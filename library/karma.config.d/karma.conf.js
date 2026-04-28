// Harden headless Chrome for wasmJs tests on CI.
// Kotlin/Gradle auto-discovers files in karma.config.d/ and merges them
// into the generated Karma configuration.
config.set({
    browserDisconnectTimeout: 30000,
    browserNoActivityTimeout: 60000,
    browsers: ["ChromeHeadlessNoSandbox"],
    customLaunchers: {
        ChromeHeadlessNoSandbox: {
            base: "ChromeHeadless",
            flags: ["--no-sandbox", "--disable-gpu", "--max-old-space-size=4096"],
        },
    },
});
