#ifndef VOANY_LWIP_H
#define VOANY_LWIP_H
#include <stddef.h>
#include <stdbool.h>
typedef void (*voany_output_fn)(const void *,size_t);
bool voany_lwip_start(const void *ipv4,const void *ipv6,int mtu,voany_output_fn output);
void voany_lwip_stop(void);
void voany_lwip_input(const void *packet,size_t length);
#endif
