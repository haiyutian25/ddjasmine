//! codec: byte-level codecs for the data spine.
//!
//! Two fixed-core concerns (ANDROID-PLAN §4.0):
//!
//! - **SSE frame parsing** as an incremental pure function `feed(bytes) ->
//!   events`. Framing semantics follow the WHATWG SSE spec and upstream's
//!   `eventsource-parser` v3.1.0: line endings are CRLF / CR / LF; a single
//!   leading BOM is stripped; exactly one leading space after `:` is removed;
//!   bare field names take an empty value; unknown fields (incl. `retry`) are
//!   ignored; comment lines (`:`-prefixed) are skipped; multiple `data:`
//!   lines join with `\n`; blocks without any `data:` field dispatch nothing
//!   but reset the buffers; the default event type is `message`; `id:` values
//!   containing NUL are ignored. Provider semantics (the `[DONE]` sentinel,
//!   end-of-stream error policy) are NOT baked in: the sentinel is a
//!   constructor parameter, so `llm-deepseek` stays a Kotlin plugin concern.
//!
//!   Two deliberate divergences from the upstream pipeline, both stricter:
//!   invalid UTF-8 fails instead of being replaced by U+FFFD
//!   (`TextDecoderStream` is non-fatal there), and `finish()` reports an
//!   unterminated tail as [`SseError::Truncated`] (upstream's "EOF 处未终止尾部算
//!   截断" rule lives in `sse.ts`, not in the framing library).
//!
//! - **Content-addressed attachments**: sha256 addressing and verification.

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use thiserror::Error;

/// One dispatched SSE event (a blank-line-terminated field group).
#[derive(Clone, Debug, Default, Serialize, Deserialize, PartialEq, Eq)]
pub struct SseEvent {
    /// Event type: the `event:` field value, defaulting to `message`
    /// (WHATWG / eventsource-parser rule).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub event: Option<String>,
    /// Concatenated `data:` fields, joined with `\n`.
    pub data: String,
    /// Value of the last `id:` field, when present.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub id: Option<String>,
    /// True when `data` equals the configured done sentinel.
    pub is_done: bool,
}

/// Errors of the SSE parser.
#[derive(Debug, Error, Clone, PartialEq, Eq)]
pub enum SseError {
    /// Bytes after the last valid UTF-8 boundary are not valid UTF-8 and are
    /// not a plausible in-progress sequence.
    #[error("invalid UTF-8 in stream")]
    InvalidUtf8,
    /// `finish()` was called with an undispatched `data:` buffer: the stream
    /// was cut off mid-frame.
    #[error("truncated stream: unterminated tail")]
    Truncated,
}

/// Incremental SSE parser. Feed bytes in arbitrary chunkings; cancellation is
/// simply "stop feeding" (ANDROID-PLAN §7), no state needs unwinding.
#[derive(Default)]
pub struct SseParser {
    raw: Vec<u8>,
    line: String,
    bom_checked: bool,
    data: Vec<String>,
    event: Option<String>,
    id: Option<String>,
    done_sentinel: Option<String>,
}

impl SseParser {
    /// Parser without a done sentinel (framework-level only).
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Parser that flags events whose data equals `done_sentinel`
    /// (e.g. `[DONE]` for DeepSeek). The sentinel is data, not baked-in
    /// behavior, so provider semantics stay with the Kotlin plugin.
    #[must_use]
    pub fn with_done_sentinel(done_sentinel: Option<String>) -> Self {
        Self {
            done_sentinel,
            ..Self::default()
        }
    }

    /// Feeds one chunk of bytes and returns every event completed by it.
    ///
    /// Handles UTF-8 sequences split across chunk boundaries, a single
    /// leading BOM (even when its bytes span chunks), and CRLF, CR-only, or
    /// LF line endings.
    ///
    /// # Errors
    /// Returns [`SseError::InvalidUtf8`] on bytes that cannot be UTF-8.
    pub fn feed(&mut self, bytes: &[u8]) -> Result<Vec<SseEvent>, SseError> {
        self.raw.extend_from_slice(bytes);
        let valid_up_to = match std::str::from_utf8(&self.raw) {
            Ok(_) => self.raw.len(),
            Err(e) if e.error_len().is_none() => e.valid_up_to(),
            Err(_) => return Err(SseError::InvalidUtf8),
        };
        let text = std::str::from_utf8(&self.raw[..valid_up_to]).expect("validated UTF-8");
        let mut text = text.to_string();
        self.raw.drain(..valid_up_to);
        // Strip one leading BOM, but only once real text has arrived: when
        // the first chunk holds only a partial BOM byte sequence, the check
        // must wait for the sequence to complete.
        if !self.bom_checked && !text.is_empty() {
            self.bom_checked = true;
            if text.starts_with('\u{feff}') {
                text.remove(0);
            }
        }
        self.line.push_str(&text);

        let mut events = Vec::new();
        // WHATWG line splitting: CRLF, CR, or LF. A trailing CR must wait —
        // the next chunk may complete a CRLF pair.
        while let Some(pos) = self.line.find(['\r', '\n']) {
            let bytes = self.line.as_bytes();
            if bytes[pos] == b'\r' && pos + 1 == self.line.len() {
                break;
            }
            let terminator_len = if bytes[pos] == b'\r' && bytes.get(pos + 1) == Some(&b'\n') {
                2
            } else {
                1
            };
            let line: String = self.line.drain(..pos).collect();
            self.line.drain(..terminator_len);
            if let Some(event) = self.process_line(&line) {
                events.push(event);
            }
        }
        Ok(events)
    }

