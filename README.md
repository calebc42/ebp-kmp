# ebp-kmp

Canonical Kotlin Multiplatform implementation of EBP.

- `:ebp-kmp` owns the storage-neutral durable-state SPI, reducers, transaction
  rules, and rollback-capable memory reference store.
- `:wire` owns framing, transport-facing protocol state, validation, and the
  generated contract vocabulary — including the §13.8 surface data document:
  `JsonPointer` (RFC 6901 evaluation, `set`, `remove`) and `Materializer`
  (bindings resolved before validation), replayed against
  `../ebp/goldens/data.golden`; `:ebp-kmp` applies `surface.data` patches as
  ordinary revisioned surface mutations (`SurfaceStore.patch`) and persists
  the authored tree beside the materialized one.

Both modules are product-, database-, UI-toolkit-, and platform-policy-neutral.
The normative specification and conformance goldens are read from sibling
[`../ebp`](../ebp).

```sh
python3 tools/gen-vocabulary.py --check
./gradlew :ebp-kmp:jvmTest :wire:jvmTest --console=plain
```
