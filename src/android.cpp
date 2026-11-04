/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/** @file android.cpp
 *     Android entry point.
 * @par Purpose:
 *     Everything that has to happen before the engine starts on Android, plus
 *     the helpers shared engine code needs here and nowhere else (android.h). The
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
#include "android.h"
#include "platform.h"
#include "bflib_basics.h"
#include "game_legacy.h"
#include "globals.h"
#include <string>
#include <vector>
#include <jni.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>
#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>
#include "post_inc.h"

#define KFX_ANDROID_LOG_TAG "KeeperFX"

// Every pump behind a paused activity sleeps in SDL for up to 100 ms when it finds
// no lifecycle event, and the engine pumps twice a frame, which would hold a network
// game at 5 turns a second for everyone in it. This often leaves it room for its
// 20 turns and still notices the return of the activity at once by human standards.
#define BACKGROUND_PUMP_INTERVAL_MS 500

// Whether the engine carries on behind a paused activity. Only the main thread
// touches it: the watch below runs inside its event pump.
static bool running_in_background = false;

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
 * Decides what happens to the engine while the activity is away.
 *
 * Alone, it stops: SDL blocks the main thread inside its event pump and pauses its
 * audio devices, so a phone in a pocket spends nothing on a game nobody is playing.
 * In a network game that would hold everyone else up until the player is dropped
 * after PEER_TIMEOUT_MS, so there the engine carries on, without drawing and without
 * sound, and GameActivity starts NetworkGameService to be allowed to.
 *
 * SDL reads the hint once when video starts, too early to know which of the two this
 * pause is, so fetch-deps.sh makes it read the hint again at every pause. That
 * happens after the event sent here.
 *
 * This has to be an event watch rather than a case in the normal event loop.
 * SDL queues the background events and then blocks the main thread inside
 * SDL_PumpEvents, so nothing that waits for the loop would run until the app
 * was already back in the foreground. A watch is called synchronously from
 * SDL_SendAppEvent, before the block.
 */
static bool SDLCALL background_watch(void *userdata, SDL_Event *event)
{
    (void)userdata;
    switch (event->type)
    {
    case SDL_EVENT_WILL_ENTER_BACKGROUND:
        running_in_background = network_is_active();
        SDL_SetHint(SDL_HINT_ANDROID_BLOCK_ON_PAUSE, running_in_background ? "0" : "1");
        android_audio_enter_background(running_in_background);
        break;
    case SDL_EVENT_DID_ENTER_FOREGROUND:
        android_audio_enter_foreground(running_in_background);
        running_in_background = false;
        break;
    default:
        break;
    }
    return true; // Leave the event in the queue for whoever else wants it
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_keeperfx_android_GameActivity_nativeNetworkGameActive(JNIEnv *, jclass)
{
    return network_is_active() ? JNI_TRUE : JNI_FALSE;
}

extern "C" int android_running_in_background(void)
{
    return running_in_background;
}

extern "C" int android_event_pump_due(void)
{
    static Uint64 last_pump = 0;
    if (!running_in_background)
        return true;
    const Uint64 now = SDL_GetTicks();
    if (now - last_pump < BACKGROUND_PUMP_INTERVAL_MS)
        return false;
    last_pump = now;
    return true;
}

/**
 * SDL_IOFromFile does not resolve a relative path against the working directory
 * on Android: it looks at the internal storage root and in the APK assets. Every
 * file that goes through LbFileOpen's stdio is unaffected, so only the few things
 * handed to SDL by name need this - the music, the speech, the screenshots.
 */
extern "C" const char *android_absolute_path(const char *path, char *buf, size_t bufsize)
{
    if (path == nullptr || path[0] == '/' || getcwd(buf, bufsize) == nullptr)
        return path;
    if (path[0] == '.' && path[1] == '/')
        path += 2;
    const size_t len = strlen(buf);
    snprintf(buf + len, bufsize - len, "/%s", path);
    return buf;
}

/**
 * Decodes through java.lang.String, since Java has the double byte tables that
 * bionic's iconv does not and sits in the same process.
 *
 * Bytes a table has no character for become U+FFFD instead of failing the whole
 * text. The official gtext_kor.dat holds nearly five hundred of them, which is what
 * Windows renders as well; iconv on Linux gives up on that file.
 */
extern "C" size_t android_codepage_to_utf8(const char *charset, const char *src, size_t src_size,
    char *dst, size_t dst_size)
{
    JNIEnv *env = static_cast<JNIEnv *>(SDL_GetAndroidJNIEnv());
    if (env == nullptr || dst_size == 0 || env->PushLocalFrame(8) != JNI_OK)
        return 0;
    size_t written = 0;
    jclass string_class = env->FindClass("java/lang/String");
    jstring from = env->NewStringUTF(charset);
    jstring to = env->NewStringUTF("UTF-8");
    jbyteArray raw = env->NewByteArray(static_cast<jsize>(src_size));
    if (!env->ExceptionCheck() && string_class != nullptr && raw != nullptr)
    {
        env->SetByteArrayRegion(raw, 0, static_cast<jsize>(src_size),
            reinterpret_cast<const jbyte *>(src));
        jobject text = env->NewObject(string_class,
            env->GetMethodID(string_class, "<init>", "([BLjava/lang/String;)V"), raw, from);
        if (!env->ExceptionCheck())
        {
            jbyteArray utf8 = static_cast<jbyteArray>(env->CallObjectMethod(text,
                env->GetMethodID(string_class, "getBytes", "(Ljava/lang/String;)[B"), to));
            if (!env->ExceptionCheck())
            {
                written = static_cast<size_t>(env->GetArrayLength(utf8));
                if (written >= dst_size)
                    written = dst_size - 1;
                env->GetByteArrayRegion(utf8, 0, static_cast<jsize>(written),
                    reinterpret_cast<jbyte *>(dst));
                dst[written] = '\0';
            }
        }
    }
    if (env->ExceptionCheck())
    {
        env->ExceptionClear();
        ERRORLOG("Cannot decode text as %s", charset);
    }
    env->PopLocalFrame(nullptr);
    return written;
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

    SDL_AddEventWatch(background_watch, nullptr);

    args.push_back(nullptr);
    const int result = kfxmain((int)(args.size() - 1), args.data());
    __android_log_print(ANDROID_LOG_INFO, KFX_ANDROID_LOG_TAG,
        "KeeperFX exited with %d", result);
    return result;
}

#endif // __ANDROID__
