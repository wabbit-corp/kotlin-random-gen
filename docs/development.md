# Development

## Build

```bash
./dev build kotlin-random-gen
```

The build covers JVM, Android library metadata, iOS simulator, iOS device, and host-native targets.

## Documentation Checks

```bash
./dev verify docs kotlin-random-gen
./gradlew -p kotlin-random-gen dokkaGeneratePublicationHtml
```

Public APIs should document whether they consume bits, reject samples, mutate tape state, or return
continuation state.

## Publication Dry Run

```bash
./dev publish --dry-run kotlin-random-gen
```

Generated Gradle metadata comes from `root.clj`. Update the root project definition and rerun
`./dev setup --local kotlin-random-gen` instead of editing generated Gradle files directly.

## Testing Guidance

Add tests for replay round-trips, minimization behavior, weighted choices, generated functions, and
bit-level codecs when changing generator interpretation.
