/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/** @file touch_gesture.c
 *     The maths of holding the map with two fingers.
 * @par Purpose:
 *     Keeps the ground point under the fingers' midpoint under that midpoint:
 *     translation, zoom and rotation as one transform around it, with small gates
 *     so that a plain drag does not zoom and a plain pinch does not turn.
 * @par Comment:
 *     The gates work on the whole movement since the grip rather than on each
 *     frame's step. A closed gate holds the movement back, an open one hands over
 *     what it held within a moment, so nothing jumps and the fingers still end up
 *     on the points they took hold of.
 * @author   KeeperFX Team
 * @date     04 Nov 2026
 * @par  Copying and copyrights:
 *     This program is free software; you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation; either version 2 of the License, or
 *     (at your option) any later version.
 */
/******************************************************************************/
#include "touch_gesture.h"

#include <math.h>
#include <string.h>

#define TG_PI 3.14159265358979323846f

/** How far back a release looks for the speed of the fingers. */
#define TG_TRAIL_WINDOW_MS 90.0f
/** What is left of a catch-up below these is not worth another frame. */
#define TG_LEFT_PAN_PX 0.25f
#define TG_LEFT_ZOOM 0.0005f
#define TG_LEFT_TWIST_RAD 0.0005f
/** A rolling map stops at this share of the speed it takes to start one. */
#define TG_FLICK_STOP_SHARE 0.1f
/**
 * The zoom chase steps once the target is this many steps away. Past the half on
 * purpose: at exactly a half, a finger's tremble around that point would send a step
 * in and a step out turn after turn, and every step writes the settings file.
 */
#define TG_CHASE_STEPS 0.55f

static float tg_yaw_radians(const struct TgView *view, float yaw)
{
    return yaw * (2.0f * TG_PI) / view->angle_units;
}

void tg_screen_to_ground(const struct TgView *view, const struct TgCamera *cam, float height,
    float sx, float sy, float *gx, float *gy)
{
    const float scale = cam->zoom * view->px_per_zoom;
    const float ux = (sx - view->centre_x) / scale;
    const float uy = ((sy - view->centre_y) / scale + view->lift * height) / view->squash;
    const float yaw = tg_yaw_radians(view, cam->yaw);
    const float c = cosf(yaw);
    const float s = sinf(yaw);
    *gx = cam->x + ux * c - uy * s;
    *gy = cam->y + ux * s + uy * c;
}

void tg_ground_to_screen(const struct TgView *view, const struct TgCamera *cam, float height,
    float gx, float gy, float *sx, float *sy)
{
    const float scale = cam->zoom * view->px_per_zoom;
    const float yaw = tg_yaw_radians(view, cam->yaw);
    const float c = cosf(yaw);
    const float s = sinf(yaw);
    const float dx = gx - cam->x;
    const float dy = gy - cam->y;
    *sx = view->centre_x + scale * (dx * c + dy * s);
    *sy = view->centre_y + scale * (view->squash * (-dx * s + dy * c) - view->lift * height);
}

static float tg_clamp(float value, float low, float high)
{
    if (value < low)
        return low;
    if (value > high)
        return high;
    return value;
}

static void tg_midpoint(const struct TgFingers *f, float *x, float *y)
{
    *x = (f->ax + f->bx) * 0.5f;
    *y = (f->ay + f->by) * 0.5f;
}

/** Never zero: fingers landing on the same pixel must not divide anything by it. */
static float tg_distance(const struct TgFingers *f)
{
    const float d = hypotf(f->bx - f->ax, f->by - f->ay);
    return (d < 1.0f) ? 1.0f : d;
}

static float tg_angle(const struct TgFingers *f)
{
    return atan2f(f->by - f->ay, f->bx - f->ax);
}

void tg_grip(struct TgGesture *g, const struct TgFingers *fingers, const struct TgView *view,
    const struct TgCamera *cam, float height)
{
    memset(g, 0, sizeof(*g));
    g->origin = *cam;
    g->height = height;
    tg_midpoint(fingers, &g->mid0_x, &g->mid0_y);
    g->dist0 = tg_distance(fingers);
    g->prev_angle = tg_angle(fingers);
    tg_screen_to_ground(view, cam, height, g->mid0_x, g->mid0_y, &g->ground_x, &g->ground_y);
    g->trail[0].x = g->mid0_x;
    g->trail[0].y = g->mid0_y;
    g->trail_count = 1;
}

