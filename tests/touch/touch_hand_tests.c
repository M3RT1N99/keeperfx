/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/** @file touch_hand_tests.c
 *     Tests of the rules of the keeper's hand in src/touch_hand.c.
 * @par Comment:
 *     Runs without a device, an engine or SDL. A small stand-in plays the game's side:
 *     a turn every 50 ms, a packet per turn, and a click arriving as many turns later as
 *     the input lag says. It drops while its hand holds something and slaps when it does
 *     not, which is what packets_input.c does with a right button release.
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
#include <stdio.h>
#include <string.h>

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

#define TURN_MS 50
#define PACKETS 4096
/* The creature the finger is over, when a test puts it over one. */
#define CREATURE_A 21UL
#define CREATURE_B 22UL

static const struct ThTuning tuning = {250.0f, 100.0f, 150.0f, 420.0f, 4, 50.0f, 6.0f, 48.0f};

static struct ThHand h;
static struct ThSees sees;
static long long now;
/* The game's side. */
static long long game_turn;
static int game_lag;
static int game_hand;
static int drops;
static int slaps;
static int picks_landed;
static int right_click_waiting;
static int pick_waiting;
static int pick_fails;
static int rubbing;
static unsigned char sent_right[PACKETS];
static unsigned char sent_pick[PACKETS];
static long long click_turns[64];
static int clicks;

/* The hand as the game has it, and the place under the finger: bare floor where things can drop. */
static void begin(int hand, int lag)
{
    th_reset(&h);
    memset(&sees, 0, sizeof(sees));
    sees.in_force = 1;
    sees.known = 1;
    sees.droppable = 1;
    now = 100000;
    game_turn = 1000;
    game_lag = lag;
    game_hand = hand;
    drops = slaps = picks_landed = 0;
    right_click_waiting = pick_waiting = pick_fails = rubbing = 0;
    clicks = 0;
    memset(sent_right, 0, sizeof(sent_right));
    memset(sent_pick, 0, sizeof(sent_pick));
}

static void refresh(void)
{
    sees.hand = game_hand;
    sees.turn = game_turn;
    sees.lag = game_lag;
}

/* One turn: the packet goes out with what is waiting, what has come through the lag happens. */
static void end_of_turn(void)
{
    th_note_packet(&h, game_turn, game_lag, right_click_waiting, pick_waiting);
    if (right_click_waiting && (clicks < 64))
        click_turns[clicks++] = game_turn;
    sent_right[game_turn % PACKETS] = (unsigned char)right_click_waiting;
    sent_pick[game_turn % PACKETS] = (unsigned char)pick_waiting;
    right_click_waiting = 0;
    pick_waiting = 0;
    const long long arriving = game_turn - game_lag;
    if (sent_pick[arriving % PACKETS]) {
        sent_pick[arriving % PACKETS] = 0;
        if (pick_fails) {
            pick_fails = 0;
        } else {
            game_hand++;
            picks_landed++;
        }
    }
    if (sent_right[arriving % PACKETS]) {
        sent_right[arriving % PACKETS] = 0;
        if (game_hand > 0) {
            game_hand--;
            drops++;
        } else {
            slaps++;
        }
    }
    game_turn++;
}

/*
 * A slap the way the wiring makes one: the finger's path went over the creature under it,
 * the pointer went where that was, and the game says what is under it there.
 */
static int slap_with(const struct ThTuning *t)
{
    if (sees.creature != 0)
        th_stroke_crossed(&h, &sees, t, sees.creature, sees.x, sees.y, 1, now);
    float aim_x;
    float aim_y;
    if (!th_slap_aim(&h, &aim_x, &aim_y))
        return 0;
    CHECK((aim_x == sees.x) && (aim_y == sees.y), "aimed where it was found, %g,%g", (double)aim_x, (double)aim_y);
    return th_slap_now(&h, &sees, t);
}

/*
 * Runs for a while with a look every 16 ms, the way the polls come: at most one click
 * read per turn, because the wiring only asks when the button is free.
 */
static void run_ms(int ms)
{
    for (int t = 0; t < ms; t += 16) {
        now += 16;
        refresh();
        if (rubbing)
            sees.x = (float)((now / 16) % 40) * 8.0f;
        if ((th_decide(&h, &sees, &tuning, now) == ThV_Hand) && !right_click_waiting) {
            if (th_drop_due(&h, &sees, &tuning) || slap_with(&tuning))
                right_click_waiting = 1;
        }
        if ((now % TURN_MS) < 16)
            end_of_turn();
    }
}

static void test_a_tap_stays_a_tap(void)
{
    // Nothing of what the rules do has started by the time a tap lifts, with or without a hand.
    for (int hand = 0; hand <= 3; hand += 3) {
        begin(hand, 0);
        th_stroke_begin(&h, now, 0.0f, 0.0f);
        refresh();
        for (int t = 0; t < 200; t += 16) {
            now += 16;
            CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Wait, "hand %d at %d ms", hand, t);
        }
    }
}

static void test_a_held_finger_drops_at_the_rate_asked(void)
{
    begin(8, 0);
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    run_ms(240);
    CHECK(drops == 0, "nothing before the hold time, %d", drops);
    run_ms(70);
    CHECK(drops == 1, "the first after the hold time, %d", drops);
    run_ms(300);
    CHECK((drops >= 3) && (drops <= 5), "one every 100 ms, %d after 300 ms more", drops);
    run_ms(3000);
    CHECK((drops == 8) && (game_hand == 0), "the whole hand, %d", drops);
    CHECK(slaps == 0, "and no slap, %d", slaps);
    for (int i = 1; i < clicks; i++)
        CHECK((click_turns[i] - click_turns[i - 1]) == 2, "two turns each time, %lld at click %d", click_turns[i] - click_turns[i - 1], i);
}

static void test_a_hold_time_past_the_long_press_time(void)
{
    const struct ThTuning long_hold = {800.0f, 100.0f, 150.0f, 420.0f, 4, 50.0f, 6.0f, 48.0f};
    begin(3, 0);
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    refresh();
    for (int t = 0; t < 780; t += 20) {
        now += 20;
        CHECK(th_decide(&h, &sees, &long_hold, now) == ThV_Wait, "still waiting at %d ms", t);
    }
    now += 40;
    CHECK(th_decide(&h, &sees, &long_hold, now) == ThV_Hand, "at the hold time");
}

