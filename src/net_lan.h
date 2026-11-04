#ifndef NET_LAN_H
#define NET_LAN_H

#include "bflib_netsession.h"
#include <stdint.h>

#define LAN_SESSIONS_MAX 8

#ifdef __cplusplus
extern "C" {
#endif

extern struct TbNetworkSessionNameEntry lan_sessions[LAN_SESSIONS_MAX];
extern int lan_session_count;

void lan_host_start(uint16_t port);
void lan_host_update(void);
#ifdef __ANDROID__
/** The host's discovery socket, or -1 while this phone is not hosting. */
int lan_host_discovery_fd(void);
#endif
void lan_service(void);
void lan_refresh_sessions(void);
void lan_shutdown(void);

#ifdef __cplusplus
}
#endif

#endif
