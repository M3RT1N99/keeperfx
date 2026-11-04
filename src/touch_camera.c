/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/** @file touch_camera.c
 *     Two fingers holding the map.
 * @par Purpose:
 *     Applies the gesture maths in touch_gesture.c to the local camera, on every
 *     drawn frame, and keeps the dungeon camera in step with it.
 * @par Comment:
 *     Moving the previous and the destination pose by the same amount shows at once
 *     and leaves the interpolation between turns alone. Position and angle then reach
 *     the dungeon camera by themselves, as absolute values in every turn's packet. The
 *     zoom does not: it only moves in flags of one step, so those have to chase the
 *     local zoom, which the same flags must then not step a second time.
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
#include "touch_camera.h"

#include <math.h>

#include "bflib_datetm.h"
#include "bflib_video.h"
#include "engine_camera.h"
#include "frontend.h"
#include "game_legacy.h"
#include "gui_parchment.h"
#include "local_camera.h"
#include "map_data.h"
#include "observer.h"
#include "packets.h"
#include "player_data.h"
#include "player_instances.h"
#include "replay.h"
#include "touch_gesture.h"
#include "post_inc.h"

#ifdef __cplusplus
extern "C" {
#endif
/******************************************************************************/
/** No column in the game is taller than this many subtiles. */
#define TOUCH_CAMERA_TALLEST_COLUMN 8
/** Input lag is a few turns; this many zoom flags under way cannot happen. */
#define TOUCH_CAMERA_FLAGS_IN_FLIGHT 32

/** A zoom flag on its way to the dungeon camera. */
struct TouchZoomFlight {
    GameTurn turn;
    signed char direction;
};

static struct TgGesture grip;
static TbBool gripping = false;
static TbBool regrip_wanted = false;
/** What this module last left in the camera, to notice when something else moved it. */
static struct TgCamera left_behind;
static int left_behind_idx = -1;
static struct TgCamera rolling;
static float roll_vx = 0.0f;
static float roll_vy = 0.0f;
static int64_t last_frame_ns = 0;
/** The local zoom has moved away from the dungeon camera's and not been caught up with. */
static TbBool zoom_owed = false;
static uint32_t zoom_flags_this_turn = 0;
static struct TouchZoomFlight flights[TOUCH_CAMERA_FLAGS_IN_FLIGHT];
static int flights_count = 0;
/******************************************************************************/

TbBool touch_camera_owns_view(void)
{
    return (game.game_kind != GKind_Unset) && (get_local_view_type() == PVT_DungeonTop);
}

static int active_camera_index(void)
{
    return get_local_active_camera() - local_state.camera.current;
}

/** Is the camera ours to move right now? */
static TbBool camera_can_move(void)
{
    if (!local_state.camera.ready || !touch_camera_owns_view() || (get_local_map_fade() != PVT_None))
        return false;
    const int idx = active_camera_index();
    if ((idx != CamIV_Isometric) && (idx != CamIV_FrontView))
        return false;
    if (local_state.camera.move_cam != NULL)
        return false;
    const struct UserState *ustate = get_local_user_state();
    if (player_instance_controls_camera(ustate->instance_num))
        return false;
    if ((ustate->init_flags & (UsrIF_KeyboardInputDisabled | UsrIF_MouseInputDisabled)) != 0)
        return false;
    if (a_menu_window_is_active())
        return false;
    // Following someone else's camera, or a recording: the fingers have to detach
    // from it first, see observer_camera_input_pressed().
    if (!is_observer_camera_active() && (observer_is_active() || replay.load_enable))
        return false;
    return true;
}

static void view_of_camera(const struct Camera *cam, int idx, struct TgView *view)
{
    const TbBool front_view = (idx == CamIV_FrontView);
    const float scale = (float)units_per_pixel_best / 16.0f / 65536.0f;
    const int32_t zoom_min = user_zoom_min(get_local_user_state(), front_view);
    view->centre_x = (float)local_state.engine_window_x + (float)(local_state.engine_window_width / 2);
    view->centre_y = (float)local_state.engine_window_y + (float)(local_state.engine_window_height / 2);
    view->angle_units = (float)DEGREES_360;
    view->limit_x = (float)(game.map_subtiles_x * COORD_PER_STL - 1);
    view->limit_y = (float)(game.map_subtiles_y * COORD_PER_STL - 1);
    if (front_view) {
        // From the front view's drawing: it scales by an eighth of the zoom, does not
        // foreshorten, and draws a subtile of height as half a subtile of screen.
        view->px_per_zoom = scale / 8.0f;
        view->squash = 1.0f;
        view->lift = 0.5f;
        view->yaw_step = (float)DEGREES_90;
        view->zoom_min = (float)max(FRONTVIEW_CAMERA_ZOOM_MIN, zoom_min);
        view->zoom_max = (float)FRONTVIEW_CAMERA_ZOOM_MAX;
    } else {
        const float tilt = (float)(-cam->rotation_angle_y) * 2.0f * 3.14159265358979323846f / (float)DEGREES_360;
        view->px_per_zoom = scale;
        view->squash = sinf(tilt);
        view->lift = cosf(tilt);
        view->yaw_step = 0.0f;
        view->zoom_min = (float)zoom_min;
        view->zoom_max = (float)CAMERA_ZOOM_MAX;
    }
}

