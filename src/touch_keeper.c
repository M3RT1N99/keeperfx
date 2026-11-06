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
#include "post_inc.h"

#ifdef __cplusplus
extern "C" {
#endif
/******************************************************************************/
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

TbBool touch_keeper_over_gui(long x, long y)
{
    // The same reach as touch_assist_gui_pointer(), which would take the pointer away.
    const long slop = max(8, MyScreenHeight / 30);
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

void touch_keeper_stroke_end(void)
{
    th_stroke_end(the_hand());
}

static void current_tuning(float longpress_ms, struct ThTuning *tuning)
{
    tuning->hold_ms = touch_hand_hold_ms;
    tuning->rain_ms = touch_hand_rain_ms;
    tuning->slap_ms = touch_hand_slap_ms;
    tuning->longpress_ms = longpress_ms;
    tuning->whip_turns = player_instance_info[PI_Whip].length_turns;
    tuning->turn_ms = 1000.0f / (float)max(turns_per_second, 1);
}

static void what_is_seen(long x, long y, TbBool settled, struct ThSees *sees)
{
    memset(sees, 0, sizeof(*sees));
    sees->turn = (long long)get_gameturn();
    sees->lag = game.input_lag_turns;
    sees->in_force = touch_keeper_in_force() && !touch_keeper_over_gui(x, y);
#ifdef __ANDROID__
    if (!sees->in_force || !settled)
        return;
    struct Coord3d pos;
    unsigned char context;
    ThingIndex under;
    if (!touch_pointer_place(&pos, &context, &under))
        return;
    const struct Dungeon *dungeon = get_my_dungeon();
    sees->known = 1;
    sees->hand = dungeon->num_things_in_hand;
    sees->wall = (context == CSt_PickAxe);
    sees->door = (context == CSt_DoorKey);
    // With nothing in the hand yet this only asks about a place to wait at, so the generous answer.
    const TbBool on_path = (sees->hand > 0) ? is_creature_droppable_on_path(thing_get(dungeon->things_in_hand[0])) : true;
    sees->droppable = can_drop_thing_here(coord_subtile(pos.x.val), coord_subtile(pos.y.val), my_player_number, on_path);
    const struct Thing *thing = thing_get(under);
    sees->thing = (under != 0);
    if ((under != 0) && thing_is_creature(thing) && (thing->owner == my_player_number) && thing_slappable(thing, my_player_number))
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

enum ThClick touch_keeper_click_due(long x, long y, TbBool settled, float longpress_ms, long long now)
{
    struct ThSees sees;
    struct ThTuning tuning;
    what_is_seen(x, y, settled, &sees);
    current_tuning(longpress_ms, &tuning);
    return th_click_due(the_hand(), &sees, &tuning, now);
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
