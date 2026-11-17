/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/** @file touch_keeper.h
 *     Header file for touch_keeper.c.
 * @par Purpose:
 *     What the rules of the hand in touch_hand.c read from the game.
 * @par Comment:
 *     Just a header file - #defines, typedefs, function prototypes etc.
 * @author   KeeperFX Team
 * @date     06 Nov 2026
 * @par  Copying and copyrights:
 *     This program is free software; you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation; either version 2 of the License, or
 *     (at your option) any later version.
 */
/******************************************************************************/
#ifndef TOUCH_KEEPER_H
#define TOUCH_KEEPER_H

#include "bflib_basics.h"
#include "touch_hand.h"

#ifdef __cplusplus
extern "C" {
#endif
/******************************************************************************/
struct Packet;

/* The knobs of touch_tune(), in milliseconds. */
extern float touch_hand_hold_ms;
extern float touch_hand_rain_ms;
extern float touch_hand_slap_ms;

/** True while the plain hand is what a finger on the map works with. Only Android has these rules. */
TbBool touch_keeper_in_force(void);

/**
 * True on a menu, the panel among them, or close enough to one that the game's
 * finger assist would pull the pointer onto a button.
 */
TbBool touch_keeper_over_gui(long x, long y);

/** True on the panel, with the same reach. */
TbBool touch_keeper_over_panel(long x);

/** The first finger on the map landed at x, y. */
void touch_keeper_stroke_begin(long x, long y, long long ms);
void touch_keeper_stroke_moved(long long ms);
void touch_keeper_stroke_cancel(void);
/** The finger lifted, see th_stroke_lift(). */
enum ThLift touch_keeper_stroke_lift(void);

/** The pointer is to stay where the finger landed, until a frame has shown what is there. */
TbBool touch_keeper_start_unseen(void);
/** A frame has been drawn with the pointer where the finger landed, at x, y. */
void touch_keeper_look_at_start(long x, long y);

/**
 * The finger went from x0, y0 to x1, y1. Looks along the way for own creatures to slap,
 * by the game's own search for a thing under the hand, and reads nothing else.
 */
void touch_keeper_stroke_path(long x0, long y0, long x1, long y1, long long now);
/** Where the slap of a creature crossed is to be aimed, once the pointer is free. */
TbBool touch_keeper_slap_aim(long *x, long *y);
/** The pointer has been at the aim for a drawn frame: whether the right click goes out now. */
TbBool touch_keeper_slap_now(long x, long y);

/**
 * @param x,y Where the finger is.
 * @param settled A frame has been drawn with the pointer on this finger, so the game's
 *     idea of what the pointer is over is about this place.
 */
enum ThVerdict touch_keeper_verdict(long x, long y, TbBool settled, float longpress_ms, long long now);
TbBool touch_keeper_drop_due(long x, long y, TbBool settled, float longpress_ms);
TbBool touch_keeper_long_press_gives_way(long x, long y, TbBool settled, float longpress_ms);
void touch_keeper_long_press_ended(float longpress_ms);

/** Takes note of what the turn's packet carries. */
void touch_keeper_note_packet(const struct Packet *pckt);

/** The game key that makes a left button drag an area of walls is to be held now, see th_area_key(). */
TbBool touch_keeper_area_key(void);
/** See th_area_owns_buttons(). */
TbBool touch_keeper_area_owns_buttons(void);
/** See th_area_anchoring(). */
TbBool touch_keeper_area_anchoring(void);
/** See th_area_step(). */
enum ThAreaStep touch_keeper_area_step(long long now, float give_up_ms);
/** The buttons were let go of for another reason, see th_area_buttons_released(). */
void touch_keeper_area_buttons_released(void);
/** The rules are out of force under the area, see th_area_give_up(). */
void touch_keeper_area_give_up(void);
/** The game is paused and the pointer is over the paused message, where a left release goes on with the game. */
TbBool touch_keeper_pointer_over_pause_box(void);

/******************************************************************************/
#ifdef __cplusplus
}
#endif
#endif