static void test_a_rate_between_turns_keeps_to_it(void)
{
    const struct ThTuning between = {250.0f, 75.0f, 150.0f, 420.0f, 4, 50.0f, 6.0f, 48.0f};
    begin(0, 0);
    game_hand = 40;
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    for (int t = 0; t < 2000; t += 16) {
        now += 16;
        refresh();
        if ((th_decide(&h, &sees, &between, now) == ThV_Hand) && !right_click_waiting
         && th_drop_due(&h, &sees, &between))
            right_click_waiting = 1;
        if ((now % TURN_MS) < 16)
            end_of_turn();
    }
    CHECK((clicks >= 20) && (clicks <= 24), "1.75 s at 75 ms, %d", clicks);
    for (int i = 1; i < clicks; i++) {
        const long long gap = click_turns[i] - click_turns[i - 1];
        CHECK((gap == 1) || (gap == 2), "a turn or two, %lld", gap);
    }
    // At the fastest the knob allows, one click per turn.
    const struct ThTuning fastest = {250.0f, 50.0f, 150.0f, 420.0f, 4, 50.0f, 6.0f, 48.0f};
    begin(0, 0);
    game_hand = 40;
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    for (int t = 0; t < 1000; t += 16) {
        now += 16;
        refresh();
        if ((th_decide(&h, &sees, &fastest, now) == ThV_Hand) && !right_click_waiting
         && th_drop_due(&h, &sees, &fastest))
            right_click_waiting = 1;
        if ((now % TURN_MS) < 16)
            end_of_turn();
    }
    for (int i = 1; i < clicks; i++)
        CHECK((click_turns[i] - click_turns[i - 1]) == 1, "every turn, %lld", click_turns[i] - click_turns[i - 1]);
    // A first click that was a turn late does not make the second one a turn early.
    begin(5, 0);
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    now += 300;
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Hand, "armed before this turn's packet");
    th_note_packet(&h, game_turn, 0, 0, 0);
    CHECK(th_drop_due(&h, &sees, &tuning), "the first goes out one packet later");
    game_turn += 1;
    refresh();
    th_note_packet(&h, game_turn, 0, 1, 0);
    CHECK(!th_drop_due(&h, &sees, &tuning), "not in the next packet");
    game_turn += 1;
    refresh();
    CHECK(!th_drop_due(&h, &sees, &tuning), "nor in this one");
    th_note_packet(&h, game_turn, 0, 0, 0);
    CHECK(th_drop_due(&h, &sees, &tuning), "two packets on from the first");
    // A click made after the turn's packet goes out in the next one, and the rate counts from there.
    begin(5, 0);
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    now += 300;
    refresh();
    th_note_packet(&h, game_turn, 0, 0, 0);
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Hand, "armed");
    CHECK(th_drop_due(&h, &sees, &tuning), "the first, for the next packet");
    game_turn += 1;
    refresh();
    th_note_packet(&h, game_turn, 0, 1, 0);
    CHECK(!th_drop_due(&h, &sees, &tuning), "the next packet would be one turn on");
    game_turn += 1;
    refresh();
    th_note_packet(&h, game_turn, 0, 0, 0);
    CHECK(th_drop_due(&h, &sees, &tuning), "after this turn's packet: two turns on");
}

static void test_no_slap_after_a_rain_at_any_lag(void)
{
    for (int lag = 0; lag <= 12; lag++) {
        begin(8, lag);
        th_stroke_begin(&h, now, 0.0f, 0.0f);
        run_ms(5000);
        CHECK(drops == 8, "lag %d: drops %d", lag, drops);
        CHECK(slaps == 0, "lag %d: slaps %d", lag, slaps);
    }
}

static void test_a_rain_goes_as_fast_as_the_lag_allows(void)
{
    // A count that only trusted what has landed would stop for the length of the lag after
    // every click; the rate stays what it is asked to be.
    begin(8, 12);
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    run_ms(250 + 8 * 100 + 12 * TURN_MS + 150);
    CHECK(drops == 8, "lag 12: %d drops in the time of 8 at the rate, and the lag once", drops);
    CHECK(slaps == 0, "slaps %d", slaps);
}

static void test_the_lag_may_change_under_way(void)
{
    begin(8, 3);
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    run_ms(500);
    game_lag = 9;
    run_ms(400);
    game_lag = 4;
    run_ms(3000);
    CHECK(slaps == 0, "slaps %d", slaps);
    CHECK(drops == 8, "drops %d", drops);
}

static void test_a_tap_on_the_floor_with_a_full_hand_drops_nothing(void)
{
    begin(8, 0);
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    run_ms(100);
    CHECK(th_stroke_lift(&h, 1) == ThL_Tap, "a tap");
    run_ms(500);
    CHECK(drops == 0, "drops %d", drops);
}

static void test_a_wall_stays_a_wall(void)
{
    // With something in the hand: nothing can drop, so the ordinary gestures have it.
    begin(5, 0);
    sees.droppable = 0;
    sees.wall = 1;
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    refresh();
    now += 16;
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Wait, "hand: open until it moves, so that a flick clicks nothing");
    th_stroke_moved(&h, now);
    now += 100;
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Today, "hand: the ordinary drag");
    run_ms(1000);
    CHECK(drops == 0, "hand: drops %d", drops);
    begin(5, 0);
    sees.droppable = 0;
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    refresh();
    now += 16;
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Wait, "hand, not moving: open");
    now += 420;
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Today, "hand: the ordinary long press at its time");
    begin(5, 0);
    sees.droppable = 0;
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    refresh();
    th_stroke_look(&h, &sees);
    now += 16;
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Wait, "hand: not decided while it rests");
    th_stroke_moved(&h, now + 24);
    CHECK(th_stroke_lift(&h, 1) == ThL_Flick, "hand: a flick where nothing can drop clicks nothing");
    // Empty.
    begin(0, 0);
    sees.droppable = 0;
    sees.wall = 1;
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    th_stroke_moved(&h, now + 50);
    refresh();
    now += 100;
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Today, "empty: the ordinary drag, which tags");
}

static void test_out_of_force_stays_today(void)
{
    // A spell or a room selected, a menu: the ordinary gestures, whatever is in the hand.
    begin(5, 0);
    sees.in_force = 0;
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    run_ms(1000);
    CHECK(drops == 0, "drops %d", drops);
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Today, "today");
    CHECK(!th_drop_due(&h, &sees, &tuning), "no click");
    // A rain that goes over the panel holds off and goes on behind it.
    begin(5, 0);
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    run_ms(300);
    const int before = drops;
    sees.in_force = 0;
    run_ms(500);
    CHECK(drops == before, "over the panel: %d, was %d", drops, before);
    sees.in_force = 1;
    run_ms(200);
    CHECK(drops > before, "back: %d, was %d", drops, before);
}

static void test_a_drag_drops_at_once(void)
{
    begin(5, 0);
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    run_ms(100);
    th_stroke_moved(&h, now);
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Hand, "a drag from a place to drop at");
    run_ms(60);
    CHECK(drops == 1, "the first at once, %d", drops);
    sees.droppable = 0;
    run_ms(300);
    CHECK(drops == 1, "nothing over a wall, %d", drops);
    sees.droppable = 1;
    run_ms(300);
    CHECK(drops >= 2, "and on behind it, %d", drops);
    CHECK(slaps == 0, "slaps %d", slaps);
}