static void pose_of_camera(const struct Camera *cam, struct TgCamera *pose)
{
    pose->x = (float)cam->mappos.x.val;
    pose->y = (float)cam->mappos.y.val;
    pose->zoom = (float)cam->zoom;
    pose->yaw = (float)cam->rotation_angle_x;
}

/** The height of the surface the player sees at a pixel: the first column a ray from the camera hits. */
static float surface_height_at(const struct TgView *view, const struct TgCamera *pose, float sx, float sy)
{
    for (int stl_z = TOUCH_CAMERA_TALLEST_COLUMN; stl_z > 0; stl_z--) {
        const float height = (float)(stl_z * COORD_PER_STL);
        float gx;
        float gy;
        tg_screen_to_ground(view, pose, height, sx, sy, &gx, &gy);
        if ((gx < 0.0f) || (gy < 0.0f) || (gx > view->limit_x) || (gy > view->limit_y))
            continue;
        const struct Map *mapblk = get_map_block_at(coord_subtile((long)gx), coord_subtile((long)gy));
        if (!map_block_invalid(mapblk) && (get_mapblk_filled_subtiles(mapblk) >= stl_z))
            return height;
    }
    return 0.0f;
}

static TbBool moved_by_something_else(const struct Camera *cam, int idx)
{
    struct TgCamera pose;
    pose_of_camera(cam, &pose);
    return (idx != left_behind_idx) || (pose.x != left_behind.x) || (pose.y != left_behind.y)
        || (pose.zoom != left_behind.zoom) || (pose.yaw != left_behind.yaw);
}

/** Writes a pose to the local camera, where the very next interpolation shows it whole. */
static void show_pose(int idx, const struct TgCamera *pose)
{
    struct Camera *cams[2];
    cams[0] = &local_state.camera.destination[idx];
    cams[1] = &local_state.camera.previous[idx];
    left_behind.x = (float)lroundf(pose->x);
    left_behind.y = (float)lroundf(pose->y);
    left_behind.zoom = pose->zoom;
    left_behind.yaw = (float)(lroundf(pose->yaw) & ANGLE_MASK);
    left_behind_idx = idx;
    for (int i = 0; i < 2; i++) {
        struct Camera *cam = cams[i];
        cam->mappos.x.val = (MapCoord)left_behind.x;
        cam->mappos.y.val = (MapCoord)left_behind.y;
        cam->zoom = (int32_t)left_behind.zoom;
        cam->rotation_angle_x = (int32_t)left_behind.yaw;
        cam->velocity_x = 0;
        cam->velocity_y = 0;
        cam->velocity_rotation = 0;
        cam->use_rotation_pivot = false;
    }
}

void touch_camera_regrip(void)
{
    regrip_wanted = true;
}

void touch_camera_catch(void)
{
    roll_vx = 0.0f;
    roll_vy = 0.0f;
}

void touch_camera_release(const struct TgTuning *tuning, TbBool may_roll)
{
    if (gripping && may_roll) {
        tg_release_speed(&grip, tuning, &roll_vx, &roll_vy);
        rolling = left_behind;
    }
    gripping = false;
}

