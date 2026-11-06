/******************************************************************************/
// Bullfrog Engine Emulation Library - for use to remake classic games like
// Syndicate Wars, Magic Carpet or Dungeon Keeper.
/******************************************************************************/
/** @file bflib_input_touch.cpp
 *     Native touch screen input.
 * @par Purpose:
 *     Turns SDL multi touch events into game actions without going through
 *     the operating system mouse. Finger positions drive the in-game pointer
 *     directly. Two fingers hold the map, see touch_camera.c; a possessed creature
 *     has rules of its own, see touch_possess.c, and so has the hand in the dungeon
 *     view, see touch_keeper.c; what has none of those gets its gestures fed into the
 *     same device independent game key layer that the keyboard and the controller use.
 * @par Comment:
 *     This module deliberately does not use SDL's touch-to-mouse translation
 *     (SDL_HINT_TOUCH_MOUSE_EVENTS); that emulation collapses multi touch into
 *     a single fake mouse and would make gestures impossible. It is switched
 *     off explicitly when the native scheme is in use.
 *
 *     On the desktop ports the whole module stays inert unless the player asks
 *     for it with "-inputmode touch", so mouse, keyboard and controller
 *     behaviour is unchanged there.
 * @author   KeeperFX Team
 * @date     04 Aug 2026
 * @par  Copying and copyrights:
 *     This program is free software; you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation; either version 2 of the License, or
 *     (at your option) any later version.
 */
/******************************************************************************/
#include "pre_inc.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>
#include "bflib_touch.h"
#include "bflib_basics.h"
#include "bflib_inputctrl.h"
#include "bflib_keybrd.h"
#include "bflib_mouse.h"
#include "bflib_planar.h"
#include "bflib_video.h"
#include "front_input.h"
#include "frontend.h"
#include "game_legacy.h"
#include "kjm_input.h"
#include "kfx/platform/WindowSystemSDL.h"
#include "kfx/renderer/RendererManager.h"
#include "touch_camera.h"
#include "touch_gesture.h"
#include "touch_keeper.h"
#include "touch_possess.h"
#include "touch_steer.h"
#include <SDL3/SDL.h>
#include "post_inc.h"