    /// Signals end of stream. A trailing CR counts as a complete CR-only
    /// terminator (eventsource-parser parity); a final line without any
    /// terminator is processed, and leftover `data:` fields then fail as
    /// [`SseError::Truncated`] — upstream's rule that a partial frame at EOF is
    /// truncation, not an event. `event:`/`id:`-only leftovers drop silently
    /// (they would not have dispatched anyway).
    ///
    /// # Errors
    /// Returns [`SseError::Truncated`] when `data:` fields remain
    /// undispatched, or [`SseError::InvalidUtf8`] on dangling bytes.
    pub fn finish(&mut self) -> Result<Vec<SseEvent>, SseError> {
        if !self.raw.is_empty() {
            return Err(SseError::InvalidUtf8);
        }
        let mut events = Vec::new();
        let mut rest = std::mem::take(&mut self.line);
        let terminated = rest.ends_with('\r');
        if terminated {
            rest.pop();
        }
        if !rest.is_empty() || terminated {
            if let Some(event) = self.process_line(&rest) {
                events.push(event);
            }
        }
        if !self.data.is_empty() {
            return Err(SseError::Truncated);
        }
        Ok(events)
    }

    /// Processes one complete line; returns an event when the line was the
    /// blank dispatch marker.
    fn process_line(&mut self, line: &str) -> Option<SseEvent> {
        if line.is_empty() {
            return self.dispatch();
        }
        if line.starts_with(':') {
            return None; // comment / heartbeat
        }
        let (field, value) = match line.split_once(':') {
            Some((field, rest)) => (field, rest.strip_prefix(' ').unwrap_or(rest)),
            None => (line, ""),
        };
        match field {
            "data" => self.data.push(value.to_string()),
            "event" => self.event = Some(value.to_string()),
            // WHATWG: id values containing NUL are ignored.
            "id" if !value.contains('\0') => self.id = Some(value.to_string()),
            _ => {} // `retry` and unknown fields are ignored
        }
        None
    }

    fn dispatch(&mut self) -> Option<SseEvent> {
        if self.data.is_empty() {
            // eventsource-parser parity: blocks without data dispatch nothing,
            // but still reset the buffers.
            self.event = None;
            self.id = None;
            return None;
        }
        let data = self.data.join("\n");
        let is_done = self.done_sentinel.as_deref() == Some(data.as_str());
        let event = SseEvent {
            event: Some(self.event.take().unwrap_or_else(|| "message".to_string())),
            data,
            id: self.id.take(),
            is_done,
        };
        self.data.clear();
        Some(event)
    }
}

/// Content address of a byte payload: `sha256:<hex>`.
#[must_use]
pub fn content_address(bytes: &[u8]) -> String {
    let digest = Sha256::digest(bytes);
    let mut out = String::with_capacity(7 + 64);
    out.push_str("sha256:");
    for byte in digest {
        out.push_str(&format!("{byte:02x}"));
    }
    out
}

/// Verifies `bytes` against a `sha256:<hex>` content address.
#[must_use]
pub fn verify_content(address: &str, bytes: &[u8]) -> bool {
    content_address(bytes) == address
}

#[cfg(test)]
mod tests {
    use super::*;

    fn feed_all(parser: &mut SseParser, chunks: &[&[u8]]) -> Vec<SseEvent> {
        let mut out = Vec::new();
        for chunk in chunks {
            out.extend(parser.feed(chunk).unwrap());
        }
        out
    }

    #[test]
    fn single_event_dispatches_on_blank_line_with_default_event_type() {
        let mut p = SseParser::new();
        let events = p.feed(b"data: hello\n\n").unwrap();
        assert_eq!(
            events,
            vec![SseEvent {
                event: Some("message".into()),
                data: "hello".into(),
                ..SseEvent::default()
            }]
        );
    }

