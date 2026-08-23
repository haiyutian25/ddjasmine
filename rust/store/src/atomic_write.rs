//! atomic_write: crash-safe whole-file replacement.
//!
//! Complement to [`crate::JsonlStore`]'s append-only contract: some state is
//! rewritten wholesale (registry ledgers, manifests), never appended. The
//! rotation is `tmp → fsync → main aside to .bak → rename tmp into place →
//! fsync dir`, so any crash point leaves either the old or the new file
//! intact and fsync'd, never a torn main. The `.bak` rotation also sidesteps
//! Windows rejecting `rename` onto an existing target.
//!
//! The `.bak` is **retained** after a successful write: it is the last
//! known-good copy, and readers can fall back to it when the main file is
//! later found corrupt (not just missing) — see [`read_backup`].

use std::fs::{self, File};
use std::io::{self, Write};
use std::path::{Path, PathBuf};

use crate::StoreError;

fn io(path: &Path, source: io::Error) -> StoreError {
    StoreError::Io {
        path: path.to_path_buf(),
        source,
    }
}

fn tmp_path(path: &Path) -> PathBuf {
    let mut name = path.as_os_str().to_os_string();
    name.push(".tmp");
    PathBuf::from(name)
}

fn bak_path(path: &Path) -> PathBuf {
    let mut name = path.as_os_str().to_os_string();
    name.push(".bak");
    PathBuf::from(name)
}

/// fsyncs the parent directory so the rename itself is durable. Directory
/// handles are not openable as files on Windows, where this is a no-op.
fn sync_dir(path: &Path) {
    #[cfg(unix)]
    if let Some(parent) = path.parent() {
        if let Ok(dir) = File::open(parent) {
            let _ = dir.sync_all();
        }
    }
    #[cfg(not(unix))]
    let _ = path;
}

/// Replaces `path` with `bytes` atomically.
///
/// Crash points: before the rename the old main is untouched; after it the
/// new main is complete and fsync'd; a crash mid-rotation is recovered by
/// [`read_atomic`], which restores a surviving `.bak` or discards a stale
/// `.tmp`.
///
/// # Errors
/// Returns [`StoreError::Io`] when any write/rename fails. When the rename
/// into place fails *and* restoring the backup also fails, returns
/// [`StoreError::AtomicWriteRollback`] carrying both errors — the double
/// failure the caller must surface, since the old main may be stranded as
/// `.bak`.
pub fn atomic_write(path: impl AsRef<Path>, bytes: &[u8]) -> Result<(), StoreError> {
    let path = path.as_ref();
    let tmp = tmp_path(path);
    let bak = bak_path(path);

    {
        let mut file = File::create(&tmp).map_err(|e| io(&tmp, e))?;
        file.write_all(bytes)
            .and_then(|()| file.sync_all())
            .map_err(|e| io(&tmp, e))?;
    }

    if path.exists() {
        if bak.exists() {
            fs::remove_file(&bak).map_err(|e| io(&bak, e))?;
        }
        fs::rename(path, &bak).map_err(|e| io(path, e))?;
    }

    if let Err(source) = fs::rename(&tmp, path) {
        let rollback = if bak.exists() {
            fs::rename(&bak, path)
        } else {
            Ok(())
        };
        return match rollback {
            Ok(()) => Err(io(path, source)),
            Err(rollback_source) => Err(StoreError::AtomicWriteRollback {
                path: path.to_path_buf(),
                source,
                rollback_source,
            }),
        };
    }
    sync_dir(path);

    // The backup is retained on purpose: it is the last known-good copy for
    // corruption recovery (see read_backup).
    Ok(())
}

/// Reads the retained `.bak` sidecar directly — the corruption fallback for
/// when the main file exists but is unreadable. Returns `None` when no
/// backup exists.
///
/// # Errors
/// Returns [`StoreError::Io`] when the backup exists but cannot be read.
pub fn read_backup(path: impl AsRef<Path>) -> Result<Option<Vec<u8>>, StoreError> {
    let bak = bak_path(path.as_ref());
    if !bak.exists() {
        return Ok(None);
    }
    fs::read(&bak).map(Some).map_err(|e| io(&bak, e))
}

/// Reads a file written by [`atomic_write`], recovering a crash mid-rotation
/// first: a stale `.tmp` is discarded; a missing main with a surviving `.bak`
/// is restored from that backup.
///
/// Returns `None` when neither the file nor any recoverable backup exists.
///
/// # Errors
/// Returns [`StoreError::Io`] when recovery or the final read fails.
pub fn read_atomic(path: impl AsRef<Path>) -> Result<Option<Vec<u8>>, StoreError> {
    let path = path.as_ref();
    let tmp = tmp_path(path);
    let bak = bak_path(path);

    if tmp.exists() {
        // Interrupted before the rename: main (or .bak) still holds the last
        // committed content; the tmp never became visible.
        fs::remove_file(&tmp).map_err(|e| io(&tmp, e))?;
    }
    if !path.exists() && bak.exists() {
        fs::rename(&bak, path).map_err(|e| io(path, e))?;
        sync_dir(path);
    }
    if !path.exists() {
        return Ok(None);
    }
    fs::read(path).map(Some).map_err(|e| io(path, e))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn write_then_read_round_trips_and_retains_bak() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("ledger.json");
        atomic_write(&path, b"v1").unwrap();
        assert_eq!(
            read_atomic(&path).unwrap().as_deref(),
            Some(b"v1".as_slice())
        );
        assert!(!tmp_path(&path).exists());
        assert!(!bak_path(&path).exists()); // first write has no prior main

        atomic_write(&path, b"v2-longer").unwrap();
        assert_eq!(
            read_atomic(&path).unwrap().as_deref(),
            Some(b"v2-longer".as_slice())
        );
        // The retained backup is the previous generation.
        assert_eq!(
            read_backup(&path).unwrap().as_deref(),
            Some(b"v1".as_slice())
        );
    }

    #[test]
    fn stale_tmp_is_discarded_and_main_survives() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("ledger.json");
        atomic_write(&path, b"committed").unwrap();
        // Crash after creating the tmp, before the rename.
        std::fs::write(tmp_path(&path), b"partial-new").unwrap();

        assert_eq!(
            read_atomic(&path).unwrap().as_deref(),
            Some(b"committed".as_slice())
        );
        assert!(!tmp_path(&path).exists());
    }

    #[test]
    fn crash_mid_rotation_restores_from_bak() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("ledger.json");
        atomic_write(&path, b"old").unwrap();
        // Crash after rotating main aside, before renaming tmp into place.
        fs::rename(&path, bak_path(&path)).unwrap();
        std::fs::write(tmp_path(&path), b"new").unwrap();

        assert_eq!(
            read_atomic(&path).unwrap().as_deref(),
            Some(b"old".as_slice())
        );
        assert!(!tmp_path(&path).exists());
        assert!(!bak_path(&path).exists());
    }

    #[test]
    fn missing_file_reads_as_none() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("never-written.json");
        assert_eq!(read_atomic(&path).unwrap(), None);
    }
}
