/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/** @file android_lan_rules.h
 *     The arithmetic of finding a game on a local network from Android.
 * @par Comment:
 *     Header only and free of any system or engine dependency, so that the host
 *     tests in tests/touch can pin it and no other platform's build gets an object
 *     from it.
 * @author   KeeperFX Team
 * @date     04 Nov 2026
 * @par  Copying and copyrights:
 *     This program is free software; you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation; either version 2 of the License, or
 *     (at your option) any later version.
 */
/******************************************************************************/
#ifndef KFX_ANDROID_LAN_RULES_H
#define KFX_ANDROID_LAN_RULES_H

#include <stdint.h>

/**
 * Where a broadcast reaches everybody on one IPv4 subnet. All in host byte order.
 *
 * @param address  The interface's address.
 * @param netmask  Its netmask.
 * @param reported The broadcast address the kernel gave for it, 0 if it gave none.
 * @return The address to send to, or 0 when this subnet has none worth using: /31
 *  and /32 have no broadcast address at all, and 255.255.255.255 does not stay on
 *  the subnet, it follows the default network.
 */
static inline uint32_t lan_subnet_broadcast(uint32_t address, uint32_t netmask, uint32_t reported)
{
    const uint32_t host_bits = ~netmask;
    if (host_bits < 3)
        return 0;
    const uint32_t broadcast = reported != 0 ? reported : (address | host_bits);
    return broadcast != UINT32_MAX ? broadcast : 0;
}

#endif // KFX_ANDROID_LAN_RULES_H
