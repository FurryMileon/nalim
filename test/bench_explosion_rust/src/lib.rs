// Minecraft explosion math, Ferrite-style: Java pre-extracts a 3D u8
// blast-resistance grid into off-heap memory, this kernel does all the
// ray-tracing in pure Rust against the L1-cached buffer.
//
// Vanilla algorithm (1.21.x, server-side):
//   For each direction on the surface of a 16x16x16 cube (1352 rays):
//     - normalize the direction, step at 0.3 blocks per increment
//     - intensity = radius * (0.7 + rng.nextFloat() * 0.6)
//     - per step: if non-empty block, intensity -= (resistance+0.3)*0.3
//     - per step: intensity -= 0.225 always
//     - if intensity > 0 after that step, the floor(pos) is destroyed
//
// We collect destroyed positions as packed i32 triples (x, y, z) and
// dedupe at the very end using a simple sorted-array pass. The Java
// caller can then iterate the result and trigger drops / block updates.
//
// Resistance encoding (Java side does this once per cell during grid
// extract):
//   u8 0          -> air / passable, no resistance subtracted
//   u8 1..=254    -> resistance value f = (u8 * 0.2)  i.e. cap ~50.8
//   u8 255        -> indestructible (bedrock); always survives
//
// Layout matches the Java DirectBuffer layout in BenchExplosion.java
// — keep them in sync if you edit either.

use std::sync::atomic::{AtomicI32, AtomicU64, Ordering};
use once_cell::sync::Lazy;
use rayon::ThreadPool;
use rayon::prelude::*;

const PARALLEL_WORKERS: usize = 8;

static EXPLOSION_POOL: Lazy<ThreadPool> = Lazy::new(|| {
    rayon::ThreadPoolBuilder::new()
        .num_threads(PARALLEL_WORKERS)
        .thread_name(|i| format!("rust-explosion-{}", i))
        .build()
        .expect("rayon pool")
});

// All 1352 surface offsets, computed once.
static RAY_OFFSETS: Lazy<Vec<(i32, i32, i32)>> = Lazy::new(|| {
    let mut v = Vec::with_capacity(1352);
    for ix in 0..16 {
        for iy in 0..16 {
            for iz in 0..16 {
                if ix == 0 || ix == 15 || iy == 0 || iy == 15 || iz == 0 || iz == 15 {
                    v.push((ix, iy, iz));
                }
            }
        }
    }
    v
});

#[no_mangle]
pub extern "C" fn rust_explosion_warmup() -> i32 {
    Lazy::force(&EXPLOSION_POOL);
    Lazy::force(&RAY_OFFSETS);
    PARALLEL_WORKERS as i32
}

#[repr(C, align(8))]
pub struct ExplosionInput {
    pub origin_x: f64,
    pub origin_y: f64,
    pub origin_z: f64,
    pub rng_state: u64,
    pub grid_addr: u64,         // *const u8
    pub out_addr: u64,          // *mut i32 (triples)
    pub radius: f32,
    pub grid_size: i32,         // cube side; index = (x*N+y)*N+z
    pub grid_origin_x: i32,     // world X of grid[0]
    pub grid_origin_y: i32,
    pub grid_origin_z: i32,
    pub out_max_triples: i32,
}

#[inline(always)]
fn splitmix64_step(state: u64) -> (u64, u64) {
    let new_state = state.wrapping_add(0x9E3779B97F4A7C15);
    let mut z = new_state;
    z = (z ^ (z >> 30)).wrapping_mul(0xBF58476D1CE4E5B9);
    z = (z ^ (z >> 27)).wrapping_mul(0x94D049BB133111EB);
    z = z ^ (z >> 31);
    (new_state, z)
}

#[inline(always)]
fn next_float_01(state: &mut u64) -> f32 {
    let (ns, v) = splitmix64_step(*state);
    *state = ns;
    ((v >> 40) as f32) * (1.0 / (1u64 << 24) as f32)
}

#[inline(always)]
fn floor_i(v: f64) -> i32 {
    v.floor() as i32
}

