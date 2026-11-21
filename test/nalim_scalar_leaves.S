// These names intentionally say nothing about the function. Only the body is evidence.
    .intel_syntax noprefix
    .text
#define FUNC(s) .p2align 4; .globl s; .type s,@function; s:
#define END(s) ret; .size s, .-s
#define BINARY(s, op) \
    FUNC(s##_i) mov eax,edi; op eax,esi; END(s##_i); \
    FUNC(s##_l) mov rax,rdi; op rax,rsi; END(s##_l)
#define UNARY(s, op) \
    FUNC(s##_i) mov eax,edi; op eax; END(s##_i); \
    FUNC(s##_l) mov rax,rdi; op rax; END(s##_l)

// Exercise both equivalent SIB byte orders (37 in gcc's raw_add, 3e here).
FUNC(sl_amber_i) .byte 0x8d,0x04,0x3e; END(sl_amber_i)
FUNC(sl_amber_l) lea rax,[rdi+rsi]; END(sl_amber_l)
FUNC(sl_umber_i) endbr64; mov eax,edi; neg eax; cmovs eax,edi; END(sl_umber_i)
FUNC(sl_umber_l) endbr64; mov rax,rdi; neg rax; cmovs rax,rdi; END(sl_umber_l)
BINARY(sl_ochre, sub)
BINARY(sl_jade, imul)
BINARY(sl_pearl, and)
BINARY(sl_indigo, or)
BINARY(sl_coral, xor)
UNARY(sl_ivory, neg)
UNARY(sl_slate, not)
FUNC(sl_ruby_i) mov eax,edi; END(sl_ruby_i)
    ud2 // Unreachable, even if returned in the readable window.
FUNC(sl_ruby_l) mov rax,rdi; END(sl_ruby_l)

// Nearly identical bodies; changes to ModRM/SIB, condition code, operand width,
// destination register, or RET position must not be treated as don't-care bits.
FUNC(sl_read) mov eax,[rdi+rsi]; END(sl_read)
FUNC(sl_scale) lea eax,[rdi+rsi*2]; END(sl_scale)
FUNC(sl_select) mov eax,edi; neg eax; cmovns eax,edi; END(sl_select)
FUNC(sl_mixed) mov eax,edi; neg rax; END(sl_mixed)
FUNC(sl_extend) movsxd rax,edi; END(sl_extend)
FUNC(sl_damage) mov eax,edi; mov ebx,eax; END(sl_damage)
FUNC(sl_trap) mov eax,edi; ud2; END(sl_trap)
FUNC(sl_double) endbr64; endbr64; mov eax,edi; END(sl_double)
FUNC(sl_late) lea eax,[rdi+rsi]; add eax,19; END(sl_late)
FUNC(sl_counted) lea eax,[rdi+rsi]; inc dword ptr [rip+.Lcount]; END(sl_counted)
FUNC(sl_count) mov eax,[rip+.Lcount]; END(sl_count)
    .data
    .p2align 2
.Lcount: .long 0
    .text
    .p2align 12
    .fill 4093,1,0x90
    .globl sl_page_end
    .type sl_page_end,@function
sl_page_end: mov eax,edi; ret
    .size sl_page_end, .-sl_page_end
    .fill 4094,1,0x90
    .globl sl_page_cross
    .type sl_page_cross,@function
sl_page_cross: mov eax,edi; ret
    .size sl_page_cross, .-sl_page_cross
    .section .rodata
    .globl sl_signature_bytes
    .type sl_signature_bytes,@object
sl_signature_bytes: .byte 0x89, 0xf8, 0xc3
    .size sl_signature_bytes, .-sl_signature_bytes
    .section .note.GNU-stack,"",@progbits
