/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/** @file android.cpp
 *     Android entry point.
 * @par Purpose:
 *     Everything that has to happen before the engine starts on Android, plus
 *     the two lifecycle helpers that have no counterpart on the desktop. The
 *     OS services the engine asks for at run time are in
 *     kfx/platform/PlatformAndroid.h.
 * @par Comment:
 *     The whole file is compiled out on every other platform, so it can sit in
 *     src/ without affecting those builds.
 *
 *     The game itself resolves all of its data relative to the working
 *     directory. The launcher hands us the directory it imported the game data
 *     into via "-datadir"; we chdir() there before the engine starts, which is
 *     why no path handling inside the engine needs to change.
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
#include "platform.h"
#include "bflib_basics.h"
#include "globals.h"
#include <string>
#include <vector>
#include <unistd.h>
#include <android/log.h>
#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>
#include <AL/al.h>
#include "post_inc.h"

#define KFX_ANDROID_LOG_TAG "KeeperFX"

/**
 * Consumes "-datadir <path>" from the argument vector and makes it the working
 * directory. The option is stripped afterwards so the engine's own command line
 * parser never sees it.
 *
 * @return true when a data directory was applied successfully.
 */
static bool apply_data_directory(std::vector<char *> & args)
{
    for (size_t i = 1; i < args.size(); i++)
    {
        if (args[i] == nullptr)
            continue;
        if (SDL_strcasecmp(args[i], "-datadir") != 0)
            continue;
        const auto offset = static_cast<std::vector<char *>::difference_type>(i);
        if ((i + 1) >= args.size())
        {
            __android_log_print(ANDROID_LOG_ERROR, KFX_ANDROID_LOG_TAG,
                "-datadir given without a path");
            args.erase(args.begin() + offset);
            return false;
        }
        const std::string path = args[i + 1];
        args.erase(args.begin() + offset, args.begin() + offset + 2);
        if (chdir(path.c_str()) != 0)
        {
            __android_log_print(ANDROID_LOG_ERROR, KFX_ANDROID_LOG_TAG,
                "Cannot enter data directory \"%s\"", path.c_str());
            return false;
        }
        __android_log_print(ANDROID_LOG_INFO, KFX_ANDROID_LOG_TAG,
            "Data directory: %s", path.c_str());
        return true;
    }
    __android_log_print(ANDROID_LOG_WARN, KFX_ANDROID_LOG_TAG,
        "No -datadir given, using the process working directory");
    return false;
}

/**
 * Silences OpenAL while the game is in the background, and brings it back after.
 *
 * The engine itself stops when the activity pauses - that is what
 * SDL_HINT_ANDROID_BLOCK_ON_PAUSE arranges - and SDL pauses its own audio
 * devices along with it, which covers the music and everything else SDL_mixer
 * plays. Pausing the mixer's tracks here as well would only add a way to wake a
 * track the game had paused itself. OpenAL has its own output and keeps mixing
 * whatever was playing, so the sound effects and the speech carried on after the
 * phone was locked.
 *
 * This has to be an event watch rather than a case in the normal event loop.
 * SDL queues the background events and then blocks the main thread inside
 * SDL_PumpEvents, so nothing that waits for the loop would run until the app
 * was already back in the foreground. A watch is called synchronously from
 * SDL_SendAppEvent, before the block.
 */
static bool SDLCALL background_audio_watch(void *userdata, SDL_Event *event)
{
    (void)userdata;
    switch (event->type)
    {
    case SDL_EVENT_WILL_ENTER_BACKGROUND:
        alListenerf(AL_GAIN, 0.0f);
        break;
    case SDL_EVENT_DID_ENTER_FOREGROUND:
        alListenerf(AL_GAIN, 1.0f);
        break;
    default:
        break;
    }
    return true; // Leave the event in the queue for whoever else wants it
}

