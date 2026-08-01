#ifdef TERMINAL_MULTICALL_CRT
__asm__(
".text \n"
".global " START "\n"
".type " START ",%function\n"
START ":\n"
".weak terminal_loader_entry\n"
" movz w2, #0x544c\n"
" movk w2, #0x574c, lsl #16\n"
" cmp w1, w2\n"
" b.eq terminal_loader_entry\n"
" mov x29, #0\n"
" mov x30, #0\n"
" mov x0, sp\n"
".weak _DYNAMIC\n"
".hidden _DYNAMIC\n"
" adrp x1, _DYNAMIC\n"
" add x1, x1, #:lo12:_DYNAMIC\n"
" and sp, x0, #-16\n"
" b " START "_c\n"
".size " START ", .-" START "\n"
);
#else
__asm__(
".text \n"
".global " START "\n"
".type " START ",%function\n"
START ":\n"
" mov x29, #0\n"
" mov x30, #0\n"
" mov x0, sp\n"
".weak _DYNAMIC\n"
".hidden _DYNAMIC\n"
" adrp x1, _DYNAMIC\n"
" add x1, x1, #:lo12:_DYNAMIC\n"
" and sp, x0, #-16\n"
" b " START "_c\n"
);
#endif

#define CRTJMP(pc,sp) __asm__ __volatile__( \
	"mov sp,%1 ; br %0" : : "r"(pc), "r"(sp) : "memory" )
