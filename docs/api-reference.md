# API Reference

Generated Dokka API docs can be built with:

```bash
./gradlew dokkaGeneratePublicationHtml
```

## Generator DSL

- `Gen<T>`: declarative generator tree.
- `map`, `flatMap`, `filter`, `zip`, `repeat`, `nullable`, and `label`: core combinators.
- `Gen.int`, `Gen.uint`, `Gen.bits`, `Gen.uniformDouble`, `Gen.string`, and primitive properties:
  built-in primitive generators.
- `Gen.oneOf`, `Gen.freq`, `Gen.oneOfGen`, and `Gen.freqGen`: choice combinators.
- `Gen.listOf`, `Gen.unfold`, and `Gen.zip(list)`: sequence combinators.

## Sampling and Checking

- `sampleR`: low-level interpreter returning `RunResult`.
- `sample`: samples once and returns null for filtered or incomplete samples.
- `sampleUnbounded`: retries until a value is produced.
- `foreach`: runs a generator repeatedly.
- `foreachMin`: runs a generator and minimizes the first non-fatal exception.
- `satisfy` and `minimize`: lower-level search and minimization helpers.

## Replay Model

- `TapeSeed`: seed plus bit-flip sequence, with Base58 encoding.
- `RawTapeReader`: deterministic bit reader backed by `TapeSeed`.
- `BitSource`: abstraction used by the interpreter.
- `RawTapeComplexity`: minimization sort key.

## Support Utilities

- `CoGen`: stable input hashing for generated functions.
- `Wheel`: weighted selection table.
- `MutableBitDeque` and `PersistentBitSeq`: bit sequence implementations.
- `Codecs`: bit-level encoders and decoders used by the interpreter.
- `Hashing`: stable hash mixing and jump consistent hashing.
- `ExceptionComparisonMode` and `compareExceptions`: failure matching for minimization.
