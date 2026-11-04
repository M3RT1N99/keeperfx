/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/** @file touch_possess.c
 *     Fingers steering a possessed creature.
 * @par Purpose:
 *     Applies the rules in touch_steer.c to the game: when they are in force, the
 *     screen they see, the look and the keys they produce, and what they draw.
 * @par Comment:
 *     The packet stays what a keyboard and a mouse send, the same flags and the same
 *     absolute angles, so nothing a finger does can reach another client differently.
 * @author   KeeperFX Team
 * @date     04 Nov 2026
 * @par  Copying and copyrights:
 *     This program is free software; you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation; either version 2 of the License, or
 *     (at your option) any later version.
 */
/******************************************************************************/
#include "pre_inc.h"
#include "touch_possess.h"

#include <math.h>
#include <stdlib.h>

#include "bflib_touch.h"
#include "config_creature.h"
#include "config_settings.h"
#include "front_input.h"
#include "frontend.h"
#include "game_legacy.h"
#include "gui_boxmenu.h"
#include "gui_frontmenu.h"
#include "keeperfx.hpp"
#include "kfx/renderer/RendererManager.h"
#include "local_camera.h"
#include "local_possession.h"
#include "observer.h"
#include "player_data.h"
#include "replay.h"
#include "thing_data.h"
#include "touch_steer.h"
#include "vidfade.h"
#include "post_inc.h"

