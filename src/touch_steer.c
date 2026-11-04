/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/** @file touch_steer.c
 *     The rules of steering a possessed creature with fingers.
 * @par Purpose:
 *     A thumb that lands on the left half of the view is a floating stick, a finger
 *     elsewhere looks and attacks. Each finger keeps the role it landed with, which
 *     is what lets a thumb walk while another finger looks, taps or holds a button.
 * @par Comment:
 *     Whether a press is a tap cannot be known until it has ended, so a look finger's
 *     travel is held back until it can no longer be a tap and then handed over whole:
 *     a tap does not move the view and a drag does not lose its start. Whether several
 *     fingers made one tap takes the last of them to settle, so a lifted finger waits
 *     for those that landed on top of it.
 * @author   KeeperFX Team
 * @date     04 Nov 2026
 * @par  Copying and copyrights:
 *     This program is free software; you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation; either version 2 of the License, or
 *     (at your option) any later version.
 */
/******************************************************************************/
#include "touch_steer.h"

#include <math.h>
#include <string.h>

/** Share of the width right of the panel that belongs to the stick. */
#define TS_STICK_ZONE_SHARE 0.45f
/** Share of its radius the stick has to be pushed along an axis for that axis to count. */
#define TS_STICK_THRESHOLD 0.38f
/** A press at least this long is no tap. */
#define TS_TAP_MS 240
/** A press that gets this far from where it landed is no tap, in shares of the screen height. */
#define TS_TAP_SLOP_SHARE (20.0f / 720.0f)
/**
 * Travel that starts the view turning once a finger can no longer be a tap. Less than
 * the tap's, or a slow careful aim would sit dead and then jump; not none, because a
 * finger resting on glass always trembles a little and is meant to do nothing.
 */
#define TS_REST_SLOP_SHARE (6.0f / 720.0f)
/* The fly buttons. */
#define TS_FLY_RADIUS_SHARE 0.44f   /* of the stick's radius at the default size */
#define TS_DEFAULT_STICK_SHARE 0.15f
#define TS_FLY_EDGE_SHARE (24.0f / 720.0f)
#define TS_FLY_GAP_SHARE (16.0f / 720.0f)
#define TS_FLY_HIT_SHARE (12.0f / 720.0f)

void ts_reset(struct TsSteer *s)
{
    memset(s, 0, sizeof(*s));
}

static struct TsFinger *ts_find(struct TsSteer *s, unsigned long long id)
{
    for (int i = 0; i < TS_MAX_FINGERS; i++) {
        if ((s->fingers[i].role != TsR_Free) && (s->fingers[i].id == id))
            return &s->fingers[i];
    }
    return NULL;
}

static const struct TsFinger *ts_find_role(const struct TsSteer *s, enum TsRole role)
{
    for (int i = 0; i < TS_MAX_FINGERS; i++) {
        if (s->fingers[i].role == role)
            return &s->fingers[i];
    }
    return NULL;
}

void ts_fly_buttons(const struct TsScreen *screen, struct TsCircle *up, struct TsCircle *down)
{
    const float radius = screen->height * TS_DEFAULT_STICK_SHARE * TS_FLY_RADIUS_SHARE;
    const float x = screen->width - radius - screen->height * TS_FLY_EDGE_SHARE;
    const float away = radius + screen->height * TS_FLY_GAP_SHARE;
    up->x = x;
    up->y = screen->height * 0.5f - away;
    up->radius = radius;
    *down = *up;
    down->y = screen->height * 0.5f + away;
}

static int ts_touches(const struct TsCircle *button, const struct TsScreen *screen, float x, float y)
{
    return hypotf(x - button->x, y - button->y) < button->radius + screen->height * TS_FLY_HIT_SHARE;
}