void touch_camera_frame(const struct TgFingers *fingers, const struct TgTuning *tuning)
{
    const int64_t now = get_time_tick_ns();
    const float dt_ms = (float)(now - last_frame_ns) / 1000000.0f;
    last_frame_ns = now;
    if (!camera_can_move()) {
        gripping = false;
        touch_camera_catch();
        return;
    }
    const int idx = active_camera_index();
    const struct Camera *cam = &local_state.camera.destination[idx];
    struct TgView view;
    view_of_camera(cam, idx, &view);
    // A hitch must not read as a huge step of anything: the catch-up and a roll are
    // meant to look the same at any frame rate, not to make up for lost time.
    const float frame_ms = (dt_ms < 1.0f) ? 1.0f : ((dt_ms > 100.0f) ? 100.0f : dt_ms);
    if (fingers != NULL) {
        if (!gripping || regrip_wanted || moved_by_something_else(cam, idx)) {
            // From what is on screen, not from the destination, which the interpolation
            // may still be on its way to.
            struct TgCamera shown;
            pose_of_camera(&local_state.camera.current[idx], &shown);
            const float mid_x = (fingers->ax + fingers->bx) * 0.5f;
            const float mid_y = (fingers->ay + fingers->by) * 0.5f;
            tg_grip(&grip, fingers, &view, &shown, surface_height_at(&view, &shown, mid_x, mid_y));
            gripping = true;
            regrip_wanted = false;
        }
        struct TgCamera pose = grip.origin;
        tg_follow(&grip, fingers, frame_ms, &view, tuning, &pose);
        if (pose.zoom != grip.origin.zoom)
            zoom_owed = true;
        show_pose(idx, &pose);
        return;
    }
    if ((roll_vx != 0.0f) || (roll_vy != 0.0f)) {
        if (moved_by_something_else(cam, idx)) {
            touch_camera_catch();
            return;
        }
        tg_flick_step(&roll_vx, &roll_vy, frame_ms, &view, tuning, &rolling);
        show_pose(idx, &rolling);
    }
}

/** The dungeon camera's zoom as it will be once the flags still under way have arrived. */
static int32_t zoom_after_flights(int32_t zoom, unsigned char view_mode, int32_t zoom_max, int32_t zoom_min)
{
    // Without input lag a flag has arrived by the next turn's input, and in a paused
    // game the turn number that would say so does not change.
    if (game.input_lag_turns <= 0) {
        flights_count = 0;
        return zoom;
    }
    const GameTurn turn = get_gameturn();
    int kept = 0;
    for (int i = 0; i < flights_count; i++) {
        const GameTurnDelta age = (GameTurnDelta)(turn - flights[i].turn);
        // A flag lost on the way, to a pause or a lag change, drops out here by itself.
        if ((age < 0) || (age > game.input_lag_turns))
            continue;
        zoom = (flights[i].direction > 0) ? zoom_in_for_view(zoom, view_mode, zoom_max, zoom_min)
                                          : zoom_out_for_view(zoom, view_mode, zoom_max, zoom_min);
        flights[kept++] = flights[i];
    }
    flights_count = kept;
    return zoom;
}

void touch_camera_packet_zoom(struct Packet *pckt)
{
    if (!zoom_owed)
        return;
    if (!camera_can_move() || is_observer_camera_active())
        return;
    // A wheel or a key got there first. It steps both zooms alike, so whatever is
    // still owed stays the same and can wait for the next turn.
    if ((pckt->control_flags & (PCtr_ViewZoomIn | PCtr_ViewZoomOut)) != 0)
        return;
    const int idx = active_camera_index();
    const struct Camera *cam = &local_state.camera.destination[idx];
    const TbBool front_view = (idx == CamIV_FrontView);
    const struct UserState *ustate = get_local_user_state();
    const int32_t zoom_max = CAMERA_ZOOM_MAX;
    const int32_t zoom_min = user_zoom_min(ustate, front_view);
    const unsigned char view_mode = cam->view_mode;
    const int32_t zoom = zoom_after_flights(ustate->dungeon_camera.zoom[front_view], view_mode, zoom_max, zoom_min);
    const int direction = tg_zoom_chase(cam->zoom, zoom,
        zoom_in_for_view(zoom, view_mode, zoom_max, zoom_min), zoom_out_for_view(zoom, view_mode, zoom_max, zoom_min));
    if (direction == 0) {
        if (!gripping)
            zoom_owed = false;
        return;
    }
    zoom_flags_this_turn = (direction > 0) ? PCtr_ViewZoomIn : PCtr_ViewZoomOut;
    set_packet_control(pckt, zoom_flags_this_turn);
    if ((game.input_lag_turns > 0) && (flights_count < TOUCH_CAMERA_FLAGS_IN_FLIGHT)) {
        flights[flights_count].turn = get_gameturn();
        flights[flights_count].direction = (signed char)direction;
        flights_count++;
    }
}

uint32_t touch_camera_take_zoom_flags(void)
{
    const uint32_t flags = zoom_flags_this_turn;
    zoom_flags_this_turn = 0;
    return flags;
}

/******************************************************************************/
#ifdef __cplusplus
}
#endif
