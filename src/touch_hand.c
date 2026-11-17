/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/** @file touch_hand.c
 *     The rules of the keeper's hand under a finger.
 * @par Purpose:
 *     A tap stays a left click. With something in the hand a finger that rests or
 *     travels lets it fall, one thing at a time; with an empty hand a quick stroke
 *     slaps the creatures it crosses, and no stroke from the floor presses the left
 *     button, so none of them marks a wall for digging. Drops and slaps are right
 *     clicks at the pointer. A finger resting on a wall it could mark, to the long press
 *     time, selects an area of them the way Shift and a left button drag do on a computer.
 * @par Comment:
 *     A right click drops while the hand holds something and slaps when it does not,
 *     and in a game of two players the count the client can read is behind what it has
 *     sent, by as much as the input lag. So every click on its way counts, the way the
 *     zoom flags do in touch_camera.c: a drop is only sent for something that will still
 *     be in the hand when it arrives, and a slap only when nothing will be. A pick up
 *     under way is never counted on for a drop, because it can come to nothing.
 *
 *     The game only knows what is under the pointer once a frame has been drawn with it
 *     there, and a quick finger travels further than a creature is wide between two
 *     frames. So the creatures a stroke crosses come from its path, looked at point by
 *     point, and a slap goes out where one was found, once a frame has shown the pointer
 *     there and the game agrees it would slap a creature. A right click on the top of a wall
 *     beside it, where a pointer aimed at a creature can land, slaps nothing: the pick axe
 *     ignores it, or with TAG_MODE_TOGGLING on it switches the dig highlight mode.
 * @author   KeeperFX Team
 * @date     06 Nov 2026
 * @par  Copying and copyrights:
 *     This program is free software; you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation; either version 2 of the License, or
 *     (at your option) any later version.
 */
/******************************************************************************/
#include "touch_hand.h"

#include <math.h>
#include <string.h>

enum ThPhase {
    ThP_None = 0,    /**< No finger, or one the rules were never asked about. */
    ThP_Deciding,
    ThP_Today,       /**< The ordinary gestures have it. */
    ThP_LongPress,   /**< An ordinary long press that gives way to a drop. */
    ThP_Armed,       /**< It drops, and a swipe slaps. */
    ThP_Area,        /**< It selects an area of walls, see enum ThAreaPhase. */
};

/**
 * The area of walls is the game's own: the key bound to Gkey_BestRoomSpace held turns a left button
 * drag from a wall into one, which the release applies to every slab in it, or a right click while
 * the button is down cancels. A packet carries one action and applies as many turns later as the
 * input lag says, so every step waits for a packet that has carried the one before: the press for
 * the key's action, which a press in the same packet could miss when another action took the
 * packet; the key, held on until a packet has carried the release, since a release read without
 * it would be a plain one and apply nothing; and the release after a cancel for the packet that
 * carried the right click with the left button still down, which is what cancels.
 */
enum ThAreaPhase {
    ThA_None = 0,
    ThA_Key,         /**< The key is held, and the press waits for a packet that has carried it. */
    ThA_PressDue,
    ThA_Pressing,    /**< The left button is down where the finger rested, until a packet has carried it. */
    ThA_Drag,        /**< The game drags the area from there to the pointer. */
    ThA_CancelDue,
    ThA_Cancel,      /**< The right button is down beside it, until a packet has carried both. */
    ThA_ReleaseDue,
    ThA_Release,     /**< The buttons are up, and the key waits for a packet without the left button held. */
};

void th_reset(struct ThHand *h)
{
    memset(h, 0, sizeof(*h));
    h->last_right_turn = -1;
}

/** Without input lag a click has arrived by the next turn, so this holds for any lag. */
static void th_age_flights(struct ThHand *h, long long turn, int lag)
{
    int kept = 0;
    for (int i = 0; i < h->flights_count; i++) {
        const long long age = turn - h->flights[i].turn;
        // A click lost on the way, to a lag change or a turn counter that started over.
        if ((age < 0) || (age > lag))
            continue;
        h->flights[kept++] = h->flights[i];
    }
    h->flights_count = kept;
}

