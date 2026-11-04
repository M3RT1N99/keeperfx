/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/** @file android_lan_rules_tests.c
 *     Tests of src/android_lan_rules.h, which sits in this harness because it is the
 *     one that runs without a device.
 * @author   KeeperFX Team
 * @date     04 Nov 2026
 * @par  Copying and copyrights:
 *     This program is free software; you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation; either version 2 of the License, or
 *     (at your option) any later version.
 */
/******************************************************************************/
#include "android_lan_rules.h"

#include <stdio.h>

static int failures = 0;
static int checks = 0;

#define IP(a, b, c, d) ((uint32_t)(a) << 24 | (uint32_t)(b) << 16 | (uint32_t)(c) << 8 | (uint32_t)(d))

static void expect(const char *what, uint32_t address, uint32_t netmask, uint32_t reported, uint32_t wanted)
{
    const uint32_t got = lan_subnet_broadcast(address, netmask, reported);
    checks++;
    if (got != wanted) {
        failures++;
        printf("FAILED %s: got %08x, wanted %08x\n", what, (unsigned)got, (unsigned)wanted);
    }
}

static void expect_network(const char *what, int64_t answer, int64_t wanted)
{
    const int64_t got = lan_socket_network(answer);
    checks++;
    if (got != wanted) {
        failures++;
        printf("FAILED %s: got %lld, wanted %lld\n", what, (long long)got, (long long)wanted);
    }
}

static void expect_follow(const char *what, int connected, int quiet, int host_in_lobby, int wanted)
{
    checks++;
    const int got = lan_should_follow_network(connected, quiet, host_in_lobby);
    if (got != wanted) {
        failures++;
        printf("FAILED %s: got %d, wanted %d\n", what, got, wanted);
    }
}

int android_lan_rules_tests(int *total_checks)
{
    expect_network("a network's handle is where the socket belongs", 600406151181LL, 600406151181LL);
    expect_network("Wi-Fi as the default takes a binding off", LAN_WIFI_IS_DEFAULT, 0);
    expect_network("so does no Wi-Fi at all, the network it named is gone", LAN_NO_WIFI_NETWORK, 0);
    expect_network("no answer leaves the socket as it is", LAN_NETWORKS_UNKNOWN, LAN_NETWORKS_UNKNOWN);
    expect("a hotspot's /24 as the kernel reports it", IP(10, 202, 11, 234), IP(255, 255, 255, 0), IP(10, 202, 11, 255), IP(10, 202, 11, 255));
    expect("a /24 with no reported address", IP(192, 168, 43, 1), IP(255, 255, 255, 0), 0, IP(192, 168, 43, 255));
    expect("a /16 with no reported address", IP(172, 20, 10, 7), IP(255, 255, 0, 0), 0, IP(172, 20, 255, 255));
    expect("the kernel's word beats the arithmetic", IP(192, 168, 1, 5), IP(255, 255, 255, 0), IP(192, 168, 1, 127), IP(192, 168, 1, 127));
    expect("a /30 still has a broadcast address", IP(10, 0, 0, 1), IP(255, 255, 255, 252), 0, IP(10, 0, 0, 3));
    expect("a /31 has none", IP(10, 0, 0, 1), IP(255, 255, 255, 254), 0, 0);
    expect("nor does it when one is reported", IP(10, 0, 0, 1), IP(255, 255, 255, 254), IP(10, 0, 0, 1), 0);
    expect("a /32 has none", IP(10, 0, 0, 1), IP(255, 255, 255, 255), 0, 0);
    expect("a /0 would be the limited broadcast", IP(10, 0, 0, 1), 0, 0, 0);
    expect("so would a reported limited broadcast", IP(10, 0, 0, 1), IP(255, 255, 255, 0), UINT32_MAX, 0);
    expect_follow("the one peer quiet: the network may have gone", 1, 1, 0, 1);
    expect_follow("the one peer heard", 1, 0, 0, 0);
    expect_follow("all quiet", 3, 3, 0, 1);
    expect_follow("one quiet among others heard is that peer's own trouble", 3, 1, 0, 0);
    expect_follow("all but one quiet, the one heard proves the socket", 3, 2, 0, 0);
    expect_follow("nobody here, a host in its lobby", 0, 0, 1, 1);
    expect_follow("nobody here, anything else", 0, 0, 0, 0);
    expect_follow("the lobby does not outvote a peer that is heard", 1, 0, 1, 0);
    *total_checks += checks;
    return failures;
}
