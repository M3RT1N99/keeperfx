/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/** @file touch_gesture_tests.c
 *     Tests of the two finger gesture maths in src/touch_gesture.c.
 * @par Comment:
 *     Runs without a device, an engine or SDL. What the fingers do is scripted
 *     and the only thing looked at is where the map ends up on screen.
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
#include <stdio.h>

static int failures = 0;
static int checks = 0;

#define CHECK(cond, ...) do { \
    checks++; \
    if (!(cond)) { \
        failures++; \
        printf("FAILED %s:%d: ", __func__, __LINE__); \
        printf(__VA_ARGS__); \
        printf("\n"); \
    } \
} while (0)

#define PI 3.14159265358979323846f
#define FRAME_MS (1000.0f / 60.0f)

/* The phone this is tuned on: a 720 high picture beside the panel, default tilt. */
static struct TgView phone_view(void)
{
    struct TgView v;
    v.centre_x = 894.0f;
    v.centre_y = 360.0f;
    v.px_per_zoom = (28.0f / 16.0f) / 65536.0f;
    v.squash = sinf(266.0f * 2.0f * PI / 2048.0f);
    v.lift = cosf(266.0f * 2.0f * PI / 2048.0f);
    v.angle_units = 2048.0f;
    v.yaw_step = 0.0f;
    v.limit_x = 65279.0f;
    v.limit_y = 65279.0f;
    v.zoom_min = 520.0f;
    v.zoom_max = 12000.0f;
    return v;
}

static struct TgTuning phone_tuning(void)
{
    struct TgTuning t;
    t.pan_slop_px = 10.0f;
    t.zoom_gate_px = 14.4f;
    t.rotate_gate_rad = 0.10f;
    t.catch_up_ms = 33.0f;
    t.step_twist_rad = 0.5f;
    t.flick_min_speed = 180.0f;
    t.flick_decay = 0.93f;
    return t;
}

static struct TgCamera some_camera(float zoom, float yaw)
{
    struct TgCamera c;
    c.x = 30000.0f;
    c.y = 28000.0f;
    c.zoom = zoom;
    c.yaw = yaw;
    return c;
}

/* Fingers from a centre, half their distance and an angle. */
static struct TgFingers fingers_at(float x, float y, float half, float angle)
{
    struct TgFingers f;
    f.ax = x - half * cosf(angle);
    f.ay = y - half * sinf(angle);
    f.bx = x + half * cosf(angle);
    f.by = y + half * sinf(angle);
    return f;
}

static void held_point_on_screen(const struct TgGesture *g, const struct TgView *v, const struct TgCamera *c, float *sx, float *sy)
{
    tg_ground_to_screen(v, c, g->height, g->ground_x, g->ground_y, sx, sy);
}

static void test_projection_round_trip(void)
{
    const struct TgView v = phone_view();
    const float yaws[] = {0.0f, 256.0f, 512.0f, 777.0f, 1800.0f};
    for (int i = 0; i < 5; i++) {
        struct TgCamera c = some_camera(5000.0f, yaws[i]);
        float gx, gy, sx, sy;
        tg_screen_to_ground(&v, &c, 256.0f, 400.0f, 200.0f, &gx, &gy);
        tg_ground_to_screen(&v, &c, 256.0f, gx, gy, &sx, &sy);
        CHECK(fabsf(sx - 400.0f) < 0.1f && fabsf(sy - 200.0f) < 0.1f, "yaw %g: came back at %g,%g", yaws[i], sx, sy);
    }
    /* The camera's own position shows in the middle of the view, at height 0. */
    struct TgCamera c = some_camera(5000.0f, 300.0f);
    float sx, sy;
    tg_ground_to_screen(&v, &c, 0.0f, c.x, c.y, &sx, &sy);
    CHECK(fabsf(sx - v.centre_x) < 0.01f && fabsf(sy - v.centre_y) < 0.01f, "camera shows at %g,%g", sx, sy);
    /* The engine's rightwards and downwards, from view_move_camera_x and _y. */
    c.yaw = 512.0f;
    float rx, ry;
    tg_ground_to_screen(&v, &c, 0.0f, c.x, c.y + 100.0f, &rx, &ry);
    CHECK(rx > v.centre_x + 1.0f && fabsf(ry - v.centre_y) < 0.1f, "at a quarter turn +y must be rightwards, went to %g,%g", rx, ry);
    tg_ground_to_screen(&v, &c, 0.0f, c.x + 100.0f, c.y, &rx, &ry);
    CHECK(ry < v.centre_y - 1.0f && fabsf(rx - v.centre_x) < 0.1f, "at a quarter turn +x must be upwards, went to %g,%g", rx, ry);
}