static void tg_remember_midpoint(struct TgGesture *g, float x, float y, float dt_ms)
{
    g->clock_ms += dt_ms;
    int keep = g->trail_count;
    if (keep == TG_TRAIL_SAMPLES) {
        memmove(&g->trail[0], &g->trail[1], sizeof(g->trail[0]) * (TG_TRAIL_SAMPLES - 1));
        keep--;
    }
    g->trail[keep].t_ms = g->clock_ms;
    g->trail[keep].x = x;
    g->trail[keep].y = y;
    g->trail_count = keep + 1;
}

/** Shrinks what a channel holds back, by the same amount per second at any frame rate. */
static float tg_catch_up(float left, float keep, float negligible)
{
    left *= keep;
    return (fabsf(left) < negligible) ? 0.0f : left;
}

/** The yaw in a view that only turns in steps: one per threshold, however long the twist. */
static float tg_stepped_yaw(struct TgGesture *g, const struct TgView *view, const struct TgTuning *tuning)
{
    while (g->twist - g->step_ref >= tuning->step_twist_rad) {
        g->step_ref += tuning->step_twist_rad;
        g->steps++;
    }
    while (g->twist - g->step_ref <= -tuning->step_twist_rad) {
        g->step_ref -= tuning->step_twist_rad;
        g->steps--;
    }
    return g->origin.yaw - (float)g->steps * view->yaw_step;
}

void tg_follow(struct TgGesture *g, const struct TgFingers *fingers, float dt_ms,
    const struct TgView *view, const struct TgTuning *tuning, struct TgCamera *cam)
{
    float mid_x;
    float mid_y;
    tg_midpoint(fingers, &mid_x, &mid_y);
    const float dist = tg_distance(fingers);
    const float angle = tg_angle(fingers);
    float turn = angle - g->prev_angle;
    if (turn > TG_PI)
        turn -= 2.0f * TG_PI;
    else if (turn < -TG_PI)
        turn += 2.0f * TG_PI;
    g->prev_angle = angle;
    g->twist += turn;
    tg_remember_midpoint(g, mid_x, mid_y, dt_ms);

    const float travel_x = mid_x - g->mid0_x;
    const float travel_y = mid_y - g->mid0_y;
    const float log_zoom = logf(dist / g->dist0);
    const float keep = expf(-dt_ms / tuning->catch_up_ms);
    if (g->pan_open) {
        g->pan_left_x = tg_catch_up(g->pan_left_x, keep, TG_LEFT_PAN_PX);
        g->pan_left_y = tg_catch_up(g->pan_left_y, keep, TG_LEFT_PAN_PX);
    } else {
        g->pan_open = (hypotf(travel_x, travel_y) >= tuning->pan_slop_px);
        g->pan_left_x = travel_x;
        g->pan_left_y = travel_y;
    }
    if (g->zoom_open) {
        g->zoom_left = tg_catch_up(g->zoom_left, keep, TG_LEFT_ZOOM);
    } else {
        g->zoom_open = (fabsf(dist - g->dist0) >= tuning->zoom_gate_px);
        g->zoom_left = log_zoom;
    }
    if (g->rotate_open) {
        g->twist_left = tg_catch_up(g->twist_left, keep, TG_LEFT_TWIST_RAD);
    } else {
        g->rotate_open = (fabsf(g->twist) >= tuning->rotate_gate_rad);
        g->twist_left = g->twist;
    }

    const float zoom = g->origin.zoom * expf(log_zoom - g->zoom_left);
    const float limited = tg_clamp(zoom, view->zoom_min, view->zoom_max);
    if (g->zoom_open && (limited != zoom)) {
        // Pulling past the limit moves the reference along, so that the way back
        // starts at once and not only when the fingers are back at the limit.
        g->dist0 = dist / expf(logf(limited / g->origin.zoom) + g->zoom_left);
    }
    cam->zoom = roundf(limited);

    float yaw = g->origin.yaw;
    if (view->yaw_step > 0.0f)
        yaw = tg_stepped_yaw(g, view, tuning);
    else
        yaw -= (g->twist - g->twist_left) * view->angle_units / (2.0f * TG_PI);
    cam->yaw = roundf(yaw);

    const float held_x = mid_x - g->pan_left_x;
    const float held_y = mid_y - g->pan_left_y;
    // With the camera at the origin the ground point under a pixel is that pixel's
    // offset from the camera, which leaves the camera itself to solve for.
    struct TgCamera at_origin = *cam;
    at_origin.x = 0.0f;
    at_origin.y = 0.0f;
    float offset_x;
    float offset_y;
    tg_screen_to_ground(view, &at_origin, g->height, held_x, held_y, &offset_x, &offset_y);
    const float x = g->ground_x - offset_x;
    const float y = g->ground_y - offset_y;
    cam->x = tg_clamp(x, 0.0f, view->limit_x);
    cam->y = tg_clamp(y, 0.0f, view->limit_y);
    if ((cam->x != x) || (cam->y != y)) {
        // The edge of the map stops the map, not the fingers. They slide over the
        // ground from here on, or coming back would be dead until they reach the
        // point where they ran into the edge.
        tg_screen_to_ground(view, cam, g->height, held_x, held_y, &g->ground_x, &g->ground_y);
    }
}