static void test_a_drag_from_a_place_with_no_drop_is_the_ordinary_drag(void)
{
    begin(5, 0);
    sees.droppable = 0;
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    run_ms(100);
    th_stroke_moved(&h, now);
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Today, "today");
}

static void test_the_conveyor(void)
{
    // A finger held with nothing in the hand waits. What comes falls at the next tick.
    begin(0, 0);
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    run_ms(600);
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Hand, "bare floor, empty hand: it waits");
    for (int i = 0; i < 5; i++) {
        pick_waiting = 1;
        run_ms(TURN_MS * 4);
        CHECK(drops == i + 1, "number %d has dropped by now: %d", i, drops);
    }
    CHECK(slaps == 0, "slaps %d", slaps);
    // A click from the panel that comes to nothing must not bring a slap with it.
    begin(0, 8);
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    run_ms(600);
    pick_waiting = 1;
    pick_fails = 1;
    run_ms(1000);
    CHECK((slaps == 0) && (drops == 0), "a pick that came to nothing: slaps %d drops %d", slaps, drops);
    // Under lag a pick only counts once it has landed, and then it falls.
    begin(0, 12);
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    run_ms(600);
    pick_waiting = 1;
    run_ms(TURN_MS * 8);
    CHECK(drops == 0, "still under way, %d", drops);
    run_ms(TURN_MS * 20);
    CHECK((drops == 1) && (slaps == 0), "landed and fallen: drops %d slaps %d", drops, slaps);
}

static void test_a_finger_before_the_hold_time_is_still_a_tap_for_the_conveyor(void)
{
    begin(0, 0);
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    run_ms(100);
    pick_waiting = 1;
    run_ms(100);
    CHECK(drops == 0, "not before the hold time, %d", drops);
    run_ms(150);
    CHECK(drops == 1, "then at once, %d", drops);
}

static void test_a_long_press_on_a_creature_with_an_empty_hand(void)
{
    begin(0, 0);
    sees.thing = 1;
    sees.creature = CREATURE_A;
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    refresh();
    enum ThVerdict verdict = ThV_Wait;
    while (verdict == ThV_Wait) {
        now += 16;
        verdict = th_decide(&h, &sees, &tuning, now);
        CHECK((now - 100000) < 500, "it took too long");
    }
    CHECK(verdict == ThV_LongPress, "the ordinary long press, %d", (int)verdict);
    CHECK((now - 100000) >= 420, "at the long press time, %lld", now - 100000);
    // Then the hand gets something: the button gives way, and it drops from there.
    CHECK(!th_long_press_gives_way(&h, &sees, &tuning), "nothing in the hand yet");
    game_hand = 2;
    refresh();
    CHECK(th_long_press_gives_way(&h, &sees, &tuning), "it gives way");
    now += 16;
    CHECK(!th_drop_due(&h, &sees, &tuning), "the release was the first drop");
    game_turn += 2;
    refresh();
    CHECK(th_drop_due(&h, &sees, &tuning), "then at the rate");
}

static void test_a_long_press_on_a_wall_selects_an_area(void)
{
    // Open until the long press time, since a quick start could still cross a creature; then an area.
    begin(0, 0);
    sees.wall = 1;
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    run_ms(16);
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Wait, "a wall, still open");
    CHECK(!th_area_key(&h), "no key yet");
    now += 420;
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Area, "an area at the long press time");
    CHECK(th_area_key(&h), "the key held");
    CHECK(th_decide(&h, &sees, &tuning, now + 500) == ThV_Area, "and it stays one");
    begin(0, 0);
    sees.door = 1;
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    run_ms(16);
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Wait, "a door, open as the floor is");
    now += 420;
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_LongPress, "the ordinary long press on it at its time");
}

/* The buttons as the wiring holds them for an area, and a right press no packet has carried yet. */
static int area_left;
static int area_right;
static int area_right_unsent;
static int area_applied;
static int area_cancelled;
static int area_packet_left;

/* A finger resting on a wall the hand could mark, to the long press time. */
static void rest_on_a_wall(int hand)
{
    begin(hand, 0);
    area_left = area_right = area_right_unsent = area_applied = area_cancelled = 0;
    sees.droppable = 0;
    sees.wall = 1;
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    refresh();
    th_stroke_look(&h, &sees);
    now += 16;
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Wait, "hand %d: open while it rests", hand);
    now += 420;
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Area, "hand %d: an area at the long press time", hand);
}

/* What the wiring does with a step, and what the game makes of the buttons going up. */
static void area_serve(void)
{
    switch (th_area_step(&h, now, 250.0f))
    {
    case ThAS_PressLeft:
        CHECK(!area_left, "pressed once");
        area_left = 1;
        break;
    case ThAS_PressRight:
        CHECK(area_left, "a right click with the left button down");
        area_right = 1;
        area_right_unsent = 1;
        break;
    case ThAS_Release:
        // A release no right click has gone ahead of applies the area, which is in drag mode.
        if (area_left && !area_right)
            area_applied++;
        else if (area_left && !area_right_unsent)
            area_cancelled++;
        area_left = area_right = 0;
        break;
    default:
        break;
    }
}

/* A packet: area_mode says it made the left button drag an area. */
static void area_packet(int area_mode)
{
    area_serve();
    area_packet_left = area_left;
    th_note_area_packet(&h, area_mode, area_left, area_right_unsent);
    area_right_unsent = 0;
    now += TURN_MS;
    area_serve();
}

/* From the long press time to the game dragging the area from where the finger rested. */
static void area_to_drag(void)
{
    area_serve();
    CHECK(!area_left, "no press before a packet has carried the key");
    CHECK(th_area_anchoring(&h), "the pointer stays where it rested");
    area_packet(0);
    CHECK(!area_left, "nor after a packet another action took");
    area_packet(1);
    CHECK(area_left, "pressed once the key has gone out");
    CHECK(th_area_anchoring(&h), "and the pointer stays until the press has gone out");
    area_packet(1);
    CHECK(!th_area_anchoring(&h), "then the pointer follows the finger");
    CHECK(th_area_owns_buttons(&h), "the area has the buttons");
}

/* Packets until the key is let go of, which only a packet without the left button held does. */
static void area_until_the_key_goes(void)
{
    for (int t = 0; (t < 10) && th_area_key(&h); t++) {
        area_packet(1);
        CHECK(!area_packet_left || th_area_key(&h), "the key outlasts a packet with the left button held");
    }
    CHECK(!th_area_key(&h), "the key let go of");
    CHECK(!area_left && !area_right, "the buttons up");
    CHECK(!th_area_owns_buttons(&h), "and nothing owned");
}

