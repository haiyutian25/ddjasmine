//! Fixed-density heuristic token pricing shared by the metering folds, so
//! every surface prices identical content to identical numbers (upstream
//! `token-meter/src/estimate.ts` parity). The estimator has no settings —
//! the constants are the specification.
//!
//! Canonical-form adaptations, documented:
//! - this engine's [`ModelMessage`] carries `content` as a plain string or
//!   an arbitrary JSON value where upstream always has a typed block array;
//!   a string prices as one text block (length + `BLOCK_OVERHEAD`), any
//!   other non-array value prices as one unknown block;
//! - text length counts Unicode scalar values (Rust `chars`), where upstream
//!   counts UTF-16 code units — identical for BMP text, lower for astral
//!   characters; JSON framing length counts UTF-8 bytes where upstream
//!   counts UTF-16 units — identical for ASCII, higher for non-ASCII.

use serde_json::Value;

use session_log::{EpochHeader, ModelMessage};

/// Fixed text-density estimate used until exact tokenization is needed.
pub const CHARS_PER_TOKEN: usize = 4;

/// Per-block structural overhead for JSON framing and type tags.
pub const BLOCK_OVERHEAD: u64 = 4;

/// Role-field framing overhead added to every priced message.
pub const ROLE_OVERHEAD: u64 = 4;

fn density(len: usize) -> u64 {
    (len.div_ceil(CHARS_PER_TOKEN)) as u64
}

/// Prices content recursively under the fixed density heuristic. `content`
/// is this engine's canonical message content: a string (one text block), a
/// block array (`type`-tagged objects), or any other JSON value (one
/// unknown block).
#[must_use]
pub fn estimate_content(content: &Value) -> u64 {
    match content {
        Value::String(text) => density(text.chars().count()) + BLOCK_OVERHEAD,
        Value::Array(blocks) => blocks.iter().map(price_block).sum(),
        other => price_unknown_block(other),
    }
}

fn price_block(block: &Value) -> u64 {
    let kind = block
        .get("type")
        .and_then(Value::as_str)
        .unwrap_or_default();
    match kind {
        "text" | "reasoning" => {
            let len = block
                .get("text")
                .and_then(Value::as_str)
                .map_or(0, |t| t.chars().count());
            density(len) + BLOCK_OVERHEAD
        }
        "tool-call" => {
            let name_len = block
                .get("name")
                .and_then(Value::as_str)
                .map_or(0, |t| t.chars().count());
            // Arguments are a raw JSON string upstream; a non-string value
            // here prices by its serialized length instead.
            let args_len = match block.get("arguments") {
                Some(Value::String(s)) => s.chars().count(),
                other => other
                    .and_then(|v| serde_json::to_string(v).ok())
                    .map_or(0, |s| s.len()),
            };
            density(name_len) + density(args_len) + BLOCK_OVERHEAD
        }
        "tool-result" => {
            let inner = block.get("content").cloned().unwrap_or(Value::Null);
            estimate_content(&inner) + BLOCK_OVERHEAD
        }
        _ => price_unknown_block(block),
    }
}

fn price_unknown_block(block: &Value) -> u64 {
    let len = serde_json::to_string(block).map_or(0, |s| s.len());
    BLOCK_OVERHEAD + density(len)
}

/// Heuristically prices one model-visible message. This engine's
/// [`ModelMessage`] carries `content` as a plain string, which prices as one
/// text block (length + `BLOCK_OVERHEAD`) plus the role overhead.
#[must_use]
pub fn estimate_message(message: &ModelMessage) -> u64 {
    density(message.content.chars().count()) + BLOCK_OVERHEAD + ROLE_OVERHEAD
}

/// Prices the system-prompt part of a canonical request envelope; 0 when
/// absent.
#[must_use]
pub fn estimate_system_tokens(header: &EpochHeader) -> u64 {
    match &header.system {
        Some(system) => density(system.chars().count()) + ROLE_OVERHEAD,
        None => 0,
    }
}

/// Prices the tool-schema part of a canonical request envelope; 0 when
/// absent or empty.
#[must_use]
pub fn estimate_tools_tokens(header: &EpochHeader) -> u64 {
    match &header.tools {
        Some(tools) if !tools.is_empty() => {
            let len = serde_json::to_string(tools).map_or(0, |s| s.len());
            density(len) + BLOCK_OVERHEAD
        }
        _ => 0,
    }
}

/// Prices the complete non-surface request envelope.
#[must_use]
pub fn estimate_header(header: &EpochHeader) -> u64 {
    estimate_system_tokens(header) + estimate_tools_tokens(header)
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::*;

    #[test]
    fn text_blocks_price_by_density_plus_overhead() {
        // "" -> ceil(0/4)+4, "abcd" -> 1+4, "abcde" -> 2+4
        let blocks = json!([
            {"type": "text", "text": ""},
            {"type": "reasoning", "text": "abcd"},
            {"type": "text", "text": "abcde"},
        ]);
        assert_eq!(estimate_content(&blocks), 4 + 5 + 6);
        assert_eq!(estimate_content(&json!("abcd")), 5); // string = text block
    }

    #[test]
    fn tool_call_blocks_price_name_and_arguments() {
        let blocks = json!([
            {"type": "tool-call", "name": "abcd", "arguments": "{\"a\":1}"},
        ]);
        // name 4 chars -> 1, arguments 7 chars -> 2, +4 overhead
        assert_eq!(estimate_content(&blocks), 1 + 2 + 4);
    }

    #[test]
    fn tool_result_blocks_recurse_and_unknown_blocks_price_json() {
        let blocks = json!([
            {"type": "tool-result", "content": "abcdefgh"},
            {"type": "image", "data": "abcdefgh"},
        ]);
        let inner = 2 + 4; // nested text block
        let unknown = 4 + density(
            serde_json::to_string(&json!({"type": "image", "data": "abcdefgh"}))
                .unwrap()
                .len(),
        );
        assert_eq!(estimate_content(&blocks), inner + 4 + unknown);
    }

    #[test]
    fn header_parts_price_system_and_tools() {
        let header = EpochHeader {
            config: serde_json::from_value(json!({
                "provider": "p", "model": "m"
            }))
            .unwrap(),
            adapter_defaults: None,
            system: Some("a".repeat(40)),
            tools: Some(vec![json!({"name": "t", "parameters": {}})]),
        };
        // system: ceil(40/4)+4 = 14; tools: json length priced + 4
        assert_eq!(estimate_system_tokens(&header), 14);
        let tools_len = serde_json::to_string(&header.tools.clone().unwrap())
            .unwrap()
            .len();
        let tools_tokens = (tools_len.div_ceil(4) + 4) as u64;
        assert_eq!(estimate_tools_tokens(&header), tools_tokens);
        assert_eq!(estimate_header(&header), 14 + tools_tokens);

        let bare = EpochHeader {
            config: serde_json::from_value(json!({"provider": "p", "model": "m"})).unwrap(),
            adapter_defaults: None,
            system: None,
            tools: None,
        };
        assert_eq!(estimate_header(&bare), 0);
        assert_eq!(estimate_tools_tokens(&bare), 0);
    }
}
