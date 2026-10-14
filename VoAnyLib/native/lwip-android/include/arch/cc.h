#ifndef VOANY_LWIP_CC_H
#define VOANY_LWIP_CC_H
#include <stdint.h>
#include <stdlib.h>
#include <stdio.h>
#include <errno.h>
#include <sys/time.h>
#define LWIP_UNIX_ANDROID 1
#define LWIP_TIMEVAL_PRIVATE 0
#define LWIP_ERRNO_STDINCLUDE 1
#define LWIP_ERRNO_INCLUDE <errno.h>
#define LWIP_RAND() arc4random()
#define LWIP_DONT_PROVIDE_BYTEORDER_FUNCTIONS 1
typedef unsigned int sys_prot_t;
#endif
