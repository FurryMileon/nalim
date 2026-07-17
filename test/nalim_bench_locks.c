// Lock implementations callable from Java via Nalim — compares the cost
// of moving a `synchronized` Java block into Rust/C. Spoiler: it does
// not pay off, because the lock hardware cost is the same in both
// languages and Java's `synchronized` is intrinsified.
//
// Compile: gcc -O2 -shared -fPIC -o libnalim_bench_locks.so \
//              -pthread nalim_bench_locks.c

#include <stdint.h>
#include <stdatomic.h>
#include <pthread.h>

// ── pthread_mutex variant: uncontended lock+work+unlock ────────────────
static pthread_mutex_t pmtx = PTHREAD_MUTEX_INITIALIZER;

int pmutex_add(int a, int b) {
    pthread_mutex_lock(&pmtx);
    int r = a + b;
    pthread_mutex_unlock(&pmtx);
    return r;
}

// ── C11 atomic_flag spin lock (closer to JVM's lightweight lock) ───────
static atomic_flag spin = ATOMIC_FLAG_INIT;

int spinlock_add(int a, int b) {
    while (atomic_flag_test_and_set_explicit(&spin, memory_order_acquire)) {
        // spin — uncontended path takes this iteration once at most
    }
    int r = a + b;
    atomic_flag_clear_explicit(&spin, memory_order_release);
    return r;
}

// ── seqlock-style RMW: even cheaper than a real lock, but doesn't
//    actually protect a critical section under contention (just for
//    overhead comparison). ─────────────────────────────────────────────
static atomic_int seq = 0;

int rmw_add(int a, int b) {
    atomic_fetch_add_explicit(&seq, 1, memory_order_acq_rel);
    int r = a + b;
    atomic_fetch_add_explicit(&seq, 1, memory_order_acq_rel);
    return r;
}