    #[test]
    fn multi_line_data_joins_with_newline_and_fields_are_captured() {
        let mut p = SseParser::new();
        let events = p
            .feed(b"event: message\nid: 42\ndata: a\ndata: b\n\n")
            .unwrap();
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].event.as_deref(), Some("message"));
        assert_eq!(events[0].id.as_deref(), Some("42"));
        assert_eq!(events[0].data, "a\nb");
    }

    #[test]
    fn crlf_endings_and_comment_lines_are_handled() {
        let mut p = SseParser::new();
        let events = p.feed(b": heartbeat\r\ndata: x\r\n\r\n").unwrap();
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].data, "x");
    }

    #[test]
    fn cr_only_line_endings_are_line_breaks() {
        // A trailing CR waits for a possible LF completing a CRLF pair, so the
        // dispatch lands at finish() — the conservative choice: eagerly
        // treating a chunk-final CR as complete would misread "…\r" + "\n…"
        // as line + blank line and dispatch prematurely.
        let mut p = SseParser::new();
        assert!(p.feed(b"data: a\r\r").unwrap().is_empty());
        let events = p.finish().unwrap();
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].data, "a");
        // Mid-stream CR-only breaks dispatch as soon as more bytes arrive.
        let mut p = SseParser::new();
        let events = p.feed(b"data: a\r\rdata: b\r\r").unwrap();
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].data, "a");
    }

    #[test]
    fn crlf_pair_split_across_chunks_is_one_terminator() {
        let mut p = SseParser::new();
        let chunks: [&[u8]; 2] = [b"data: a\r", b"\n\r\n"];
        let events = feed_all(&mut p, &chunks);
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].data, "a");
    }

    #[test]
    fn leading_bom_is_stripped_once() {
        let mut p = SseParser::new();
        let events = p.feed("\u{feff}data: y\n\n".as_bytes()).unwrap();
        assert_eq!(events[0].data, "y");
    }

    #[test]
    fn bom_split_across_chunks_is_still_stripped() {
        // BOM is EF BB BF; feed it one byte at a time.
        let mut p = SseParser::new();
        let chunks: [&[u8]; 4] = [b"\xEF", b"\xBB", b"\xBFdata: y\n", b"\n"];
        let events = feed_all(&mut p, &chunks);
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].data, "y");
    }

    #[test]
    fn utf8_sequence_split_across_chunks_is_reassembled() {
        // '€' is E2 82 AC in UTF-8; split it across three feeds.
        let mut p = SseParser::new();
        let chunks: [&[u8]; 4] = [b"data: \xE2", b"\x82", b"\xAC\n", b"\n"];
        let events = feed_all(&mut p, &chunks);
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].data, "€");
    }

    #[test]
    fn event_split_across_chunks_mid_line_dispatches_once() {
        let mut p = SseParser::new();
        let chunks: [&[u8]; 3] = [b"data: hel", b"lo\n", b"\ndata: next\n\n"];
        let events = feed_all(&mut p, &chunks);
        assert_eq!(events.len(), 2);
        assert_eq!(events[0].data, "hello");
        assert_eq!(events[1].data, "next");
    }

    #[test]
    fn done_sentinel_flags_matching_event_only() {
        let mut p = SseParser::with_done_sentinel(Some("[DONE]".into()));
        let events = p.feed(b"data: {}\n\ndata: [DONE]\n\n").unwrap();
        assert!(!events[0].is_done);
        assert!(events[1].is_done);
    }

    #[test]
    fn block_without_data_dispatches_nothing_but_resets_buffers() {
        let mut p = SseParser::new();
        let events = p.feed(b"event: ping\nid: 1\n\ndata: real\n\n").unwrap();
        // only the data-bearing block dispatches; the stale event/id do not leak
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].event.as_deref(), Some("message"));
        assert_eq!(events[0].id, None);
        assert_eq!(events[0].data, "real");
    }

    #[test]
    fn id_containing_nul_is_ignored() {
        let mut p = SseParser::new();
        let events = p.feed(b"id: a\0b\ndata: x\n\n").unwrap();
        assert_eq!(events[0].id, None);
    }

    #[test]
    fn invalid_utf8_is_rejected() {
        let mut p = SseParser::new();
        assert_eq!(p.feed(b"data: \xFF\xFE\n"), Err(SseError::InvalidUtf8));
    }

    #[test]
    fn finish_treats_unterminated_frame_as_truncation() {
        let mut p = SseParser::new();
        p.feed(b"data: partial\n").unwrap();
        assert_eq!(p.finish(), Err(SseError::Truncated));
    }

    #[test]
    fn finish_reports_truncated_for_an_unterminated_tail() {
        let mut p = SseParser::new();
        p.feed(b"data: a\n\ndata: b").unwrap();
        let events = p.finish().unwrap_err();
        // "data: b" completes a line but has no blank dispatch marker after it
        assert_eq!(events, SseError::Truncated);
    }

    #[test]
    fn finish_treats_trailing_cr_as_a_complete_terminator() {
        // "data: a\n\r": the lone CR is an empty line, i.e. the dispatch marker.
        let mut p = SseParser::new();
        p.feed(b"data: a\n\r").unwrap();
        let events = p.finish().unwrap();
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].data, "a");
    }

    #[test]
    fn stray_blank_lines_dispatch_nothing() {
        let mut p = SseParser::new();
        assert!(p.feed(b"\n\n\n").unwrap().is_empty());
    }

    #[test]
    fn content_address_matches_known_sha256_vector() {
        // NIST test vector: sha256("abc")
        assert_eq!(
            content_address(b"abc"),
            "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
        assert!(verify_content(
            "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            b"abc"
        ));
        assert!(!verify_content(
            "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            b"abd"
        ));
    }
}