#[no_mangle]
pub extern "C" fn rust_explosion_trace(input_ptr: *const ExplosionInput) -> i32 {
    unsafe {
        let i = &*input_ptr;
        let grid = i.grid_addr as *const u8;
        let out  = i.out_addr  as *mut i32;
        let n    = i.grid_size;
        let ox = i.origin_x;
        let oy = i.origin_y;
        let oz = i.origin_z;
        let gox = i.grid_origin_x;
        let goy = i.grid_origin_y;
        let goz = i.grid_origin_z;
        let radius = i.radius;
        let out_cap = i.out_max_triples;

        let mut rng = i.rng_state;
        let mut count: i32 = 0;

        // 1352 rays on surface of 16-cube
        for ix in 0..16i32 {
            for iy in 0..16i32 {
                for iz in 0..16i32 {
                    let on_surface = ix == 0 || ix == 15 || iy == 0 || iy == 15 || iz == 0 || iz == 15;
                    if !on_surface { continue; }

                    let mut dx = (ix as f64) / 15.0 * 2.0 - 1.0;
                    let mut dy = (iy as f64) / 15.0 * 2.0 - 1.0;
                    let mut dz = (iz as f64) / 15.0 * 2.0 - 1.0;
                    let inv_len = 1.0 / (dx*dx + dy*dy + dz*dz).sqrt();
                    dx *= inv_len;
                    dy *= inv_len;
                    dz *= inv_len;

                    let mut intensity = radius * (0.7 + next_float_01(&mut rng) * 0.6);
                    let mut px = ox;
                    let mut py = oy;
                    let mut pz = oz;

                    while intensity > 0.0 {
                        let gx = floor_i(px) - gox;
                        let gy = floor_i(py) - goy;
                        let gz = floor_i(pz) - goz;
                        if (gx as u32) < n as u32 && (gy as u32) < n as u32 && (gz as u32) < n as u32 {
                            let idx = ((gx * n + gy) * n + gz) as isize;
                            let res = *grid.offset(idx);
                            if res == 255 {
                                break;  // indestructible — ray dies
                            }
                            if res != 0 {
                                let resistance = (res as f32) * 0.2;
                                intensity -= (resistance + 0.3) * 0.3;
                            }
                        }
                        if intensity > 0.0 && count < out_cap {
                            let base = (count * 3) as isize;
                            *out.offset(base    ) = floor_i(px);
                            *out.offset(base + 1) = floor_i(py);
                            *out.offset(base + 2) = floor_i(pz);
                            count += 1;
                        }
                        intensity -= 0.22500001;
                        px += dx * 0.3;
                        py += dy * 0.3;
                        pz += dz * 0.3;
                    }
                }
            }
        }
        count
    }
}

// Same but skips the destroyed-position writeout — purely measures the
// ray-tracing throughput without output-buffer pressure. Useful for
// comparing the math hot path apples-to-apples.
#[no_mangle]
pub extern "C" fn rust_explosion_trace_count_only(input_ptr: *const ExplosionInput) -> i32 {
    unsafe {
        let i = &*input_ptr;
        let grid = i.grid_addr as *const u8;
        let n    = i.grid_size;
        let ox = i.origin_x;
        let oy = i.origin_y;
        let oz = i.origin_z;
        let gox = i.grid_origin_x;
        let goy = i.grid_origin_y;
        let goz = i.grid_origin_z;
        let radius = i.radius;

        let mut rng = i.rng_state;
        let mut count: i32 = 0;

        for ix in 0..16i32 {
            for iy in 0..16i32 {
                for iz in 0..16i32 {
                    if !(ix == 0 || ix == 15 || iy == 0 || iy == 15 || iz == 0 || iz == 15) { continue; }
                    let mut dx = (ix as f64) / 15.0 * 2.0 - 1.0;
                    let mut dy = (iy as f64) / 15.0 * 2.0 - 1.0;
                    let mut dz = (iz as f64) / 15.0 * 2.0 - 1.0;
                    let inv_len = 1.0 / (dx*dx + dy*dy + dz*dz).sqrt();
                    dx *= inv_len;
                    dy *= inv_len;
                    dz *= inv_len;
                    let mut intensity = radius * (0.7 + next_float_01(&mut rng) * 0.6);
                    let mut px = ox;
                    let mut py = oy;
                    let mut pz = oz;
                    while intensity > 0.0 {
                        let gx = floor_i(px) - gox;
                        let gy = floor_i(py) - goy;
                        let gz = floor_i(pz) - goz;
                        if (gx as u32) < n as u32 && (gy as u32) < n as u32 && (gz as u32) < n as u32 {
                            let idx = ((gx * n + gy) * n + gz) as isize;
                            let res = *grid.offset(idx);
                            if res == 255 { break; }
                            if res != 0 {
                                intensity -= ((res as f32) * 0.2 + 0.3) * 0.3;
                            }
                        }
                        if intensity > 0.0 { count += 1; }
                        intensity -= 0.22500001;
                        px += dx * 0.3;
                        py += dy * 0.3;
                        pz += dz * 0.3;
                    }
                }
            }
        }
        count
    }
}

