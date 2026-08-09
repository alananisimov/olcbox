#ifndef Tun2SocksSupport_h
#define Tun2SocksSupport_h

#include <stdint.h>

#define OLCBOX_CTLIOCGINFO 0xc0644e03UL

typedef struct {
    uint32_t ctl_id;
    char ctl_name[96];
} olcbox_ctl_info;

typedef struct {
    unsigned char sc_len;
    unsigned char sc_family;
    uint16_t ss_sysaddr;
    uint32_t sc_id;
    uint32_t sc_unit;
    uint32_t sc_reserved[5];
} olcbox_sockaddr_ctl;

#endif