static void test_grip_does_not_move_anything(void)
{
    const struct TgView v = phone_view();
    const struct TgTuning t = phone_tuning();
    struct TgGesture g;
    struct TgCamera c = some_camera(4000.0f, 300.0f);
    const struct TgCamera start = c;
    const struct TgFingers f = fingers_at(800.0f, 300.0f, 70.0f, 0.3f);
    tg_grip(&g, &f, &v, &c, 256.0f);
    for (int i = 0; i < 30; i++)
        tg_follow(&g, &f, FRAME_MS, &v, &t, &c);
    CHECK(fabsf(c.x - start.x) < 0.1f && fabsf(c.y - start.y) < 0.1f && c.zoom == start.zoom && c.yaw == start.yaw,
        "still fingers moved the camera to %g,%g zoom %g yaw %g", c.x, c.y, c.zoom, c.yaw);
}

/* Moves the midpoint in a straight line and returns how far the held point is from it afterwards. */
static float pan_error(float zoom, float yaw, float height, float dx, float dy)
{
    const struct TgView v = phone_view();
    const struct TgTuning t = phone_tuning();
    struct TgGesture g;
    struct TgCamera c = some_camera(zoom, yaw);
    const float x0 = 800.0f, y0 = 300.0f;
    struct TgFingers f = fingers_at(x0, y0, 70.0f, 0.0f);
    tg_grip(&g, &f, &v, &c, height);
    for (int i = 1; i <= 30; i++) {
        f = fingers_at(x0 + dx * i / 30.0f, y0 + dy * i / 30.0f, 70.0f, 0.0f);
        tg_follow(&g, &f, FRAME_MS, &v, &t, &c);
    }
    for (int i = 0; i < 12; i++)
        tg_follow(&g, &f, FRAME_MS, &v, &t, &c);
    float sx, sy;
    held_point_on_screen(&g, &v, &c, &sx, &sy);
    return hypotf(sx - (x0 + dx), sy - (y0 + dy));
}

static void test_pan_holds_the_ground(void)
{
    const float zooms[] = {520.0f, 1500.0f, 5000.0f, 12000.0f};
    const float yaws[] = {0.0f, 256.0f, 700.0f, 1024.0f, 1999.0f};
    const float heights[] = {0.0f, 256.0f, 1280.0f};
    for (int z = 0; z < 4; z++)
        for (int y = 0; y < 5; y++)
            for (int h = 0; h < 3; h++) {
                const float err = pan_error(zooms[z], yaws[y], heights[h], 300.0f, -120.0f);
                CHECK(err < 1.0f, "zoom %g yaw %g height %g: held point is %g px off", zooms[z], yaws[y], heights[h], err);
            }
}

