// Hand-written encodings for the extended auto-inline decoder — one tiny leaf
// per accepted instruction class, immune to compiler mood. System V AMD64.
// Build: gcc -shared -o libnalim_fx_asm.so nalim_fx_asm.S
//
// Mirrored bit-exactly in InlineFixturesTest.java. Every function here MUST
// be accepted by the inliner (the test asserts the inline counter).

    .intel_syntax noprefix
    .text

#define FUNC(name)      \
    .globl name;        \
    .type name, @function; \
    .p2align 4;         \
name:

// jmp rel8 forward over a garbage hole (never executed, still copied).
FUNC(fxa_jmp_fwd)               // (rdi: long) -> long = rdi+1 via detour
    jmp 1f
    .byte 0x0f, 0x0b            // ud2 — garbage in the gap
    .byte 0xcc, 0xcc
1:  lea rax, [rdi+1]
    ret

// Explicit backward loop: dec/jnz.
FUNC(fxa_loop_back)             // (rdi: n>=1) -> sum 1..n
    xor eax, eax
2:  add rax, rdi
    dec rdi
    jnz 2b
    ret

// rep stosq — fill rdi with rcx quadwords of rax... args: rdi=ptr, rsi=count, rdx=value
FUNC(fxa_rep_stos)              // (u64* p, long n, u64 v)
    mov rcx, rsi
    mov rax, rdx
    rep stosq
    mov rax, rsi
    ret

// rep movsb — copy rdx bytes rsi->rdi.
FUNC(fxa_rep_movs)              // (u8* dst, const u8* src, long n)
    mov rcx, rdx
    rep movsb
    mov rax, rdx
    ret

// bswap.
FUNC(fxa_bswap)                 // (rdi) -> byte-reversed
    mov rax, rdi
    bswap rax
    ret

// shld/shrd imm and cl forms.
FUNC(fxa_shld)                  // (rdi, rsi) -> ((rdi:rsi) << 13).hi
    mov rax, rdi
    shld rax, rsi, 13
    ret

FUNC(fxa_shrd_cl)               // (rdi, rsi, rdx=count)
    mov rcx, rdx
    mov rax, rdi
    shrd rax, rsi, cl
    ret

// inc/dec register and memory forms (grp4/5 ext 0/1).
FUNC(fxa_inc_dec)               // (rdi, rsi: u64* scratch)
    mov rax, rdi
    inc rax
    dec qword ptr [rsi]
    inc dword ptr [rsi+8]
    dec rax
    inc rax
    ret

// 16-bit ops: loads, stores, ALU, imm16 forms.
FUNC(fxa_16bit)                 // (rdi: u16* p, esi: v) -> u16 result
    movzx eax, word ptr [rdi]
    add ax, si                  // 66 ALU reg-reg
    add ax, word ptr [rdi+2]    // 66 ALU load
    mov word ptr [rdi+4], ax    // 66 store
    add word ptr [rdi+4], 7     // 66 83 RMW imm8
    mov word ptr [rdi+6], 0x1234 // 66 C7 imm16
    cmp ax, 0x7FFF              // 66 3D acc-imm16... NOTE: acc-imm forms take imm16 with 66
    movzx eax, word ptr [rdi+4]
    ret

// Multi-prefix alignment nops, reachable: 66 2E 0F 1F + 66 90 + F3 90 (pause).
FUNC(fxa_padded_nop)            // (rdi) -> rdi*2
    .byte 0x66, 0x2e, 0x0f, 0x1f, 0x84, 0x00, 0x00, 0x00, 0x00, 0x00
    .byte 0x66, 0x90
    pause
    lea rax, [rdi+rdi]
    ret

// ── VEX scalar float (SysV: xmm0=a, xmm1=b) ────────────────────────────
FUNC(fxa_vex_scalar)            // (float a, float b) -> (a+b)*a-b
    vaddss xmm2, xmm0, xmm1
    vmulss xmm2, xmm2, xmm0
    vsubss xmm0, xmm2, xmm1
    ret

FUNC(fxa_fma)                   // (double a, double b, double c) -> a*b+c
    vfmadd213sd xmm0, xmm1, xmm2
    ret

FUNC(fxa_vround)                // (double x) -> floor(x)
    vroundsd xmm0, xmm0, xmm0, 9
    ret

// VEX with RIP-relative scalar literal (tests literal relocation under VEX).
FUNC(fxa_vex_lit)               // (double x) -> x*2.5 + 0.125
    vmulsd xmm0, xmm0, qword ptr [rip + .Lc25]
    vaddsd xmm0, xmm0, qword ptr [rip + .Lc0125]
    ret

// ── BMI1/BMI2 integer ops ──────────────────────────────────────────────
FUNC(fxa_bmi)                   // (rdi=x, rsi=y) -> mix
    andn rax, rdi, rsi          // rax = ~rdi & rsi
    shlx rcx, rdi, rsi
    shrx rdx, rdi, rsi
    xor rax, rcx
    xor rax, rdx
    rorx rcx, rdi, 17
    xor rax, rcx
    blsr rcx, rdi
    xor rax, rcx
    blsi rcx, rsi
    xor rax, rcx
    blsmsk rcx, rdi
    xor rax, rcx
    bzhi rcx, rdi, rsi
    xor rax, rcx
    pdep rcx, rdi, rsi
    xor rax, rcx
    pext rcx, rdi, rsi
    xor rax, rcx
    ret