static unsigned char ts_role_for(const struct TsSteer *s, float x, float y, const struct TsScreen *screen, int on_menu)
{
    if (on_menu || (x < screen->panel_right))
        return ts_find_role(s, TsR_Pointer) ? TsR_Idle : TsR_Pointer;
    if (screen->flying) {
        struct TsCircle up;
        struct TsCircle down;
        ts_fly_buttons(screen, &up, &down);
        if (ts_touches(&up, screen, x, y))
            return TsR_Ascend;
        if (ts_touches(&down, screen, x, y))
            return TsR_Descend;
    }
    const float zone_end = screen->panel_right + (screen->width - screen->panel_right) * TS_STICK_ZONE_SHARE;
    if ((x < zone_end) && !ts_find_role(s, TsR_Stick))
        return TsR_Stick;
    return ts_find_role(s, TsR_Look) ? TsR_Idle : TsR_Look;
}

/** Once no finger can still join a tap, it is known what the ones that did amount to. */
static void ts_settle_tap(struct TsSteer *s)
{
    for (int i = 0; i < TS_MAX_FINGERS; i++) {
        if ((s->fingers[i].role != TsR_Free) && s->fingers[i].may_tap)
            return;
    }
    if (s->tap_lifted == 1) {
        // Also when another finger landed with it and stayed: that is a thumb on the
        // stick and a tap beside it.
        if (s->tap_role == TsR_Look) {
            s->actions.attacks++;
        } else if (s->tap_role == TsR_Pointer) {
            s->actions.pointer_click = 1;
            s->actions.click_x = s->tap_x;
            s->actions.click_y = s->tap_y;
        }
    } else if (s->tap_lifted == s->tap_members) {
        if (s->tap_lifted == 2)
            s->actions.right_click = 1;
        else if (s->tap_lifted >= 3)
            s->actions.escape = 1;
    }
    s->tap_members = 0;
    s->tap_lifted = 0;
}

void ts_finger_down(struct TsSteer *s, unsigned long long id, float x, float y, long long ms,
    const struct TsScreen *screen, int on_menu)
{
    for (int i = 0; i < TS_MAX_FINGERS; i++) {
        struct TsFinger *f = &s->fingers[i];
        if (f->role != TsR_Free)
            continue;
        const unsigned char role = ts_role_for(s, x, y, screen, on_menu);
        memset(f, 0, sizeof(*f));
        f->id = id;
        f->role = role;
        // A button is held, never tapped: a quick press of it beside a landing thumb
        // must not read as two fingers tapping, which would end the possession.
        f->may_tap = (role != TsR_Ascend) && (role != TsR_Descend);
        f->still = 1;
        f->x0 = f->x = x;
        f->y0 = f->y = y;
        f->down_ms = ms;
        if (f->may_tap)
            s->tap_members++;
        return;
    }
}

void ts_finger_move(struct TsSteer *s, unsigned long long id, float x, float y, long long ms,
    const struct TsScreen *screen)
{
    struct TsFinger *f = ts_find(s, id);
    if (f == NULL)
        return;
    const float travel = hypotf(x - f->x0, y - f->y0);
    const float tap_slop = screen->height * TS_TAP_SLOP_SHARE;
    if (f->role == TsR_Look) {
        if (f->flowing) {
            s->owed_x += x - f->x;
            s->owed_y += y - f->y;
        } else if (travel >= (((ms - f->down_ms) < TS_TAP_MS) ? tap_slop : screen->height * TS_REST_SLOP_SHARE)) {
            f->flowing = 1;
            s->owed_x += x - f->x0;
            s->owed_y += y - f->y0;
        }
    }
    f->x = x;
    f->y = y;
    if (travel >= tap_slop) {
        f->still = 0;
        if (f->may_tap) {
            f->may_tap = 0;
            ts_settle_tap(s);
        }
    }
}