static void test_pan_slop_then_catches_up_without_a_jump(void)
{
    const struct TgView v = phone_view();
    const struct TgTuning t = phone_tuning();
    struct TgGesture g;
    struct TgCamera c = some_camera(4000.0f, 300.0f);
    const float x0 = 800.0f, y0 = 300.0f;
    struct TgFingers f = fingers_at(x0, y0, 70.0f, 0.0f);
    tg_grip(&g, &f, &v, &c, 256.0f);
    float last_sx = x0, last_sy = y0;
    float biggest_step = 0.0f;
    /* Three pixels per frame: the slop is used up on the fourth. */
    for (int i = 1; i <= 3; i++) {
        f = fingers_at(x0 + 3.0f * i, y0, 70.0f, 0.0f);
        tg_follow(&g, &f, FRAME_MS, &v, &t, &c);
        float sx, sy;
        held_point_on_screen(&g, &v, &c, &sx, &sy);
        CHECK(fabsf(sx - x0) < 0.5f && fabsf(sy - y0) < 0.5f, "moved at %g px of travel, before the slop", 3.0f * i);
    }
    for (int i = 4; i <= 40; i++) {
        f = fingers_at(x0 + 3.0f * i, y0, 70.0f, 0.0f);
        tg_follow(&g, &f, FRAME_MS, &v, &t, &c);
        float sx, sy;
        held_point_on_screen(&g, &v, &c, &sx, &sy);
        const float step = hypotf(sx - last_sx, sy - last_sy);
        if (step > biggest_step)
            biggest_step = step;
        last_sx = sx;
        last_sy = sy;
        if (i == 10) /* 100 ms after the gate */
            CHECK(fabsf(sx - (x0 + 3.0f * i)) < 1.5f, "still %g px behind 100 ms after the slop", (x0 + 3.0f * i) - sx);
    }
    /* A jump would be the whole slop at once; catching up may at most add a part of it. */
    CHECK(biggest_step < 8.0f, "the map jumped %g px in one frame", biggest_step);
    CHECK(fabsf(last_sx - (x0 + 120.0f)) < 0.5f, "ended %g px off", last_sx - (x0 + 120.0f));
}

static void test_a_plain_drag_neither_zooms_nor_turns(void)
{
    const struct TgView v = phone_view();
    const struct TgTuning t = phone_tuning();
    struct TgGesture g;
    struct TgCamera c = some_camera(4000.0f, 300.0f);
    struct TgFingers f = fingers_at(800.0f, 300.0f, 70.0f, 0.0f);
    tg_grip(&g, &f, &v, &c, 256.0f);
    for (int i = 1; i <= 60; i++) {
        /* A hand is not a machine: a little of everything goes with it. */
        f = fingers_at(800.0f + 5.0f * i, 300.0f, 70.0f + 5.0f * sinf(i * 0.4f), 0.05f * sinf(i * 0.3f));
        tg_follow(&g, &f, FRAME_MS, &v, &t, &c);
    }
    CHECK(c.zoom == 4000.0f && c.yaw == 300.0f, "zoom became %g, yaw %g", c.zoom, c.yaw);
}

static void test_a_pinch_scales_around_the_midpoint(void)
{
    const float yaws[] = {0.0f, 300.0f, 1024.0f};
    for (int y = 0; y < 3; y++) {
        const struct TgView v = phone_view();
        const struct TgTuning t = phone_tuning();
        struct TgGesture g;
        struct TgCamera c = some_camera(2000.0f, yaws[y]);
        struct TgFingers f = fingers_at(700.0f, 250.0f, 70.0f, 0.0f);
        tg_grip(&g, &f, &v, &c, 256.0f);
        float last_zoom = c.zoom;
        float biggest_step = 1.0f;
        for (int i = 1; i <= 30; i++) {
            f = fingers_at(700.0f, 250.0f, 70.0f * (1.0f + 0.5f * i / 30.0f), 0.0f);
            tg_follow(&g, &f, FRAME_MS, &v, &t, &c);
            const float step = c.zoom / last_zoom;
            if (step > biggest_step)
                biggest_step = step;
            last_zoom = c.zoom;
        }
        for (int i = 0; i < 12; i++)
            tg_follow(&g, &f, FRAME_MS, &v, &t, &c);
        CHECK(fabsf(c.zoom / 2000.0f - 1.5f) < 0.01f, "yaw %g: scale %g for fingers at 1.5", yaws[y], c.zoom / 2000.0f);
        CHECK(biggest_step < 1.08f, "yaw %g: the zoom jumped by %g in a frame", yaws[y], biggest_step);
        float sx, sy;
        held_point_on_screen(&g, &v, &c, &sx, &sy);
        CHECK(hypotf(sx - 700.0f, sy - 250.0f) < 1.0f, "yaw %g: held point %g,%g", yaws[y], sx, sy);
    }
}

