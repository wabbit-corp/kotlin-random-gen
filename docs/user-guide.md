# User Guide

## Building Generators

Use primitive generators and combinators from `Gen`:

```kotlin
import one.wabbit.random.gen.Gen

data class Point(val x: Int, val y: Int)

val point = Gen.map(
    Gen.int(-10..10),
    Gen.int(-10..10),
) { x, y -> Point(x, y) }
```

Use `flatMap` when later choices depend on earlier values:

```kotlin
import one.wabbit.random.gen.Gen

val boundedList = Gen.int(0..5).flatMap { size ->
    Gen.listOf(size, Gen.printableAsciiChar)
}
```

## Choices and Weights

Use `oneOf`, `oneOfGen`, `freq`, and `freqGen` for alternatives:

```kotlin
import one.wabbit.random.gen.Gen

val smallToken = Gen.freq(
    listOf(
        8 to "ok",
        1 to "rare",
    )
)
```

Weights must be non-negative and the total weight must be positive.

## Sampling

```kotlin
import kotlin.random.Random
import one.wabbit.random.gen.Gen
import one.wabbit.random.gen.sample

val sample = Gen.string(0..8).sample(Random(42))
```

`filter` rejects samples. Rejected samples return null from `sample`.

## Property Checks

```kotlin
import one.wabbit.random.gen.Gen

Gen.foreach(Gen.int(0..100), count = 100) { value ->
    check(value >= 0)
}
```

Use `foreachMin` when you want failures minimized and replayable:

```kotlin
import kotlin.random.Random
import one.wabbit.random.gen.Gen
import one.wabbit.random.gen.foreachMin

Gen.int(0..100).foreachMin(Random(1), iters = 100) { value ->
    check(value < 50)
}
```

## Replay Seeds

`TapeSeed` stores a PRNG seed plus bit flips. Use `toBase58String` for compact logging:

```kotlin
import one.wabbit.random.gen.TapeSeed
import one.wabbit.random.gen.util.MutableBitDeque

val seed = TapeSeed(1234L, MutableBitDeque())
val encoded = seed.toBase58String()
val decoded = TapeSeed.fromBase58String(encoded)

check(decoded.seed == seed.seed)
```

## Generated Functions

`Gen.func` and `Gen.func2` generate deterministic table-backed functions. `CoGen` controls how
inputs are hashed into table buckets.

```kotlin
import one.wabbit.random.gen.Gen

val fGen = Gen.func<Int, String>(Gen.oneOf("a", "b", "c"))
```

Use explicit `CoGen` instances when platform `hashCode` behavior is not stable enough for the
domain you are generating.