static void test_an_area_of_walls(void)
{
    // With or without things in the hand: nothing can fall on a wall, and the game marks under the hand too.
    for (int hand = 0; hand <= 5; hand += 5) {
        rest_on_a_wall(hand);
        CHECK(th_area_key(&h), "hand %d: the key held from the long press time", hand);
        area_to_drag();
        th_stroke_moved(&h, now);
        CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Area, "hand %d: moving keeps the area", hand);
        CHECK(th_stroke_lift(&h, 1) == ThL_Area, "hand %d: a lift after moving applies it", hand);
        CHECK(th_area_key(&h), "hand %d: the key held for the packet with the release", hand);
        area_until_the_key_goes();
        CHECK(area_applied == 1, "hand %d: applied %d", hand, area_applied);
        CHECK(area_cancelled == 0, "hand %d: cancelled %d", hand, area_cancelled);
        refresh();
        CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Today, "hand %d: a phase of none is nobody's", hand);
    }
}

static void test_a_hold_lifted_where_it_rested_applies_nothing(void)
{
    // One slab is a tap's, and a finger held on a creature drawn over a wall top has not asked for it.
    rest_on_a_wall(0);
    area_to_drag();
    CHECK(th_stroke_lift(&h, 1) == ThL_Hand, "nothing clicked where it lifted");
    area_serve();
    CHECK(area_right && area_left, "a right click with the left button still down");
    area_serve();
    CHECK(area_left, "and the left button waits for the packet that carries both");
    area_until_the_key_goes();
    CHECK((area_applied == 0) && (area_cancelled == 1), "applied %d, cancelled %d", area_applied, area_cancelled);
    // Lifted before any packet carried the key: nothing was pressed, and the key goes at once.
    rest_on_a_wall(0);
    area_serve();
    CHECK(th_stroke_lift(&h, 1) == ThL_Hand, "nothing clicked");
    CHECK(!th_area_key(&h), "the key let go of at once");
    area_serve();
    CHECK(!area_left && !area_right, "no button");
    // Lifted with the press on its way: cancelled as well, moved or not.
    rest_on_a_wall(0);
    area_packet(1);
    CHECK(area_left, "pressed");
    th_stroke_moved(&h, now);
    CHECK(th_stroke_lift(&h, 1) == ThL_Hand, "before the game had the press, nothing is applied");
    area_until_the_key_goes();
    CHECK((area_applied == 0) && (area_cancelled == 1), "applied %d, cancelled %d", area_applied, area_cancelled);
}

static void test_a_second_finger_cancels_an_area(void)
{
    rest_on_a_wall(0);
    area_to_drag();
    th_stroke_moved(&h, now);
    th_stroke_cancel(&h);
    area_serve();
    CHECK(area_right && area_left, "a right click with the left button still down");
    for (int t = 0; t < 10; t++) {
        now += 16;
        area_serve();
    }
    CHECK(area_left, "no release before a packet has carried the right click");
    CHECK(th_stroke_lift(&h, 1) == ThL_Tap, "the finger lifting later is nobody's");
    area_until_the_key_goes();
    CHECK((area_applied == 0) && (area_cancelled == 1), "applied %d, cancelled %d", area_applied, area_cancelled);
    // Before the press nothing is held, and the key goes at once.
    rest_on_a_wall(5);
    th_stroke_cancel(&h);
    CHECK(!th_area_key(&h), "the key let go of");
    area_packet(1);
    CHECK(!area_left && !area_right, "no button");
}

static void test_the_key_waits_for_the_packet_with_the_release(void)
{
    // The game can read the buttons before they go up and make its packet after: that packet still
    // has the left button held, and the release is in the next one, which needs the key as well.
    rest_on_a_wall(0);
    area_to_drag();
    th_stroke_moved(&h, now);
    CHECK(th_stroke_lift(&h, 1) == ThL_Area, "applied by the lift");
    area_serve();
    CHECK(!area_left, "the buttons up");
    th_note_area_packet(&h, 1, 1, 0);
    CHECK(th_area_key(&h), "a packet read before the release keeps the key");
    th_note_area_packet(&h, 1, 0, 0);
    CHECK(!th_area_key(&h), "the one with the release lets it go");
    // The same after a cancel.
    rest_on_a_wall(0);
    area_to_drag();
    th_stroke_cancel(&h);
    area_serve();
    th_note_area_packet(&h, 1, 0, 1);
    CHECK(th_area_key(&h) && area_left, "a right click without the left button held cancels nothing yet");
    th_note_area_packet(&h, 1, 1, 0);
    area_serve();
    CHECK(area_left, "nor a left button held without the right click");
}

static void test_a_cancel_no_packet_carries(void)
{
    // A pause carries no buttons: the right click is given up on, and the buttons still go up.
    rest_on_a_wall(0);
    area_to_drag();
    th_stroke_cancel(&h);
    area_serve();
    CHECK(area_right, "the right click");
    now += 250;
    area_serve();
    CHECK(area_left, "still down at the overdue time");
    now += 16;
    area_serve();
    CHECK(!area_left && !area_right, "up after it");
    CHECK(th_area_key(&h), "the key until a packet without the left button");
    th_note_area_packet(&h, 0, 0, 0);
    CHECK(!th_area_key(&h), "let go of");
}

static void test_the_buttons_let_go_of_elsewhere(void)
{
    // The touch layer reset: the buttons are up already, and the key waits for the packet with the release.
    rest_on_a_wall(0);
    area_to_drag();
    th_area_buttons_released(&h);
    area_left = area_right = 0;
    CHECK(th_area_key(&h), "the key held");
    th_stroke_cancel(&h);
    area_serve();
    CHECK(!area_left && !area_right, "no right click after the buttons went up");
    area_until_the_key_goes();
    rest_on_a_wall(0);
    th_area_buttons_released(&h);
    CHECK(!th_area_key(&h), "before the press, the key goes at once");
}

static void test_moving_first_keeps_the_stroke_rules(void)
{
    // A stroke from a wall that moves before the long press time marks as before, and holds no key.
    begin(0, 0);
    sees.droppable = 0;
    sees.wall = 1;
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    refresh();
    th_stroke_look(&h, &sees);
    th_stroke_moved(&h, now + 300);
    now += 320;
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Today, "the ordinary drag, which marks");
    now += 500;
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Today, "still");
    CHECK(!th_area_key(&h), "no key");
    // With things in the hand the same.
    begin(5, 0);
    sees.droppable = 0;
    sees.wall = 1;
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    refresh();
    th_stroke_look(&h, &sees);
    th_stroke_moved(&h, now + 100);
    now += 600;
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Today, "hand: the ordinary drag");
    CHECK(!th_area_key(&h), "hand: no key");
}

static void test_an_area_needs_a_wall_now(void)
{
    // Landed on a wall, and by the long press time the game says otherwise: dug out, or covered.
    begin(0, 0);
    sees.droppable = 0;
    sees.wall = 1;
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    refresh();
    th_stroke_look(&h, &sees);
    now += 436;
    sees.known = 0;
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Wait, "waits for the game to say");
    sees.known = 1;
    sees.wall = 0;
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Today, "the ordinary long press");
    CHECK(!th_area_key(&h), "no key");
    // A hand over a wall it cannot dig: the ordinary long press, as before.
    begin(5, 0);
    sees.droppable = 0;
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    refresh();
    th_stroke_look(&h, &sees);
    now += 436;
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Today, "hand: the ordinary long press");
    // One area at a time: one waiting for its release keeps the key, and a new finger has the long press.
    rest_on_a_wall(0);
    area_to_drag();
    th_stroke_moved(&h, now);
    th_stroke_lift(&h, 1);
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    refresh();
    th_stroke_look(&h, &sees);
    now += 436;
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Today, "the ordinary long press while one is on its way out");
}