/* One hand: the thumb rests, the finger pulls. The thumb must stay on its ground point. */
static void test_a_pinch_with_one_finger_keeps_the_other_in_place(void)
{
    const struct TgView v = phone_view();
    const struct TgTuning t = phone_tuning();
    struct TgGesture g;
    struct TgCamera c = some_camera(2000.0f, 0.0f);
    struct TgFingers f = {600.0f, 300.0f, 800.0f, 300.0f};
    tg_grip(&g, &f, &v, &c, 256.0f);
    float gx, gy;
    tg_screen_to_ground(&v, &c, 256.0f, 600.0f, 300.0f, &gx, &gy);
    for (int i = 1; i <= 30; i++) {
        f.bx = 800.0f + 5.0f * i;
        tg_follow(&g, &f, FRAME_MS, &v, &t, &c);
    }
    for (int i = 0; i < 12; i++)
        tg_follow(&g, &f, FRAME_MS, &v, &t, &c);
    float sx, sy;
    tg_ground_to_screen(&v, &c, 256.0f, gx, gy, &sx, &sy);
    CHECK(hypotf(sx - 600.0f, sy - 300.0f) < 1.5f, "the resting finger's point went to %g,%g", sx, sy);
}

static void test_a_twist_turns_with_the_fingers(void)
{
    const struct TgView v = phone_view();
    const struct TgTuning t = phone_tuning();
    struct TgGesture g;
    struct TgCamera c = some_camera(3000.0f, 300.0f);
    struct TgFingers f = fingers_at(760.0f, 330.0f, 130.0f, 0.0f);
    tg_grip(&g, &f, &v, &c, 256.0f);
    /* Under the gate nothing turns. */
    f = fingers_at(760.0f, 330.0f, 130.0f, 0.08f);
    tg_follow(&g, &f, FRAME_MS, &v, &t, &c);
    CHECK(c.yaw == 300.0f, "turned before the gate, yaw %g", c.yaw);
    float last_yaw = c.yaw;
    float biggest = 0.0f;
    const float radians = 40.0f * PI / 180.0f;
    for (int i = 1; i <= 30; i++) {
        f = fingers_at(760.0f, 330.0f, 130.0f, 0.08f + (radians - 0.08f) * i / 30.0f);
        tg_follow(&g, &f, FRAME_MS, &v, &t, &c);
        if (fabsf(c.yaw - last_yaw) > biggest)
            biggest = fabsf(c.yaw - last_yaw);
        last_yaw = c.yaw;
    }
    for (int i = 0; i < 12; i++)
        tg_follow(&g, &f, FRAME_MS, &v, &t, &c);
    const float turned = (300.0f - c.yaw) * 360.0f / 2048.0f;
    CHECK(fabsf(turned - 40.0f) < 0.3f, "picture turned %g degrees for fingers at 40", turned);
    /* A jump would be the whole gate and a frame of twist at once, about seven degrees. */
    CHECK(biggest < 4.5f * 2048.0f / 360.0f, "the yaw jumped %g units in a frame", biggest);
    float sx, sy;
    held_point_on_screen(&g, &v, &c, &sx, &sy);
    CHECK(hypotf(sx - 760.0f, sy - 330.0f) < 1.0f, "held point went to %g,%g", sx, sy);
}

