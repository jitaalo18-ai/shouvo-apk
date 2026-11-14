# Changes made for Nock Nock

Upstream: https://github.com/Rikorose/DeepFilterNet (libDF), commit 7b14b895fe7fc42cab72b4a4c1e49c982bd6404b,
license MIT OR Apache-2.0 (LICENSE-MIT, LICENSE-APACHE).

- Only libDF's sources are kept (`libDF/src`, without `bin/` and `wasm.rs`); `Cargo.lock` is upstream's.
- `Cargo.toml` (workspace) and `libDF/Cargo.toml` trimmed: only the C API is built (`cdylib`),
  the dataset / HDF5 / binaries / wasm dependencies are left out, and the `capi` feature no
  longer embeds the model in the library (the app ships `DeepFilterNet3_onnx.tar.gz`, from the
  same commit's `models/`, as the asset `app/src/main/assets/df/DeepFilterNet3_onnx.dfn` - renamed
  so the APK packager keeps it byte for byte).
- No source code changes.
