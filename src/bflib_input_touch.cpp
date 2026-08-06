/******************************************************************************/
// Bullfrog Engine Emulation Library - for use to remake classic games like
// Syndicate Wars, Magic Carpet or Dungeon Keeper.
/******************************************************************************/
/** @file bflib_input_touch.cpp
 *     Native touch screen input.
 * @par Purpose:
 *     Turns SDL multi touch events into game actions without going through
 *     the operating system mouse. Finger positions drive the in-game pointer
 *     directly and multi finger gestures are fed into the same device
 *     independent game key layer that the keyboard and the controller use.
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
#include <SDL2/SDL.h>
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
#define TOUCH_DRAG_THRESHOLD_MIN_PX 12

/** Safety net in case update_mouse() is not running; keeps a tap from sticking. */
#define TOUCH_CLICK_MAX_HOLD_MS 250

/** Hold time in milliseconds before a motionless press becomes a right click. */
#define TOUCH_LONGPRESS_MS 420

/**
 * Longest press still counted as a tap. Equal to the long press time on
 * purpose: any gap between the two is a window in which a press is neither,
 * and a finger lifted in it does nothing whatsoever.
 */
#define TOUCH_TAP_MAX_MS TOUCH_LONGPRESS_MS

/**
 * Finger travel per game frame, in pixels, that corresponds to a fully
 * deflected pan axis.
 *
 * The camera is velocity driven and its speed is capped by the engine, so the
 * map can never quite chase the finger; what this decides is how much of that
 * speed an ordinary drag gets. At 18 a normal drag sat at about half throttle
 * and the map crawled, which is the wrong trade - overshooting slightly is far
 * less annoying than a map that will not keep up.
 */
#define TOUCH_PAN_FULL_SPEED_PX 7.0f

/** Pinch distance change in pixels that corresponds to a fully deflected zoom axis. */
#define TOUCH_PINCH_FULL_SPEED_PX 16.0f

/** Twist in radians that corresponds to a fully deflected rotation axis. */
#define TOUCH_TWIST_FULL_SPEED_RAD 0.07f

/** Deadzones, so that a slightly imprecise two finger drag does not zoom or rotate. */
#define TOUCH_PINCH_DEADZONE_PX 2.5f
#define TOUCH_TWIST_DEADZONE_RAD 0.012f

/**
 * How far a two finger gesture has to get from where it started before it
 * commits to being a pan, a pinch or a twist. Two fingers on a screen always
 * produce a little of all three at once, and acting on all of them together is
 * what makes the camera feel like it is fighting back.
 *
 * Panning is deliberately the easiest to reach and zooming the hardest. It is
 * the movement people make constantly, two fingers dragged across a screen
 * never stay exactly the same distance apart, and being zoomed when you meant
 * to move is far more disruptive than the other way round.
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
    Uint32 down_ticks;
    TbBool moved;
};

/** Multi finger gesture currently being recognised. */
enum TouchGestureKind {
    TGest_None = 0,
    TGest_Point,   /**< One finger, still deciding between tap and long press. */
    TGest_Drag,    /**< One finger, left button held down. */
    TGest_LongTap, /**< One finger held still, right button held down. */
    TGest_Camera,  /**< Two fingers: pan, pinch and twist. */
    TGest_Blocked, /**< Extra fingers landed; wait until all are lifted. */
};

static struct TouchFinger touch_fingers[TOUCH_MAX_FINGERS];
static int touch_finger_count = 0;
static unsigned char touch_gesture = TGest_None;
static TbBool touch_seen_device = false;
static TbBool touch_initialised = false;

/* Gesture axis values, all in the range 0.0 to 1.0. */
static float touch_axis_pan_left, touch_axis_pan_right;
static float touch_axis_pan_up, touch_axis_pan_down;
static float touch_axis_zoom_in, touch_axis_zoom_out;
static float touch_axis_rotate_cw, touch_axis_rotate_ccw;

/* One shot gestures, consumed by touch_game_key_pressed(). */
static TbBool touch_tapped_map_toggle = false;
static TbBool touch_tapped_pause_menu = false;

/* Two finger tap: leaves whatever is open. Emitted as Escape rather than as a
   game key because that is the one the menus themselves listen for, and it is
   what the system back button produces too. */
