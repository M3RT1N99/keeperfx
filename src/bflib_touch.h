/******************************************************************************/
// Bullfrog Engine Emulation Library - for use to remake classic games like
// Syndicate Wars, Magic Carpet or Dungeon Keeper.
/******************************************************************************/
/** @file bflib_touch.h
 *     Header file for bflib_input_touch.cpp.
 * @par Purpose:
 *     Native touch screen input - multi touch gesture recognition.
 * @par Comment:
 *     Just a header file - #defines, typedefs, function prototypes etc.
 *     Kept free of SDL types so it can be included from plain C modules.
 * @author   KeeperFX Team
 * @date     04 Aug 2026
 * @par  Copying and copyrights:
 *     This program is free software; you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation; either version 2 of the License, or
 *     (at your option) any later version.
 */
/******************************************************************************/
#ifndef BFLIB_TOUCH_H
#define BFLIB_TOUCH_H

#include "globals.h"

#ifdef __cplusplus
extern "C" {
#endif
/******************************************************************************/

/**
 * Control scheme requested by the user.
 *
 * On Android the launcher passes this in through the "-inputmode" command line
 * option. On the desktop ports the default is TCMode_PointerKeys, which leaves
 * the whole touch layer inert so that existing mouse, keyboard and controller
 * behaviour is completely unchanged.
 */
enum TouchControlModes {
    TCMode_PointerKeys = 0, /**< Mouse/keyboard/controller only, touch layer disabled. */
    TCMode_Touch       = 1, /**< Native touch scheme always on. */
    TCMode_Auto        = 2, /**< Follow the input device the player used last. */
};

extern unsigned char touch_control_mode;

/** Sets the control scheme; safe to call before the video system is up. */
void touch_set_control_mode(unsigned char mode);

/**
 * Parses "auto", "touch" or "keyboard"/"mouse"/"kbm" into a TouchControlModes value.
 * @return true when the text was recognised.
 */
TbBool touch_parse_control_mode(const char *text, unsigned char *mode);

/**
 * Adjusts one gesture constant at run time, by name.
 *
 * Names: panspeed, longpress, dragslop, commitpan, commitpinch, committwist.
 * @return true when the name was recognised.
 */
TbBool touch_tune(const char *name, float value);

/**
 * Sticky right click: while on, a plain tap produces a right click.
 *
 * The gesture for it is a two finger tap, which works but is not something a
 * player finds without being told. Android puts a toggle on screen next to the
 * back arrow and flips this, so the verb is reachable by looking at it.
 */
void touch_set_sticky_right_click(TbBool on);
TbBool touch_sticky_right_click(void);

/** Prepares the touch layer. Called once from init_inputcontrol(). */
void init_touch_input(void);

/** True when the native touch scheme should currently drive the game. */
TbBool touch_controls_active(void);

/** True when a touch screen has produced at least one event this session. */
TbBool touch_device_seen(void);

/** Per frame update - long press detection and gesture decay. Called from poll_inputs(). */
void update_touch_inputs(void);

/**
 * Touch equivalent of the controller term in is_game_key_pressed().
 * @return non-zero when an active gesture maps onto the given game key.
 */
int touch_game_key_pressed(long key_id, TbBool clear_pressed);

/**
 * Touch equivalent of cbtn_axis_value(); returns 0.0 when the gesture is inactive.
 */
float touch_game_key_axis_value(long key_id);

/******************************************************************************/
#ifdef __cplusplus
}
#endif
#endif
