/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/** @file touch_gesture.h
 *     Header file for touch_gesture.c.
 * @par Purpose:
 *     The maths of holding the map with two fingers. No SDL and no engine in
 *     here, so tests/touch can run all of it on its own.
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
#ifndef TOUCH_GESTURE_H
#define TOUCH_GESTURE_H

#ifdef __cplusplus
extern "C" {
#endif
/******************************************************************************/

/** Two fingers, in screen pixels. */
struct TgFingers {
    float ax, ay, bx, by;
};

/**
 * How the ground reaches the screen. The projection is parallel, so a point at
 * height h goes to
 *   sx = centre_x + scale * ( dx * cos(yaw) + dy * sin(yaw))
 *   sy = centre_y + scale * (squash * (-dx * sin(yaw) + dy * cos(yaw)) - lift * h)
 * where dx, dy is the point relative to the camera and scale is zoom * px_per_zoom.
 */
struct TgView {
    float centre_x, centre_y;
    float px_per_zoom;
    float squash;       /**< Foreshortening of the ground plane, the sine of the tilt. */
    float lift;         /**< Share of a unit of height that shows on screen. */
    float angle_units;  /**< Yaw units in a full turn. */
    float yaw_step;     /**< The yaw only moves in steps of this; 0 when it is free. */
    float limit_x, limit_y;      /**< The camera stays inside 0..limit. */
    float zoom_min, zoom_max;
};

/** The part of a camera a gesture moves, in the engine's units. */
struct TgCamera {
    float x, y;
    float zoom;
    float yaw;
};

struct TgTuning {
    float pan_slop_px;       /**< Midpoint travel before the map moves. */
    float zoom_gate_px;      /**< Change of the finger distance before the zoom moves. */
    float rotate_gate_rad;   /**< Twist before the yaw moves. */
    float catch_up_ms;       /**< Time constant of a channel catching up after its gate. */
    float step_twist_rad;    /**< Twist per step, in a view whose yaw moves in steps. */
    float flick_min_speed;   /**< Pixels per second a release needs to roll on. */
    float flick_decay;       /**< Share of the speed left after 1/60 s; 0 turns flicks off. */
};

#define TG_TRAIL_SAMPLES 16

struct TgTrailSample {
    float t_ms;
    float x, y;
};

/** One grip of the map, from the fingers landing until a finger is added or lifted. */
struct TgGesture {
    struct TgCamera origin;
    float ground_x, ground_y;  /**< The map point held under the fingers. */
    float height;              /**< The height of the surface that point is on. */
    float mid0_x, mid0_y;
    float dist0;
    float prev_angle;
    float twist;
    /* How much of each movement the map has not been given yet. All of it while a
       gate is closed, then shrinking to nothing, so that opening one does not jump. */
    float pan_left_x, pan_left_y;
    float zoom_left;
    float twist_left;
    unsigned char pan_open, zoom_open, rotate_open;
    float step_ref;
    int steps;
    struct TgTrailSample trail[TG_TRAIL_SAMPLES];
    int trail_count;
    float clock_ms;
};

void tg_screen_to_ground(const struct TgView *view, const struct TgCamera *cam, float height,
    float sx, float sy, float *gx, float *gy);
void tg_ground_to_screen(const struct TgView *view, const struct TgCamera *cam, float height,
    float gx, float gy, float *sx, float *sy);

/** Takes hold of the map at the fingers' midpoint, on the surface at the given height. */
void tg_grip(struct TgGesture *g, const struct TgFingers *fingers, const struct TgView *view,
    const struct TgCamera *cam, float height);

/**
 * Moves the camera so that the map follows the fingers. Writes zoom and yaw rounded to
 * whole engine units and the position solved for those, so the rounding does not show.
 */
void tg_follow(struct TgGesture *g, const struct TgFingers *fingers, float dt_ms,
    const struct TgView *view, const struct TgTuning *tuning, struct TgCamera *cam);

/** The speed a release leaves the map with, in screen pixels per second; 0, 0 for none. */
void tg_release_speed(const struct TgGesture *g, const struct TgTuning *tuning, float *vx, float *vy);

/**
 * One frame of a released map rolling on. @return false once it has stopped, which
 * is also when it reaches the edge of the map.
 */
int tg_flick_step(float *vx, float *vy, float dt_ms, const struct TgView *view,
    const struct TgTuning *tuning, struct TgCamera *cam);

/**
 * Decides what the flag stepped zoom has to do to follow a continuous one. The steps
 * come from whoever knows the stepping rules; a step that does not change the zoom
 * means the limit is reached.
 * @return 1 to step in, -1 to step out, 0 when target is near enough to current.
 */
int tg_zoom_chase(int target, int current, int current_in, int current_out);

/******************************************************************************/
#ifdef __cplusplus
}
#endif
#endif