#ifdef __cplusplus
extern "C" {
#endif
/******************************************************************************/
/** The height the look sensitivity is given for. */
#define TOUCH_LOOK_REFERENCE_HEIGHT 720.0f
/** Width of the rings, as a share of the screen height. */
#define TOUCH_RING_SHARE (4.0f / 720.0f)

float touch_look_sensitivity = 0.20f;
float touch_stick_radius_percent = 15.0f;

static struct TsSteer steer;
/******************************************************************************/

TbBool touch_possess_steering(void)
{
    if (!touch_controls_active() || (game.game_kind == GKind_Unset))
        return false;
    if (get_local_view_type() != PVT_CreatureContrl)
        return false;
    if (replay.load_enable || observer_is_active() || ((game.mode_flags & MFlg_IsDemoMode) != 0))
        return false;
    if ((get_local_user_state()->init_flags & (UsrIF_CreaturePassengerMode | UsrIF_MouseInputDisabled)) != 0)
        return false;
    return !cheat_menu_is_active() && !a_menu_window_is_active();
}

void touch_possess_reset(void)
{
    ts_reset(&steer);
}

static void current_screen(struct TsScreen *screen)
{
    const struct Thing *thing = thing_get(get_local_user_state()->controlled_thing_idx);
    screen->width = (float)RendererPhysicalWidth();
    screen->height = (float)RendererPhysicalHeight();
    screen->panel_right = local_state.show_gui ? (float)status_panel_width : 0.0f;
    screen->stick_radius = screen->height * touch_stick_radius_percent / 100.0f;
    screen->flying = thing_exists(thing) && flag_is_set(thing->movement_flags, TMvF_Flying);
}

void touch_possess_finger_down(unsigned long long id, long x, long y, long long ms)
{
    struct TsScreen screen;
    current_screen(&screen);
    const TbBool on_menu = (point_is_over_gui_menu(x, y) >= 0) || point_is_over_gui_box(x, y);
    ts_finger_down(&steer, id, (float)x, (float)y, ms, &screen, on_menu);
}

void touch_possess_finger_move(unsigned long long id, long x, long y, long long ms)
{
    struct TsScreen screen;
    current_screen(&screen);
    ts_finger_move(&steer, id, (float)x, (float)y, ms, &screen);
}

void touch_possess_finger_up(unsigned long long id, long long ms)
{
    ts_finger_up(&steer, id, ms);
}

void touch_possess_finger_cancel(unsigned long long id)
{
    ts_finger_cancel(&steer, id);
}

void touch_possess_poll(long long now_ms, struct TsActions *actions)
{
    ts_tick(&steer, now_ms);
    ts_take_actions(&steer, actions);
}

TbBool touch_possess_pointer_at(long *x, long *y)
{
    float px;
    float py;
    if (!ts_pointer_at(&steer, &px, &py))
        return false;
    *x = (long)px;
    *y = (long)py;
    return true;
}

TbBool touch_possess_owns_keys(void)
{
    return (game.game_kind != GKind_Unset) && (get_local_view_type() == PVT_CreatureContrl);
}

float touch_possess_axis(long key_id)
{
    // The game asks about every key it knows, many times a turn, so these come first.
    switch (key_id)
    {
    case Gkey_MoveUp:
    case Gkey_MoveDown:
    case Gkey_MoveRight:
    case Gkey_MoveLeft:
    case Gkey_Ascend:
    case Gkey_Descend:
        break;
    default:
        return 0.0f;
    }
    if (!touch_possess_steering())
        return 0.0f;
    const float radius = (float)RendererPhysicalHeight() * touch_stick_radius_percent / 100.0f;
    int forward;
    int right;
    ts_walk(&steer, radius, &forward, &right);
    switch (key_id)
    {
    case Gkey_MoveUp:    return (forward > 0) ? 1.0f : 0.0f;
    case Gkey_MoveDown:  return (forward < 0) ? 1.0f : 0.0f;
    case Gkey_MoveRight: return (right > 0) ? 1.0f : 0.0f;
    case Gkey_MoveLeft:  return (right < 0) ? 1.0f : 0.0f;
    case Gkey_Ascend:    return ts_holding(&steer, TsR_Ascend) ? 1.0f : 0.0f;
    default:             return ts_holding(&steer, TsR_Descend) ? 1.0f : 0.0f;
    }
}

void touch_possess_take_look(struct Thing *thing, int32_t *turn_x, int32_t *turn_y)
{
    *turn_x = 0;
    *turn_y = 0;
    // A view that cannot turn just now does not collect what the finger does meanwhile,
    // which would come out as a jolt the moment it can.
    if (!thing_exists(thing) || local_possession_look_frozen(thing))
    {
        ts_drop_look(&steer);
        return;
    }
    const float height = (float)RendererPhysicalHeight();
    const float units_per_degree = (float)DEGREES_360 / 360.0f;
    const float units_per_px = touch_look_sensitivity * units_per_degree * TOUCH_LOOK_REFERENCE_HEIGHT / height;
    // The same limit the look is cut to further on, here so that the rest is kept.
    const int max_turn = max((int)creature_stats_get_from_thing(thing)->max_turning_speed, 1);
    int x;
    int y;
    ts_take_look(&steer, units_per_px, max_turn, &x, &y);
    *turn_x = x;
    *turn_y = settings.first_person_move_invert ? y : -y;
}

/** A disc, or with inner above zero a ring, one scan line at a time. */
static void draw_circle(long cx, long cy, long outer, long inner, TbPixel colour)
{
    for (long dy = -outer; dy <= outer; dy++)
    {
        const long out = (long)sqrtf((float)(outer * outer - dy * dy));
        if (labs(dy) < inner)
        {
            const long in = (long)sqrtf((float)(inner * inner - dy * dy));
            UIRenderer_SubmitSolidBox(cx - out, cy + dy, out - in, 1, colour);
            UIRenderer_SubmitSolidBox(cx + in + 1, cy + dy, out - in, 1, colour);
        } else
        {
            UIRenderer_SubmitSolidBox(cx - out, cy + dy, 2 * out + 1, 1, colour);
        }
    }
}

/** An arrow pointing up or down: direction -1 is up, 1 is down. It widens away from its tip. */
static void draw_arrow(long cx, long cy, long size, int direction, TbPixel colour)
{
    for (long row = 0; row <= size; row++)
    {
        const long half = row * 2 / 3 + 1;
        const long y = cy + direction * (size / 2 - row);
        UIRenderer_SubmitSolidBox(cx - half, y, 2 * half + 1, 1, colour);
    }
}

static void draw_fly_button(const struct TsCircle *button, long ring, int direction)
{
    const long x = (long)button->x;
    const long y = (long)button->y;
    const long radius = (long)button->radius;
    draw_circle(x, y, radius, 0, colours[3][2][2]);
    draw_circle(x, y, radius, radius - ring, colours[12][11][9]);
    draw_arrow(x, y, radius * 3 / 5, direction, colours[15][14][12]);
}

void touch_possess_draw(void)
{
    if (!RendererCanDraw() || !touch_possess_steering())
        return;
    struct TsScreen screen;
    current_screen(&screen);
    const long ring_width = max((long)(screen.height * TOUCH_RING_SHARE), 1L);
    if (screen.flying)
    {
        struct TsCircle up;
        struct TsCircle down;
        ts_fly_buttons(&screen, &up, &down);
        draw_fly_button(&up, ring_width, -1);
        draw_fly_button(&down, ring_width, 1);
    }
    struct TsCircle ring;
    struct TsCircle knob;
    if (ts_stick_shape(&steer, screen.stick_radius, &ring, &knob))
    {
        const long radius = (long)ring.radius;
        draw_circle((long)ring.x, (long)ring.y, radius, radius - ring_width, colours[15][14][12]);
        draw_circle((long)knob.x, (long)knob.y, (long)knob.radius, 0, colours[15][9][2]);
    }
}

/******************************************************************************/
#ifdef __cplusplus
}
#endif
