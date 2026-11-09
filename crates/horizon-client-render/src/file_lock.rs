//! Android's std file-lock API can be unsupported; use Bionic's advisory flock.
use std::{fs::File, io};

pub fn exclusive(file: &File) -> io::Result<()> {
    #[cfg(not(target_os = "android"))]
    {
        file.lock()
    }
    #[cfg(target_os = "android")]
    {
        loop {
            // The persistent inode is unlocked when File is dropped.
            match rustix::fs::flock(file, rustix::fs::FlockOperation::LockExclusive) {
                Ok(()) => return Ok(()),
                Err(rustix::io::Errno::INTR) => {}
                Err(error) => return Err(error.into()),
            }
        }
    }
}

pub fn shared(file: &File) -> io::Result<()> {
    #[cfg(not(target_os = "android"))]
    {
        file.lock_shared()
    }
    #[cfg(target_os = "android")]
    {
        loop {
            match rustix::fs::flock(file, rustix::fs::FlockOperation::LockShared) {
                Ok(()) => return Ok(()),
                Err(rustix::io::Errno::INTR) => {}
                Err(error) => return Err(error.into()),
            }
        }
    }
}

/// An exclusive lock taken only if no one holds the file, as `File::try_lock`.
pub fn try_exclusive(file: &File) -> Result<(), std::fs::TryLockError> {
    #[cfg(not(target_os = "android"))]
    {
        file.try_lock()
    }
    #[cfg(target_os = "android")]
    {
        match rustix::fs::flock(file, rustix::fs::FlockOperation::NonBlockingLockExclusive) {
            Ok(()) => Ok(()),
            Err(rustix::io::Errno::WOULDBLOCK) => Err(std::fs::TryLockError::WouldBlock),
            Err(error) => Err(std::fs::TryLockError::Error(error.into())),
        }
    }
}
