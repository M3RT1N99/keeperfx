/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/** @file touch_keeper.c
 *     What the rules of the hand read from the game.
 * @par Purpose:
 *     Feeds the rules in touch_hand.c: when they are in force, what is under the
 *     finger, and what the turn's packet carried.
 * @par Comment:
 *     Only reads. The clicks these rules ask for go out as a mouse's do, the same flags
 *     in the same packet, so nothing a finger does can reach another client differently.
 *     What is under the finger is the game's own answer about the pointer, the one the
 *     packet is going to carry, so it is only good once a frame has been drawn with the
 *     pointer there. The hooks for that are Android's, and so are these rules.
 * @author   KeeperFX Team
 * @date     06 Nov 2026
 * @par  Copying and copyrights:
 *     This program is free software; you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation; either version 2 of the License, or
 *     (at your option) any later version.
 */
/******************************************************************************/
#include "pre_inc.h"
#include "touch_keeper.h"

#include <math.h>
#include <string.h>

#include "bflib_touch.h"
#include "bflib_video.h"
#include "dungeon_data.h"
#include "front_input.h"
#include "frontend.h"
#include "game_legacy.h"
#include "gui_boxmenu.h"
#include "gui_frontmenu.h"
#include "gui_parchment.h"
#include "keeperfx.hpp"
#include "local_camera.h"
#include "map_data.h"
#include "observer.h"
#include "packets.h"
#include "player_data.h"
#include "player_instances.h"
#include "power_hand.h"
#include "replay.h"
#include "thing_creature.h"
#include "thing_data.h"
#include "touch_camera.h"
#include "post_inc.h"