/**
 * Collects frame times and logs one summary line per minute, so a stutter
 * report arrives with numbers in the engine log instead of an impression.
 * Called from RendererPresentFrame() once per presented frame.
 *
 * A summary rather than a line per slow frame, learned the hard way: the log
 * is flushed to flash per line, that write can cost tens of milliseconds, and
 * a first device log showed a report every two seconds - each write slow
 * enough to make the following frame the next report. Counting silently and
 * writing once a minute cannot feed itself, and the clock is re-read after
 * writing so the write's own cost never lands in the next measurement.
 */
extern "C" void android_frame_watch(void)
{
    static Uint64 prev_ticks = 0;
    static Uint64 window_start = 0;
    static Uint32 frames = 0;
    static Uint32 slow = 0;
    static Uint64 worst = 0;
    static float avg_ms = 0.0f;
    const Uint64 now = SDL_GetTicks();
    if (prev_ticks == 0) {
        prev_ticks = now;
        window_start = now;
        return;
    }
    const Uint64 delta = now - prev_ticks;
    prev_ticks = now;
    // Coming back from the background, a level load: not a frame at all.
    if (delta > 2000) {
        return;
    }
    if (avg_ms <= 0.0f) {
        avg_ms = (float)delta;
        return;
    }
    frames++;
    const float limit = (avg_ms * 3.0f > 50.0f) ? (avg_ms * 3.0f) : 50.0f;
    if ((float)delta > limit) {
        slow++;
        if (delta > worst)
            worst = delta;
    }
    // Adapts slowly, so a burst of slow frames does not become the norm the
    // next ones are measured against.
    avg_ms += ((float)delta - avg_ms) * 0.02f;
    if (now - window_start >= 60000) {
        if (slow > 0) {
            SYNCMSG("Frames over the last minute: %u, slow: %u (worst %u ms, typical %d ms)",
                (unsigned)frames, (unsigned)slow, (unsigned)worst, (int)(avg_ms + 0.5f));
        }
        frames = 0;
        slow = 0;
        worst = 0;
        window_start = SDL_GetTicks();
        prev_ticks = window_start;
    }
}

/**
 * Entry point. SDL_main.h renames this to SDL_main and exports it, and
 * SDLActivity.nativeRunMain() calls it on the SDL thread.
 */
extern "C" int main(int argc, char *argv[])
{
    std::vector<char *> args(argv, argv + argc);
    apply_data_directory(args);

    // Touch screens are handled by our own layer in bflib_input_touch.cpp;
    // SDL's built-in translation would collapse multi touch into a fake mouse.
    SDL_SetHint(SDL_HINT_TOUCH_MOUSE_EVENTS, "0");
    SDL_SetHint(SDL_HINT_MOUSE_TOUCH_EVENTS, "0");
    // Stop the engine while the activity is in the background. SDL pauses its
    // audio devices along with it, which background_audio_watch() relies on.
    SDL_SetHint(SDL_HINT_ANDROID_BLOCK_ON_PAUSE, "1");
    // Left alone, the back button closes the activity, which drops the player
    // out of the game entirely. Trapped, it arrives as SDLK_AC_BACK and the
    // input layer turns it into Escape, which is what leaves a menu.
    SDL_SetHint(SDL_HINT_ANDROID_TRAP_BACK_BUTTON, "1");
    // SDL's default Android audio backend is AAudio, and the mentor speech
    // stuttered through it on a Samsung device even with the data verified,
    // the CPU idle between frames and the locking clean. AAudio glitches on
    // Samsung firmwares are a known SDL issue and the usual cure is exactly
    // this: the older OpenSL ES backend, which trades a little latency for
    // output that does not tear. Nothing here is latency sensitive.
    SDL_SetHint(SDL_HINT_AUDIO_DRIVER, "openslES");

    SDL_AddEventWatch(background_audio_watch, nullptr);

    args.push_back(nullptr);
    const int result = kfxmain((int)(args.size() - 1), args.data());
    __android_log_print(ANDROID_LOG_INFO, KFX_ANDROID_LOG_TAG,
        "KeeperFX exited with %d", result);
    return result;
}

#endif // __ANDROID__
