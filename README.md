# ebp-kmp

Canonical Kotlin Multiplatform implementation of EBP.

- `:ebp-kmp` owns the storage-neutral durable-state SPI, reducers, transaction
  rules, and rollback-capable memory reference store.
- `:wire` owns framing, transport-facing protocol state, validation, and the
  generated contract vocabulary.

Both modules are product-, database-, UI-toolkit-, and platform-policy-neutral.
The normative specification and conformance goldens are read from sibling
[`../ebp`](../ebp).

```sh
python3 tools/gen-vocabulary.py --check
./gradlew :ebp-kmp:jvmTest :wire:jvmTest --console=plain
```
