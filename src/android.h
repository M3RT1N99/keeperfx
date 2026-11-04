/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/** @file android.h
 *     Header file for android.cpp.
 * @par Purpose:
 *     What shared engine code calls on Android. Only ever included under
 *     __ANDROID__.
 * @author   KeeperFX Team
 * @date     04 Nov 2026
 * @par  Copying and copyrights:
 *     This program is free software; you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation; either version 2 of the License, or
 *     (at your option) any later version.
 */
/******************************************************************************/
#ifndef KFX_ANDROID_H
#define KFX_ANDROID_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Called once per presented frame. */
void android_frame_watch(void);

/**
 * Converts text from a code page to UTF-8, as far as dst has room.
 * @return the length written, without the terminator; 0 on failure.
 */
size_t android_codepage_to_utf8(const char *charset, const char *src, size_t src_size,
                                char *dst, size_t dst_size);

/**
 * What to hand SDL's file functions for a path relative to the working directory.
 * @return path itself when it is absolute already, otherwise buf.
 */
const char *android_absolute_path(const char *path, char *buf, size_t bufsize);

/** Whether the engine is running behind a paused activity, with no surface to draw on. */
int android_running_in_background(void);

/** Whether event polling should ask SDL for events right now. */
int android_event_pump_due(void);

#define ANDROID_LAN_TARGETS_MAX 8
#define ANDROID_LAN_ADDRESS_LEN 16 // INET_ADDRSTRLEN

/**
 * The broadcast address of every IPv4 subnet this device is on right now, in dotted
 * form. Logs when the set changes.
 * @return how many there are.
 */
int android_lan_broadcast_targets(char targets[ANDROID_LAN_TARGETS_MAX][ANDROID_LAN_ADDRESS_LEN]);

/**
 * Puts a local game's socket on the Wi-Fi network if Wi-Fi is there but is not the
 * default network, and leaves it alone otherwise. Logs which it was and why. A socket
 * that cannot be bound stays usable as it was.
 * @param what What the socket is for, for the log.
 */
void android_lan_bind_socket(int fd, const char *what);

/** Implemented in bflib_sndlib.cpp, called when the activity goes away and comes back. */
void android_audio_enter_background(int engine_keeps_running);
void android_audio_enter_foreground(int engine_kept_running);

#ifdef __cplusplus
}
#endif

#endif // KFX_ANDROID_H
