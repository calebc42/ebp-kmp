# ebp-kmp implementation guide

The sibling `ebp` repository is normative.  Keep this implementation free of
Jetpacs, Room, Navigation, Compose, Android product policy, and renderer-owned
node names.

- `:wire` may depend on `:ebp-kmp`; the reverse edge is forbidden.
- Portable state and validation belong in common source sets unless a platform
  transport genuinely requires otherwise.
- Storage acceptance must remain atomic, partitioned by pairing, ordered, and
  rollback-safe.
- Never hand-edit generated `Vocabulary.kt`; update the contract and run the
  generator, then verify both implementations against the same fixtures.

Run the commands in `README.md` before handing off changes.
