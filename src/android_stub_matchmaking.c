/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/** @file android_stub_matchmaking.c
 *     Android replacement for net_matchmaking.c.
 * @par Purpose:
 *     The online lobby browser talks to the matchmaking server over libcurl.
 *     The Android build does not bundle curl and OpenSSL yet, so the public
 *     lobby list is unavailable there; direct IP connections still work.
 * @par Comment:
 *     Compiled out on every other platform. net_matchmaking.c is excluded from
 *     the Android build instead, so no existing source file has to change.
 * @author   KeeperFX Team
 * @date     04 Aug 2026
 * @par  Copying and copyrights:
 *     This program is free software; you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation; either version 2 of the License, or
 *     (at your option) any later version.
 */
/******************************************************************************/
#ifdef __ANDROID__

#include "pre_inc.h"
#include "net_matchmaking.h"
#include "bflib_basics.h"
#include "globals.h"
#include "post_inc.h"

struct TbNetworkSessionNameEntry matchmaking_sessions[MATCHMAKING_SESSIONS_MAX];
int matchmaking_session_count = 0;
char join_lobby_id[MATCHMAKING_ID_MAX] = {0};

void matchmaking_connect_async(void)
{
}

int matchmaking_connect(void)
{
    return -1;
}

int matchmaking_request_list(void)
{
    matchmaking_session_count = 0;
    return -1;
}

void matchmaking_disconnect(void)
{
}

void matchmaking_close_lobby(void)
{
}

void matchmaking_refresh_sessions(void)
{
    matchmaking_session_count = 0;
}

int matchmaking_create(const char *name, int udp_ipv4_port, int udp_ipv6_port)
{
    (void)name;
    (void)udp_ipv4_port;
    (void)udp_ipv6_port;
    WARNLOG("The public lobby list is not available in the Android build");
    return -1;
}

int matchmaking_punch(const char *lobby_id, int udp_ipv4_port, int udp_ipv6_port, PunchAddresses *output)
{
    (void)lobby_id;
    (void)udp_ipv4_port;
    (void)udp_ipv6_port;
    (void)output;
    return -1;
}

int matchmaking_poll_punch(PunchAddresses *output)
{
    (void)output;
    return 0;
}

#endif // __ANDROID__