static TbBool touch_tapped_back = false;
static int touch_back_key_frames = 0;

/** What a running two finger gesture has committed to. */
enum TouchTwoFingerMode {
    T2F_Undecided = 0,
    T2F_Pan,
    T2F_Zoom,
    T2F_Rotate,
};

/* Finger travel collected since the last frame, in screen pixels. */
static float touch_pan_accum_x, touch_pan_accum_y;
/* The same for the pinch and the twist, for the same reason: SDL reports one
   motion event per finger per display frame, so a single event on a 120 Hz
   panel carries a fraction of the movement a deadzone is meant to filter. */
static float touch_pinch_accum, touch_twist_accum;

/* State since the two finger gesture began, used to commit to one of them. */
static unsigned char touch_two_finger_mode = T2F_Undecided;
static float touch_start_dist;
static long touch_start_cx, touch_start_cy;
/** Signed sum of the per event twists, so that jitter cancels instead of adding up. */
static float touch_total_twist;

/* Reference values of the running two finger gesture. */
static float touch_camera_prev_dist = 0.0f;
static float touch_camera_prev_angle = 0.0f;
static long touch_camera_prev_cx = 0, touch_camera_prev_cy = 0;

/* Deferred click, so the game always observes the press before it is released. */
static TbBool touch_click_pending = false;
static Uint32 touch_click_press_ticks = 0;
static TbBool touch_button_down_left = false;
static TbBool touch_button_down_right = false;

/** Highest number of fingers seen during the current contact. */
static int touch_max_fingers_this_contact = 0;

/******************************************************************************/

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

TbBool touch_device_seen(void)
{
    return touch_seen_device;
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
    touch_pinch_accum = touch_twist_accum = 0.0f;
    touch_two_finger_mode = T2F_Undecided;
    touch_total_twist = 0.0f;
    touch_axis_pan_left = touch_axis_pan_right = 0.0f;
    touch_axis_pan_up = touch_axis_pan_down = 0.0f;
    touch_axis_zoom_in = touch_axis_zoom_out = 0.0f;
    touch_axis_rotate_cw = touch_axis_rotate_ccw = 0.0f;
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
}

static void touch_reset_state(void)
{
    touch_release_buttons();
    memset(touch_fingers, 0, sizeof(touch_fingers));
    touch_finger_count = 0;
    touch_gesture = TGest_None;
    touch_max_fingers_this_contact = 0;
    touch_reset_gesture_axes();
    touch_tapped_map_toggle = false;
    touch_tapped_pause_menu = false;
    touch_tapped_back = false;
    touch_back_key_frames = 0;
}

void init_touch_input(void)
{
    touch_reset_state();
    touch_initialised = true;
    if (touch_control_mode != TCMode_PointerKeys)
    {
        // Native handling of SDL_FINGER* events; no synthetic mouse behind our back.
        SDL_SetHint(SDL_HINT_TOUCH_MOUSE_EVENTS, "0");
        SDL_SetHint(SDL_HINT_MOUSE_TOUCH_EVENTS, "0");
        SYNCLOG("Native touch input enabled (mode %d)", (int)touch_control_mode);
    }
}

/******************************************************************************/

static long touch_screen_width(void)
{
    long w = lbDisplay.PhysicalScreenWidth;
    return (w > 0) ? w : 640;
}

static long touch_screen_height(void)
{
    long h = lbDisplay.PhysicalScreenHeight;
    return (h > 0) ? h : 480;
}

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