static void test_out_of_force_no_area(void)
{
    begin(0, 0);
    sees.wall = 1;
    sees.in_force = 0;
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    refresh();
    now += 436;
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Today, "today");
    CHECK(!th_area_key(&h), "no key");
    // Read in force, out of it by the long press time: a menu over it.
    begin(0, 0);
    sees.wall = 1;
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    refresh();
    th_stroke_look(&h, &sees);
    sees.in_force = 0;
    sees.known = 0;
    now += 436;
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Today, "today");
    CHECK(!th_area_key(&h), "no key");
}

static void test_a_hold_after_a_rain_is_no_slap(void)
{
    // The last one dropped is right under the finger.
    begin(2, 0);
    sees.thing = 1;
    sees.creature = CREATURE_A;
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    run_ms(2000);
    CHECK(drops == 2, "drops %d", drops);
    CHECK(slaps == 0, "slaps %d", slaps);
}

static void test_a_quick_stroke_with_an_empty_hand_slaps(void)
{
    begin(0, 0);
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    run_ms(64);
    th_stroke_moved(&h, now);
    run_ms(100);
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Hand, "a swipe");
    CHECK(slaps == 0, "bare floor: %d", slaps);
    sees.thing = 1;
    sees.creature = CREATURE_A;
    // Rubbing goes on for as long as the finger goes on, at the game's pace.
    rubbing = 1;
    run_ms(2000);
    CHECK((slaps >= 8) && (slaps <= 11), "one per whip of 4 turns, %d in 2 s", slaps);
    CHECK(drops == 0, "drops %d", drops);
    // Come to rest on the creature: one more at most, and then nothing however long it stays.
    rubbing = 0;
    run_ms(300);
    const int at_rest = slaps;
    run_ms(2000);
    CHECK(slaps == at_rest, "a resting finger: %d, was %d", slaps, at_rest);
    // A tremble is still a rest.
    sees.x += 3.0f;
    run_ms(500);
    CHECK(slaps == at_rest, "a trembling finger: %d, was %d", slaps, at_rest);
    // It moves on, however slowly.
    sees.x += 10.0f;
    run_ms(300);
    CHECK(slaps == at_rest + 1, "moving again: %d, was %d", slaps, at_rest);
}

static void test_the_slap_rate_keeps_a_creature_waiting(void)
{
    const struct ThTuning slow = {250.0f, 100.0f, 500.0f, 420.0f, 4, 50.0f, 6.0f, 48.0f};
    begin(0, 0);
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    th_stroke_moved(&h, now);
    now += 100;
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Hand, "a swipe");
    sees.thing = 1;
    sees.creature = CREATURE_A;
    CHECK(slap_with(&slow), "the first");
    th_note_packet(&h, sees.turn, sees.lag, 1, 0);
    game_turn += 6;
    now += 300;
    refresh();
    sees.x += 20.0f;
    CHECK(!slap_with(&slow), "under the rate");
    // Another one does not wait for this one's rate, only for the whip.
    sees.creature = CREATURE_B;
    CHECK(slap_with(&slow), "the next creature");
    th_note_packet(&h, sees.turn, sees.lag, 1, 0);
    now += 16;
    sees.creature = CREATURE_A;
    sees.x += 20.0f;
    game_turn += 1;
    now += 200;
    refresh();
    CHECK(!slap_with(&slow), "the whip is still on its way");
    game_turn += 3;
    refresh();
    CHECK(slap_with(&slow), "and over");
}

static void test_a_slow_stroke_does_nothing(void)
{
    // Over a creature, from the floor: no slap, and no left button, which would pick up or mark
    // wherever the finger is once it has travelled.
    begin(0, 0);
    sees.thing = 1;
    sees.creature = CREATURE_A;
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    run_ms(300);
    th_stroke_moved(&h, now);
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Hand, "the hand's, not the ordinary drag");
    rubbing = 1;
    run_ms(1000);
    CHECK((slaps == 0) && (drops == 0), "slaps %d drops %d", slaps, drops);
    // What the other hand fetches meanwhile still falls.
    rubbing = 0;
    pick_waiting = 1;
    run_ms(300);
    CHECK((drops == 1) && (slaps == 0), "drops %d slaps %d", drops, slaps);
}

static void test_a_swipe_counts_what_is_under_way(void)
{
    // A pick up on its way: the right click would drop what it picks.
    begin(0, 8);
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    th_stroke_moved(&h, now);
    now += 100;
    refresh();
    th_note_packet(&h, sees.turn, sees.lag, 0, 1);
    sees.thing = 1;
    sees.creature = CREATURE_A;
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Hand, "it goes on as a finger that drops");
    CHECK(!th_drop_due(&h, &sees, &tuning) && !slap_with(&tuning), "and sends no slap");
    // A swipe already going when the other hand's click goes out: the same.
    begin(0, 8);
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    th_stroke_moved(&h, now);
    now += 100;
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Hand, "a swipe");
    th_note_packet(&h, sees.turn, sees.lag, 0, 1);
    sees.thing = 1;
    sees.creature = CREATURE_A;
    CHECK(!slap_with(&tuning), "no slap with a pick up on its way");
    game_turn += 9;
    refresh();
    sees.x += 20.0f;
    CHECK(slap_with(&tuning), "once that has landed or come to nothing");
    // The hand reads one and that one is on its way out: empty by the time this arrives.
    begin(1, 8);
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    th_stroke_moved(&h, now);
    now += 100;
    refresh();
    th_note_packet(&h, sees.turn, sees.lag, 1, 0);
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Hand, "a swipe");
    sees.thing = 1;
    sees.creature = CREATURE_A;
    CHECK(!slap_with(&tuning), "under the whip's turns, as a right click just went");
    game_turn += 4;
    refresh();
    CHECK(slap_with(&tuning), "it slaps");
    // A pick up the other hand sends while the slap's pointer is on its way: no right click.
    begin(0, 8);
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    th_stroke_moved(&h, now);
    now += 100;
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Hand, "a swipe");
    CHECK(th_stroke_crossed(&h, &sees, &tuning, CREATURE_A, 50.0f, 60.0f, 1, now), "crossed");
    th_note_packet(&h, sees.turn, sees.lag, 0, 1);
    sees.creature = CREATURE_A;
    CHECK(!th_slap_now(&h, &sees, &tuning), "not with the pick up on its way");
}

