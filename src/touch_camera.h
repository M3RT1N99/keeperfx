/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/** @file touch_camera.h
 *     Header file for touch_camera.c.
 * @par Purpose:
 *     Two fingers holding the map: what the gesture maths does to the game's cameras.
 * @par Comment:
 *     Just a header file - #defines, typedefs, function prototypes etc.
 * @author   KeeperFX Team
 * @date     04 Nov 2026
 * @par  Copying and copyrights:
 *     This program is free software; you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation; either version 2 of the License, or
 *     (at your option) any later version.
 */
/******************************************************************************/
#ifndef TOUCH_CAMERA_H
#define TOUCH_CAMERA_H

#include "globals.h"

#ifdef __cplusplus
extern "C" {
#endif
/******************************************************************************/
struct Packet;
struct TgFingers;
struct TgTuning;

/**
 * Moves the camera for the frame about to be drawn, so that a finger reaches the
 * picture in the same frame. Runs before the cameras are interpolated.
 * @param fingers The two fingers holding the map, NULL when none are.
 */
void touch_camera_frame(const struct TgFingers *fingers, const struct TgTuning *tuning);

/** The next frame takes hold of the map afresh, with the fingers as they are now. */
void touch_camera_regrip(const struct TgFingers *fingers);

/** Fingers left the map. With may_roll, a release with speed rolls on. */
void touch_camera_release(const struct TgTuning *tuning, TbBool may_roll);

/** A new touch catches a rolling map. */
void touch_camera_catch(void);

/**
 * Keeps the dungeon camera's zoom near the local one. Called once per camera turn
 * while the packet is being filled; sets at most one zoom flag, and none when
 * something else already did.
 */
void touch_camera_packet_zoom(struct Packet *pckt);

/**
 * The zoom flags in this turn's packet which came from touch_camera_packet_zoom().
 * The local camera follows the fingers, so those flags are for the dungeon camera
 * alone. Asking uses them up.
 */
uint32_t touch_camera_take_zoom_flags(void);

/** True where two fingers move the camera directly, rather than through game keys. */
TbBool touch_camera_owns_view(void);

/******************************************************************************/
#ifdef __cplusplus
}
#endif
#endif