static void th_add_flight(struct ThHand *h, long long turn, int is_pick)
{
    if (h->flights_count >= TH_MAX_FLIGHTS) {
        memmove(&h->flights[0], &h->flights[1], sizeof(h->flights[0]) * (TH_MAX_FLIGHTS - 1));
        h->flights_count = TH_MAX_FLIGHTS - 1;
    }
    h->flights[h->flights_count].turn = turn;
    h->flights[h->flights_count].is_pick = (unsigned char)is_pick;
    h->flights_count++;
}

void th_note_packet(struct ThHand *h, long long turn, int lag, int right_released, int picking)
{
    th_age_flights(h, turn, lag);
    h->last_packet_turn = turn;
    if (right_released) {
        th_add_flight(h, turn, 0);
        h->last_right_turn = turn;
    }
    if (picking)
        th_add_flight(h, turn, 1);
}

static int th_count_flights(const struct ThHand *h, int is_pick)
{
    int count = 0;
    for (int i = 0; i < h->flights_count; i++) {
        if (h->flights[i].is_pick == is_pick)
            count++;
    }
    return count;
}

/** What the hand will hold once every click on its way has arrived. */
static int th_will_hold(struct ThHand *h, const struct ThSees *s)
{
    th_age_flights(h, s->turn, s->lag);
    return s->hand - th_count_flights(h, 0) + th_count_flights(h, 1);
}

/** Empty by the time a click made now arrives, with no pick up on its way that could change that. */
static int th_stays_empty(struct ThHand *h, const struct ThSees *s)
{
    th_age_flights(h, s->turn, s->lag);
    return ((s->hand - th_count_flights(h, 0)) <= 0) && (th_count_flights(h, 1) == 0);
}

static void th_drop_aim(struct ThHand *h)
{
    memset(&h->aim, 0, sizeof(h->aim));
}

void th_stroke_begin(struct ThHand *h, long long ms, float x, float y)
{
    memset(&h->stroke, 0, sizeof(h->stroke));
    h->stroke.phase = ThP_Deciding;
    h->stroke.down_ms = ms;
    h->stroke.land_x = x;
    h->stroke.land_y = y;
    // A new touch speaks for itself: a slap the last one left behind does not wait for it.
    th_drop_aim(h);
}

void th_stroke_moved(struct ThHand *h, long long ms)
{
    if (h->stroke.moved)
        return;
    h->stroke.moved = 1;
    h->stroke.moved_ms = ms;
}

/** The finger is gone or part of something else: nothing it selected applies. */
static void th_area_abandon(struct ThHand *h)
{
    if ((h->area == ThA_Key) || (h->area == ThA_PressDue))
        h->area = ThA_None;
    else if ((h->area == ThA_Pressing) || (h->area == ThA_Drag))
        h->area = ThA_CancelDue;
}

void th_stroke_cancel(struct ThHand *h)
{
    if (h->stroke.phase == ThP_Area)
        th_area_abandon(h);
    memset(&h->stroke, 0, sizeof(h->stroke));
    th_drop_aim(h);
}

enum ThLift th_stroke_lift(struct ThHand *h, int in_force)
{
    const struct ThStroke *k = &h->stroke;
    enum ThLift lift = ThL_Tap;
    if ((k->phase == ThP_Deciding) && k->moved && in_force) {
        // It travelled and lifted before anything was decided, inside the second finger's grace
        // or before a frame could show what it was over: a flick, which clicks nothing where it
        // ended. Only one from a place an empty hand could not dig slaps what it went over: an own
        // door is a stroke from the floor, which is where a creature walking through one is.
        lift = ThL_Flick;
        if (!k->read_start || !k->start_empty || k->start_wall)
            th_drop_aim(h);
    } else if (k->phase == ThP_Area) {
        // Lifted where it rested, the area is the one slab, which is a tap's; and a finger held on
        // a creature drawn over the top of a wall has not asked for that wall.
        lift = ThL_Hand;
        if (k->moved && (h->area == ThA_Drag)) {
            lift = ThL_Area;
            h->area = ThA_ReleaseDue;
        } else {
            th_area_abandon(h);
        }
        th_drop_aim(h);
    } else {
        if ((k->phase == ThP_Armed) || (k->phase == ThP_LongPress))
            lift = ThL_Hand;
        if ((k->phase != ThP_Armed) || !k->swipe)
            th_drop_aim(h);
    }
    memset(&h->stroke, 0, sizeof(h->stroke));
    return lift;
}