/* Fingers turning clockwise on the screen must take a point to the right of them downwards. */
static void test_the_picture_turns_the_way_the_fingers_do(void)
{
    const struct TgView v = phone_view();
    const struct TgTuning t = phone_tuning();
    struct TgGesture g;
    struct TgCamera c = some_camera(3000.0f, 300.0f);
    struct TgFingers f = fingers_at(760.0f, 330.0f, 130.0f, 0.0f);
    tg_grip(&g, &f, &v, &c, 0.0f);
    float gx, gy;
    tg_screen_to_ground(&v, &c, 0.0f, 900.0f, 330.0f, &gx, &gy);
    for (int i = 1; i <= 40; i++) {
        f = fingers_at(760.0f, 330.0f, 130.0f, 0.5f * i / 40.0f);
        tg_follow(&g, &f, FRAME_MS, &v, &t, &c);
    }
    float sx, sy;
    tg_ground_to_screen(&v, &c, 0.0f, gx, gy, &sx, &sy);
    CHECK(sy > 340.0f, "the point on the right went to %g,%g", sx, sy);
}

static void test_twist_over_the_half_turn(void)
{
    const struct TgView v = phone_view();
    const struct TgTuning t = phone_tuning();
    struct TgGesture g;
    struct TgCamera c = some_camera(3000.0f, 300.0f);
    /* atan2 flips from +pi to -pi on the way: the twist has to carry on through it. */
    struct TgFingers f = fingers_at(760.0f, 330.0f, 130.0f, PI - 0.5f);
    tg_grip(&g, &f, &v, &c, 0.0f);
    for (int i = 1; i <= 50; i++) {
        f = fingers_at(760.0f, 330.0f, 130.0f, PI - 0.5f + 1.0f * i / 50.0f);
        tg_follow(&g, &f, FRAME_MS, &v, &t, &c);
    }
    for (int i = 0; i < 12; i++)
        tg_follow(&g, &f, FRAME_MS, &v, &t, &c);
    const float turned = (300.0f - c.yaw) * 2.0f * PI / 2048.0f;
    CHECK(fabsf(turned - 1.0f) < 0.01f, "turned %g radians for a twist of 1", turned);
}

/* All three together, from the moment the gates are open. */
static void test_everything_at_once(void)
{
    const struct TgView v = phone_view();
    const struct TgTuning t = phone_tuning();
    struct TgGesture g;
    struct TgCamera c = some_camera(3000.0f, 300.0f);
    struct TgFingers f = fingers_at(760.0f, 330.0f, 100.0f, 0.2f);
    tg_grip(&g, &f, &v, &c, 256.0f);
    const float x = 760.0f + 150.0f, y = 330.0f - 60.0f;
    for (int i = 1; i <= 40; i++) {
        const float s = i / 40.0f;
        f = fingers_at(760.0f + 150.0f * s, 330.0f - 60.0f * s, 100.0f * (1.0f + 0.3f * s), 0.2f + 0.6f * s);
        tg_follow(&g, &f, FRAME_MS, &v, &t, &c);
    }
    for (int i = 0; i < 12; i++)
        tg_follow(&g, &f, FRAME_MS, &v, &t, &c);
    float sx, sy;
    held_point_on_screen(&g, &v, &c, &sx, &sy);
    CHECK(hypotf(sx - x, sy - y) < 1.0f, "held point at %g,%g, fingers at %g,%g", sx, sy, x, y);
    CHECK(fabsf(c.zoom / 3000.0f - 1.3f) < 0.01f, "scale %g", c.zoom / 3000.0f);
    CHECK(fabsf((300.0f - c.yaw) * 2.0f * PI / 2048.0f - 0.6f) < 0.01f, "turned %g", (300.0f - c.yaw) * 2.0f * PI / 2048.0f);
}

