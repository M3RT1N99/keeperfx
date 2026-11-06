/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/** @file touch_hand.c
 *     The rules of the keeper's hand under a finger.
 * @par Purpose:
 *     A tap stays a left click. With something in the hand a finger that rests or
 *     travels lets it fall, one thing at a time; with an empty hand a quick stroke
 *     slaps the creatures it crosses. All of that is right clicks at the pointer.
 * @par Comment:
 *     A right click drops while the hand holds something and slaps when it does not,
 *     and in a game of two players the count the client can read is behind what it has
 *     sent, by as much as the input lag. So every click on its way counts, the way the
 *     zoom flags do in touch_camera.c: a drop is only sent for something that will still
 *     be in the hand when it arrives, and a slap only when nothing will be. A pick up
 *     under way is never counted on for a drop, because it can come to nothing.
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
    ThP_Armed,       /**< It drops and slaps. */
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

void th_stroke_begin(struct ThHand *h, long long ms)
{
    memset(&h->stroke, 0, sizeof(h->stroke));
    h->stroke.phase = ThP_Deciding;
    h->stroke.down_ms = ms;
}

void th_stroke_moved(struct ThHand *h, long long ms)
{
    if (h->stroke.moved)
        return;
    h->stroke.moved = 1;
    h->stroke.moved_ms = ms;
}

void th_stroke_end(struct ThHand *h)
{
    memset(&h->stroke, 0, sizeof(h->stroke));
}

/** The turn whose packet a click made now goes out in: this one, unless that packet is made already. */
static long long th_packet_turn(const struct ThHand *h, const struct ThSees *s)
{
    return (h->last_packet_turn == s->turn) ? (s->turn + 1) : s->turn;
}

static enum ThVerdict th_arm(struct ThStroke *k, int swipe, double first_drop_turn)
{
    k->phase = ThP_Armed;
    k->swipe = (unsigned char)swipe;
    k->next_drop_turn = first_drop_turn;
    return ThV_Hand;
}

enum ThVerdict th_decide(struct ThHand *h, const struct ThSees *s, const struct ThTuning *t, long long now)
{
    struct ThStroke *k = &h->stroke;
    if (k->phase == ThP_Armed)
        return ThV_Hand;
    if (k->phase != ThP_Deciding)
        return ThV_Today;
    if (!s->in_force) {
        k->phase = ThP_Today;
        return ThV_Today;
    }
    if (!s->known)
        return ThV_Wait;
    th_age_flights(h, s->turn, s->lag);
    const int will_hold = s->hand - th_count_flights(h, 0) + th_count_flights(h, 1);
    if (!k->read_start) {
        k->read_start = 1;
        k->open = (unsigned char)((will_hold > 0) ? s->droppable : (!s->wall && !s->door));
    }
    if (!k->open) {
        k->phase = ThP_Today;
        return ThV_Today;
    }
    if (k->moved) {
        if (will_hold > 0)
            return th_arm(k, 0, (double)th_packet_turn(h, s));
        if ((k->moved_ms - k->down_ms) < TH_SWIPE_MS)
            return th_arm(k, 1, (double)th_packet_turn(h, s));
        k->phase = ThP_Today;
        return ThV_Today;
    }
    const long long held = now - k->down_ms;
    // The hold time on its own, since its knob goes past the long press time.
    if ((will_hold > 0) && s->droppable)
        return ((float)held >= t->hold_ms) ? th_arm(k, 0, (double)th_packet_turn(h, s)) : ThV_Wait;
    if ((float)held < t->longpress_ms)
        return ThV_Wait;
    if (will_hold > 0) {
        // Nothing can fall here. The press ends in the game's own refusal, as it always did.
        k->phase = ThP_Today;
        return ThV_LongPress;
    }
    if (s->thing || s->wall || s->door) {
        k->phase = ThP_LongPress;
        return ThV_LongPress;
    }
    // Bare floor and an empty hand: a right click would do nothing at all. Held like this the
    // finger is waiting for what the other hand takes from the panel.
    return th_arm(k, 0, (double)th_packet_turn(h, s));
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

enum ThClick th_click_due(struct ThHand *h, const struct ThSees *s, const struct ThTuning *t, long long now)
{
    struct ThStroke *k = &h->stroke;
    if ((k->phase != ThP_Armed) || !s->in_force || !s->known)
        return ThC_None;
    th_age_flights(h, s->turn, s->lag);
    const long long packet_turn = th_packet_turn(h, s);
    if ((s->hand - th_count_flights(h, 0)) > 0) {
        if (!s->droppable || ((double)packet_turn < k->next_drop_turn))
            return ThC_None;
        // In turns, because that is what the game takes clicks in: a rate of two turns is two
        // turns each time, where a clock falls on either side of a look of the game's. What a
        // rate between turns carries over stays, but nothing that was late comes out as two
        // clicks too close together.
        const double period = (double)(t->rain_ms / t->turn_ms);
        const double soonest = (double)packet_turn + floor(period);
        k->next_drop_turn += period;
        if (k->next_drop_turn < soonest)
            k->next_drop_turn = soonest;
        return ThC_Drop;
    }
    if (!k->swipe || (th_count_flights(h, 1) > 0))
        return ThC_None;
    // Not on a door, which the press itself would lock: only a stroke that starts there means that.
    if (s->wall && !k->crossed) {
        k->phase = ThP_Today;
        return ThC_PressLeft;
    }
    if (s->creature == 0)
        return ThC_None;
    k->crossed = 1;
    const long long since_right = packet_turn - h->last_right_turn;
    if ((h->last_right_turn >= 0) && (since_right >= 0) && (since_right < t->whip_turns))
        return ThC_None;
    // A swipe is a finger going over something. One that has come to rest on a creature has
    // said what it wanted, and slaps take health.
    if (k->slapped_once && (hypotf(s->x - k->slap_x, s->y - k->slap_y) < t->rest_px))
        return ThC_None;
    if (!th_slap_is_due(k, s->creature, t->slap_ms, now))
        return ThC_None;
    k->slapped_once = 1;
    k->slap_x = s->x;
    k->slap_y = s->y;
    return ThC_Slap;
}

int th_long_press_gives_way(struct ThHand *h, const struct ThSees *s, const struct ThTuning *t)
{
    struct ThStroke *k = &h->stroke;
    if ((k->phase != ThP_LongPress) || !s->in_force || !s->known || !s->droppable)
        return 0;
    th_age_flights(h, s->turn, s->lag);
    if ((s->hand - th_count_flights(h, 0)) <= 0)
        return 0;
    th_arm(k, 0, (double)th_packet_turn(h, s) + (double)(t->rain_ms / t->turn_ms));
    return 1;
}

void th_long_press_ended(struct ThHand *h, const struct ThTuning *t)
{
    if (h->stroke.phase == ThP_LongPress)
        th_arm(&h->stroke, 0, (double)(h->last_packet_turn + 1) + (double)(t->rain_ms / t->turn_ms));
}

/******************************************************************************/