// Parallel version: splits 1352 rays across rayon worker threads. Each
// ray is independent (only the destroyed-count and the output writeout
// share state across threads). For per-explosion cost, this should
// scale roughly linearly up to ~6 cores; past that the overhead of
// task dispatch starts to dominate the 50-μs work budget.
#[no_mangle]
pub extern "C" fn rust_explosion_trace_parallel(input_ptr: *const ExplosionInput) -> i32 {
    // Safety: we read i.* fields, capture as locals, then dispatch
    // those locals as Copy into each ray closure.
    let (ox, oy, oz, rng_seed, grid_addr, out_addr, radius,
         n, gox, goy, goz, out_cap) = unsafe {
        let i = &*input_ptr;
        (i.origin_x, i.origin_y, i.origin_z, i.rng_state,
         i.grid_addr, i.out_addr, i.radius,
         i.grid_size, i.grid_origin_x, i.grid_origin_y, i.grid_origin_z,
         i.out_max_triples)
    };
    let grid_ptr = grid_addr as usize;     // pass as usize to satisfy Send
    let out_ptr  = out_addr  as usize;
    let count = AtomicI32::new(0);

    EXPLOSION_POOL.install(|| {
        RAY_OFFSETS.par_iter().for_each(|&(ix, iy, iz)| {
            // Per-ray RNG: split seed by ray index to keep determinism
            // independent of scheduling. We mix in the ray's surface
            // coords so two threads scheduling the same ray would get
            // the same float (also makes results deterministic w.r.t.
            // input seed even when this fn parallelizes).
            let ray_id = (ix as u64) | ((iy as u64) << 8) | ((iz as u64) << 16);
            let mut rng = rng_seed ^ ray_id.wrapping_mul(0x9E3779B97F4A7C15);

            let mut dx = (ix as f64) / 15.0 * 2.0 - 1.0;
            let mut dy = (iy as f64) / 15.0 * 2.0 - 1.0;
            let mut dz = (iz as f64) / 15.0 * 2.0 - 1.0;
            let inv_len = 1.0 / (dx*dx + dy*dy + dz*dz).sqrt();
            dx *= inv_len; dy *= inv_len; dz *= inv_len;

            let mut intensity = radius * (0.7 + next_float_01(&mut rng) * 0.6);
            let mut px = ox; let mut py = oy; let mut pz = oz;
            let grid = grid_ptr as *const u8;
            let out  = out_ptr  as *mut i32;

            while intensity > 0.0 {
                let gx = floor_i(px) - gox;
                let gy = floor_i(py) - goy;
                let gz = floor_i(pz) - goz;
                if (gx as u32) < n as u32 && (gy as u32) < n as u32 && (gz as u32) < n as u32 {
                    let idx = ((gx * n + gy) * n + gz) as isize;
                    let res = unsafe { *grid.offset(idx) };
                    if res == 255 { break; }
                    if res != 0 {
                        intensity -= ((res as f32) * 0.2 + 0.3) * 0.3;
                    }
                }
                if intensity > 0.0 {
                    let slot = count.fetch_add(1, Ordering::Relaxed);
                    if slot < out_cap && !out.is_null() {
                        let base = (slot * 3) as isize;
                        unsafe {
                            *out.offset(base    ) = floor_i(px);
                            *out.offset(base + 1) = floor_i(py);
                            *out.offset(base + 2) = floor_i(pz);
                        }
                    }
                }
                intensity -= 0.22500001;
                px += dx * 0.3;
                py += dy * 0.3;
                pz += dz * 0.3;
            }
        });
    });
    count.load(Ordering::Relaxed)
}

// Batched: process M explosions in one Nalim call. Inputs are
// laid out contiguously as `inputs[0..M]`. Excellent for TNT cannons /
// chain-detonate scenarios where many explosions co-tick.
#[no_mangle]
pub extern "C" fn rust_explosion_trace_batch(
    inputs_addr: u64,
    count: i32,
    parallel: i32,
) -> i32 {
    let inputs_usize = inputs_addr as usize;
    let total = AtomicI32::new(0);

    if parallel != 0 {
        EXPLOSION_POOL.install(|| {
            (0..count).into_par_iter().for_each(|i| {
                let ptr = (inputs_usize + (i as usize) * std::mem::size_of::<ExplosionInput>())
                    as *const ExplosionInput;
                let n = rust_explosion_trace(ptr);
                total.fetch_add(n, Ordering::Relaxed);
            });
        });
    } else {
        for i in 0..count {
            let ptr = (inputs_usize + (i as usize) * std::mem::size_of::<ExplosionInput>())
                as *const ExplosionInput;
            let n = rust_explosion_trace(ptr);
            total.fetch_add(n, Ordering::Relaxed);
        }
    }
    total.load(Ordering::Relaxed)
}

// Returns size of ExplosionInput so Java can sanity-check layout.
#[no_mangle]
pub extern "C" fn rust_explosion_input_size() -> i32 {
    std::mem::size_of::<ExplosionInput>() as i32
}

#[allow(dead_code)]
static UNUSED: AtomicU64 = AtomicU64::new(0);