static void test_a_quick_stroke_reaching_a_wall(void)
{
    // From the floor nothing presses the left button: no marks, wherever the finger goes.
    begin(0, 0);
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    th_stroke_moved(&h, now + 30);
    now += 100;
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Hand, "a swipe");
    sees.wall = 1;
    now += 16;
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Hand, "still the hand's on the wall");
    CHECK(!th_drop_due(&h, &sees, &tuning) && !slap_with(&tuning), "and nothing to click there");
    CHECK(th_stroke_lift(&h, 1) == ThL_Hand, "the hand's lift, which clicks nothing where it lifted either");
}

static void test_a_stroke_from_a_wall(void)
{
    // Quick, nothing crossed by the time the drag would press: the ordinary drag, which marks.
    begin(0, 0);
    sees.wall = 1;
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    refresh();
    th_stroke_look(&h, &sees);
    th_stroke_moved(&h, now + 40);
    sees.wall = 0;
    now += 100;
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Today, "it marks as it always did");
    // Quick, and over an own creature before that: a swipe, and the creature gets its slap.
    begin(0, 0);
    sees.wall = 1;
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    refresh();
    th_stroke_look(&h, &sees);
    sees.wall = 0;
    th_stroke_moved(&h, now + 40);
    now += 60;
    refresh();
    CHECK(th_stroke_crossed(&h, &sees, &tuning, CREATURE_A, 300.0f, 200.0f, 1, now), "crossed, and kept for later");
    float aim_x;
    float aim_y;
    CHECK(!th_slap_aim(&h, &aim_x, &aim_y), "no slap before it is decided");
    now += 40;
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Hand, "a swipe after all");
    CHECK(th_slap_aim(&h, &aim_x, &aim_y) && (aim_x == 300.0f) && (aim_y == 200.0f), "aimed where it was found");
    sees.creature = CREATURE_A;
    CHECK(th_slap_now(&h, &sees, &tuning), "slapped");
    // Slow, crossing or not: the ordinary drag.
    begin(0, 0);
    sees.wall = 1;
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    refresh();
    th_stroke_look(&h, &sees);
    sees.wall = 0;
    th_stroke_crossed(&h, &sees, &tuning, CREATURE_A, 300.0f, 200.0f, 1, now + 100);
    th_stroke_moved(&h, now + 300);
    now += 310;
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Today, "slow: it marks");
    CHECK(!th_slap_aim(&h, &aim_x, &aim_y), "and slaps nothing");
    // Quick, and only near one, within the hand's reach: it marks.
    begin(0, 0);
    sees.wall = 1;
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    refresh();
    th_stroke_look(&h, &sees);
    sees.wall = 0;
    CHECK(th_stroke_crossed(&h, &sees, &tuning, CREATURE_A, 300.0f, 200.0f, 100.0f, now + 30), "near enough to slap");
    th_stroke_moved(&h, now + 40);
    now += 100;
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Today, "near is not over: it marks");
    CHECK(!th_slap_aim(&h, &aim_x, &aim_y), "and slaps nothing");
    // With a hand: as it always was.
    begin(2, 0);
    sees.wall = 1;
    sees.droppable = 0;
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    refresh();
    th_stroke_look(&h, &sees);
    th_stroke_crossed(&h, &sees, &tuning, CREATURE_A, 300.0f, 200.0f, 1, now);
    th_stroke_moved(&h, now + 40);
    now += 100;
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Today, "with a hand it marks");
}

static void test_a_crossing_from_a_wall_is_a_real_one(void)
{
    float aim_x;
    float aim_y;
    // Landed on the face an imp digs, beside it, and away along the wall: no crossing, it marks.
    begin(0, 0);
    sees.wall = 1;
    th_stroke_begin(&h, now, 400.0f, 300.0f);
    th_stroke_crossed(&h, &sees, &tuning, CREATURE_A, 420.0f, 320.0f, hypotf(20.0f, 20.0f), now);
    refresh();
    th_stroke_look(&h, &sees);
    th_stroke_crossed(&h, &sees, &tuning, CREATURE_A, 420.0f, 320.0f, hypotf(20.0f, 20.0f), now + 16);
    th_stroke_crossed(&h, &sees, &tuning, CREATURE_A, 420.0f, 320.0f, hypotf(20.0f, 20.0f) + 1.0f, now + 32);
    th_stroke_moved(&h, now + 40);
    now += 100;
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Today, "the landing beside it is no crossing: it marks");
    CHECK(!th_slap_aim(&h, &aim_x, &aim_y), "and slaps nothing");
    // Nearer than the landing, but not by half the distance that counts as over: no crossing either.
    begin(0, 0);
    sees.wall = 1;
    th_stroke_begin(&h, now, 400.0f, 300.0f);
    refresh();
    th_stroke_look(&h, &sees);
    th_stroke_crossed(&h, &sees, &tuning, CREATURE_A, 440.0f, 300.0f, 20.0f, now + 16);
    th_stroke_moved(&h, now + 40);
    now += 100;
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Today, "a wobble towards it: it marks");
    // From the rock onto it: over it, and that much nearer than the landing. A swipe.
    begin(0, 0);
    sees.wall = 1;
    th_stroke_begin(&h, now, 400.0f, 300.0f);
    refresh();
    th_stroke_look(&h, &sees);
    th_stroke_crossed(&h, &sees, &tuning, CREATURE_A, 520.0f, 340.0f, 4.0f, now + 30);
    th_stroke_moved(&h, now + 40);
    now += 100;
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Hand, "onto it from the rock: a swipe");
    CHECK(th_slap_aim(&h, &aim_x, &aim_y) && (aim_x == 520.0f) && (aim_y == 340.0f), "aimed at it");
    // Where the picture does not show the creature, behind the wall the stroke is on, the wiring
    // gives no distance: nothing crossed, and the ordinary drag marks.
    begin(0, 0);
    sees.wall = 1;
    th_stroke_begin(&h, now, 400.0f, 300.0f);
    refresh();
    th_stroke_look(&h, &sees);
    th_stroke_crossed(&h, &sees, &tuning, CREATURE_A, 520.0f, 300.0f, 1.0e30f, now + 30);
    th_stroke_moved(&h, now + 40);
    now += 100;
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Today, "a creature behind the wall: it marks");
}

