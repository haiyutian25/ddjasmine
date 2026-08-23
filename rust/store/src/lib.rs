//! store: append-only persistence backends.
//!
//! JSONL is the phase-1 backend; SQLite lands in phase 2 as a second provider
//! behind the same contract. The engine is fixed core (ANDROID-PLAN §4.0):
//! provider *selection* stays a Kotlin plugin concern, this crate only executes
//! byte-level append/read/flush.
//!
//! Crash tolerance mirrors upstream's `session-persistence-jsonl`: a final record
//! left without its terminating newline by a crash mid-write is a *torn tail*.
//! It is reported, never parsed as a record, and [`JsonlStore::truncate`]
//! repairs the file to the committed prefix (upstream's commitRepair equivalent).

use std::fs::{File, OpenOptions};
use std::io::{self, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};

use thiserror::Error;

mod atomic_write;
pub use atomic_write::{atomic_write, read_atomic, read_backup};

/// Errors of the storage backends.
#[derive(Debug, Error)]
pub enum StoreError {
    /// Underlying IO failure.
    #[error("io error on {path}: {source}")]
    Io {
        /// Path of the offending file.
        path: PathBuf,
        /// Original IO error.
        source: io::Error,
    },
    /// Atomic-write rotation failed and restoring the backup failed too;
    /// both errors are reported because the old content may be stranded.
    #[error("atomic write to {path} failed ({source}); rollback also failed ({rollback_source})")]
    AtomicWriteRollback {
        /// Path of the target file.
        path: PathBuf,
        /// Failure of the rename into place.
        source: io::Error,
        /// Failure of the backup restore.
        rollback_source: io::Error,
    },
}

/// Result of scanning a JSONL file.
#[derive(Debug)]
pub struct ReadAll {
    /// Complete newline-terminated records, in file order; empty lines skipped.
    pub lines: Vec<Vec<u8>>,
    /// Bytes after the final newline, when the file does not end with one: a
    /// torn tail from a crash mid-write. Never a complete record by framing
    /// contract (every append writes the newline).
    pub torn_tail: Option<Vec<u8>>,
    /// Offset just past the final newline; `truncate(committed_len)` removes
    /// the torn tail.
    pub committed_len: u64,
}

/// Append-only JSONL file backend.
///
/// Records are raw bytes terminated by `\n`; the store never interprets
/// content. Writes go straight to the OS (no in-process buffer); durability
/// is explicit via [`JsonlStore::flush`], wired to the checkpoint policy
/// (durable before every model request).
pub struct JsonlStore {
    file: File,
    path: PathBuf,
}

impl JsonlStore {
    /// Opens (or creates) a JSONL file for reading and appending.
    ///
    /// # Errors
    /// Returns [`StoreError::Io`] when the file cannot be opened.
    pub fn open(path: impl AsRef<Path>) -> Result<Self, StoreError> {
        let path = path.as_ref().to_path_buf();
        // `write` alongside `append`: truncating a torn tail (set_len)
        // requires write access, which append-only handles lack on Windows.
        #[allow(clippy::ineffective_open_options)]
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .append(true)
            .create(true)
            .open(&path)
            .map_err(|source| StoreError::Io {
                path: path.clone(),
                source,
            })?;
        Ok(Self { file, path })
    }

    /// Appends one record followed by `\n`.
    ///
    /// Returns the byte offset at which the record starts. Does not fsync;
    /// call [`JsonlStore::flush`] at checkpoint boundaries.
    ///
    /// Partial-write caveat: if the record bytes land but the trailing `\n`
    /// write fails, the on-disk tail is record bytes without a terminator.
    /// This is not a crash-style torn tail — a later successful append
    /// completes the line, so the residue merges into that record and
    /// `read_all` may reject the file as malformed rather than repair it.
    /// The torn-tail contract below covers crash interruption only; callers
    /// that observe an `append_line` error must not reuse the store.
    ///
    /// # Errors
    /// Returns [`StoreError::Io`] when the write fails.
    pub fn append_line(&mut self, bytes: &[u8]) -> Result<u64, StoreError> {
        let offset = self
            .file
            .seek(SeekFrom::End(0))
            .map_err(|source| StoreError::Io {
                path: self.path.clone(),
                source,
            })?;
        self.file
            .write_all(bytes)
            .and_then(|()| self.file.write_all(b"\n"))
            .map_err(|source| StoreError::Io {
                path: self.path.clone(),
                source,
            })?;
        Ok(offset)
    }

    /// Flushes buffered data and fsyncs to durable storage.
    ///
    /// # Errors
    /// Returns [`StoreError::Io`] when flush or fsync fails.
    pub fn flush(&mut self) -> Result<(), StoreError> {
        self.file
            .flush()
            .and_then(|()| self.file.sync_data())
            .map_err(|source| StoreError::Io {
                path: self.path.clone(),
                source,
            })
    }

