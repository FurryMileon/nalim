// Rust-side mirror of Async15's ParallelProcessor pattern: a thread pool
// that accepts fan-out tasks and awaits all of them per "tick".
//
// Uses rayon's work-stealing pool — the same algorithmic class as Java's
// ForkJoinPool, so this is an apples-to-apples comparison of language
// & runtime overhead at the orchestration layer.
//
// All exports are `extern "C"` so Nalim can @Link them from Java without
// JNI bookkeeping.

use once_cell::sync::Lazy;
use rayon::{ThreadPool, ThreadPoolBuilder};
use std::sync::atomic::{AtomicU64, Ordering};

const WORKERS: usize = 16;

static POOL: Lazy<ThreadPool> = Lazy::new(|| {
    ThreadPoolBuilder::new()
        .num_threads(WORKERS)
        .thread_name(|i| format!("rust-bench-pool-{}", i))
        .build()
        .expect("rayon pool")
});

// Tell Java how many workers we configured. Useful for log lines.
#[no_mangle]
pub extern "C" fn rust_pool_workers() -> i32 {
    WORKERS as i32
}

// Force pool eager init so the first measured tick doesn't pay the
// one-shot spawn cost.
#[no_mangle]
pub extern "C" fn rust_pool_warmup() -> i32 {
    POOL.scope(|s| {
        for _ in 0..WORKERS {
            s.spawn(|_| {
                std::hint::black_box(0u64);
            });
        }
    });
    1
}

// Fan out `task_count` empty tasks and await all. Measures pure
// orchestration overhead of a Rust work-stealing pool.
#[no_mangle]
pub extern "C" fn rust_empty_tick(task_count: i32) -> i32 {
    POOL.scope(|s| {
        for _ in 0..task_count {
            s.spawn(|_| {});
        }
    });
    task_count
}

// Fan out `task_count` tasks, each doing `work_per_task` sin() ops.
// Returns an arbitrary reduction of the results so the optimizer cannot
// eliminate the work.
#[no_mangle]
pub extern "C" fn rust_pool_tick(task_count: i32, work_per_task: i32) -> i64 {
    let sink = AtomicU64::new(0);
    POOL.scope(|s| {
        for seed in 0..task_count {
            let sink = &sink;
            s.spawn(move |_| {
                let mut acc: f64 = 0.0;
                for i in 0..work_per_task {
                    acc += ((seed as f64) + (i as f64) * 0.01).sin();
                }
                sink.fetch_xor(acc.to_bits(), Ordering::Relaxed);
            });
        }
    });
    sink.load(Ordering::Relaxed) as i64
}

// Same as rust_pool_tick but uses rayon's parallel iterator — internally
// rayon may split work-ranges differently, less per-task overhead because
// it avoids per-task closure heap allocation.
#[no_mangle]
pub extern "C" fn rust_pool_tick_iter(task_count: i32, work_per_task: i32) -> i64 {
    use rayon::prelude::*;
    let sink = AtomicU64::new(0);
    POOL.install(|| {
        (0..task_count).into_par_iter().for_each(|seed| {
            let mut acc: f64 = 0.0;
            for i in 0..work_per_task {
                acc += ((seed as f64) + (i as f64) * 0.01).sin();
            }
            sink.fetch_xor(acc.to_bits(), Ordering::Relaxed);
        });
    });
    sink.load(Ordering::Relaxed) as i64
}
