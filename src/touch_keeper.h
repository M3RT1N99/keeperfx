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

void touch_keeper_stroke_begin(long long ms);
void touch_keeper_stroke_moved(long long ms);
void touch_keeper_stroke_end(void);

/**
 * @param x,y Where the finger is.
 * @param settled A frame has been drawn with the pointer on this finger, so the game's
 *     idea of what the pointer is over is about this place.
 */
enum ThVerdict touch_keeper_verdict(long x, long y, TbBool settled, float longpress_ms, long long now);
enum ThClick touch_keeper_click_due(long x, long y, TbBool settled, float longpress_ms, long long now);
TbBool touch_keeper_long_press_gives_way(long x, long y, TbBool settled, float longpress_ms, long long now);
void touch_keeper_long_press_ended(float longpress_ms, long long now);

/** Takes note of what the turn's packet carries. */
void touch_keeper_note_packet(const struct Packet *pckt);

/******************************************************************************/
#ifdef __cplusplus
}
#endif
#endif