#ifdef __cplusplus
extern "C" {
#endif
/******************************************************************************/
/** What a finger resting on glass trembles by, the same as a possessed creature's look allows. */
#define TOUCH_HAND_REST_SHARE (6.0f / 720.0f)

float touch_hand_hold_ms = 250.0f;
float touch_hand_rain_ms = 100.0f;
float touch_hand_slap_ms = 150.0f;

static struct ThHand hand;
static TbBool hand_ready = false;
/******************************************************************************/

static struct ThHand *the_hand(void)
{
    if (!hand_ready) {
        th_reset(&hand);
        hand_ready = true;
    }
    return &hand;
}

TbBool touch_keeper_in_force(void)
{
#ifdef __ANDROID__
    if (!touch_controls_active() || (game.game_kind == GKind_Unset))
        return false;
    if ((get_local_view_type() != PVT_DungeonTop) || (get_local_map_fade() != PVT_None))
        return false;
    if (flag_is_set(game.operation_flags, GOF_Paused) || (get_my_player()->victory_state == VicS_LostLevel))
        return false;
    if (replay.load_enable || observer_is_active() || ((game.mode_flags & MFlg_IsDemoMode) != 0))
        return false;
    if (get_local_user_state_prediction()->work_state != PSt_CtrlDungeon)
        return false;
    if ((get_local_user_state()->init_flags & (UsrIF_KeyboardInputDisabled | UsrIF_MouseInputDisabled)) != 0)
        return false;
    return !cheat_menu_is_active() && !a_menu_window_is_active() && !game_is_busy_doing_gui_string_input();
#else
    return false;
#endif
}

/** The same reach as touch_assist_gui_pointer(), which would take the pointer away. */
static long finger_reach(void)
{
    return max(8, MyScreenHeight / 30);
}

TbBool touch_keeper_over_panel(long x)
{
    return local_state.show_gui && (x < (long)status_panel_width + finger_reach());
}

TbBool touch_keeper_over_gui(long x, long y)
{
    const long slop = finger_reach();
    for (int i = 0; i < ACTIVE_MENUS_COUNT; i++)
    {
        const struct GuiMenu *gmnu = &active_menus[i];
        if ((gmnu->visual_state == 0) || (!gmnu->is_turned_on))
            continue;
        if ((x >= gmnu->pos_x - slop) && (x < gmnu->pos_x + gmnu->width + slop)
         && (y >= gmnu->pos_y - slop) && (y < gmnu->pos_y + gmnu->height + slop))
            return true;
    }
    return point_is_over_gui_box(x, y);
}

void touch_keeper_stroke_begin(long long ms)
{
    th_stroke_begin(the_hand(), ms);
}

void touch_keeper_stroke_moved(long long ms)
{
    th_stroke_moved(the_hand(), ms);
}

void touch_keeper_stroke_cancel(void)
{
    th_stroke_cancel(the_hand());
}

enum ThLift touch_keeper_stroke_lift(void)
{
    return th_stroke_lift(the_hand(), touch_keeper_in_force());
}

TbBool touch_keeper_start_unseen(void)
{
    return th_stroke_start_unseen(the_hand()) && touch_keeper_in_force();
}

static void current_tuning(float longpress_ms, struct ThTuning *tuning)
{
    tuning->hold_ms = touch_hand_hold_ms;
    tuning->rain_ms = touch_hand_rain_ms;
    tuning->slap_ms = touch_hand_slap_ms;
    tuning->longpress_ms = longpress_ms;
    tuning->whip_turns = player_instance_info[PI_Whip].length_turns;
    tuning->turn_ms = 1000.0f / (float)max(turns_per_second, 1);
    tuning->rest_px = (float)MyScreenHeight * TOUCH_HAND_REST_SHARE;
}

#ifdef __ANDROID__
/** How far a point is from a piece of a finger's path, on the screen. */
static float distance_to_path(float x0, float y0, float x1, float y1, float px, float py)
{
    const float dx = x1 - x0;
    const float dy = y1 - y0;
    const float length2 = dx * dx + dy * dy;
    float share = (length2 > 0.0f) ? (((px - x0) * dx + (py - y0) * dy) / length2) : 0.0f;
    if (share < 0.0f)
        share = 0.0f;
    if (share > 1.0f)
        share = 1.0f;
    return hypotf(px - (x0 + dx * share), py - (y0 + dy * share));
}

/** An own creature a right click with an empty hand would slap. */
static TbBool own_slappable(const struct Thing *thing)
{
    return !thing_is_invalid(thing) && thing_is_creature(thing) && (thing->owner == my_player_number)
        && thing_slappable(thing, my_player_number);
}
#endif

static void what_is_seen(long x, long y, TbBool settled, struct ThSees *sees)
{
    memset(sees, 0, sizeof(*sees));
    sees->turn = (long long)get_gameturn();
    sees->lag = game.input_lag_turns;
    sees->x = (float)x;
    sees->y = (float)y;
    sees->in_force = touch_keeper_in_force() && !touch_keeper_over_gui(x, y);
#ifdef __ANDROID__
    if (!sees->in_force)
        return;
    const struct Dungeon *dungeon = get_my_dungeon();
    // The hand is the simulation's, whatever the pointer is over.
    sees->hand = dungeon->num_things_in_hand;
    if (!settled)
        return;
    struct Coord3d pos;
    unsigned char context;
    ThingIndex under;
    if (!touch_pointer_place(&pos, &context, &under))
        return;
    sees->known = 1;
    sees->wall = (context == CSt_PickAxe);
    sees->door = (context == CSt_DoorKey);
    // With nothing in the hand yet this only asks about a place to wait at, so the generous answer.
    const TbBool on_path = (sees->hand > 0) ? is_creature_droppable_on_path(thing_get(dungeon->things_in_hand[0])) : true;
    sees->droppable = can_drop_thing_here(coord_subtile(pos.x.val), coord_subtile(pos.y.val), my_player_number, on_path);
    sees->thing = (under != 0);
    // The game only looks for a thing under the hand where a left button would not dig.
    if ((under != 0) && own_slappable(thing_get(under)))
        sees->creature = under;
#endif
}

enum ThVerdict touch_keeper_verdict(long x, long y, TbBool settled, float longpress_ms, long long now)
{
    struct ThSees sees;
    struct ThTuning tuning;
    what_is_seen(x, y, settled, &sees);
    current_tuning(longpress_ms, &tuning);
    return th_decide(the_hand(), &sees, &tuning, now);
}

TbBool touch_keeper_drop_due(long x, long y, TbBool settled, float longpress_ms)
{
    struct ThSees sees;
    struct ThTuning tuning;
    what_is_seen(x, y, settled, &sees);
    current_tuning(longpress_ms, &tuning);
    return th_drop_due(the_hand(), &sees, &tuning);
}

void touch_keeper_look_at_start(long x, long y)
{
    struct ThSees sees;
    what_is_seen(x, y, true, &sees);
    th_stroke_look(the_hand(), &sees);
}

void touch_keeper_stroke_path(long x0, long y0, long x1, long y1, long long now)
{
#ifdef __ANDROID__
    struct ThHand *h = the_hand();
    if (!th_stroke_looks_for_crossings(h))
        return;
    struct ThSees sees;
    struct ThTuning tuning;
    what_is_seen(x1, y1, false, &sees);
    // A long press plays no part in what a path crosses.
    current_tuning(0.0f, &tuning);
    const float step = touch_camera_floor_step_px();
    if (!sees.in_force || (step <= 0.0f))
        return;
    float xs[TH_PATH_SAMPLES];
    float ys[TH_PATH_SAMPLES];
    const int count = th_path_samples((float)x0, (float)y0, (float)x1, (float)y1, step, xs, ys, TH_PATH_SAMPLES);
    for (int i = 0; i < count; i++)
    {
        MapCoord pos_x;
        MapCoord pos_y;
        if (touch_keeper_over_gui((long)xs[i], (long)ys[i]) || !touch_camera_floor_at(xs[i], ys[i], &pos_x, &pos_y))
            continue;
        // The same search the game makes for the thing under the hand, from the floor under this point.
        const struct Thing *thing = get_nearest_thing_for_hand_or_slap(my_player_number, pos_x, pos_y);
        if (!own_slappable(thing))
            continue;
        // Aimed at the floor under the creature itself, where the game's own search cannot miss it:
        // the point of the path that found it can be at the far edge of the hand's reach, and the
        // creature walks on. Its own height would put a flying one's aim over another place's floor.
        float aim_x;
        float aim_y;
        const TbBool shown = touch_camera_floor_screen_at(thing->mappos.x.val, thing->mappos.y.val, &aim_x, &aim_y) && (aim_x >= 0.0f) && (aim_y >= 0.0f)
            && (aim_x < (float)MyScreenWidth) && (aim_y < (float)MyScreenHeight) && !touch_keeper_over_gui((long)aim_x, (long)aim_y);
        if (!shown)
        {
            aim_x = xs[i];
            aim_y = ys[i];
        }
        // Gone over, and not only within the hand's reach, when the creature's place is within a
        // finger of the path.
        const TbBool over = shown && (distance_to_path((float)x0, (float)y0, (float)x1, (float)y1, aim_x, aim_y) <= (float)(2 * finger_reach()));
        if (th_stroke_crossed(h, &sees, &tuning, thing->index, aim_x, aim_y, over, now))
            return;
    }
#endif
}

TbBool touch_keeper_slap_aim(long *x, long *y)
{
    float aim_x;
    float aim_y;
    if (!th_slap_aim(the_hand(), &aim_x, &aim_y))
        return false;
    *x = lroundf(aim_x);
    *y = lroundf(aim_y);
    return true;
}

TbBool touch_keeper_slap_now(long x, long y)
{
    struct ThSees sees;
    struct ThTuning tuning;
    what_is_seen(x, y, true, &sees);
    current_tuning(0.0f, &tuning);
    return th_slap_now(the_hand(), &sees, &tuning);
}

TbBool touch_keeper_long_press_gives_way(long x, long y, TbBool settled, float longpress_ms)
{
    struct ThSees sees;
    struct ThTuning tuning;
    what_is_seen(x, y, settled, &sees);
    current_tuning(longpress_ms, &tuning);
    return th_long_press_gives_way(the_hand(), &sees, &tuning);
}

void touch_keeper_long_press_ended(float longpress_ms)
{
    struct ThTuning tuning;
    current_tuning(longpress_ms, &tuning);
    th_long_press_ended(the_hand(), &tuning);
}

void touch_keeper_note_packet(const struct Packet *pckt)
{
    const TbBool gui = (pckt->control_flags & PCtr_Gui) != 0;
    const TbBool right_released = !gui && ((pckt->control_flags & PCtr_RBtnRelease) != 0);
    const TbBool picking = (pckt->action == PckA_UsePwrHandPick) || (!gui && ((pckt->control_flags & PCtr_LBtnRelease) != 0));
    th_note_packet(the_hand(), (long long)get_gameturn(), game.input_lag_turns, right_released, picking);
}

/******************************************************************************/
#ifdef __cplusplus
}
#endif
