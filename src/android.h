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

#ifdef __cplusplus
}
#endif

#endif // KFX_ANDROID_H
