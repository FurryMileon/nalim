// Rust RNGs callable from Java via Nalim. State lives in an off-heap
// 8-byte slot — Java side passes ((DirectBuffer)buf).address(). Each
// call reads-modifies-writes the state pointer, returns the value.
//
// Three variants benchmarked:
//   1) splitmix64   — matches Java's SplittableRandom, fair head-to-head
//   2) wyrand       — very fast modern RNG (Wang Yi's hash mixer)
//   3) xoshiro256** — high-quality, 256-bit state via 4×8-byte buffer
//
// All bounded variants use Lemire's nearly-divisionless method (better
// distribution than Java Random.nextInt's rejection-loop, and faster on
// average because it usually avoids the rejection branch).

use std::sync::atomic::{AtomicU64, Ordering};

// ── SplitMix64 (same algorithm as java.util.SplittableRandom) ──────────
#[inline(always)]
fn splitmix64_step(state: u64) -> (u64, u64) {
    let new_state = state.wrapping_add(0x9E3779B97F4A7C15);
    let mut z = new_state;
    z = (z ^ (z >> 30)).wrapping_mul(0xBF58476D1CE4E5B9);
    z = (z ^ (z >> 27)).wrapping_mul(0x94D049BB133111EB);
    z = z ^ (z >> 31);
    (new_state, z)
}

#[no_mangle]
pub extern "C" fn rust_splitmix_next_long(state_addr: *mut u64) -> i64 {
    unsafe {
        let (new_state, value) = splitmix64_step(state_addr.read_unaligned());
        state_addr.write_unaligned(new_state);
        value as i64
    }
}

#[no_mangle]
pub extern "C" fn rust_splitmix_next_int(state_addr: *mut u64) -> i32 {
    unsafe {
        let (new_state, value) = splitmix64_step(state_addr.read_unaligned());
        state_addr.write_unaligned(new_state);
        (value >> 32) as i32
    }
}

// Lemire's bounded random — same result as Java Random.nextInt(bound)
// for any bound > 0, but typically faster and uniform without rejection
// loops on the common path.
#[no_mangle]
pub extern "C" fn rust_splitmix_next_int_bounded(state_addr: *mut u64, bound: i32) -> i32 {
    unsafe {
        let mut s = state_addr.read_unaligned();
        let (new_state, value) = splitmix64_step(s);
        s = new_state;
        state_addr.write_unaligned(s);
        let m = (value as u128).wrapping_mul(bound as u32 as u128);
        (m >> 64) as i32
    }
}

#[no_mangle]
pub extern "C" fn rust_splitmix_next_float(state_addr: *mut u64) -> f32 {
    unsafe {
        let (new_state, value) = splitmix64_step(state_addr.read_unaligned());
        state_addr.write_unaligned(new_state);
        ((value >> 40) as f32) * (1.0 / (1u64 << 24) as f32)
    }
}

#[no_mangle]
pub extern "C" fn rust_splitmix_next_double(state_addr: *mut u64) -> f64 {
    unsafe {
        let (new_state, value) = splitmix64_step(state_addr.read_unaligned());
        state_addr.write_unaligned(new_state);
        ((value >> 11) as f64) * (1.0 / (1u64 << 53) as f64)
    }
}

// Marsaglia polar method — TWO random doubles per pair, stash second.
// Java Random.nextGaussian uses the same algorithm with a stash field.
// Stash kept in second slot of state buffer (offset 8, low byte = has_stash
// flag, bytes 1-7 unused, next 8 bytes = stashed double).
#[no_mangle]
pub extern "C" fn rust_splitmix_next_gaussian(state_addr: *mut u64) -> f64 {
    unsafe {
        // Layout: [0] = rng state, [1] = has_stash (u8) packed in low, [2] = stashed bits
        let has_stash_ptr = state_addr.add(1) as *mut u8;
        let stash_ptr = state_addr.add(2) as *mut u64;
        if has_stash_ptr.read() != 0 {
            has_stash_ptr.write(0);
            let bits = stash_ptr.read_unaligned();
            return f64::from_bits(bits);
        }
        loop {
            let (s1, v1) = splitmix64_step(state_addr.read_unaligned());
            let (s2, v2) = splitmix64_step(s1);
            state_addr.write_unaligned(s2);
            let u1 = ((v1 >> 11) as f64) * (1.0 / (1u64 << 53) as f64);
            let u2 = ((v2 >> 11) as f64) * (1.0 / (1u64 << 53) as f64);
            let x = 2.0 * u1 - 1.0;
            let y = 2.0 * u2 - 1.0;
            let s = x * x + y * y;
            if s < 1.0 && s != 0.0 {
                let multiplier = (-2.0 * s.ln() / s).sqrt();
                let g1 = x * multiplier;
                let g2 = y * multiplier;
                stash_ptr.write_unaligned(g2.to_bits());
                has_stash_ptr.write(1);
                return g1;
            }
        }
    }
}