void tg_release_speed(const struct TgGesture *g, const struct TgTuning *tuning, float *vx, float *vy)
{
    *vx = 0.0f;
    *vy = 0.0f;
    if (!g->pan_open || (tuning->flick_decay <= 0.0f) || (g->trail_count < 2))
        return;
    const struct TgTrailSample *newest = &g->trail[g->trail_count - 1];
    const struct TgTrailSample *oldest = &g->trail[g->trail_count - 2];
    for (int i = g->trail_count - 2; i >= 0; i--) {
        if (newest->t_ms - g->trail[i].t_ms > TG_TRAIL_WINDOW_MS)
            break;
        oldest = &g->trail[i];
    }
    const float seconds = (newest->t_ms - oldest->t_ms) / 1000.0f;
    if (seconds <= 0.0f)
        return;
    const float x = (newest->x - oldest->x) / seconds;
    const float y = (newest->y - oldest->y) / seconds;
    if (hypotf(x, y) < tuning->flick_min_speed)
        return;
    *vx = x;
    *vy = y;
}

int tg_flick_step(float *vx, float *vy, float dt_ms, const struct TgView *view,
    const struct TgTuning *tuning, struct TgCamera *cam)
{
    const float scale = cam->zoom * view->px_per_zoom;
    const float yaw = tg_yaw_radians(view, cam->yaw);
    const float c = cosf(yaw);
    const float s = sinf(yaw);
    const float ux = *vx * dt_ms / 1000.0f / scale;
    const float uy = *vy * dt_ms / 1000.0f / (scale * view->squash);
    // The map rolls with the fingers, so the camera runs the other way.
    const float x = cam->x - (ux * c - uy * s);
    const float y = cam->y - (ux * s + uy * c);
    cam->x = tg_clamp(x, 0.0f, view->limit_x);
    cam->y = tg_clamp(y, 0.0f, view->limit_y);
    const float left = powf(tuning->flick_decay, dt_ms * 60.0f / 1000.0f);
    *vx *= left;
    *vy *= left;
    if ((cam->x != x) || (cam->y != y)
     || (hypotf(*vx, *vy) < tuning->flick_min_speed * TG_FLICK_STOP_SHARE)) {
        *vx = 0.0f;
        *vy = 0.0f;
        return 0;
    }
    return 1;
}

int tg_zoom_chase(int target, int current, int current_in, int current_out)
{
    if ((target <= 0) || (current <= 0))
        return 0;
    const double away = log((double)target / current);
    if ((current_in > current) && (away > TG_CHASE_STEPS * log((double)current_in / current)))
        return 1;
    if ((current_out > 0) && (current_out < current) && (-away > TG_CHASE_STEPS * log((double)current / current_out)))
        return -1;
    return 0;
}
