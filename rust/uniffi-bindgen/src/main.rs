//! Workspace-local uniffi-bindgen binary. Building it from the same uniffi
//! version as the libraries guarantees generated bindings match the runtime;
//! keeping it in its own crate keeps the CLI-only dependencies out of the
//! cross-compiled library graph.
fn main() {
    uniffi::uniffi_bindgen_main()
}
