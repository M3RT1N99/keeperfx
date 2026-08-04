/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/** @file android.cpp
 *     Android platform layer.
 * @par Purpose:
 *     Provides the platform specific entry point and helpers for the native
 *     Android port, mirroring what linux.cpp does for the Linux port.
 * @par Comment:
 *     The whole file is compiled out on every other platform, so it can sit in
 *     src/ next to linux.cpp and windows.cpp without affecting those builds.
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

#include "platform.h"
#include "steam_api.hpp"
#include "bflib_crash.h"
#include "bflib_fileio.h"
#include "cdrom.h"
#include <algorithm>
#include <ctype.h>
#include <string>
#include <memory>
#include <utility>
#include <vector>
#include <sys/types.h>
#include <sys/stat.h>
#include <dirent.h>
#include <unistd.h>
#include <fnmatch.h>
#include <android/log.h>
#include <SDL2/SDL.h>

#define KFX_ANDROID_LOG_TAG "KeeperFX"

extern "C" const char * get_os_version()
{
    return "Android";
}

extern "C" const void * get_image_base()
{
    return nullptr;
}

extern "C" const char * get_wine_version()
{
    return nullptr; // we're running native
}

extern "C" const char * get_wine_host()
{
    return nullptr; // we're running native
}

extern "C" void install_exception_handler()
{
    LbErrorParachuteInstall();
}

extern "C" int steam_api_init()
{
    // Steam not supported on Android
    return 0;
}

extern "C" void steam_api_shutdown()
{
    // Steam not supported on Android
}

extern "C" void SetRedbookVolume(SoundVolume)
{
    // No CD audio on Android
}

extern "C" TbBool PlayRedbookTrack(int)
{
    // No CD audio on Android
    return false;
}

extern "C" void PauseRedbookTrack()
{
}

extern "C" void ResumeRedbookTrack()
{
}

extern "C" void StopRedbookTrack()
{
}

/******************************************************************************/
/* Directory scanning - identical to the Linux port, bionic provides all of it. */

struct TbFileFind {
    std::vector<std::pair<std::string, std::string>> names;
    size_t index = 0;
};

static bool filespec_is_pattern(const char * filespec) {
    return strchr(filespec, '*') != nullptr;
}

static std::string directory_from_filespec(const char * filespec) {
    const auto sep = strrchr(filespec, '/');
    if (sep && sep != filespec) {
        return std::string(filespec, sep - filespec);
    } else {
        return ".";
    }
}

extern "C" TbFileFind * LbFileFindFirst(const char * filespec, TbFileEntry * fe)
{
    try {
        auto ff = std::make_unique<TbFileFind>();
        bool is_pattern = filespec_is_pattern(filespec);
        std::string path;
        if (is_pattern) {
            path = directory_from_filespec(filespec);
        } else {
            path = filespec;
        }
        DIR *handle = opendir(path.c_str());
        if (handle) {
            while (true) {
                auto de = readdir(handle);
                if (!de) {
                    break;
                }
                if (strcmp(de->d_name, ".") == 0) {
                    continue;
                }
                if (strcmp(de->d_name, "..") == 0) {
                    continue;
                }
                const std::string file_path = path + "/" + de->d_name;
                if (is_pattern) {
                    if (fnmatch(filespec, file_path.c_str(), FNM_FILE_NAME | FNM_CASEFOLD) != 0) {
                        continue;
                    }
                }
                struct stat sb;
                if (stat(file_path.c_str(), &sb) < 0) {
                    continue;
                }
                if (!S_ISREG(sb.st_mode)) {
                    continue;
                }
                std::string key = de->d_name;
                for (size_t i = 0; i < key.size(); i++) {
                    key[i] = (char)tolower((unsigned char)key[i]);
                }
                ff->names.emplace_back(key, de->d_name);
            }
            closedir(handle);
        }
        if (!ff->names.empty()) {
            std::sort(ff->names.begin(), ff->names.end());
            fe->Filename = ff->names[0].second.c_str();
            return ff.release();
        }
    } catch (...) {}
    return nullptr;
}

extern "C" int32_t LbFileFindNext(TbFileFind * ff, TbFileEntry * fe)
{
    try {
        if (ff) {
            ff->index++;
            if (ff->index < ff->names.size()) {
                fe->Filename = ff->names[ff->index].second.c_str();
                return 1;
            }
        }
    } catch (...) {}
    return -1;
}

extern "C" void LbFileFindEnd(TbFileFind * ff)
{
    delete ff;
}

/******************************************************************************/

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
        if ((i + 1) >= args.size())
        {
            __android_log_print(ANDROID_LOG_ERROR, KFX_ANDROID_LOG_TAG,
                "-datadir given without a path");
            args.erase(args.begin() + i);
            return false;
        }
        const std::string path = args[i + 1];
        args.erase(args.begin() + i, args.begin() + i + 2);
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
 * Entry point. SDL renames this to SDL_main and calls it from
 * SDLActivity.nativeRunMain() on the SDL thread.
 */
extern "C" int main(int argc, char *argv[])
{
    std::vector<char *> args(argv, argv + argc);
    apply_data_directory(args);

    // Touch screens are handled by our own layer in bflib_input_touch.cpp;
    // SDL's built-in translation would collapse multi touch into a fake mouse.
    SDL_SetHint(SDL_HINT_TOUCH_MOUSE_EVENTS, "0");
    SDL_SetHint(SDL_HINT_MOUSE_TOUCH_EVENTS, "0");
    // Keep the engine running while the activity is in the background rather
    // than tearing down the GL context on every notification shade pull.
    SDL_SetHint(SDL_HINT_ANDROID_BLOCK_ON_PAUSE, "1");

    args.push_back(nullptr);
    const int result = kfxmain((int)(args.size() - 1), args.data());
    __android_log_print(ANDROID_LOG_INFO, KFX_ANDROID_LOG_TAG,
        "KeeperFX exited with %d", result);
    return result;
}

#endif // __ANDROID__