/** What the finger landed on, the first time the game can say. */
static void th_read_start(struct ThHand *h, const struct ThSees *s)
{
    struct ThStroke *k = &h->stroke;
    if (k->read_start || !s->known)
        return;
    k->read_start = 1;
    k->looked = 1;
    k->start_empty = (unsigned char)(th_will_hold(h, s) <= 0);
    k->start_wall = (unsigned char)s->wall;
    k->start_door = (unsigned char)s->door;
    // A tap on an own door still locks it: a tap is a left click wherever it lands.
    k->open = (unsigned char)(k->start_empty ? !s->wall : s->droppable);
}

void th_stroke_look(struct ThHand *h, const struct ThSees *s)
{
    if (h->stroke.phase != ThP_Deciding)
        return;
    h->stroke.looked = 1;
    if (s->in_force)
        th_read_start(h, s);
}

int th_stroke_start_unseen(const struct ThHand *h)
{
    return (h->stroke.phase == ThP_Deciding) && !h->stroke.looked;
}

/** The turn whose packet a click made now goes out in: this one, unless that packet is made already. */
static long long th_packet_turn(const struct ThHand *h, const struct ThSees *s)
{
    return (h->last_packet_turn == s->turn) ? (s->turn + 1) : s->turn;
}

/** No right click is still being whipped out: the game ignores a slap meanwhile. */
static int th_whip_over(const struct ThHand *h, const struct ThSees *s, const struct ThTuning *t)
{
    const long long since_right = th_packet_turn(h, s) - h->last_right_turn;
    return (h->last_right_turn < 0) || (since_right < 0) || (since_right >= t->whip_turns);
}

static int th_quick(const struct ThStroke *k)
{
    return (k->moved_ms - k->down_ms) < TH_SWIPE_MS;
}

static enum ThVerdict th_arm(struct ThHand *h, int swipe, double first_drop_turn)
{
    struct ThStroke *k = &h->stroke;
    k->phase = ThP_Armed;
    k->swipe = (unsigned char)swipe;
    k->next_drop_turn = first_drop_turn;
    if (!swipe)
        th_drop_aim(h);
    return ThV_Hand;
}

static enum ThVerdict th_today(struct ThHand *h)
{
    h->stroke.phase = ThP_Today;
    th_drop_aim(h);
    return ThV_Today;
}

enum ThVerdict th_decide(struct ThHand *h, const struct ThSees *s, const struct ThTuning *t, long long now)
{
    struct ThStroke *k = &h->stroke;
    if (k->phase == ThP_Armed)
        return ThV_Hand;
    if (k->phase == ThP_Area)
        return ThV_Area;
    if (k->phase != ThP_Deciding)
        return ThV_Today;
    // Where it landed out of force the ordinary gestures have it. A start read in force is the
    // rules' wherever the finger has got to: a quick stroke from the floor reaches the margin
    // around the panel before it is decided, and that is no reason to press the left button.
    if (!k->read_start && !s->in_force)
        return th_today(h);
    th_read_start(h, s);
    if (!k->read_start)
        return ThV_Wait;
    const int will_hold = th_will_hold(h, s);
    const long long held = now - k->down_ms;
    if (!k->open) {
        // An empty hand on a wall marks as ever, unless a quick start goes over an own creature
        // before the drag would press: a finger aimed at a creature beside a wall can land on the
        // wall's top.
        if (k->moved && k->start_empty && k->start_wall && (will_hold <= 0) && th_quick(k) && k->crossed)
            return th_arm(h, 1, (double)th_packet_turn(h, s));
        // Until it moves, or the long press time is up, that is still open, and a flick that lifts
        // meanwhile is one: it clicks nothing where it lifted.
        if (!k->moved && ((float)held < t->longpress_ms))
            return ThV_Wait;
        // Resting on a wall it could mark, as the game says by now, it selects an area of them. One
        // still on its way out keeps its key until the game has the release, and a finger meanwhile
        // has the ordinary long press.
        if (!k->moved && k->start_wall && s->in_force) {
            if (!s->known)
                return ThV_Wait;
            if (s->wall && (h->area == ThA_None)) {
                k->phase = ThP_Area;
                h->area = ThA_Key;
                th_drop_aim(h);
                return ThV_Area;
            }
        }
        return th_today(h);
    }
    if (k->moved) {
        // Nothing from here presses the left button: what it would mark is wherever the finger
        // happens to be once it has travelled, a wall as often as not. With an empty hand a quick
        // start slaps what it goes over and a slow one does nothing, until something drops.
        return th_arm(h, (will_hold <= 0) && th_quick(k), (double)th_packet_turn(h, s));
    }
    if (!s->known)
        return ThV_Wait;
    // The hold time on its own, since its knob goes past the long press time.
    if ((will_hold > 0) && s->droppable)
        return ((float)held >= t->hold_ms) ? th_arm(h, 0, (double)th_packet_turn(h, s)) : ThV_Wait;
    if ((float)held < t->longpress_ms)
        return ThV_Wait;
    if (will_hold > 0) {
        // Nothing can fall here. The press ends in the game's own refusal, as it always did.
        th_today(h);
        return ThV_LongPress;
    }
    if (s->thing || s->wall || s->door) {
        k->phase = ThP_LongPress;
        th_drop_aim(h);
        return ThV_LongPress;
    }
    // Bare floor and an empty hand: a right click would do nothing at all. Held like this the
    // finger is waiting for what the other hand takes from the panel.
    return th_arm(h, 0, (double)th_packet_turn(h, s));
}

