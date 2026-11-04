/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/** @file touch_possess.h
 *     Header file for touch_possess.c.
 * @par Purpose:
 *     Fingers steering a possessed creature: what the rules in touch_steer.c do to the game.
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
#ifndef TOUCH_POSSESS_H
#define TOUCH_POSSESS_H

#include "globals.h"

#ifdef __cplusplus
extern "C" {
#endif
/******************************************************************************/
struct Thing;
struct TsActions;

/** Degrees of view per pixel of finger travel, on a screen 720 high. */
extern float touch_look_sensitivity;
/** Radius of the stick, as a percentage of the screen height. */
extern float touch_stick_radius_percent;

/**
 * True while the fingers steer a creature rather than point. Never on the desktop
 * ports, whose touch layer stays off: this is what keeps every hook below inert there.
 */
TbBool touch_possess_steering(void);

/** Forgets the fingers. Those still down do nothing from here on, however they move. */
void touch_possess_reset(void);

void touch_possess_finger_down(unsigned long long id, long x, long y, long long ms);
void touch_possess_finger_move(unsigned long long id, long x, long y, long long ms);
void touch_possess_finger_up(unsigned long long id, long x, long y, long long ms);
void touch_possess_finger_cancel(unsigned long long id);

/** Called once per poll, after the fingers were pumped: hands over what they asked for. */
void touch_possess_poll(long long now_ms, struct TsActions *actions);

/** Where a finger on the panel or a menu wants the pointer. @return false when none does. */
TbBool touch_possess_pointer_at(long *x, long *y);

/** True where the game keys come from here rather than from the gesture axes. */
TbBool touch_possess_owns_keys(void);

/** The stick and the fly buttons as game keys: 1 while held, 0 otherwise. */
float touch_possess_axis(long key_id);

/**
 * The look for this turn, in units of turn. Called once per turn in place of reading
 * the mouse. What the creature cannot turn in one turn stays owed for the next.
 */
void touch_possess_take_look(struct Thing *thing, int32_t *turn_x, int32_t *turn_y);

/** Draws the stick while a thumb is on it and the fly buttons while the creature flies. */
void touch_possess_draw(void);

/******************************************************************************/
#ifdef __cplusplus
}
#endif
#endif
