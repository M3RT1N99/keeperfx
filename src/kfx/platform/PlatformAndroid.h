#ifndef PLATFORM_ANDROID_H
#define PLATFORM_ANDROID_H

#include "kfx/platform/PlatformLinux.h"

/** Android platform. Bionic provides everything PlatformLinux uses: the directory
 *  walk, SDL's video init and pref path, and no CD audio or Steam here either. */
class PlatformAndroid : public PlatformLinux {
public:
    const char* GetOSVersion() const override { return "Android"; }
};

#endif // PLATFORM_ANDROID_H
