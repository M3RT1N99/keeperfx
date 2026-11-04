/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/** @file touch_steer.h
 *     Header file for touch_steer.c.
 * @par Purpose:
 *     The rules of steering a possessed creature with fingers. No SDL and no
 *     engine in here, so tests/touch can run all of it on its own.
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
#ifndef TOUCH_STEER_H
#define TOUCH_STEER_H

#ifdef __cplusplus
extern "C" {
#endif
/******************************************************************************/

/** Fingers followed at the same time; more than this are ignored. */
#define TS_MAX_FINGERS 5

/** What a finger is for, decided when it lands and kept until it lifts. */
enum TsRole {
    TsR_Free = 0,
    TsR_Stick,     /**< Walks the creature. */
    TsR_Look,      /**< Its travel turns the view, and a tap attacks. */
    TsR_Idle,      /**< Nothing, other than being one of the fingers of a tap. */
    TsR_Ascend,    /**< Holds the upper fly button. */
    TsR_Descend,   /**< Holds the lower fly button. */
    TsR_Pointer,   /**< On the panel or a menu: the game's pointer goes with it. */
};

/** The screen as far as the rules care, in screen pixels. */
struct TsScreen {
    float width, height;
    float panel_right;   /**< Where the panel ends; 0 when there is none. */
    float stick_radius;
    int flying;          /**< The creature flies, which is when the fly buttons exist. */
};

struct TsCircle {
    float x, y;
    float radius;
};

struct TsFinger {
    unsigned long long id;
    unsigned char role;
    unsigned char may_tap;  /**< So far short and still enough to turn out a tap. */
    unsigned char still;    /**< Has not gone further than a tap may. */
    unsigned char flowing;  /**< Has started to turn the view. */
    float x0, y0;           /**< Where it landed. */
    float x, y;
    long long down_ms;
};

/** What came of the fingers since it was last asked. */
struct TsActions {
    int attacks;
    int right_click;
    int escape;
    int pointer_click;
    float click_x, click_y;
};

struct TsSteer {
    struct TsFinger fingers[TS_MAX_FINGERS];
    /* Travel of the look finger the view has not been given yet, in pixels. */
    float owed_x, owed_y;
    /* The tap being watched: all the fingers that landed on top of each other. A tap
       is only known once none of them can still become one. */
    int tap_members;
    int tap_lifted;
    unsigned char tap_role;
    float tap_x, tap_y;
    struct TsActions actions;
};

void ts_reset(struct TsSteer *s);

/**
 * A finger landed.
 * @param on_menu It landed on a menu other than the panel, which the rules cannot know.
 */
void ts_finger_down(struct TsSteer *s, unsigned long long id, float x, float y, long long ms,
    const struct TsScreen *screen, int on_menu);
void ts_finger_move(struct TsSteer *s, unsigned long long id, float x, float y, long long ms,
    const struct TsScreen *screen);
/** A finger lifted. With canceled the system took it away, which nobody meant as a tap. */
void ts_finger_up(struct TsSteer *s, unsigned long long id, long long ms, int canceled);

/** Lets time pass: a finger resting past the tap time stops being a tap, which can settle another's. */
void ts_tick(struct TsSteer *s, long long ms);

/** Hands over what the fingers have asked for since the last call, and forgets it. */
void ts_take_actions(struct TsSteer *s, struct TsActions *actions);

/**
 * Takes this turn's share of the look travel, in whole units of turn. Whatever a
 * fraction or the limit keeps back stays owed for the next turn, so nothing is lost.
 */
void ts_take_look(struct TsSteer *s, float units_per_px, int max_turn, int *turn_x, int *turn_y);

/** Forgets the look travel not given yet. */
void ts_drop_look(struct TsSteer *s);

/** Where the stick is pushed: forward and right are each -1, 0 or 1. */
void ts_walk(const struct TsSteer *s, float stick_radius, int *forward, int *right);

/** True while a finger with this role is down. */
int ts_holding(const struct TsSteer *s, enum TsRole role);

/** Where the pointer finger is. @return false when there is none. */
int ts_pointer_at(const struct TsSteer *s, float *x, float *y);

/** The ring and the knob of the stick. @return false when no thumb is down. */
int ts_stick_shape(const struct TsSteer *s, float stick_radius, struct TsCircle *ring, struct TsCircle *knob);

/** Where the fly buttons are; radius is what shows, a touch counts a little further out. */
void ts_fly_buttons(const struct TsScreen *screen, struct TsCircle *up, struct TsCircle *down);

/******************************************************************************/
#ifdef __cplusplus
}
#endif
#endif
