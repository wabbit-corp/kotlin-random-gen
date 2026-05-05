# Troubleshooting

## `sample` Returns Null

The generator either rejected the sample with `filter` or ran out of replay bits. Use
`sampleUnbounded` for best-effort generation from an unbounded random source.

## Generated Values Are Not Reproducible

Use the same generator definition, library version, random seed, and replay tape. Small generator
structure changes can change bit consumption and therefore generated values.

## `foreachMin` Throws `FailedToMinimizeException`

The original failure could not be reproduced during minimization. Check whether the property depends
on external mutable state, wall-clock time, platform-specific hash codes, or non-deterministic
exceptions.

## Generated Functions Change Across Platforms

Prefer explicit `CoGen` instances over `unsafeFromHashCode` when cross-platform stability matters.
Platform `hashCode` behavior can differ for user-defined or mutable objects.

## Weighted Choices Reject Options

Weighted choices require at least one option and a positive total weight. Integer and double
constructors accept zero weights, but not an all-zero set.
