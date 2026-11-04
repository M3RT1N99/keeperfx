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

/** What GameActivity.lanNetworkHandle() answers when it names no network. */
#define LAN_NO_WIFI_NETWORK 0
#define LAN_WIFI_IS_DEFAULT (-1)
#define LAN_NETWORKS_UNKNOWN (-2)

/**
 * Which network a local game's socket belongs on, given that answer.
 *
 * @return The network's handle, or 0, which android_setsocknetwork() takes as none, for
 *  a socket that has to follow the default network: Wi-Fi is the default there, or there
 *  is no Wi-Fi network and whatever the socket was bound to is gone.
 *  LAN_NETWORKS_UNKNOWN when the answer says nothing and the socket stays as it is.
 */
static inline int64_t lan_socket_network(int64_t answer)
{
    if (answer == LAN_NETWORKS_UNKNOWN)
        return LAN_NETWORKS_UNKNOWN;
    return answer > 0 ? answer : 0;
}

/**
 * Whether a local game's socket should go and look for its network again.
 *
 * A network that has gone silences every peer at once. One quiet peer among others that
 * are heard has trouble of its own, a level loading or a locked screen, and moving the
 * socket would take the others away for its sake.
 *
 * @param connected       Peers connected at this moment.
 * @param quiet           How many of them have not been heard for a while.
 * @param host_in_lobby   This is a host still waiting in its lobby. With nobody there to
 *  hear, the player may only now be joining the Wi-Fi the others are on.
 */
static inline int lan_should_follow_network(int connected, int quiet, int host_in_lobby)
{
    if (connected == 0)
        return host_in_lobby;
    return quiet == connected;
}

#endif // KFX_ANDROID_LAN_RULES_H