static void test_the_map_edge_stops_the_map_not_the_way_back(void)
{
    const struct TgView v = phone_view();
    const struct TgTuning t = phone_tuning();
    struct TgGesture g;
    /* A few hundred units from the edge, heading for it. At yaw 0, dragging right moves the camera towards x = 0. */
    struct TgCamera c = some_camera(5000.0f, 0.0f);
    c.x = 200.0f;
    struct TgFingers f = fingers_at(700.0f, 300.0f, 70.0f, 0.0f);
    tg_grip(&g, &f, &v, &c, 0.0f);
    for (int i = 1; i <= 40; i++) {
        f = fingers_at(700.0f + 10.0f * i, 300.0f, 70.0f, 0.0f);
        tg_follow(&g, &f, FRAME_MS, &v, &t, &c);
    }
    CHECK(c.x == 0.0f, "the edge did not stop the camera, x = %g", c.x);
    /* Coming back moves the map again straight away. */
    for (int i = 1; i <= 3; i++) {
        f = fingers_at(1100.0f - 10.0f * i, 300.0f, 70.0f, 0.0f);
        tg_follow(&g, &f, FRAME_MS, &v, &t, &c);
    }
    const float scale = 5000.0f * v.px_per_zoom;
    CHECK(fabsf(c.x * scale - 30.0f) < 2.0f, "30 px back moved the map %g px", c.x * scale);
}

static void test_a_zoom_limit_has_no_dead_zone_either(void)
{
    const struct TgView v = phone_view();
    const struct TgTuning t = phone_tuning();
    struct TgGesture g;
    struct TgCamera c = some_camera(10000.0f, 0.0f);
    struct TgFingers f = fingers_at(700.0f, 300.0f, 70.0f, 0.0f);
    tg_grip(&g, &f, &v, &c, 0.0f);
    for (int i = 1; i <= 40; i++) {
        f = fingers_at(700.0f, 300.0f, 70.0f + 3.0f * i, 0.0f);
        tg_follow(&g, &f, FRAME_MS, &v, &t, &c);
    }
    CHECK(c.zoom == 12000.0f, "zoom %g past the limit", c.zoom);
    const float half = 70.0f + 120.0f;
    f = fingers_at(700.0f, 300.0f, half * 0.9f, 0.0f);
    for (int i = 0; i < 12; i++)
        tg_follow(&g, &f, FRAME_MS, &v, &t, &c);
    CHECK(fabsf(c.zoom / 12000.0f - 0.9f) < 0.01f, "fingers back by 10%% left the zoom at %g", c.zoom);
}

static void test_a_stepped_view_turns_once_per_threshold(void)
{
    struct TgView v = phone_view();
    v.yaw_step = 512.0f;
    v.squash = 1.0f;
    v.lift = 0.5f;
    const struct TgTuning t = phone_tuning();
    struct TgGesture g;
    struct TgCamera c = some_camera(3000.0f, 0.0f);
    struct TgFingers f = fingers_at(760.0f, 330.0f, 130.0f, 0.0f);
    tg_grip(&g, &f, &v, &c, 0.0f);
    f = fingers_at(760.0f, 330.0f, 130.0f, 0.3f);
    tg_follow(&g, &f, FRAME_MS, &v, &t, &c);
    CHECK(c.yaw == 0.0f, "stepped at 0.3 rad, yaw %g", c.yaw);
    f = fingers_at(760.0f, 330.0f, 130.0f, 0.55f);
    tg_follow(&g, &f, FRAME_MS, &v, &t, &c);
    CHECK(c.yaw == -512.0f, "yaw %g after one threshold", c.yaw);
    /* Trembling around the threshold must not go back and forth. */
    for (int i = 0; i < 20; i++) {
        f = fingers_at(760.0f, 330.0f, 130.0f, 0.5f + 0.06f * ((i & 1) ? 1.0f : -1.0f));
        tg_follow(&g, &f, FRAME_MS, &v, &t, &c);
        CHECK(c.yaw == -512.0f, "yaw %g while trembling at the threshold", c.yaw);
    }
    f = fingers_at(760.0f, 330.0f, 130.0f, 1.05f);
    tg_follow(&g, &f, FRAME_MS, &v, &t, &c);
    CHECK(c.yaw == -1024.0f, "yaw %g after two thresholds", c.yaw);
    float sx, sy;
    held_point_on_screen(&g, &v, &c, &sx, &sy);
    CHECK(hypotf(sx - 760.0f, sy - 330.0f) < 1.0f, "the step moved the held point to %g,%g", sx, sy);
}

