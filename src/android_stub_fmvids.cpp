/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/** @file android_stub_fmvids.cpp
 *     Android replacement for bflib_fmvids.cpp.
 * @par Purpose:
 *     Full motion video playback and FLI recording rely on FFmpeg, which the
 *     Android build does not ship. This translation unit provides the four
 *     symbols other modules reference so the engine links and simply skips the
 *     intro/outro movies.
 * @par Comment:
 *     Compiled out on every other platform. bflib_fmvids.cpp is excluded from
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
#include "bflib_fmvids.h"
#include "bflib_basics.h"
#include "globals.h"
#include "post_inc.h"

extern "C" TbBool play_smk(const char * filename, int flags)
{
    (void)flags;
    SYNCLOG("Movie playback is not available on Android, skipping \"%s\"",
        (filename != NULL) ? filename : "(null)");
    return true; // reported as "played", so the caller moves on instead of erroring
}

extern "C" short anim_stop(void)
{
    return 0;
}

extern "C" short anim_record(void)
{
    WARNLOG("Movie recording is not available on Android");
    return 0;
}

extern "C" TbBool anim_record_frame(unsigned char * screenbuf, unsigned char * palette)
{
    (void)screenbuf;
    (void)palette;
    return false;
}

#endif // __ANDROID__