    /// Scans the file: complete records plus torn-tail detection. A torn tail
    /// is reported, never returned as a record.
    ///
    /// # Errors
    /// Returns [`StoreError::Io`] when the file cannot be read.
    pub fn read_all(&self) -> Result<ReadAll, StoreError> {
        let mut file = File::open(&self.path).map_err(|source| StoreError::Io {
            path: self.path.clone(),
            source,
        })?;
        let mut data = Vec::new();
        file.read_to_end(&mut data)
            .map_err(|source| StoreError::Io {
                path: self.path.clone(),
                source,
            })?;
        let committed_len = data.iter().rposition(|&b| b == b'\n').map_or(0, |p| p + 1);
        let torn_tail = (committed_len < data.len()).then(|| data[committed_len..].to_vec());
        let lines = data[..committed_len]
            .split(|&b| b == b'\n')
            .filter(|line| !line.is_empty())
            .map(<[u8]>::to_vec)
            .collect();
        Ok(ReadAll {
            lines,
            torn_tail,
            committed_len: committed_len as u64,
        })
    }

    /// Truncates the file to `len` bytes and fsyncs — the repair operation
    /// for a torn tail (pass [`ReadAll::committed_len`]). Uses a dedicated
    /// read+write handle: Windows rejects `set_len` on append-mode handles.
    ///
    /// # Errors
    /// Returns [`StoreError::Io`] when truncation or fsync fails.
    pub fn truncate(&mut self, len: u64) -> Result<(), StoreError> {
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .open(&self.path)
            .map_err(|source| StoreError::Io {
                path: self.path.clone(),
                source,
            })?;
        file.set_len(len)
            .and_then(|()| file.sync_data())
            .map_err(|source| StoreError::Io {
                path: self.path.clone(),
                source,
            })
    }

    /// Path of the backing file.
    #[must_use]
    pub fn path(&self) -> &Path {
        &self.path
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn append_then_read_all_round_trips_in_order() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("session.jsonl");
        let mut store = JsonlStore::open(&path).unwrap();
        store.append_line(br#"{"record":"header"}"#).unwrap();
        store.append_line(br#"{"record":"event","seq":0}"#).unwrap();
        store.flush().unwrap();

        let read = store.read_all().unwrap();
        assert_eq!(
            read.lines,
            vec![
                br#"{"record":"header"}"#.to_vec(),
                br#"{"record":"event","seq":0}"#.to_vec(),
            ]
        );
        assert!(read.torn_tail.is_none());
        assert_eq!(
            read.committed_len as usize,
            std::fs::metadata(&path).unwrap().len() as usize
        );
    }

    #[test]
    fn offsets_are_monotonic_and_reopen_preserves_content() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("session.jsonl");
        let first;
        let second;
        {
            let mut store = JsonlStore::open(&path).unwrap();
            first = store.append_line(b"aa").unwrap();
            second = store.append_line(b"bbb").unwrap();
            store.flush().unwrap();
        }
        assert_eq!(first, 0);
        assert_eq!(second, 3);

        let reopened = JsonlStore::open(&path).unwrap();
        assert_eq!(
            reopened.read_all().unwrap().lines,
            vec![b"aa".to_vec(), b"bbb".to_vec()]
        );
    }

    #[test]
    fn crash_mid_write_leaves_a_detectable_torn_tail_never_parsed_as_record() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("session.jsonl");
        {
            let mut store = JsonlStore::open(&path).unwrap();
            store.append_line(b"one").unwrap();
            store.append_line(b"two").unwrap();
            store.flush().unwrap();
        }
        // Simulate a crash mid-write: partial record bytes, no newline.
        std::fs::write(&path, b"one\ntwo\n{\"record\":\"ev").unwrap();

        let read = JsonlStore::open(&path).unwrap().read_all().unwrap();
        assert_eq!(read.lines, vec![b"one".to_vec(), b"two".to_vec()]);
        assert_eq!(
            read.torn_tail.as_deref(),
            Some(b"{\"record\":\"ev".as_slice())
        );
        assert_eq!(read.committed_len, 8);
    }

    #[test]
    fn truncate_repairs_torn_tail_and_appends_continue() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("session.jsonl");
        std::fs::write(&path, b"one\ntwo\npar").unwrap();
        let mut store = JsonlStore::open(&path).unwrap();
        let read = store.read_all().unwrap();
        assert!(read.torn_tail.is_some());

        store.truncate(read.committed_len).unwrap();
        store.append_line(b"three").unwrap();
        store.flush().unwrap();

        let read = store.read_all().unwrap();
        assert_eq!(
            read.lines,
            vec![b"one".to_vec(), b"two".to_vec(), b"three".to_vec()]
        );
        assert!(read.torn_tail.is_none());
    }
}