void ts_finger_up(struct TsSteer *s, unsigned long long id, long long ms, int canceled)
{
    struct TsFinger *f = ts_find(s, id);
    if (f == NULL)
        return;
    if (canceled) {
        // The fingers of a tap go together: the system taking one away leaves no tap.
        for (int i = 0; i < TS_MAX_FINGERS; i++)
            s->fingers[i].may_tap = 0;
        s->tap_members = 0;
        s->tap_lifted = 0;
    } else if (f->may_tap && ((ms - f->down_ms) < TS_TAP_MS)) {
        s->tap_lifted++;
        s->tap_role = f->role;
        s->tap_x = f->x;
        s->tap_y = f->y;
    } else if ((f->role == TsR_Pointer) && f->still) {
        // The panel has no use for the difference between a tap and a slow press.
        s->actions.pointer_click = 1;
        s->actions.click_x = f->x;
        s->actions.click_y = f->y;
    }
    f->role = TsR_Free;
    f->may_tap = 0;
    ts_settle_tap(s);
}

void ts_tick(struct TsSteer *s, long long ms)
{
    int changed = 0;
    for (int i = 0; i < TS_MAX_FINGERS; i++) {
        struct TsFinger *f = &s->fingers[i];
        if ((f->role != TsR_Free) && f->may_tap && ((ms - f->down_ms) >= TS_TAP_MS)) {
            f->may_tap = 0;
            changed = 1;
        }
    }
    if (changed)
        ts_settle_tap(s);
}

void ts_take_actions(struct TsSteer *s, struct TsActions *actions)
{
    *actions = s->actions;
    memset(&s->actions, 0, sizeof(s->actions));
}

static int ts_take_axis(float *owed_px, float units_per_px, int max_turn)
{
    int units = (int)(*owed_px * units_per_px);
    if (units > max_turn)
        units = max_turn;
    else if (units < -max_turn)
        units = -max_turn;
    *owed_px -= (float)units / units_per_px;
    return units;
}

void ts_take_look(struct TsSteer *s, float units_per_px, int max_turn, int *turn_x, int *turn_y)
{
    *turn_x = ts_take_axis(&s->owed_x, units_per_px, max_turn);
    *turn_y = ts_take_axis(&s->owed_y, units_per_px, max_turn);
}

void ts_drop_look(struct TsSteer *s)
{
    s->owed_x = 0.0f;
    s->owed_y = 0.0f;
}

static int ts_axis(float push)
{
    if (push > TS_STICK_THRESHOLD)
        return 1;
    if (push < -TS_STICK_THRESHOLD)
        return -1;
    return 0;
}

void ts_walk(const struct TsSteer *s, float stick_radius, int *forward, int *right)
{
    *forward = 0;
    *right = 0;
    const struct TsFinger *f = ts_find_role(s, TsR_Stick);
    if (f == NULL)
        return;
    *forward = -ts_axis((f->y - f->y0) / stick_radius);
    *right = ts_axis((f->x - f->x0) / stick_radius);
}

int ts_holding(const struct TsSteer *s, enum TsRole role)
{
    return ts_find_role(s, role) != NULL;
}

int ts_pointer_at(const struct TsSteer *s, float *x, float *y)
{
    const struct TsFinger *f = ts_find_role(s, TsR_Pointer);
    if (f == NULL)
        return 0;
    *x = f->x;
    *y = f->y;
    return 1;
}

int ts_stick_shape(const struct TsSteer *s, float stick_radius, struct TsCircle *ring, struct TsCircle *knob)
{
    const struct TsFinger *f = ts_find_role(s, TsR_Stick);
    if (f == NULL)
        return 0;
    const float dx = f->x - f->x0;
    const float dy = f->y - f->y0;
    const float length = hypotf(dx, dy);
    const float reach = (length > stick_radius) ? stick_radius / length : 1.0f;
    ring->x = f->x0;
    ring->y = f->y0;
    ring->radius = stick_radius;
    knob->x = f->x0 + dx * reach;
    knob->y = f->y0 + dy * reach;
    knob->radius = stick_radius * 0.42f;
    return 1;
}