static float release_speed_after(float px_per_frame, int hold_frames, float frame_ms)
{
    const struct TgView v = phone_view();
    const struct TgTuning t = phone_tuning();
    struct TgGesture g;
    struct TgCamera c = some_camera(3000.0f, 0.0f);
    struct TgFingers f = fingers_at(700.0f, 300.0f, 70.0f, 0.0f);
    tg_grip(&g, &f, &v, &c, 0.0f);
    float x = 700.0f;
    for (int i = 0; i < 30; i++) {
        x += px_per_frame;
        f = fingers_at(x, 300.0f, 70.0f, 0.0f);
        tg_follow(&g, &f, frame_ms, &v, &t, &c);
    }
    for (int i = 0; i < hold_frames; i++)
        tg_follow(&g, &f, frame_ms, &v, &t, &c);
    float vx, vy;
    tg_release_speed(&g, &t, &vx, &vy);
    return hypotf(vx, vy);
}

static void test_only_a_release_with_speed_rolls_on(void)
{
    const float fast = release_speed_after(12.0f, 0, FRAME_MS);
    CHECK(fabsf(fast - 720.0f) < 30.0f, "12 px per frame came out as %g px/s", fast);
    CHECK(release_speed_after(12.0f, 12, FRAME_MS) == 0.0f, "fingers that came to rest still flicked");
    CHECK(release_speed_after(1.0f, 0, FRAME_MS) == 0.0f, "a slow drag flicked");
    const float slow_frames = release_speed_after(36.0f, 0, 50.0f);
    CHECK(fabsf(slow_frames - 720.0f) < 30.0f, "at 20 frames a second 720 px/s came out as %g", slow_frames);
}

static float roll_distance(float frame_ms, const struct TgTuning *t)
{
    const struct TgView v = phone_view();
    struct TgCamera c = some_camera(3000.0f, 0.0f);
    const float x0 = c.x;
    float vx = 800.0f, vy = 0.0f;
    int guard = 0;
    while (tg_flick_step(&vx, &vy, frame_ms, &v, t, &c) && (guard++ < 100000))
        ;
    return (x0 - c.x) * 3000.0f * v.px_per_zoom;
}

static void test_a_flick_rolls_the_same_at_any_frame_rate(void)
{
    const struct TgTuning t = phone_tuning();
    const float reference = roll_distance(FRAME_MS, &t);
    CHECK(reference > 150.0f, "800 px/s rolled only %g px", reference);
    const float rates[] = {1000.0f / 120.0f, 1000.0f / 30.0f, 50.0f};
    for (int i = 0; i < 3; i++) {
        const float d = roll_distance(rates[i], &t);
        CHECK(fabsf(d / reference - 1.0f) < 0.15f, "%g ms per frame rolled %g px, 60 Hz rolled %g", rates[i], d, reference);
    }
}

static void test_a_flick_stops_at_the_edge(void)
{
    const struct TgView v = phone_view();
    const struct TgTuning t = phone_tuning();
    struct TgCamera c = some_camera(3000.0f, 0.0f);
    c.x = 100.0f;
    float vx = 3000.0f, vy = 0.0f;
    int frames = 0;
    while (tg_flick_step(&vx, &vy, FRAME_MS, &v, &t, &c) && (frames < 1000))
        frames++;
    CHECK(c.x == 0.0f && vx == 0.0f && frames < 100, "x %g speed %g after %d frames", c.x, vx, frames);
}

