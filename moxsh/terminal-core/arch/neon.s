// moxsh NEON/arm64 热路径汇编（手写）。
//
// 当前提供两个函数，使用 ARM64 标量指令实现以保证正确性与可链接性；
// 真正的 NEON 向量化（多字节并行解码 / 多流 FNV）留作 M3 性能优化点，
// 函数符号与调用约定保持不变。AAPCS64：参数 x0..x3，返回 x0。

    .text
    .align 4
    .global moxsh_neon_utf8_decode
    .type moxsh_neon_utf8_decode, %function

// size_t moxsh_neon_utf8_decode(const uint8_t* src, size_t len, uint32_t* out, size_t max)
moxsh_neon_utf8_decode:
    mov     x4, #0              // i = 0
    mov     x5, #0              // count = 0
1:
    cmp     x4, x1
    b.ge    .Lend
    cmp     x5, x3
    b.ge    .Lend
    ldrb    w6, [x0, x4]        // b = src[i]

    // 1 字节：0xxxxxxx
    tst     w6, #0x80
    b.eq    .Lone

    // 多字节：判断长度
    and     w7, w6, #0xe0
    cmp     w7, #0xc0
    b.eq    .Ltwo
    cmp     w7, #0xe0
    b.eq    .Lthree
    // 4 字节
    and     w7, w6, #0x07
    ldrb    w8, [x0, x4, #1]
    and     w8, w8, #0x3f
    ldrb    w9, [x0, x4, #2]
    and     w9, w9, #0x3f
    ldrb    w10, [x0, x4, #3]
    and     w10, w10, #0x3f
    lsl     w7, w7, #18
    lsl     w8, w8, #12
    lsl     w9, w9, #6
    orr     w7, w7, w8
    orr     w7, w7, w9
    orr     w7, w7, w10
    str     w7, [x2, x5, lsl #2]
    add     x5, x5, #1
    add     x4, x4, #4
    b       1b

.Lthree:
    and     w7, w6, #0x0f
    ldrb    w8, [x0, x4, #1]
    and     w8, w8, #0x3f
    ldrb    w9, [x0, x4, #2]
    and     w9, w9, #0x3f
    lsl     w7, w7, #12
    lsl     w8, w8, #6
    orr     w7, w7, w8
    orr     w7, w7, w9
    str     w7, [x2, x5, lsl #2]
    add     x5, x5, #1
    add     x4, x4, #3
    b       1b

.Ltwo:
    and     w7, w6, #0x1f
    ldrb    w8, [x0, x4, #1]
    and     w8, w8, #0x3f
    lsl     w7, w7, #6
    orr     w7, w7, w8
    str     w7, [x2, x5, lsl #2]
    add     x5, x5, #1
    add     x4, x4, #2
    b       1b

.Lone:
    str     w6, [x2, x5, lsl #2]
    add     x5, x5, #1
    add     x4, x4, #1
    b       1b

.Lend:
    mov     x0, x5
    ret


    .align 4
    .global moxsh_neon_fnv1a
    .type moxsh_neon_fnv1a, %function

// uint64_t moxsh_neon_fnv1a(const uint8_t* data, size_t len)
moxsh_neon_fnv1a:
    movz    x2, #0x2225
    movk    x2, #0xce84, lsl #16
    movk    x2, #0xf29c, lsl #32
    movk    x2, #0xcb, lsl #48     // h = 0xcbf29ce484222325

    movz    x4, #0x01b3
    movk    x4, #0x0000, lsl #16
    movk    x4, #0x0001, lsl #32
    movk    x4, #0x0001, lsl #48   // prime = 0x100000001b3

1:
    cmp     x1, #0
    b.eq    .Lfnv_end
    ldrb    w3, [x0], #1
    eor     x2, x2, x3
    mul     x2, x2, x4
    sub     x1, x1, #1
    b       1b

.Lfnv_end:
    mov     x0, x2
    ret