static int th_slap_is_due(struct ThStroke *k, unsigned long creature, float slap_ms, long long now)
{
    int oldest = 0;
    int slot = -1;
    for (int i = 0; i < TH_SLAP_MEMORY; i++) {
        if (k->slapped[i].creature == creature)
            slot = i;
        if (k->slapped[i].ms < k->slapped[oldest].ms)
            oldest = i;
    }
    if ((slot >= 0) && ((float)(now - k->slapped[slot].ms) < slap_ms))
        return 0;
    if (slot < 0)
        slot = oldest;
    k->slapped[slot].creature = creature;
    k->slapped[slot].ms = now;
    return 1;
}

int th_drop_due(struct ThHand *h, const struct ThSees *s, const struct ThTuning *t)
{
    struct ThStroke *k = &h->stroke;
    if ((k->phase != ThP_Armed) || !s->in_force || !s->known)
        return 0;
    th_age_flights(h, s->turn, s->lag);
    if ((s->hand - th_count_flights(h, 0)) <= 0)
        return 0;
    const long long packet_turn = th_packet_turn(h, s);
    if (!s->droppable || ((double)packet_turn < k->next_drop_turn))
        return 0;
    // In turns, because that is what the game takes clicks in: a rate of two turns is two
    // turns each time, where a clock falls on either side of a look of the game's. What a
    // rate between turns carries over stays, but nothing that was late comes out as two
    // clicks too close together.
    const double period = (double)(t->rain_ms / t->turn_ms);
    const double soonest = (double)packet_turn + floor(period);
    k->next_drop_turn += period;
    if (k->next_drop_turn < soonest)
        k->next_drop_turn = soonest;
    return 1;
}

int th_path_samples(float x0, float y0, float x1, float y1, float step, float *xs, float *ys, int max)
{
    const float length = hypotf(x1 - x0, y1 - y0);
    int count = (step > 0.0f) ? (int)ceilf(length / step) : 1;
    if (count < 1)
        count = 1;
    if (count > max)
        count = max;
    for (int i = 1; i <= count; i++) {
        const float share = (float)i / (float)count;
        xs[i - 1] = x0 + (x1 - x0) * share;
        ys[i - 1] = y0 + (y1 - y0) * share;
    }
    return count;
}

int th_stroke_looks_for_crossings(const struct ThHand *h)
{
    const struct ThStroke *k = &h->stroke;
    return (k->phase == ThP_Deciding) || ((k->phase == ThP_Armed) && k->swipe);
}

