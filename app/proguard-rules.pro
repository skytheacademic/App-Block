# App-Block — release IS minified and resource-shrunk (isMinifyEnabled = true, build.gradle.kts).
#
# No keep rules have been needed so far. Everything reflective in the app is reached through
# frameworks that ship their own consumer rules: WorkManager instantiates the Workers, Compose keeps
# its own runtime, and zxing's CaptureActivity is named in the manifest (which R8 treats as a root).
#
# If a release build ever crashes with ClassNotFoundException / NoSuchMethodException where the debug
# build is fine, that is the symptom of a missing keep rule — add it here, don't disable minification.