#ifdef __cplusplus
extern "C" {
#endif
/******************************************************************************/

#if defined(__ANDROID__)
unsigned char touch_control_mode = TCMode_Auto;
#else
unsigned char touch_control_mode = TCMode_PointerKeys;
#endif

/** Fingers tracked at the same time; more than this is ignored. */
#define TOUCH_MAX_FINGERS 5

/**
 * Fraction of the screen width a finger may wander and still count as a tap.
 * An absolute pixel count is useless here: the same physical wobble is 14
 * pixels on one panel and 40 on another, and a thumb never lands still.
 */
#define TOUCH_DRAG_THRESHOLD_DIVISOR 50
static float touch_drag_threshold_min_px = 12.0f;

/** Safety net in case update_mouse() is not running; keeps a tap from sticking. */
#define TOUCH_CLICK_MAX_HOLD_MS 250

/** Hold time in milliseconds before a motionless press becomes a right click. */
static float touch_longpress_ms = 420.0f;

/**
 * Longest press still counted as a tap. Equal to the long press time on
 * purpose: any gap between the two is a window in which a press is neither,
 * and a finger lifted in it does nothing whatsoever.
 */
#define TOUCH_TAP_MAX_MS touch_longpress_ms

/**
 * How long a first finger has to be down before moving it starts a drag. Two fingers
 * never land at once, and until the second is there a moving first finger looks like
 * a drag: its press would tag a block or place a room on the way into a camera gesture.
 */
#define TOUCH_SECOND_FINGER_GRACE_MS 100

/* The gates of two fingers holding the map, see touch_gesture.h. A share of the
   screen's short side says the same on any panel; the pixels come from it per frame. */
static float touch_pan_slop_percent = 1.4f;
static float touch_zoom_gate_percent = 2.0f;
static float touch_rotate_gate_rad = 0.10f;
/** A channel whose gate has opened has caught up with the fingers after about three of these. */
#define TOUCH_CATCH_UP_MS 33.0f
/** Twist for one quarter turn of the front view, which has no angles in between. */
#define TOUCH_FRONT_VIEW_STEP_RAD 0.5f
/** Speed a release needs to roll on, in shares of the short side per second. */
#define TOUCH_FLICK_MIN_SPEED_SHARE 0.25f
static float touch_flick_decay = 0.93f;

/*
 * The rest of this block is the game key lane, for what has no map to hold and no
 * creature to steer: scrolling the land view and the parchment map.
 */

/**
 * Finger travel per game frame, in pixels, that corresponds to a fully
 * deflected pan axis.
 */
#define TOUCH_PAN_FULL_SPEED_PX 7.0f

/**
 * How far each part of a two finger gesture has to get from where it started
 * before its channel unlocks. Two fingers on a screen always produce a little
 * of all three movements at once; below these thresholds that is treated as
 * noise, above them the channel flows for the rest of the gesture.
 */
#define TOUCH_COMMIT_PAN_PX 20.0f
#define TOUCH_COMMIT_PINCH_PX 34.0f
#define TOUCH_COMMIT_TWIST_RAD 0.30f

/** M_PI is not guaranteed by the C standard on every toolchain we build with. */
#define TOUCH_PI 3.14159265358979323846f

struct TouchFinger {
    SDL_FingerID id;
    TbBool active;
    long x, y;             /**< Current position in game screen pixels. */
    long start_x, start_y;
    Uint64 down_ticks;
    TbBool moved;
    TbBool on_gui;         /**< It landed on the panel or a menu. */
    TbBool beside;         /**< On the panel beside a finger on the map: part of no gesture, see touch_keep_panel_finger_aside(). */
    TbBool spoiled;        /**< One beside that became part of a grip or a three finger tap, and clicks nothing. */
};

/** Multi finger gesture currently being recognised. */
enum TouchGestureKind {
    TGest_None = 0,
    TGest_Point,   /**< One finger, still deciding between tap and long press. */
    TGest_Drag,    /**< One finger, left button held down. */
    TGest_LongTap, /**< One finger held still, right button held down. */
    TGest_Hand,    /**< One finger the hand's rules have taken: it drops and slaps, no button stays down. */
    TGest_Camera,  /**< Two fingers: pan, pinch and twist. */
    TGest_Blocked, /**< Extra fingers landed; wait until all are lifted. */
};

static struct TouchFinger touch_fingers[TOUCH_MAX_FINGERS];
static int touch_finger_count = 0;
/** Fingers beside the gesture, which touch_finger_count leaves out. */
static int touch_beside_fingers = 0;
static unsigned char touch_gesture = TGest_None;
static TbBool touch_seen_device = false;
static TbBool touch_initialised = false;

/* Gesture axis values, all in the range 0.0 to 1.0. */
static float touch_axis_pan_left, touch_axis_pan_right;
static float touch_axis_pan_up, touch_axis_pan_down;

/** Two finger tap: a right click where the first finger landed. */
static TbBool touch_tapped_right_click = false;

/* Three finger tap: leaves whatever is open. Emitted as Escape rather than as a
   game key because that is the one the menus themselves listen for, and it is
   what the system back button produces too. */
static TbBool touch_tapped_back = false;
static int touch_back_key_frames = 0;

/* The channels of a two finger gesture. Deliberately not an exclusive mode:
   a one handed pinch - thumb resting, index finger pulling - moves the centre
   of the fingers about half as fast as it changes their distance, so a race
   for a single commitment was always a coin toss. While a mis-call landed on
   a slow clamped pan it went unnoticed; once panning tracked the finger 1:1
   it yanked the map and locked the intended zoom out entirely. Instead each
   channel unlocks by itself once its own net travel is reached, the way every
   map application layers them: a pinch zooms while the map gently follows the
   drifting centre, and a plain drag never unlocks zoom because the finger
   distance stays where it started. */
static TbBool touch_t2f_pan;
static TbBool touch_t2f_zoom;
static TbBool touch_t2f_rotate;

/* Finger travel collected since the last frame, in screen pixels. */
static float touch_pan_accum_x, touch_pan_accum_y;

/* State since the two finger gesture began, used to unlock the channels. */
static float touch_start_dist;
static long touch_start_cx, touch_start_cy;
/** Signed sum of the per event twists, so that jitter cancels instead of adding up. */
static float touch_total_twist;

/* Reference values of the running two finger gesture. */
static float touch_camera_prev_angle = 0.0f;
static long touch_camera_prev_cx = 0, touch_camera_prev_cy = 0;

/* Deferred click, so the game always observes the press before it is released. */
static TbBool touch_click_pending = false;
static Uint64 touch_click_press_ticks = 0;
static TbBool touch_right_click_pending = false;
static Uint64 touch_right_click_ticks = 0;
static TbBool touch_button_down_left = false;
static TbBool touch_button_down_right = false;

/** Highest number of fingers seen during the current contact. */
static int touch_max_fingers_this_contact = 0;
/* What decides whether a contact of several fingers was a tap: all of it counts, not
   just the finger that happens to lift last. Its own tolerance, the drag threshold: a
   sloppy tap may well cross the much smaller slop of the camera. */
static Uint64 touch_contact_start_ticks = 0;
static TbBool touch_contact_moved = false;

/* Steering a possessed creature. */
static TbBool touch_was_steering = false;
/** Tapped and not clicked yet: a tap can come faster than the game takes in a click. */
static int touch_attacks_owed = 0;
/** A click the panel is waiting for, once the one under way is done. */
static TbBool touch_panel_click_wanted = false;
static long touch_panel_click_x, touch_panel_click_y;
/** The click under way is the panel's. It holds the pointer until the game has seen it end. */
static TbBool touch_panel_click = false;
/** A tap on the map while the panel had the pointer, and where. */
static TbBool touch_map_tap_owed = false;
static long touch_map_tap_x, touch_map_tap_y;

/**
 * A frame has been drawn with the pointer where it is. The game's answer to what the pointer
 * is over comes from drawing, and a packet carries that answer, so after a jump a click has to
 * wait for it or it lands where the pointer was. A finger moving along has no such jump.
 */
static TbBool touch_pointer_settled = false;

/******************************************************************************/

/**
 * Adjusts one of the gesture constants at run time.
 *
 * Every one of these was picked by reasoning about a device nobody building
 * this port can test on, and getting a single number wrong costs a CI build, a
 * release, an install and a play session to find out. Exposing them means the
 * whole set can be settled in one sitting on the actual hardware, and the
 * values that win can then be baked back into the defaults above.
 *
 * @return true when the name was recognised.
 */
TbBool touch_tune(const char *name, float value)
{
    static const struct { const char *name; float *target; float min; float max; } tunables[] = {
        { "longpress",   &touch_longpress_ms,         120.0f, 2000.0f },
        { "dragslop",    &touch_drag_threshold_min_px,  2.0f,  80.0f },
        { "panslop",     &touch_pan_slop_percent,       0.2f,  10.0f },
        { "zoomgate",    &touch_zoom_gate_percent,      0.2f,  20.0f },
        { "rotategate",  &touch_rotate_gate_rad,       0.02f,   1.0f },
        { "flickdecay",  &touch_flick_decay,            0.0f,   0.99f },
        { "looksens",    &touch_look_sensitivity,      0.05f,   0.60f },
        { "stickradius", &touch_stick_radius_percent,   8.0f,  25.0f },
        { "handhold",    &touch_hand_hold_ms,         120.0f, 1000.0f },
        { "handrain",    &touch_hand_rain_ms,          50.0f,  500.0f },
        { "slaprate",    &touch_hand_slap_ms,         100.0f,  500.0f },
    };
    for (size_t i = 0; i < sizeof(tunables) / sizeof(tunables[0]); i++)
    {
        if (strcasecmp(name, tunables[i].name) != 0)
            continue;
        if ((value < tunables[i].min) || (value > tunables[i].max))
        {
            WARNLOG("Touch tuning \"%s\" out of range %g..%g, ignored",
                name, (double)tunables[i].min, (double)tunables[i].max);
            return true;
        }
        *tunables[i].target = value;
        SYNCLOG("Touch tuning %s = %g", name, (double)value);
        return true;
    }
    return false;
}

void touch_set_control_mode(unsigned char mode)
{
    if (mode > TCMode_Auto)
        mode = TCMode_Auto;
    touch_control_mode = mode;
}

TbBool touch_parse_control_mode(const char *text, unsigned char *mode)
{
    if ((text == NULL) || (mode == NULL))
        return false;
    if (strcasecmp(text, "auto") == 0) {
        *mode = TCMode_Auto;
        return true;
    }
    if ((strcasecmp(text, "touch") == 0) || (strcasecmp(text, "native") == 0)) {
        *mode = TCMode_Touch;
        return true;
    }
    if ((strcasecmp(text, "kbm") == 0) || (strcasecmp(text, "mouse") == 0)
     || (strcasecmp(text, "keyboard") == 0) || (strcasecmp(text, "pointer") == 0)) {
        *mode = TCMode_PointerKeys;
        return true;
    }
    return false;
}

TbBool touch_controls_active(void)
{
    switch (touch_control_mode)
    {
    case TCMode_Touch:
        return true;
    case TCMode_Auto:
        // Automatic mode follows the device the player used last. Until a touch
        // screen has actually been used we stay out of the way completely, so a
        // tablet with a keyboard attached behaves like a desktop machine.
        return touch_seen_device && (last_used_input_device == ID_Touch);
    default:
        return false;
    }
}

static void touch_reset_gesture_axes(void)
{
    touch_pan_accum_x = touch_pan_accum_y = 0.0f;
    touch_t2f_pan = touch_t2f_zoom = touch_t2f_rotate = false;
    touch_total_twist = 0.0f;
    touch_axis_pan_left = touch_axis_pan_right = 0.0f;
    touch_axis_pan_up = touch_axis_pan_down = 0.0f;
}

/** The camera tuning for the screen as it is now, in pixels of that screen. */
static struct TgTuning touch_current_tuning(void)
{
    const long width = RendererPhysicalWidth();
    const long height = RendererPhysicalHeight();
    const float short_side = (float)((width < height) ? width : height);
    struct TgTuning tuning;
    tuning.pan_slop_px = short_side * touch_pan_slop_percent / 100.0f;
    tuning.zoom_gate_px = short_side * touch_zoom_gate_percent / 100.0f;
    tuning.rotate_gate_rad = touch_rotate_gate_rad;
    tuning.catch_up_ms = TOUCH_CATCH_UP_MS;
    tuning.step_twist_rad = TOUCH_FRONT_VIEW_STEP_RAD;
    tuning.flick_min_speed = short_side * TOUCH_FLICK_MIN_SPEED_SHARE;
    tuning.flick_decay = touch_flick_decay;
    return tuning;
}

static void touch_release_buttons(void)
{
    struct TbPoint delta = {0, 0};
    if (touch_button_down_left) {
        mouseControl(MActn_LBUTTONUP, &delta);
        touch_button_down_left = false;
    }
    if (touch_button_down_right) {
        mouseControl(MActn_RBUTTONUP, &delta);
        touch_button_down_right = false;
    }
    touch_click_pending = false;
    touch_right_click_pending = false;
}

static void touch_reset_state(void)
{
    touch_release_buttons();
    memset(touch_fingers, 0, sizeof(touch_fingers));
    touch_finger_count = 0;
    touch_beside_fingers = 0;
    touch_gesture = TGest_None;
    touch_max_fingers_this_contact = 0;
    touch_contact_moved = false;
    touch_reset_gesture_axes();
    touch_camera_release(NULL, false);
    touch_camera_catch();
    touch_tapped_back = false;
    // Whatever counts the hold down stops here, so nothing else would ever let go of the key.
    if (touch_back_key_frames > 0)
        lbKeyOn[KC_ESCAPE] = 0;
    touch_back_key_frames = 0;
    touch_possess_reset();
    touch_attacks_owed = 0;
    touch_panel_click_wanted = false;
    touch_panel_click = false;
    touch_map_tap_owed = false;
    touch_pointer_settled = false;
    touch_keeper_stroke_end();
}

void touch_focus_lost(void)
{
    // Nothing says what the fingers do from here on. A cancel usually arrives first,
    // but when it does not, a finger counted as down would make every later touch
    // look like one more finger, for as long as the game runs.
    if ((touch_finger_count > 0) || (touch_beside_fingers > 0))
        touch_reset_state();
    touch_possess_reset();
}

void init_touch_input(void)
{
    touch_reset_state();
    touch_initialised = true;
    if (touch_control_mode != TCMode_PointerKeys)
    {
        // Native handling of SDL_EVENT_FINGER_* events; no synthetic mouse behind our back.
        SDL_SetHint(SDL_HINT_TOUCH_MOUSE_EVENTS, "0");
        SDL_SetHint(SDL_HINT_MOUSE_TOUCH_EVENTS, "0");
        SYNCLOG("Native touch input enabled (mode %d)", (int)touch_control_mode);
    }
}

/******************************************************************************/

static struct TouchFinger *touch_find(SDL_FingerID id)
{
    for (int i = 0; i < TOUCH_MAX_FINGERS; i++) {
        if (touch_fingers[i].active && (touch_fingers[i].id == id))
            return &touch_fingers[i];
    }
    return NULL;
}

static struct TouchFinger *touch_alloc(SDL_FingerID id)
{
    for (int i = 0; i < TOUCH_MAX_FINGERS; i++) {
        if (!touch_fingers[i].active) {
            memset(&touch_fingers[i], 0, sizeof(touch_fingers[i]));
            touch_fingers[i].active = true;
            touch_fingers[i].id = id;
            return &touch_fingers[i];
        }
    }
    return NULL;
}

/** Collects the two oldest fingers of the gesture, used for the camera gesture. */
static int touch_two_fingers(struct TouchFinger **a, struct TouchFinger **b)
{
    int found = 0;
    for (int i = 0; i < TOUCH_MAX_FINGERS; i++) {
        if (!touch_fingers[i].active || touch_fingers[i].beside)
            continue;
        if (found == 0)
            *a = &touch_fingers[i];
        else if (found == 1)
            *b = &touch_fingers[i];
        found++;
    }
    return found;
}

static void touch_move_pointer(long x, long y)
{
    // Absolute positioning straight into the game pointer. Deliberately not
    // LbMouseSetPosition(), which would also warp the host OS cursor.
    LbMouseSetPositionInitial(x, y);
}

static void touch_press_left(void)
{
    struct TbPoint delta = {0, 0};
    if (!touch_button_down_left) {
        mouseControl(MActn_LBUTTONDOWN, &delta);
        touch_button_down_left = true;
    }
}

static void touch_press_right(void)
{
    struct TbPoint delta = {0, 0};
    if (!touch_button_down_right) {
        mouseControl(MActn_RBUTTONDOWN, &delta);
        touch_button_down_right = true;
    }
}

/**
 * Presses the left button and leaves it down until the game has seen it.
 *
 * Counting frames here does not work: update_touch_inputs() runs inside
 * poll_inputs(), and poll_inputs() is called several times between two
 * update_mouse() calls in some loops, so a counted release could happen before
 * the click was ever observed. That is why a tap did nothing while a drag,
 * which holds the button across many frames, worked.
 */
static void touch_queue_click(void)
{
    touch_press_left();
    touch_click_pending = true;
    touch_click_press_ticks = SDL_GetTicks();
}

/**
 * Presses the right button and leaves it down until the game has seen it.
 *
 * Same shape as touch_queue_click(): mouseControl() sets lbDisplay.RightButton
 * straight away and update_mouse() clears it once it has taken the press, so
 * that returning to zero is the proof the click was observed.
 */
static void touch_queue_right_click(void)
{
    touch_press_right();
    touch_right_click_pending = true;
    touch_right_click_ticks = SDL_GetTicks();
}

/**
 * A right click the game reads whole at its next look: pressed and released before it looks.
 * A mouse clicked quickly within a turn gives the same thing, both flags in one packet, and
 * the game's handling of the release drops or slaps there and then. So the game takes one of
 * these every turn, where a click read in two looks takes two. lbDisplay.RightButton back at
 * zero is what says it has been read.
 */
static void touch_right_click_whole(void)
{
    struct TbPoint delta = {0, 0};
    mouseControl(MActn_RBUTTONDOWN, &delta);
    mouseControl(MActn_RBUTTONUP, &delta);
}

static void touch_begin_camera_gesture(void)
{
    struct TouchFinger *a = NULL;
    struct TouchFinger *b = NULL;
    if (touch_two_fingers(&a, &b) < 2)
        return;
    const float dx = (float)(b->x - a->x);
    const float dy = (float)(b->y - a->y);
    touch_start_dist = sqrtf(dx * dx + dy * dy);
    touch_camera_prev_angle = atan2f(dy, dx);
    touch_camera_prev_cx = (a->x + b->x) / 2;
    touch_camera_prev_cy = (a->y + b->y) / 2;
    touch_start_cx = touch_camera_prev_cx;
    touch_start_cy = touch_camera_prev_cy;
    touch_t2f_pan = touch_t2f_zoom = touch_t2f_rotate = false;
    touch_total_twist = 0.0f;
    touch_gesture = TGest_Camera;
    const struct TgFingers fingers = {(float)a->x, (float)a->y, (float)b->x, (float)b->y};
    touch_camera_regrip(&fingers);
}

static float touch_clamp01(float value)
{
    if (value < 0.0f)
        return 0.0f;
    if (value > 1.0f)
        return 1.0f;
    return value;
}

static void touch_update_camera_gesture(void)
{
    struct TouchFinger *a = NULL;
    struct TouchFinger *b = NULL;
    if (touch_two_fingers(&a, &b) < 2)
        return;

    const float dx = (float)(b->x - a->x);
    const float dy = (float)(b->y - a->y);
    const float dist = sqrtf(dx * dx + dy * dy);
    const float angle = atan2f(dy, dx);
    const long cx = (a->x + b->x) / 2;
    const long cy = (a->y + b->y) / 2;

    const float pan_dx = (float)(cx - touch_camera_prev_cx);
    const float pan_dy = (float)(cy - touch_camera_prev_cy);
    float angle_delta = angle - touch_camera_prev_angle;
    while (angle_delta > TOUCH_PI)
        angle_delta -= 2.0f * TOUCH_PI;
    while (angle_delta < -TOUCH_PI)
        angle_delta += 2.0f * TOUCH_PI;

    // Each channel unlocks on its own once its net travel since the gesture
    // began crosses its threshold, and stays unlocked until the fingers lift.
    // Net values rather than summed steps: a digitiser reports a pixel or two
    // of noise per event and a 120 Hz screen produces dozens of events per
    // frame, so summing absolute steps made a perfectly parallel drag
    // accumulate tens of pixels of "pinch" out of nothing.
    const float net_pan_x = (float)(cx - touch_start_cx);
    const float net_pan_y = (float)(cy - touch_start_cy);
    touch_total_twist += angle_delta;
    if (!touch_t2f_pan
     && (sqrtf(net_pan_x * net_pan_x + net_pan_y * net_pan_y) >= TOUCH_COMMIT_PAN_PX))
    {
        touch_t2f_pan = true;
    }
    if (!touch_t2f_zoom && (fabsf(dist - touch_start_dist) >= TOUCH_COMMIT_PINCH_PX))
    {
        touch_t2f_zoom = true;
    }
    if (!touch_t2f_rotate && (fabsf(touch_total_twist) >= TOUCH_COMMIT_TWIST_RAD))
    {
        touch_t2f_rotate = true;
    }

    if (touch_t2f_pan)
    {
        // Only accumulated here. A 120 Hz screen delivers many motion events
        // per game frame, each a couple of pixels, so turning one event into
        // an axis value would describe the sampling rate rather than how fast
        // the finger is actually moving. update_touch_inputs() converts the
        // sum once a frame.
        touch_pan_accum_x += pan_dx;
        touch_pan_accum_y += pan_dy;
    }

    touch_camera_prev_angle = angle;
    touch_camera_prev_cx = cx;
    touch_camera_prev_cy = cy;
}

/******************************************************************************/

/**
 * Maps a position given as a fraction of the window onto the game surface.
 *
 * The renderers only fill the rectangle GetPresentRect() fits into the window,
 * so the same rectangle has to come out of the finger position again - without
 * this, a letterboxed frame shifts every touch by the width of the bars.
 */
static void touch_normalized_to_game(float nx, float ny, long *x, long *y)
{
    const long game_w = RendererPhysicalWidth();
    const long game_h = RendererPhysicalHeight();
    const WindowSystemSDL *window = GetSDLWindowSystem();
    int drawable_w = 0;
    int drawable_h = 0;
    int rect_x = 0;
    int rect_y = 0;
    int rect_w = 0;
    int rect_h = 0;
    window->GetDrawableSize(&drawable_w, &drawable_h);
    window->GetPresentRect(&rect_x, &rect_y, &rect_w, &rect_h);
    *x = 0;
    *y = 0;
    if ((rect_w <= 0) || (rect_h <= 0) || (game_w <= 0) || (game_h <= 0))
        return;
    *x = ((long)(nx * (float)drawable_w) - rect_x) * game_w / rect_w;
    *y = ((long)(ny * (float)drawable_h) - rect_y) * game_h / rect_h;
    if (*x < 0)
        *x = 0;
    if (*x >= game_w)
        *x = game_w - 1;
    if (*y < 0)
        *y = 0;
    if (*y >= game_h)
        *y = game_h - 1;
}

/** The game's idea of what the pointer is over is about the finger on the map right now. */
static TbBool touch_hand_sees_finger(void)
{
    return touch_pointer_settled && !touch_panel_click && !touch_panel_click_wanted;
}

/**
 * A first finger that has moved far enough holds the left button down, so that dig
 * areas and rooms can be painted the same way as with a mouse - once a second finger
 * had its chance to arrive and make this a camera gesture instead, and unless the
 * hand's rules make it something else.
 */
static void touch_start_drag_when_due(const struct TouchFinger *finger, Uint64 now_ms)
{
    if (!finger->moved || (touch_gesture != TGest_Point)
     || ((now_ms - finger->down_ticks) < TOUCH_SECOND_FINGER_GRACE_MS))
        return;
    switch (touch_keeper_verdict(finger->x, finger->y, touch_hand_sees_finger(), touch_longpress_ms, (long long)now_ms))
    {
    case ThV_Wait:
        return;
    case ThV_Hand:
        touch_gesture = TGest_Hand;
        return;
    default:
        break;
    }
    touch_gesture = TGest_Drag;
    touch_press_left();
}

/** The one finger of the gesture, other than the one given. */
static struct TouchFinger *touch_gesture_finger(const struct TouchFinger *other_than)
{
    for (int i = 0; i < TOUCH_MAX_FINGERS; i++) {
        struct TouchFinger *finger = &touch_fingers[i];
        if (finger->active && !finger->beside && (finger != other_than))
            return finger;
    }
    return NULL;
}

/**
 * A finger on the panel beside a finger on the map is no part of what that finger is doing:
 * a thumb fetching the next creature does not grip the camera or end a drop. All it does is
 * click where it lifted, once the game's one pointer can be spared. Only with the plain hand,
 * and only for one such finger: two of them and a finger on the map are three fingers, as ever.
 * @return true when the finger that has just landed stays out of the gesture.
 */
static TbBool touch_keep_panel_finger_aside(struct TouchFinger *finger)
{
    if (!touch_keeper_in_force())
        return false;
    if (touch_beside_fingers > 0)
    {
        if (finger->on_gui)
        {
            for (int i = 0; i < TOUCH_MAX_FINGERS; i++) {
                if (touch_fingers[i].active && touch_fingers[i].beside) {
                    touch_fingers[i].beside = false;
                    touch_finger_count++;
                }
            }
            touch_beside_fingers = 0;
        }
        return false;
    }
    if (touch_finger_count != 1)
        return false;
    struct TouchFinger *other = touch_gesture_finger(finger);
    if (other == NULL)
        return false;
    if (finger->on_gui && !other->on_gui
     && ((touch_gesture == TGest_Point) || (touch_gesture == TGest_Hand) || (touch_gesture == TGest_LongTap) || (touch_gesture == TGest_Drag)))
    {
        finger->beside = true;
        touch_beside_fingers++;
        return true;
    }
    if (!finger->on_gui && other->on_gui && (touch_gesture == TGest_Point))
    {
        // The other way round: the one on the panel was first, and has not pressed anything yet.
        other->beside = true;
        touch_beside_fingers++;
        touch_finger_count--;
    }
    return false;
}

/** A grip or three fingers: whatever else is down was part of that, and clicks nothing when it lifts. */
static void touch_spoil_beside_fingers(void)
{
    for (int i = 0; i < TOUCH_MAX_FINGERS; i++) {
        if (touch_fingers[i].active && touch_fingers[i].beside)
            touch_fingers[i].spoiled = true;
    }
}

/**
 * When a finger event happened, on the same clock as SDL_GetTicks(). After a slow
 * frame several events wait in the queue, and timing them by when they get processed
 * would count that wait as the finger's: a tap would read as held too long, and a
 * first finger's motion would run out the grace with the second finger's touch right
 * behind it in the queue.
 */
static Uint64 touch_event_ms(const SDL_Event *ev)
{
    return SDL_NS_TO_MS(ev->tfinger.timestamp);
}

/**
 * Fingers belong to what they landed on. When a creature starts or stops being steered,
 * by a possession ending by itself or a menu opening over it, the ones still down drop
 * out of the game until they have been lifted, and nothing they did stays held.
 */
static void touch_sync_steering(void)
{
    const TbBool steering = touch_possess_steering();
    if (steering == touch_was_steering)
        return;
    touch_was_steering = steering;
    touch_reset_state();
}

static void touch_steering_event(const SDL_Event *ev)
{
    const unsigned long long id = ev->tfinger.fingerID;
    const long long ms = (long long)touch_event_ms(ev);
    long x;
    long y;
    touch_normalized_to_game(ev->tfinger.x, ev->tfinger.y, &x, &y);
    switch (ev->type)
    {
    case SDL_EVENT_FINGER_DOWN:
        touch_possess_finger_down(id, x, y, ms);
        break;
    case SDL_EVENT_FINGER_MOTION:
        touch_possess_finger_move(id, x, y, ms);
        break;
    case SDL_EVENT_FINGER_UP:
        touch_possess_finger_up(id, ms);
        break;
    case SDL_EVENT_FINGER_CANCELED:
        touch_possess_finger_cancel(id);
        break;
    default:
        break;
    }
    if (touch_possess_pointer_at(&x, &y))
        touch_move_pointer(x, y);
}

/**
 * Handles one SDL touch event. Declared in bflib_inputctrl.cpp the same way
 * JEvent() is, to keep SDL types out of the shared headers.
 */
void TEvent(const SDL_Event *ev)
{
    if (!touch_initialised)
        init_touch_input();

    touch_seen_device = true;
    if (!touch_controls_active())
        return;
    touch_sync_steering();
    if (touch_possess_steering())
    {
        touch_steering_event(ev);
        return;
    }

    switch (ev->type)
    {
    case SDL_EVENT_FINGER_DOWN:
    {
        // Touching the screen catches a rolling map, as it does everywhere.
        touch_camera_catch();
        struct TouchFinger *finger = touch_alloc(ev->tfinger.fingerID);
        if (finger == NULL)
            break;
        // Through the present rectangle, not a plain multiplication: while the
        // game frame is being fitted into the window, the picture only covers
        // part of it, and the finger has to land on the pixel it was actually
        // over.
        touch_normalized_to_game(ev->tfinger.x, ev->tfinger.y, &finger->x, &finger->y);
        finger->start_x = finger->x;
        finger->start_y = finger->y;
        finger->down_ticks = touch_event_ms(ev);
        finger->moved = false;
        finger->on_gui = touch_keeper_over_gui(finger->x, finger->y);
        if (touch_keep_panel_finger_aside(finger))
            break;
        touch_finger_count++;
        if ((touch_finger_count + touch_beside_fingers) > touch_max_fingers_this_contact)
            touch_max_fingers_this_contact = touch_finger_count + touch_beside_fingers;

        if (touch_finger_count == 1)
        {
            // Put the pointer where the player touched right away, so the
            // cursor sprite and any hover highlight track the finger.
            if (!touch_panel_click)
                touch_move_pointer(finger->x, finger->y);
            touch_pointer_settled = false;
            touch_gesture = TGest_Point;
            touch_contact_start_ticks = finger->down_ticks;
            touch_contact_moved = false;
            if (!finger->on_gui)
                touch_keeper_stroke_begin((long long)finger->down_ticks);
        }
        else if (touch_finger_count == 2)
        {
            // A second finger cancels whatever the first one started.
            touch_release_buttons();
            touch_keeper_stroke_end();
            touch_spoil_beside_fingers();
            touch_begin_camera_gesture();
        }
        else
        {
            touch_release_buttons();
            touch_keeper_stroke_end();
            touch_spoil_beside_fingers();
            touch_reset_gesture_axes();
            touch_camera_release(NULL, false);
            touch_gesture = TGest_Blocked;
        }
        break;
    }

    case SDL_EVENT_FINGER_MOTION:
    {
        struct TouchFinger *finger = touch_find(ev->tfinger.fingerID);
        if (finger == NULL)
            break;
        touch_normalized_to_game(ev->tfinger.x, ev->tfinger.y, &finger->x, &finger->y);
        const long travel_x = finger->x - finger->start_x;
        const long travel_y = finger->y - finger->start_y;
        long threshold = RendererPhysicalWidth() / TOUCH_DRAG_THRESHOLD_DIVISOR;
        if (threshold < (long)touch_drag_threshold_min_px)
            threshold = (long)touch_drag_threshold_min_px;
        if ((labs(travel_x) >= threshold) || (labs(travel_y) >= threshold))
        {
            finger->moved = true;
            if (finger->beside)
                break;
            touch_contact_moved = true;
            touch_keeper_stroke_moved((long long)touch_event_ms(ev));
        }
        if (finger->beside)
            break;

        if (touch_gesture == TGest_Camera)
        {
            touch_update_camera_gesture();
        }
        else if ((touch_finger_count == 1) && (touch_gesture != TGest_Blocked))
        {
            if (!touch_panel_click)
                touch_move_pointer(finger->x, finger->y);
            touch_start_drag_when_due(finger, touch_event_ms(ev));
        }
        break;
    }

    case SDL_EVENT_FINGER_UP:
    case SDL_EVENT_FINGER_CANCELED:
    {
        struct TouchFinger *finger = touch_find(ev->tfinger.fingerID);
        if (finger == NULL)
            break;
        const Uint64 held_ms = touch_event_ms(ev) - finger->down_ticks;
        // A contact the system took away ends the gesture like a lifted finger
        // does, but nobody meant it as a tap, so it counts as a drag.
        const TbBool canceled = (ev->type == SDL_EVENT_FINGER_CANCELED);
        if (canceled)
            touch_contact_moved = true;
        const TbBool moved = finger->moved;
        const long up_x = finger->x;
        const long up_y = finger->y;
        finger->active = false;
        if (finger->beside)
        {
            // A left button held down by a drag leaves nothing to click with, and saving the
            // click up would fire it long after the thumb has gone.
            touch_beside_fingers--;
            if (!canceled && !moved && !finger->spoiled && (touch_gesture != TGest_Drag))
            {
                touch_panel_click_wanted = true;
                touch_panel_click_x = up_x;
                touch_panel_click_y = up_y;
            }
            break;
        }
        if (touch_finger_count > 0)
            touch_finger_count--;
        touch_keeper_stroke_end();

        if (touch_finger_count == 0)
        {
            switch (touch_gesture)
            {
            case TGest_Point:
                // Moved and still a point: a stroke over before the second finger's grace
                // was. Without the grace that would have been a press, so it still is.
                if (!canceled && (moved || (held_ms <= TOUCH_TAP_MAX_MS)))
                {
                    if (touch_panel_click || touch_panel_click_wanted)
                    {
                        touch_map_tap_owed = true;
                        touch_map_tap_x = up_x;
                        touch_map_tap_y = up_y;
                    }
                    else
                    {
                        touch_move_pointer(up_x, up_y);
                        touch_queue_click();
                    }
                }
                break;
            case TGest_Drag:
            case TGest_LongTap:
                touch_release_buttons();
                break;
            case TGest_Camera:
            case TGest_Blocked:
                {
                    const struct TgTuning tuning = touch_current_tuning();
                    touch_camera_release(&tuning, touch_contact_moved);
                }
                // Multi finger taps that never turned into a drag act as shortcuts.
                if (!touch_contact_moved && ((touch_event_ms(ev) - touch_contact_start_ticks) <= TOUCH_TAP_MAX_MS))
                {
                    // Two fingers is the shortest gesture there is, and right
                    // click is the verb Dungeon Keeper uses most after left:
                    // slap, drop what the hand is holding, undesignate, cancel
                    // an armed room. Leaving it behind a 420 ms hold made all of
                    // those cost most of a second. Escape moves up a finger; it
                    // still has the system back button and the on screen arrow.
                    if (touch_max_fingers_this_contact == 2)
                        touch_tapped_right_click = true;
                    else if (touch_max_fingers_this_contact >= 3)
                        touch_tapped_back = true;
                }
                touch_reset_gesture_axes();
                break;
            default:
                break;
            }
            touch_gesture = TGest_None;
            touch_max_fingers_this_contact = 0;
        }
        else if (touch_finger_count == 1)
        {
            // Dropped back to a single finger; stop the camera gesture but do
            // not start a new click with the finger that is still down.
            const struct TgTuning tuning = touch_current_tuning();
            touch_camera_release(&tuning, touch_contact_moved);
            touch_reset_gesture_axes();
            touch_gesture = TGest_Blocked;
        }
        else if (touch_finger_count == 2)
        {
            touch_begin_camera_gesture();
        }
        break;
    }

    default:
        break;
    }
}

/******************************************************************************/

static void touch_apply_pan_accumulator(void);

/** Turns what the fingers asked for into clicks, one at a time. */
static void touch_serve_steering(void)
{
    struct TsActions actions;
    touch_possess_poll((long long)SDL_GetTicks(), &actions);
    touch_attacks_owed += actions.attacks;
    if (actions.right_click)
        touch_tapped_right_click = true;
    if (actions.escape)
        touch_tapped_back = true;
    if (actions.pointer_click)
    {
        touch_panel_click_wanted = true;
        touch_panel_click_x = (long)actions.click_x;
        touch_panel_click_y = (long)actions.click_y;
    }
    long pointer_x;
    long pointer_y;
    const TbBool panel_has_pointer = touch_possess_pointer_at(&pointer_x, &pointer_y);
    // A tap beside a finger resting on the panel has no pointer to click with, and
    // saving it up would fire it long after, the moment that finger lifts.
    if (panel_has_pointer)
        touch_attacks_owed = 0;
    // The game reads a press and a release in two separate turns. Another press before
    // it has read the release would merge the two clicks into one.
    if (touch_click_pending || touch_right_click_pending || left_button_held || right_button_held)
        return;
    touch_panel_click = false;
    if (touch_panel_click_wanted)
    {
        touch_panel_click_wanted = false;
        touch_panel_click = true;
        touch_move_pointer(touch_panel_click_x, touch_panel_click_y);
        touch_queue_click();
        return;
    }
    if (panel_has_pointer)
        return;
    // An attack clicks at the centre, on no button, and that is where the pointer rests.
    touch_move_pointer(RendererPhysicalWidth() / 2, RendererPhysicalHeight() / 2);
    if (touch_attacks_owed > 0)
    {
        touch_attacks_owed--;
        touch_queue_click();
    }
}

/**
 * The hand's one pointer, between a finger on the map and a thumb on the panel. As with a
 * steered creature the panel gets it for its press and its release and nothing goes out
 * meanwhile; then it goes back to the finger, and the drops go on once a frame has shown
 * where that is.
 */
static void touch_serve_hand(void)
{
    const Uint64 now = SDL_GetTicks();
    struct TouchFinger *finger = (touch_finger_count == 1) ? touch_gesture_finger(NULL) : NULL;
    if (touch_gesture == TGest_LongTap)
    {
        if (touch_panel_click_wanted)
        {
            touch_release_buttons();
            touch_keeper_long_press_ended(touch_longpress_ms, (long long)now);
            touch_gesture = TGest_Hand;
        }
        else if ((finger != NULL) && touch_keeper_long_press_gives_way(finger->x, finger->y, touch_hand_sees_finger(), touch_longpress_ms, (long long)now))
        {
            touch_release_buttons();
            touch_gesture = TGest_Hand;
        }
    }
    // The game reads a press and a release in two separate turns. Another press before
    // it has read the release would merge the two clicks into one.
    if (touch_click_pending || touch_right_click_pending || left_button_held || right_button_held
     || lbDisplay.LeftButton || lbDisplay.RightButton)
        return;
    if (touch_panel_click)
    {
        touch_panel_click = false;
        if (touch_map_tap_owed)
            touch_move_pointer(touch_map_tap_x, touch_map_tap_y);
        else if (finger != NULL)
            touch_move_pointer(finger->x, finger->y);
        touch_pointer_settled = false;
    }
    if (touch_panel_click_wanted)
    {
        touch_panel_click_wanted = false;
        touch_panel_click = true;
        touch_move_pointer(touch_panel_click_x, touch_panel_click_y);
        touch_queue_click();
        return;
    }
    if (touch_map_tap_owed)
    {
        if (touch_pointer_settled)
        {
            touch_map_tap_owed = false;
            touch_queue_click();
        }
        return;
    }
    if ((finger == NULL) || (touch_gesture != TGest_Hand))
        return;
    switch (touch_keeper_click_due(finger->x, finger->y, touch_pointer_settled, touch_longpress_ms, (long long)now))
    {
    case ThC_Drop:
    case ThC_Slap:
        touch_right_click_whole();
        break;
    case ThC_PressLeft:
        touch_gesture = TGest_Drag;
        touch_press_left();
        break;
    default:
        break;
    }
}

/**
 * Runs for every frame the game draws, before the cameras are interpolated.
 *
 * The rest of the input is only looked at once per game turn, so finger events get
 * their own pump here: at 20 turns a second a finger would otherwise reach the map
 * in steps of 50 ms, whatever the frame rate.
 */
void touch_frame_update(void)
{
    if (touch_control_mode == TCMode_PointerKeys)
        return;
    LbPollTouchEvents();
    // This frame is about to be drawn with the pointer where it is now.
    touch_pointer_settled = true;
    if (!touch_controls_active())
        return;
    struct TouchFinger *a = NULL;
    struct TouchFinger *b = NULL;
    const struct TgTuning tuning = touch_current_tuning();
    if ((touch_gesture == TGest_Camera) && (touch_two_fingers(&a, &b) >= 2))
    {
        const struct TgFingers fingers = {(float)a->x, (float)a->y, (float)b->x, (float)b->y};
        touch_camera_frame(&fingers, &tuning);
    }
    else
    {
        touch_camera_frame(NULL, &tuning);
    }
}

void update_touch_inputs(void)
{
    touch_sync_steering();
    if (!touch_controls_active())
    {
        if (touch_button_down_left || touch_button_down_right || (touch_back_key_frames > 0))
            touch_reset_state();
        return;
    }

    // update_mouse() clears lbDisplay.LeftButton once it has taken the press,
    // so seeing it back at zero proves the game observed the click.
    if (touch_click_pending)
    {
        const TbBool observed = (lbDisplay.LeftButton == 0);
        const TbBool overdue =
            (SDL_GetTicks() - touch_click_press_ticks) > TOUCH_CLICK_MAX_HOLD_MS;
        if (observed || overdue)
        {
            touch_click_pending = false;
            touch_release_buttons();
        }
    }
    if (touch_right_click_pending)
    {
        const TbBool observed = (lbDisplay.RightButton == 0);
        const TbBool overdue =
            (SDL_GetTicks() - touch_right_click_ticks) > TOUCH_CLICK_MAX_HOLD_MS;
        if (observed || overdue)
        {
            touch_right_click_pending = false;
            touch_release_buttons();
        }
    }
    if (touch_possess_steering())
        touch_serve_steering();
    else
        touch_serve_hand();

    // A single finger resting in place turns into a right click (slap / drop),
    // unless the hand's rules say it drops by itself or has nothing to do with one.
    if ((touch_gesture == TGest_Point) && (touch_finger_count == 1))
    {
        for (int i = 0; i < TOUCH_MAX_FINGERS; i++)
        {
            struct TouchFinger *finger = &touch_fingers[i];
            if (!finger->active || finger->beside)
                continue;
            // A finger that stopped within the grace sends no more motion to start its drag.
            // The queue was emptied just before this, so no second finger can be waiting.
            touch_start_drag_when_due(finger, SDL_GetTicks());
            if (finger->moved)
                continue;
            const enum ThVerdict verdict = touch_keeper_verdict(finger->x, finger->y, touch_hand_sees_finger(), touch_longpress_ms, (long long)SDL_GetTicks());
            if (verdict == ThV_Hand)
            {
                touch_gesture = TGest_Hand;
            }
            else if ((verdict == ThV_LongPress)
                  || ((verdict == ThV_Today) && ((SDL_GetTicks() - finger->down_ticks) >= touch_longpress_ms)))
            {
                touch_move_pointer(finger->x, finger->y);
                touch_press_right();
                touch_gesture = TGest_LongTap;
            }
            break;
        }
    }

    // Escape is held for a frame so the menu code sees a complete keypress, the same
    // shape the controller path uses for its pause button. In a level the game only
    // looks once per turn, and this runs for every drawn frame, so it is left in the
    // place that keeps a press until that look too.
    if (touch_back_key_frames > 0)
    {
        touch_back_key_frames--;
        if (touch_back_key_frames == 0)
            lbKeyOn[KC_ESCAPE] = 0;
    }
    if (touch_tapped_right_click)
    {
        touch_tapped_right_click = false;
        touch_queue_right_click();
    }
    if (touch_tapped_back)
    {
        touch_tapped_back = false;
        lbKeyOn[KC_ESCAPE] = 1;
        touch_keep_escape_for_turn();
        lbInkey = KC_ESCAPE;
        touch_back_key_frames = 2;
    }

    // Summed over the frame, so it needs no decaying: a frame in which the fingers
    // did not move already yields zero.
    touch_apply_pan_accumulator();
}

/**
 * Turns the finger travel collected since the last frame into pan axis values.
 *
 * get_movement_inputs() squares the axis before using it, which suits a
 * controller stick that gets pushed to its limit but would reduce a typical
 * pan to a hundredth of its intended speed. Taking the square root here undoes
 * that, so the camera follows the finger at the speed it actually moved.
 */
static void touch_apply_pan_accumulator(void)
{
    touch_axis_pan_left = touch_axis_pan_right = 0.0f;
    touch_axis_pan_up = touch_axis_pan_down = 0.0f;

    if (touch_gesture == TGest_Camera)
    {
        const float dx = touch_pan_accum_x / TOUCH_PAN_FULL_SPEED_PX;
        const float dy = touch_pan_accum_y / TOUCH_PAN_FULL_SPEED_PX;
        if (dx > 0.0f)
            touch_axis_pan_left = sqrtf(touch_clamp01(dx));
        else if (dx < 0.0f)
            touch_axis_pan_right = sqrtf(touch_clamp01(-dx));
        if (dy > 0.0f)
            touch_axis_pan_up = sqrtf(touch_clamp01(dy));
        else if (dy < 0.0f)
            touch_axis_pan_down = sqrtf(touch_clamp01(-dy));
    }
    touch_pan_accum_x = touch_pan_accum_y = 0.0f;
}

/******************************************************************************/

float touch_game_key_axis_value(long key_id)
{
    if (!touch_controls_active() || touch_camera_owns_view())
        return 0.0f;
    if (touch_possess_owns_keys())
        return touch_possess_axis(key_id);
    switch (key_id)
    {
    case Gkey_MoveLeft:  return touch_axis_pan_left;
    case Gkey_MoveRight: return touch_axis_pan_right;
    case Gkey_MoveUp:    return touch_axis_pan_up;
    case Gkey_MoveDown:  return touch_axis_pan_down;
    default:             return 0.0f;
    }
}

TbBool touch_click_is_for_panel(void)
{
    return touch_panel_click;
}

TbBool touch_gesture_committed(void)
{
    return touch_controls_active() && (touch_gesture == TGest_Camera)
        && (touch_t2f_pan || touch_t2f_zoom || touch_t2f_rotate);
}

TbBool touch_allows_edge_scroll(void)
{
    // A pointer on a touch screen stays where the last finger left it, or at the corner
    // it started in, and an edge scroll from there would run for ever. Only a finger
    // dragging a paint to the edge means it.
    if (touch_controls_active())
        return (touch_gesture == TGest_Drag);
    // Automatic mode before the first touch: only a real mouse says it is at the edge.
    if (touch_control_mode == TCMode_Auto)
        return (last_used_input_device == ID_Keyboard_Mouse);
    return true;
}

void touch_keep_escape_for_turn(void)
{
    // A level looks at the keyboard once per turn however many polls come in between, and
    // clears this array after each look. The menus look at the key being down after every
    // poll and nothing there clears it, so a press left in it would wait for whichever
    // screen asks next, and that screen would close again as soon as it opened.
    if ((frontend_menu_state == FeSt_INITIAL) && (game.game_kind != GKind_Unset))
        lbKeyPressed[KC_ESCAPE] = 1;
}

int touch_game_key_pressed(long key_id, TbBool clear_pressed)
{
    if (!touch_controls_active())
        return 0;

    return (touch_game_key_axis_value(key_id) > 0.0f) ? 1 : 0;
}

/******************************************************************************/
#ifdef __cplusplus
}
#endif
