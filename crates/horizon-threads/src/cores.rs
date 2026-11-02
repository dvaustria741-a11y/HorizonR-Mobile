//! The fast cores of an asymmetric (big.LITTLE) phone CPU. The render and
//! server threads run on them, so a frame or a tick never lands on a slow
//! core while the worker pools fill the rest. Helper threads are released to
//! every core again, since a thread starts with its parent's affinity.

/// A core counts as big at this share of the fastest core's capacity.
const BIG_SHARE: u64 = 75;

/// The big cores, from each core's capacity (or top frequency), indexed by
/// core. `None` when the cores are alike, when a value is missing, or when
/// fewer than two are big: pinning two busy threads to one core would hurt.
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
pub(crate) fn classify(values: &[u64]) -> Option<Vec<usize>> {
    let max = *values.iter().max()?;
    if values.contains(&0) {
        return None;
    }
    let big: Vec<usize> = values
        .iter()
        .enumerate()
        .filter(|(_, v)| **v * 100 >= max * BIG_SHARE)
        .map(|(i, _)| i)
        .collect();
    (big.len() >= 2 && big.len() < values.len()).then_some(big)
}

#[cfg(target_os = "android")]
mod android {
    use std::sync::OnceLock;

    static BIG: OnceLock<Option<Vec<usize>>> = OnceLock::new();
    static ALL: OnceLock<Vec<usize>> = OnceLock::new();

    fn read_values() -> Option<Vec<u64>> {
        let mut values = Vec::new();
        for cpu in 0..256 {
            let dir = format!("/sys/devices/system/cpu/cpu{cpu}");
            if !std::path::Path::new(&dir).is_dir() {
                break;
            }
            let read = |name: &str| {
                std::fs::read_to_string(format!("{dir}/{name}"))
                    .ok()?
                    .trim()
                    .parse::<u64>()
                    .ok()
            };
            values.push(read("cpu_capacity").or_else(|| read("cpufreq/cpuinfo_max_freq"))?);
        }
        (!values.is_empty()).then_some(values)
    }

    fn current_affinity() -> Vec<usize> {
        // SAFETY: a zeroed cpu_set_t is an empty set; sched_getaffinity (pid 0
        // is the calling thread) writes within it and CPU_ISSET reads it.
        unsafe {
            let mut set: libc::cpu_set_t = std::mem::zeroed();
            if libc::sched_getaffinity(0, std::mem::size_of::<libc::cpu_set_t>(), &mut set) != 0 {
                return Vec::new();
            }
            (0..libc::CPU_SETSIZE as usize)
                .filter(|&cpu| libc::CPU_ISSET(cpu, &set))
                .collect()
        }
    }

    fn set_affinity(cores: &[usize]) -> bool {
        if cores.is_empty() {
            return false;
        }
        // SAFETY: a zeroed cpu_set_t is an empty set; CPU_SET stays below its
        // size and sched_setaffinity (pid 0 is the calling thread) only reads it.
        unsafe {
            let mut set: libc::cpu_set_t = std::mem::zeroed();
            for &cpu in cores.iter().filter(|&&c| c < libc::CPU_SETSIZE as usize) {
                libc::CPU_SET(cpu, &mut set);
            }
            libc::sched_setaffinity(0, std::mem::size_of::<libc::cpu_set_t>(), &set) == 0
        }
    }

    /// Run the calling thread on the big cores only. `HORIZON_BIG_CORES=0` turns it off.
    pub(crate) fn pin_to_big() -> bool {
        if std::env::var_os("HORIZON_BIG_CORES").is_some_and(|v| v == "0") {
            return false;
        }
        let Some(big) = BIG.get_or_init(|| read_values().and_then(|v| super::classify(&v))) else {
            return false;
        };
        // The mask before the first pin is every core the process may use.
        ALL.get_or_init(current_affinity);
        set_affinity(big)
    }

    /// Let the calling thread run on every core again (a helper thread that
    /// inherited a pinned parent's affinity).
    pub(crate) fn release() -> bool {
        ALL.get().is_some_and(|all| set_affinity(all))
    }
}

#[cfg(target_os = "android")]
pub(crate) use android::{pin_to_big, release};

#[cfg(test)]
mod tests {
    use super::classify;

    #[test]
    fn big_little_cpus_pin_to_the_fast_cores() {
        // Two big cores and six little ones, capacities as the kernel reports them.
        let caps = [400, 400, 400, 400, 400, 400, 1024, 1024];
        assert_eq!(classify(&caps), Some(vec![6, 7]));
        // Prime, gold and silver clusters.
        let caps = [350, 350, 350, 350, 800, 800, 800, 1024];
        assert_eq!(classify(&caps), Some(vec![4, 5, 6, 7]));
    }

    #[test]
    fn alike_missing_or_lone_cores_are_left_alone() {
        assert_eq!(classify(&[1024; 8]), None);
        assert_eq!(classify(&[]), None);
        assert_eq!(classify(&[400, 400, 400, 0, 1024, 1024]), None);
        // A single fast core is not enough to pin two busy threads to.
        assert_eq!(classify(&[400, 400, 400, 1024]), None);
    }
}
