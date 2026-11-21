/* Exact SysV code shapes; build together with nalim_mem.c for its real long accessors. */
.text
.macro leaf name
.p2align 4
.globl \name
.type \name,@function
\name:
.endm
.macro done name
ret
.size \name,.-\name
.endm

leaf ml_load_previous
movl -4(%rdi), %eax
done ml_load_previous

leaf ml_store_previous
movl %esi, -4(%rdi)
done ml_store_previous

leaf ml_load_far
movl 129(%rdi), %eax
done ml_load_far

leaf ml_store_far
.byte 0xf3, 0x0f, 0x1e, 0xfa   /* ENDBR64 */
movl %esi, 129(%rdi)
done ml_store_far

/* Near misses: copying just the first MOV would lose observable effects,
 * or use the wrong address for an indexed/RIP-relative memory access. */
leaf ml_store_twice
movl %esi, (%rdi)
movl %esi, 4(%rdi)
done ml_store_twice

leaf ml_load_index
movl (%rdi,%rsi,4), %eax
done ml_load_index

leaf ml_load_literal
movl .Lconstant(%rip), %eax
done ml_load_literal

.section .rodata
.p2align 2
.Lconstant:
.long 0x23456789

.data
.p2align 3
.globl ml_test_buffer
.type ml_test_buffer,@object
ml_test_buffer:
.quad 0x1122334455667788, 0x0123456789abcdef, 0x7766554433221100
.size ml_test_buffer,.-ml_test_buffer
.section .note.GNU-stack,"",@progbits