int th_stroke_crossed(struct ThHand *h, const struct ThSees *s, const struct ThTuning *t,
    unsigned long creature, float x, float y, float piece_px, long long now)
{
    struct ThStroke *k = &h->stroke;
    if (!th_stroke_looks_for_crossings(h) || !s->in_force || (creature == 0) || !th_stays_empty(h, s))
        return 0;
    // Gone over the creature: the path came within over_px of its place, and nearer to it than
    // where the finger landed by half that more. Landing beside one, or setting out from it along
    // the wall it digs, is not going over it.
    if ((piece_px <= t->over_px) && ((piece_px + 0.5f * t->over_px) <= hypotf(x - k->land_x, y - k->land_y)))
        k->crossed = 1;
    // One slap at a time, and none the game would ignore.
    if ((h->aim.creature != 0) || !th_whip_over(h, s, t))
        return 0;
    // A swipe is a finger going over something. One that has come to rest on a creature has
    // said what it wanted, and slaps take health.
    if (k->slapped_once && (hypotf(s->x - k->slap_x, s->y - k->slap_y) < t->rest_px))
        return 0;
    if (!th_slap_is_due(k, creature, t->slap_ms, now))
        return 0;
    k->slapped_once = 1;
    k->slap_x = s->x;
    k->slap_y = s->y;
    h->aim.creature = creature;
    h->aim.x = x;
    h->aim.y = y;
    return 1;
}

int th_slap_aim(const struct ThHand *h, float *x, float *y)
{
    if ((h->aim.creature == 0) || (h->stroke.phase == ThP_Deciding))
        return 0;
    *x = h->aim.x;
    *y = h->aim.y;
    return 1;
}

int th_slap_now(struct ThHand *h, const struct ThSees *s, const struct ThTuning *t)
{
    const int aimed = (h->aim.creature != 0);
    th_drop_aim(h);
    return aimed && s->in_force && s->known && (s->creature != 0) && th_stays_empty(h, s) && th_whip_over(h, s, t);
}

int th_long_press_gives_way(struct ThHand *h, const struct ThSees *s, const struct ThTuning *t)
{
    struct ThStroke *k = &h->stroke;
    if ((k->phase != ThP_LongPress) || !s->in_force || !s->known || !s->droppable)
        return 0;
    th_age_flights(h, s->turn, s->lag);
    if ((s->hand - th_count_flights(h, 0)) <= 0)
        return 0;
    th_arm(h, 0, (double)th_packet_turn(h, s) + (double)(t->rain_ms / t->turn_ms));
    return 1;
}

void th_long_press_ended(struct ThHand *h, const struct ThTuning *t)
{
    if (h->stroke.phase == ThP_LongPress)
        th_arm(h, 0, (double)(h->last_packet_turn + 1) + (double)(t->rain_ms / t->turn_ms));
}

int th_area_key(const struct ThHand *h)
{
    return h->area != ThA_None;
}

int th_area_owns_buttons(const struct ThHand *h)
{
    return (h->area >= ThA_Pressing) && (h->area <= ThA_ReleaseDue);
}

int th_area_anchoring(const struct ThHand *h)
{
    return (h->area == ThA_Key) || (h->area == ThA_PressDue) || (h->area == ThA_Pressing);
}

enum ThAreaStep th_area_step(struct ThHand *h, long long now, float overdue_ms)
{
    switch (h->area)
    {
    case ThA_PressDue:
        h->area = ThA_Pressing;
        return ThAS_PressLeft;
    case ThA_CancelDue:
        h->area = ThA_Cancel;
        h->area_ms = now;
        return ThAS_PressRight;
    case ThA_Cancel:
        if ((float)(now - h->area_ms) <= overdue_ms)
            return ThAS_None;
        // fall through
    case ThA_ReleaseDue:
        h->area = ThA_Release;
        return ThAS_Release;
    default:
        return ThAS_None;
    }
}

void th_note_area_packet(struct ThHand *h, int area_mode, int left_held, int right_click)
{
    switch (h->area)
    {
    case ThA_Key:
        if (area_mode)
            h->area = ThA_PressDue;
        break;
    case ThA_Pressing:
        if (left_held)
            h->area = ThA_Drag;
        break;
    case ThA_Cancel:
        if (right_click && left_held)
            h->area = ThA_ReleaseDue;
        break;
    case ThA_Release:
        if (!left_held)
            h->area = ThA_None;
        break;
    default:
        break;
    }
}

void th_area_buttons_released(struct ThHand *h)
{
    if ((h->area == ThA_Key) || (h->area == ThA_PressDue))
        h->area = ThA_None;
    else if (h->area != ThA_None)
        h->area = ThA_Release;
}

/******************************************************************************/