FUNC(fxa_mulx)                  // (rdi, rsi) -> high64(rdi*rsi) ^ low64
    mov rdx, rdi
    mulx rax, rcx, rsi          // rax=hi, rcx=lo
    xor rax, rcx
    ret

FUNC(fxa_popcnts)               // (rdi) -> popcnt + (lzcnt<<8) + (tzcnt<<16)
    popcnt rax, rdi
    lzcnt rcx, rdi
    shl rcx, 8
    add rax, rcx
    tzcnt rcx, rdi
    shl rcx, 16
    add rax, rcx
    ret

FUNC(fxa_bsf_bsr)               // (rdi != 0) -> bsf | (bsr<<8)
    bsf rax, rdi
    bsr rcx, rdi
    shl rcx, 8
    or  rax, rcx
    ret

// ── Legacy SSE2 integer / SSE4.1 ───────────────────────────────────────
FUNC(fxa_sse2_int)              // (rdi: const u64* p16bytes) -> u64
    movdqu xmm0, [rdi]          // F3 0F 6F
    movdqa xmm1, xmm0           // 66 0F 6F reg
    paddq  xmm1, xmm0           // 66 0F D4
    psllq  xmm1, 3              // 66 0F 73 /6 imm
    pxor   xmm1, xmm0           // 66 0F EF
    pshufd xmm2, xmm1, 0x4E     // 66 0F 70 imm
    paddq  xmm1, xmm2
    movq   rax, xmm1            // 66 REX.W 0F 7E reg
    ret

FUNC(fxa_pmulld)                // (rdi: const i32* p) -> i32 (p[0]*p[1] via SIMD)
    movdqu xmm0, [rdi]
    pshufd xmm1, xmm0, 0x39
    pmulld xmm0, xmm1           // 66 0F 38 40
    movd eax, xmm0
    ret

FUNC(fxa_pshufb)                // (rdi: const u8* p16) -> reversed-bytes low u64
    movdqu xmm0, [rdi]
    movdqa xmm1, [rip + .Lrev]  // 66 0F 6F RIP literal (16B aligned pool)
    pshufb xmm0, xmm1           // 66 0F 38 00
    movq rax, xmm0
    ret

FUNC(fxa_roundsd_legacy)        // (double x) -> ceil(x)
    roundsd xmm0, xmm0, 10      // 66 0F 3A 0B imm
    ret

// ── AVX2 ymm with strict-aligned 32-byte RIP literal + vzeroupper ──────
FUNC(fxa_ymm_lit)               // (rdi: const i64* p4) -> sum(p4[i] + K[i])
    vmovdqu ymm0, [rdi]
    vpaddq ymm0, ymm0, [rip + .LK32]   // 32B literal via arith mem operand
    vextracti128 xmm1, ymm0, 1
    vpaddq xmm0, xmm0, xmm1
    vpshufd xmm1, xmm0, 0x4E
    vpaddq xmm0, xmm0, xmm1
    vmovq rax, xmm0
    vzeroupper
    ret

FUNC(fxa_ymm_aligned)           // (rdi: const i64* p4) -> p4[0]+K2[0] etc, vmovdqa literal
    vmovdqa ymm1, [rip + .LK32b]       // strict 32B-aligned load from pool
    vpaddq ymm0, ymm1, [rdi]
    vextracti128 xmm1, ymm0, 1
    vpaddq xmm0, xmm0, xmm1
    vpshufd xmm1, xmm0, 0x4E
    vpaddq xmm0, xmm0, xmm1
    vmovq rax, xmm0
    vzeroupper
    ret

// Narrow return (byte) with a loop and single terminal ret.
FUNC(fxa_parity8)               // (rdi, rsi=n>=1) -> parity byte of rdi over n rounds
    xor eax, eax
3:  xor rax, rdi
    ror rdi, 8                  // keep mixing... ROR r/m,imm = C1 /1
    dec rsi
    jnz 3b
    and eax, 1
    ret

// Two independent rets (wide return type) — multi-ret CFG.
FUNC(fxa_two_rets)              // (rdi) -> rdi>0 ? rdi*2 : -rdi
    test rdi, rdi
    jle 4f
    lea rax, [rdi+rdi]
    ret
4:  mov rax, rdi
    neg rax
    ret

// cmov + setcc regression (previously supported — keep verified).
FUNC(fxa_cmov_set)              // (rdi, rsi) -> max + (rdi==rsi)
    cmp rdi, rsi
    mov rax, rsi
    cmovge rax, rdi
    sete cl
    movzx ecx, cl
    add rax, rcx
    ret

    .section .rodata
    .p2align 5
.LK32:
    .quad 0x1111111111111111, 0x2222222222222222, 0x3333333333333333, 0x4444444444444444
    .p2align 5
.LK32b:
    .quad 0x0102030405060708, 0x1112131415161718, 0x2122232425262728, 0x3132333435363738
    .p2align 4
.Lrev:
    .byte 7,6,5,4,3,2,1,0,15,14,13,12,11,10,9,8
.Lc25:
    .double 2.5
.Lc0125:
    .double 0.125

    .section .note.GNU-stack,"",@progbits