/** Collects the two oldest active fingers, used for the camera gesture. */
static int touch_two_fingers(struct TouchFinger **a, struct TouchFinger **b)
{
    int found = 0;
    for (int i = 0; i < TOUCH_MAX_FINGERS; i++) {
        if (!touch_fingers[i].active)
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

static void touch_begin_camera_gesture(void)
{
    struct TouchFinger *a = NULL;
    struct TouchFinger *b = NULL;
    if (touch_two_fingers(&a, &b) < 2)
        return;
    const float dx = (float)(b->x - a->x);
    const float dy = (float)(b->y - a->y);
    touch_camera_prev_dist = sqrtf(dx * dx + dy * dy);
    touch_camera_prev_angle = atan2f(dy, dx);
    touch_camera_prev_cx = (a->x + b->x) / 2;
    touch_camera_prev_cy = (a->y + b->y) / 2;
    touch_start_dist = touch_camera_prev_dist;
    touch_start_cx = touch_camera_prev_cx;
    touch_start_cy = touch_camera_prev_cy;
    touch_two_finger_mode = T2F_Undecided;
    touch_total_twist = 0.0f;
    touch_pinch_accum = touch_twist_accum = 0.0f;
    touch_gesture = TGest_Camera;
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
    const float dist_delta = dist - touch_camera_prev_dist;
    float angle_delta = angle - touch_camera_prev_angle;
    while (angle_delta > TOUCH_PI)
        angle_delta -= 2.0f * TOUCH_PI;
    while (angle_delta < -TOUCH_PI)
        angle_delta += 2.0f * TOUCH_PI;

    // Decide once what this gesture is, then stick to it until the fingers lift.
    if (touch_two_finger_mode == T2F_Undecided)
    {
        // Measured against where the fingers started, not by adding up the size
        // of every step. A digitiser reports a pixel or two of noise per event
        // and a 120 Hz screen produces dozens of events per frame, so summing
        // absolute steps made a perfectly parallel drag accumulate tens of
        // pixels of "pinch" out of nothing and commit to a zoom. Net values
        // cancel that noise instead of collecting it.
        const float net_pan_x = (float)(cx - touch_start_cx);
        const float net_pan_y = (float)(cy - touch_start_cy);
        touch_total_twist += angle_delta;

        const float pan_share =
            sqrtf(net_pan_x * net_pan_x + net_pan_y * net_pan_y) / TOUCH_COMMIT_PAN_PX;
        const float pinch_share = fabsf(dist - touch_start_dist) / TOUCH_COMMIT_PINCH_PX;
        const float twist_share = fabsf(touch_total_twist) / TOUCH_COMMIT_TWIST_RAD;
        if ((pan_share >= 1.0f) || (pinch_share >= 1.0f) || (twist_share >= 1.0f))
        {
            if ((pan_share >= pinch_share) && (pan_share >= twist_share))
                touch_two_finger_mode = T2F_Pan;
            else if (pinch_share >= twist_share)
                touch_two_finger_mode = T2F_Zoom;
            else
                touch_two_finger_mode = T2F_Rotate;
        }
    }

    switch (touch_two_finger_mode)
    {
    case T2F_Pan:
        // Only accumulated here. A 120 Hz screen delivers many motion events per
        // game frame, each a couple of pixels, so turning one event into an axis
        // value would describe the sampling rate rather than how fast the finger
        // is actually moving. update_touch_inputs() converts the sum once a frame.
        // The world follows the fingers, so the camera travels the other way.
        touch_pan_accum_x += pan_dx;
        touch_pan_accum_y += pan_dy;
        break;

    case T2F_Zoom:
        // Collected, not acted on. Testing one event's delta against a deadzone
        // measured in whole pixels asks a 120 Hz screen for movement it never
        // reports in a single event, so a deliberate pinch cleared the commit
        // threshold - which is measured against the gesture's start - and then
        // produced nothing at all.
        touch_pinch_accum += dist_delta;
        break;

    case T2F_Rotate:
        touch_twist_accum += angle_delta;
        break;

    default:
        break;
    }

    touch_camera_prev_dist = dist;
    touch_camera_prev_angle = angle;
    touch_camera_prev_cx = cx;
    touch_camera_prev_cy = cy;
}

/******************************************************************************/

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

    const long screen_w = touch_screen_width();
    const long screen_h = touch_screen_height();

    switch (ev->type)
    {
    case SDL_FINGERDOWN:
    {
        struct TouchFinger *finger = touch_alloc(ev->tfinger.fingerId);
        if (finger == NULL)
            break;
        finger->x = (long)(ev->tfinger.x * (float)screen_w);
        finger->y = (long)(ev->tfinger.y * (float)screen_h);
        finger->start_x = finger->x;
        finger->start_y = finger->y;
        finger->down_ticks = SDL_GetTicks();
        finger->moved = false;
        touch_finger_count++;
        if (touch_finger_count > touch_max_fingers_this_contact)
            touch_max_fingers_this_contact = touch_finger_count;

        if (touch_finger_count == 1)
        {
            // Put the pointer where the player touched right away, so the
            // cursor sprite and any hover highlight track the finger.
            touch_move_pointer(finger->x, finger->y);
            touch_gesture = TGest_Point;
        }
        else if (touch_finger_count == 2)
        {
            // A second finger cancels whatever the first one started.
            touch_release_buttons();
            touch_begin_camera_gesture();
        }
        else
        {
            touch_release_buttons();
            touch_reset_gesture_axes();
            touch_gesture = TGest_Blocked;
        }
        break;
    }

    case SDL_FINGERMOTION:
    {
        struct TouchFinger *finger = touch_find(ev->tfinger.fingerId);
        if (finger == NULL)
            break;
        finger->x = (long)(ev->tfinger.x * (float)screen_w);
        finger->y = (long)(ev->tfinger.y * (float)screen_h);
        const long travel_x = finger->x - finger->start_x;
        const long travel_y = finger->y - finger->start_y;
        long threshold = screen_w / TOUCH_DRAG_THRESHOLD_DIVISOR;
        if (threshold < TOUCH_DRAG_THRESHOLD_MIN_PX)
            threshold = TOUCH_DRAG_THRESHOLD_MIN_PX;
        if ((labs(travel_x) >= threshold) || (labs(travel_y) >= threshold))
            finger->moved = true;

        if (touch_gesture == TGest_Camera)
        {
            touch_update_camera_gesture();
        }
        else if ((touch_finger_count == 1) && (touch_gesture != TGest_Blocked))
        {
            touch_move_pointer(finger->x, finger->y);
            if (finger->moved && (touch_gesture == TGest_Point))
            {
                // A moving finger is a drag: hold the left button so that
                // dig areas and rooms can be painted the same way as with a mouse.
                touch_gesture = TGest_Drag;
                touch_press_left();
            }
        }
        break;
    }

    case SDL_FINGERUP:
    {
        struct TouchFinger *finger = touch_find(ev->tfinger.fingerId);
        if (finger == NULL)
            break;
        const Uint32 held_ms = SDL_GetTicks() - finger->down_ticks;
        const TbBool was_moved = finger->moved;
        const long up_x = finger->x;
        const long up_y = finger->y;
        finger->active = false;
        if (touch_finger_count > 0)
            touch_finger_count--;

        if (touch_finger_count == 0)
        {
            switch (touch_gesture)
            {
            case TGest_Point:
                if (!was_moved && (held_ms <= TOUCH_TAP_MAX_MS))
                {
                    touch_move_pointer(up_x, up_y);
                    touch_queue_click();
                }
                break;
            case TGest_Drag:
            case TGest_LongTap:
                touch_release_buttons();
                break;
            case TGest_Camera:
            case TGest_Blocked:
                // Multi finger taps that never turned into a drag act as shortcuts.
                if (!was_moved && (held_ms <= TOUCH_TAP_MAX_MS))
                {
                    if (touch_max_fingers_this_contact == 2)
                        touch_tapped_back = true;
                    else if (touch_max_fingers_this_contact == 3)
                        touch_tapped_map_toggle = true;
                    else if (touch_max_fingers_this_contact >= 4)
                        touch_tapped_pause_menu = true;
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
static void touch_apply_camera_accumulators(void);

void update_touch_inputs(void)
{
    if (!touch_controls_active())
    {
        if (touch_button_down_left || touch_button_down_right)
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

    // A single finger resting in place turns into a right click (slap / drop).
    if ((touch_gesture == TGest_Point) && (touch_finger_count == 1))
    {
        for (int i = 0; i < TOUCH_MAX_FINGERS; i++)
        {
            struct TouchFinger *finger = &touch_fingers[i];
            if (!finger->active || finger->moved)
                continue;
            if ((SDL_GetTicks() - finger->down_ticks) >= TOUCH_LONGPRESS_MS)
            {
                touch_move_pointer(finger->x, finger->y);
                touch_press_right();
                touch_gesture = TGest_LongTap;
            }
            break;
        }
    }

    // Escape is held for a frame so the menu code sees a complete keypress,
    // the same shape the controller path uses for its pause button.
    if (touch_back_key_frames > 0)
    {
        touch_back_key_frames--;
        if (touch_back_key_frames == 0)
            lbKeyOn[KC_ESCAPE] = 0;
    }
    if (touch_tapped_back)
    {
        touch_tapped_back = false;
        lbKeyOn[KC_ESCAPE] = 1;
        lbInkey = KC_ESCAPE;
        touch_back_key_frames = 2;
    }

    // All four camera axes are summed over the frame now, so none of them needs
    // decaying: a frame in which the fingers did not move already yields zero.
    touch_apply_pan_accumulator();
    touch_apply_camera_accumulators();
}

/**
 * Turns the finger travel collected since the last frame into pan axis values.
 *
 * get_movement_inputs() squares the axis before using it, which suits a
 * controller stick that gets pushed to its limit but would reduce a typical
 * pan to a hundredth of its intended speed. Taking the square root here undoes
 * that, so the camera follows the finger at the speed it actually moved.
 */
/**
 * Turns the pinch and twist collected since the last frame into axis values.
 *
 * The deadzone is subtracted from the total rather than gating on it, so a slow
 * steady pinch still moves at a rate that follows the fingers instead of
 * switching on and off at frame boundaries. Both axes are read as booleans by
 * the engine - they end up as discrete zoom and rotate packets - so what this
 * really decides is whether the gesture does anything at all.
 */
static void touch_apply_camera_accumulators(void)
{
    touch_axis_zoom_in = touch_axis_zoom_out = 0.0f;
    touch_axis_rotate_cw = touch_axis_rotate_ccw = 0.0f;

    if (touch_gesture == TGest_Camera)
    {
        if (touch_pinch_accum > TOUCH_PINCH_DEADZONE_PX)
            touch_axis_zoom_in = touch_clamp01((touch_pinch_accum - TOUCH_PINCH_DEADZONE_PX) / TOUCH_PINCH_FULL_SPEED_PX);
        else if (touch_pinch_accum < -TOUCH_PINCH_DEADZONE_PX)
            touch_axis_zoom_out = touch_clamp01((-touch_pinch_accum - TOUCH_PINCH_DEADZONE_PX) / TOUCH_PINCH_FULL_SPEED_PX);

        if (touch_twist_accum > TOUCH_TWIST_DEADZONE_RAD)
            touch_axis_rotate_cw = touch_clamp01((touch_twist_accum - TOUCH_TWIST_DEADZONE_RAD) / TOUCH_TWIST_FULL_SPEED_RAD);
        else if (touch_twist_accum < -TOUCH_TWIST_DEADZONE_RAD)
            touch_axis_rotate_ccw = touch_clamp01((-touch_twist_accum - TOUCH_TWIST_DEADZONE_RAD) / TOUCH_TWIST_FULL_SPEED_RAD);
    }
    touch_pinch_accum = touch_twist_accum = 0.0f;
}

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
    if (!touch_controls_active())
        return 0.0f;
    switch (key_id)
    {
    case Gkey_MoveLeft:  return touch_axis_pan_left;
    case Gkey_MoveRight: return touch_axis_pan_right;
    case Gkey_MoveUp:    return touch_axis_pan_up;
    case Gkey_MoveDown:  return touch_axis_pan_down;
    case Gkey_ZoomIn:    return touch_axis_zoom_in;
    case Gkey_ZoomOut:   return touch_axis_zoom_out;
    case Gkey_RotateCW:  return touch_axis_rotate_cw;
    case Gkey_RotateCCW: return touch_axis_rotate_ccw;
    default:             return 0.0f;
    }
}

int touch_game_key_pressed(long key_id, TbBool clear_pressed)
{
    if (!touch_controls_active())
        return 0;

    if (key_id == Gkey_SwitchToMap)
    {
        if (!touch_tapped_map_toggle)
            return 0;
        if (clear_pressed)
            touch_tapped_map_toggle = false;
        return 1;
    }
    if (key_id == Gkey_PauseMenu)
    {
        if (!touch_tapped_pause_menu)
            return 0;
        if (clear_pressed)
            touch_tapped_pause_menu = false;
        return 1;
    }

    return (touch_game_key_axis_value(key_id) > 0.0f) ? 1 : 0;
}

/******************************************************************************/
#ifdef __cplusplus
}
#endif