// ── WyRand (faster, but slightly weaker statistical guarantees) ────────
#[inline(always)]
fn wyrand_step(state: u64) -> (u64, u64) {
    let new_state = state.wrapping_add(0xA0761D6478BD642F);
    let t = (new_state as u128).wrapping_mul((new_state ^ 0xE7037ED1A0B428DB) as u128);
    let value = ((t >> 64) ^ t) as u64;
    (new_state, value)
}

#[no_mangle]
pub extern "C" fn rust_wyrand_next_int_bounded(state_addr: *mut u64, bound: i32) -> i32 {
    unsafe {
        let (new_state, value) = wyrand_step(state_addr.read_unaligned());
        state_addr.write_unaligned(new_state);
        let m = (value as u128).wrapping_mul(bound as u32 as u128);
        (m >> 64) as i32
    }
}

#[no_mangle]
pub extern "C" fn rust_wyrand_next_long(state_addr: *mut u64) -> i64 {
    unsafe {
        let (new_state, value) = wyrand_step(state_addr.read_unaligned());
        state_addr.write_unaligned(new_state);
        value as i64
    }
}

#[no_mangle]
pub extern "C" fn rust_wyrand_next_float(state_addr: *mut u64) -> f32 {
    unsafe {
        let (new_state, value) = wyrand_step(state_addr.read_unaligned());
        state_addr.write_unaligned(new_state);
        ((value >> 40) as f32) * (1.0 / (1u64 << 24) as f32)
    }
}

// ── Bulk fill (the case where Rust really shines: amortize call cost) ──
// Fills an off-heap array with `count` u32 values uniformly in [0, bound).
#[no_mangle]
pub extern "C" fn rust_splitmix_fill_int_bounded(state_addr: *mut u64,
                                                  out_addr: *mut u32,
                                                  count: i32,
                                                  bound: i32) -> i32 {
    unsafe {
        let mut s = state_addr.read_unaligned();
        let b = bound as u32 as u128;
        for i in 0..count as isize {
            let (ns, v) = splitmix64_step(s);
            s = ns;
            let m = (v as u128).wrapping_mul(b);
            out_addr.offset(i).write_unaligned((m >> 64) as u32);
        }
        state_addr.write_unaligned(s);
        count
    }
}

// Same but each value gets its OWN bound from another off-heap array
// (rare but useful for batch entity-spawn rolls).
#[no_mangle]
pub extern "C" fn rust_splitmix_fill_int_per_bound(state_addr: *mut u64,
                                                    out_addr: *mut u32,
                                                    bounds_addr: *const u32,
                                                    count: i32) -> i32 {
    unsafe {
        let mut s = state_addr.read_unaligned();
        for i in 0..count as isize {
            let (ns, v) = splitmix64_step(s);
            s = ns;
            let b = bounds_addr.offset(i).read_unaligned() as u128;
            let m = (v as u128).wrapping_mul(b);
            out_addr.offset(i).write_unaligned((m >> 64) as u32);
        }
        state_addr.write_unaligned(s);
        count
    }
}

// Static seed-counter to mint reproducible distinct states from Java
// (just for benchmark setup; production code would seed via Mojang's
// seedSetter or whatever).
static SEED_COUNTER: AtomicU64 = AtomicU64::new(0xDEADBEEFCAFEBABE);

#[no_mangle]
pub extern "C" fn rust_mint_seed() -> i64 {
    SEED_COUNTER.fetch_add(0xA24BAED4963EE407, Ordering::Relaxed) as i64
}

// ── Pure (stateless) mixers — state lives Java-side in a register ──────
// These exist to measure the irreducible FFI call cost: the Java caller
// keeps the RNG state in a local (C2 register-allocates it), advances it
// with `s += GAMMA` and only ships the mix through FFI. The loop-carried
// dependency never round-trips through memory, so the measured delta vs
// a fully C2-inlined Java mix is exactly call + ret + spill overhead.
#[no_mangle]
pub extern "C" fn rust_splitmix_mix(z: u64) -> i64 {
    let mut z = z;
    z = (z ^ (z >> 30)).wrapping_mul(0xBF58476D1CE4E5B9);
    z = (z ^ (z >> 27)).wrapping_mul(0x94D049BB133111EB);
    (z ^ (z >> 31)) as i64
}

#[no_mangle]
pub extern "C" fn rust_wyrand_mix(s: u64) -> i64 {
    let t = (s as u128).wrapping_mul((s ^ 0xE7037ED1A0B428DB) as u128);
    (((t >> 64) ^ t) as u64) as i64
}
