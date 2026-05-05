# Module kotlin-random-gen

Kotlin Multiplatform random data generation combinators built on `kotlin-random`.

The main entry point is `Gen<T>`, a declarative generator tree interpreted by sampling and checking
helpers. The replay engine records bit-level decisions with `TapeSeed` and `RawTapeReader` so failing
inputs can be replayed and minimized.