static void test_a_stroke_from_an_own_door(void)
{
    float aim_x;
    float aim_y;
    // The floor's rules: a quick one swipes and slaps what it went over, and nothing presses the left button.
    begin(0, 0);
    sees.door = 1;
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    refresh();
    th_stroke_look(&h, &sees);
    sees.door = 0;
    th_stroke_crossed(&h, &sees, &tuning, CREATURE_A, 300.0f, 200.0f, 1.0f, now);
    th_stroke_moved(&h, now + 40);
    now += 100;
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Hand, "quick from a door: a swipe");
    CHECK(th_slap_aim(&h, &aim_x, &aim_y), "with its slap");
    // A slow one: nothing at all.
    begin(0, 0);
    sees.door = 1;
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    refresh();
    th_stroke_look(&h, &sees);
    sees.door = 0;
    th_stroke_moved(&h, now + 300);
    now += 310;
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Hand, "slow from a door: the hand's, no press");
    CHECK(!th_drop_due(&h, &sees, &tuning) && !th_slap_aim(&h, &aim_x, &aim_y), "and nothing to click");
    // A flick slaps what it went over, as one from the floor does.
    begin(0, 0);
    sees.door = 1;
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    refresh();
    th_stroke_look(&h, &sees);
    th_stroke_crossed(&h, &sees, &tuning, CREATURE_A, 120.0f, 80.0f, 1.0f, now + 30);
    th_stroke_moved(&h, now + 40);
    CHECK(th_stroke_lift(&h, 1) == ThL_Flick, "a flick from a door");
    CHECK(th_slap_aim(&h, &aim_x, &aim_y), "its slap waits past the lift");
    // A tap still is the left click that locks or unlocks it.
    begin(0, 0);
    sees.door = 1;
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    refresh();
    th_stroke_look(&h, &sees);
    now += 80;
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Wait, "not decided before the lift");
    CHECK(th_stroke_lift(&h, 1) == ThL_Tap, "a tap on a door");
}

static void test_a_start_read_in_force_stays_the_hands(void)
{
    // A quick stroke from the floor that is over the panel's margin when it is decided: still a swipe.
    begin(0, 0);
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    refresh();
    th_stroke_look(&h, &sees);
    th_stroke_moved(&h, now + 40);
    sees.in_force = 0;
    sees.known = 0;
    now += 100;
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Hand, "the hand's over the margin too");
    CHECK(!th_drop_due(&h, &sees, &tuning), "and nothing dropped there");
    // A slow one with something in the hand: the hand's as well, dropping once it is back in force.
    begin(2, 0);
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    refresh();
    th_stroke_look(&h, &sees);
    th_stroke_moved(&h, now + 300);
    sees.in_force = 0;
    sees.known = 0;
    now += 310;
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Hand, "a drag with a hand over the margin");
    CHECK(!th_drop_due(&h, &sees, &tuning), "no drop over the margin");
    sees.in_force = 1;
    sees.known = 1;
    CHECK(th_drop_due(&h, &sees, &tuning), "a drop once back on the map");
    // One that landed out of force: the ordinary gestures.
    begin(0, 0);
    sees.in_force = 0;
    sees.known = 0;
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    th_stroke_look(&h, &sees);
    th_stroke_moved(&h, now + 40);
    now += 100;
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Today, "landed out of force: today");
}

static void test_a_flick(void)
{
    float aim_x;
    float aim_y;
    // Over a creature from the floor, lifted inside the grace: no click where it ended, a slap where the creature was.
    begin(0, 0);
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    refresh();
    th_stroke_look(&h, &sees);
    sees.x = 140.0f;
    th_stroke_crossed(&h, &sees, &tuning, CREATURE_A, 120.0f, 80.0f, 1, now + 30);
    th_stroke_moved(&h, now + 40);
    now += 70;
    refresh();
    CHECK(th_stroke_lift(&h, 1) == ThL_Flick, "a flick, not a tap");
    CHECK(th_slap_aim(&h, &aim_x, &aim_y) && (aim_x == 120.0f) && (aim_y == 80.0f), "its slap waits past the lift");
    sees.creature = CREATURE_A;
    CHECK(th_slap_now(&h, &sees, &tuning), "and goes out");
    CHECK(!th_slap_aim(&h, &aim_x, &aim_y), "once");
    // Where the game says the pointer would hit a wall there, no right click: it would slap nothing.
    begin(0, 0);
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    refresh();
    th_stroke_look(&h, &sees);
    th_stroke_crossed(&h, &sees, &tuning, CREATURE_A, 120.0f, 80.0f, 1, now + 30);
    th_stroke_moved(&h, now + 40);
    CHECK(th_stroke_lift(&h, 1) == ThL_Flick, "a flick");
    sees.wall = 1;
    sees.creature = 0;
    CHECK(!th_slap_now(&h, &sees, &tuning), "no click on the wall beside it");
    CHECK(!th_slap_aim(&h, &aim_x, &aim_y), "and nothing left waiting");
    // Over nothing: nothing at all.
    begin(0, 0);
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    refresh();
    th_stroke_look(&h, &sees);
    th_stroke_moved(&h, now + 40);
    CHECK((th_stroke_lift(&h, 1) == ThL_Flick) && !th_slap_aim(&h, &aim_x, &aim_y), "nothing");
    // From a wall, or with a hand: nothing.
    begin(0, 0);
    sees.wall = 1;
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    refresh();
    th_stroke_look(&h, &sees);
    sees.wall = 0;
    th_stroke_crossed(&h, &sees, &tuning, CREATURE_A, 120.0f, 80.0f, 1, now + 30);
    th_stroke_moved(&h, now + 40);
    CHECK((th_stroke_lift(&h, 1) == ThL_Flick) && !th_slap_aim(&h, &aim_x, &aim_y), "from a wall");
    begin(3, 0);
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    refresh();
    th_stroke_look(&h, &sees);
    CHECK(!th_stroke_crossed(&h, &sees, &tuning, CREATURE_A, 120.0f, 80.0f, 1, now + 30), "a hand crosses nothing");
    th_stroke_moved(&h, now + 40);
    CHECK((th_stroke_lift(&h, 1) == ThL_Flick) && !th_slap_aim(&h, &aim_x, &aim_y), "with a hand");
    // Before anything showed where it landed: nothing.
    begin(0, 0);
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    th_stroke_crossed(&h, &sees, &tuning, CREATURE_A, 120.0f, 80.0f, 1, now + 10);
    th_stroke_moved(&h, now + 20);
    CHECK((th_stroke_lift(&h, 1) == ThL_Flick) && !th_slap_aim(&h, &aim_x, &aim_y), "start unknown");
    // A finger that did not travel stays a tap, even one that landed on a creature.
    begin(0, 0);
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    refresh();
    th_stroke_look(&h, &sees);
    th_stroke_crossed(&h, &sees, &tuning, CREATURE_A, 120.0f, 80.0f, 1, now);
    CHECK((th_stroke_lift(&h, 1) == ThL_Tap) && !th_slap_aim(&h, &aim_x, &aim_y), "a tap");
    // Where the rules are not in force, a stroke over before the grace is the press it always was.
    begin(0, 0);
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    th_stroke_moved(&h, now + 40);
    CHECK(th_stroke_lift(&h, 0) == ThL_Tap, "out of force");
    // A second finger: nothing the first one crossed is slapped.
    begin(0, 0);
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    refresh();
    th_stroke_look(&h, &sees);
    th_stroke_crossed(&h, &sees, &tuning, CREATURE_A, 120.0f, 80.0f, 1, now + 30);
    th_stroke_moved(&h, now + 40);
    th_stroke_cancel(&h);
    CHECK(!th_slap_aim(&h, &aim_x, &aim_y), "a grip");
}

