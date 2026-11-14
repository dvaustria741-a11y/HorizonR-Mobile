use std::{process::ExitCode, time::Instant};

use jvm_boot_spike::{current_rss_kib, Jvm, JvmConfig};

const USAGE: &str = "usage: jvm-boot <path to libjvm.so> [--heap-mib N]\n\
The path is not guessed. On a device it is wherever the bundled runtime keeps libjvm.so (unknown here).";

fn main() -> ExitCode {
    let mut config = JvmConfig { heap_mib: 128, ..Default::default() };
    let mut args = std::env::args().skip(1);
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--heap-mib" => match args.next().and_then(|v| v.parse().ok()) {
                Some(n) => config.heap_mib = n,
                None => return usage_error(),
            },
            _ if config.libjvm.is_none() && !arg.starts_with('-') => config.libjvm = Some(arg.into()),
            _ => return usage_error(),
        }
    }
    if config.libjvm.is_none() {
        return usage_error();
    }
    let rss_before = current_rss_kib();
    let started = Instant::now();
    let jvm = match Jvm::boot(&config) {
        Ok(jvm) => jvm,
        Err(e) => {
            eprintln!("boot failed: {e}");
            return ExitCode::from(1);
        }
    };
    let boot_ms = started.elapsed().as_millis();
    let rss_after = current_rss_kib();
    println!("boot_ms={boot_ms}");
    println!("rss_kib_before={rss_before:?} rss_kib_after={rss_after:?}");
    for key in ["java.version", "java.vm.name", "java.home"] {
        match jvm.system_property(key) {
            Ok(value) => println!("{key}={value:?}"),
            Err(e) => {
                eprintln!("call failed for {key}: {e}");
                return ExitCode::from(1);
            }
        }
    }
    ExitCode::SUCCESS
}

fn usage_error() -> ExitCode {
    eprintln!("{USAGE}");
    ExitCode::from(2)
}
