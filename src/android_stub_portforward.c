/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/** @file android_stub_portforward.c
 *     Android replacement for net_portforward.cpp.
 * @par Purpose:
 *     Automatic port forwarding uses miniupnpc and libnatpmp, neither of which
 *     the Android build bundles. Mobile networks are behind carrier grade NAT
 *     anyway, where both protocols would fail regardless.
 * @par Comment:
 *     Compiled out on every other platform. net_portforward.cpp is excluded
 *     from the Android build instead, so no existing source file has to change.
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
#include "net_portforward.h"
#include "bflib_basics.h"
#include "post_inc.h"

int port_forward_add_mapping(uint16_t port)
{
    (void)port;
    WARNLOG("Automatic port forwarding is not available in the Android build");
    return 0;
}

void port_forward_remove_mapping(void)
{
}

#endif // __ANDROID__