static void test_where_it_landed(void)
{
    // What it landed on is read once, where it landed, and a wall it reaches later is no start.
    begin(0, 0);
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    CHECK(th_stroke_start_unseen(&h), "the pointer waits where it landed");
    sees.known = 0;
    th_stroke_look(&h, &sees);
    CHECK(!th_stroke_start_unseen(&h), "one frame, whatever the game could say");
    sees.known = 1;
    th_stroke_moved(&h, now + 40);
    sees.wall = 1;
    now += 100;
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Today, "an unknown start read later, here on a wall");
    begin(0, 0);
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    refresh();
    th_stroke_look(&h, &sees);
    th_stroke_moved(&h, now + 40);
    sees.wall = 1;
    now += 100;
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Hand, "the floor it landed on");
}

static void test_the_path_samples(void)
{
    float xs[TH_PATH_SAMPLES];
    float ys[TH_PATH_SAMPLES];
    int count = th_path_samples(0.0f, 0.0f, 100.0f, 0.0f, 10.0f, xs, ys, TH_PATH_SAMPLES);
    CHECK(count == 10, "a point every 10 px past the start: %d", count);
    for (int i = 0; i < count; i++)
        CHECK((fabsf(xs[i] - 10.0f * (float)(i + 1)) < 0.001f) && (ys[i] == 0.0f), "point %d in order: %g", i, (double)xs[i]);
    count = th_path_samples(0.0f, 0.0f, 30.0f, 40.0f, 9.0f, xs, ys, TH_PATH_SAMPLES);
    CHECK(count == 6, "50 px at 9: %d", count);
    for (int i = 1; i < count; i++)
        CHECK(hypotf(xs[i] - xs[i - 1], ys[i] - ys[i - 1]) <= 9.0f, "no further apart than the step, %d", i);
    CHECK((xs[count - 1] == 30.0f) && (ys[count - 1] == 40.0f), "the end itself");
    count = th_path_samples(5.0f, 7.0f, 5.0f, 7.0f, 10.0f, xs, ys, TH_PATH_SAMPLES);
    CHECK((count == 1) && (xs[0] == 5.0f) && (ys[0] == 7.0f), "where it landed: %d", count);
    count = th_path_samples(0.0f, 0.0f, 10000.0f, 0.0f, 1.0f, xs, ys, TH_PATH_SAMPLES);
    CHECK((count == TH_PATH_SAMPLES) && (xs[count - 1] == 10000.0f), "no more than the most: %d", count);
    count = th_path_samples(0.0f, 0.0f, 50.0f, 0.0f, 0.0f, xs, ys, TH_PATH_SAMPLES);
    CHECK((count == 1) && (xs[0] == 50.0f), "no step: the end alone, %d", count);
}

static void test_a_swipe_that_gets_something_drops(void)
{
    begin(0, 0);
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    th_stroke_moved(&h, now);
    run_ms(100);
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Hand, "a swipe");
    game_hand = 1;
    sees.thing = 1;
    sees.creature = CREATURE_A;
    run_ms(200);
    CHECK((drops == 1) && (slaps == 0), "what the panel gave falls: drops %d slaps %d", drops, slaps);
}

static void test_the_finger_lifts_and_it_all_ends(void)
{
    begin(5, 0);
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    run_ms(300);
    th_stroke_lift(&h, 1);
    const int before = drops;
    run_ms(500);
    CHECK(drops == before, "drops %d, was %d", drops, before);
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Today, "a phase of none is nobody's");
}

static void test_a_new_level_starts_the_turns_over(void)
{
    begin(0, 0);
    th_note_packet(&h, 5000, 0, 1, 0);
    game_turn = 50;
    th_stroke_begin(&h, now, 0.0f, 0.0f);
    th_stroke_moved(&h, now);
    now += 100;
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Hand, "a swipe");
    sees.thing = 1;
    sees.creature = CREATURE_A;
    CHECK(slap_with(&tuning), "not waiting for turn 5004");
    CHECK(h.flights_count == 0, "and nothing under way: %d", h.flights_count);
}

static void test_the_book_keeping(void)
{
    begin(0, 12);
    for (int t = 0; t < 100; t++) {
        th_note_packet(&h, 2000 + t, 12, 1, 1);
        CHECK(h.flights_count <= TH_MAX_FLIGHTS, "flights %d", h.flights_count);
    }
    CHECK(h.flights_count == 26, "the ages 0 to 12, both kinds: %d", h.flights_count);
    th_note_packet(&h, 2200, 12, 0, 0);
    CHECK(h.flights_count == 0, "all landed: %d", h.flights_count);
}

int touch_hand_tests(int *total_checks)
{
    test_a_tap_stays_a_tap();
    test_a_held_finger_drops_at_the_rate_asked();
    test_a_hold_time_past_the_long_press_time();
    test_a_rate_between_turns_keeps_to_it();
    test_no_slap_after_a_rain_at_any_lag();
    test_a_rain_goes_as_fast_as_the_lag_allows();
    test_the_lag_may_change_under_way();
    test_a_tap_on_the_floor_with_a_full_hand_drops_nothing();
    test_a_wall_stays_a_wall();
    test_out_of_force_stays_today();
    test_a_drag_drops_at_once();
    test_a_drag_from_a_place_with_no_drop_is_the_ordinary_drag();
    test_the_conveyor();
    test_a_finger_before_the_hold_time_is_still_a_tap_for_the_conveyor();
    test_a_long_press_on_a_creature_with_an_empty_hand();
    test_a_long_press_on_a_wall_selects_an_area();
    test_an_area_of_walls();
    test_a_hold_lifted_where_it_rested_applies_nothing();
    test_a_second_finger_cancels_an_area();
    test_the_key_waits_for_the_packet_with_the_release();
    test_a_cancel_no_packet_carries();
    test_the_buttons_let_go_of_elsewhere();
    test_moving_first_keeps_the_stroke_rules();
    test_an_area_needs_a_wall_now();
    test_out_of_force_no_area();
    test_a_hold_after_a_rain_is_no_slap();
    test_a_quick_stroke_with_an_empty_hand_slaps();
    test_the_slap_rate_keeps_a_creature_waiting();
    test_a_slow_stroke_does_nothing();
    test_a_swipe_counts_what_is_under_way();
    test_a_quick_stroke_reaching_a_wall();
    test_a_stroke_from_a_wall();
    test_a_crossing_from_a_wall_is_a_real_one();
    test_a_stroke_from_an_own_door();
    test_a_start_read_in_force_stays_the_hands();
    test_a_flick();
    test_where_it_landed();
    test_the_path_samples();
    test_a_swipe_that_gets_something_drops();
    test_the_finger_lifts_and_it_all_ends();
    test_a_new_level_starts_the_turns_over();
    test_the_book_keeping();
    *total_checks += checks;
    return failures;
}
