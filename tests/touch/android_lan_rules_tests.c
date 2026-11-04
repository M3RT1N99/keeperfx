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

int android_lan_rules_tests(int *total_checks)
{
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
    *total_checks += checks;
    return failures;
}