/* The engine's stepping rules, the way packets.c applies them. */
static int step_in(int zoom)
{
    int z = 100 * zoom / 85;
    if (z == zoom)
        z++;
    return (z > 12000) ? 12000 : z;
}

static int step_out(int zoom)
{
    int z = 85 * zoom / 100;
    if (z == zoom)
        z--;
    return (z < 520) ? 520 : z;
}

static void test_the_chase_stays_within_half_a_step(void)
{
    /* The fingers go wherever, one flag per turn, and when they stop the steps have to end close. */
    const int targets[] = {3000, 3050, 3300, 3600, 9000, 12000, 700, 520, 2500};
    int canon = 3000;
    for (int i = 0; i < 9; i++) {
        const int target = targets[i];
        int flags = 0;
        for (int turn = 0; turn < 100; turn++) {
            const int dir = tg_zoom_chase(target, canon, step_in(canon), step_out(canon));
            if (dir > 0)
                canon = step_in(canon);
            else if (dir < 0)
                canon = step_out(canon);
            else
                break;
            flags++;
        }
        CHECK(flags < 40, "%d flags to reach %d", flags, target);
        const double away = fabs(log((double)target / canon));
        CHECK(away <= 0.55 * log(100.0 / 85.0) + 0.001, "stopped at %d for %d, %g in log", canon, target, away);
        CHECK(tg_zoom_chase(target, canon, step_in(canon), step_out(canon)) == 0, "does not settle at %d for %d", canon, target);
    }
}

static void test_the_chase_does_not_tremble(void)
{
    /* Sitting right at the half way point, with a finger trembling by a percent. */
    int canon = 3000;
    const double mid = sqrt(3000.0 * step_in(3000));
    int changes = 0;
    for (int i = 0; i < 200; i++) {
        const int target = (int)(mid * ((i & 1) ? 1.008 : 0.992));
        const int dir = tg_zoom_chase(target, canon, step_in(canon), step_out(canon));
        if (dir > 0) {
            canon = step_in(canon);
            changes++;
        } else if (dir < 0) {
            canon = step_out(canon);
            changes++;
        }
    }
    CHECK(changes <= 1, "%d flags for a trembling finger", changes);
}

static void test_the_chase_respects_the_limits(void)
{
    CHECK(tg_zoom_chase(20000, 12000, step_in(12000), step_out(12000)) == 0, "stepped in past the maximum");
    CHECK(tg_zoom_chase(100, 520, step_in(520), step_out(520)) == 0, "stepped out past the minimum");
    CHECK(tg_zoom_chase(0, 3000, step_in(3000), step_out(3000)) == 0, "chased a zoom of nothing");
}

int main(void)
{
    test_projection_round_trip();
    test_grip_does_not_move_anything();
    test_pan_holds_the_ground();
    test_pan_slop_then_catches_up_without_a_jump();
    test_a_plain_drag_neither_zooms_nor_turns();
    test_a_pinch_scales_around_the_midpoint();
    test_a_pinch_with_one_finger_keeps_the_other_in_place();
    test_a_twist_turns_with_the_fingers();
    test_the_picture_turns_the_way_the_fingers_do();
    test_twist_over_the_half_turn();
    test_everything_at_once();
    test_the_map_edge_stops_the_map_not_the_way_back();
    test_a_zoom_limit_has_no_dead_zone_either();
    test_a_stepped_view_turns_once_per_threshold();
    test_only_a_release_with_speed_rolls_on();
    test_a_flick_rolls_the_same_at_any_frame_rate();
    test_a_flick_stops_at_the_edge();
    test_the_chase_stays_within_half_a_step();
    test_the_chase_does_not_tremble();
    test_the_chase_respects_the_limits();
    printf("%d checks, %d failed\n", checks, failures);
    return failures ? 1 : 0;
}
