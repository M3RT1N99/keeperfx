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

static const struct ThTuning tuning = {250.0f, 100.0f, 150.0f, 420.0f, 4, 50.0f};

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
    right_click_waiting = pick_waiting = pick_fails = 0;
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
 * Runs for a while with a look every 16 ms, the way the polls come: at most one click
 * read per turn, because the wiring only asks when the button is free.
 */
static void run_ms(int ms)
{
    for (int t = 0; t < ms; t += 16) {
        now += 16;
        refresh();
        if (th_decide(&h, &sees, &tuning, now) == ThV_Hand) {
            if (!right_click_waiting) {
                const enum ThClick click = th_click_due(&h, &sees, &tuning, now);
                if ((click == ThC_Drop) || (click == ThC_Slap))
                    right_click_waiting = 1;
            }
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
        th_stroke_begin(&h, now);
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
    th_stroke_begin(&h, now);
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

static void test_a_rate_between_turns_keeps_to_it(void)
{
    const struct ThTuning between = {250.0f, 75.0f, 150.0f, 420.0f, 4, 50.0f};
    begin(0, 0);
    game_hand = 40;
    th_stroke_begin(&h, now);
    for (int t = 0; t < 2000; t += 16) {
        now += 16;
        refresh();
        if ((th_decide(&h, &sees, &between, now) == ThV_Hand) && !right_click_waiting
         && (th_click_due(&h, &sees, &between, now) == ThC_Drop))
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
    const struct ThTuning fastest = {250.0f, 50.0f, 150.0f, 420.0f, 4, 50.0f};
    begin(0, 0);
    game_hand = 40;
    th_stroke_begin(&h, now);
    for (int t = 0; t < 1000; t += 16) {
        now += 16;
        refresh();
        if ((th_decide(&h, &sees, &fastest, now) == ThV_Hand) && !right_click_waiting
         && (th_click_due(&h, &sees, &fastest, now) == ThC_Drop))
            right_click_waiting = 1;
        if ((now % TURN_MS) < 16)
            end_of_turn();
    }
    for (int i = 1; i < clicks; i++)
        CHECK((click_turns[i] - click_turns[i - 1]) == 1, "every turn, %lld", click_turns[i] - click_turns[i - 1]);
    // A click made after the turn's packet goes out in the next one, and the rate counts from there.
    begin(5, 0);
    th_stroke_begin(&h, now);
    now += 300;
    refresh();
    th_note_packet(&h, game_turn, 0, 0, 0);
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Hand, "armed");
    CHECK(th_click_due(&h, &sees, &tuning, now) == ThC_Drop, "the first, for the next packet");
    game_turn += 1;
    refresh();
    th_note_packet(&h, game_turn, 0, 1, 0);
    CHECK(th_click_due(&h, &sees, &tuning, now) == ThC_None, "the next packet would be one turn on");
    game_turn += 1;
    refresh();
    th_note_packet(&h, game_turn, 0, 0, 0);
    CHECK(th_click_due(&h, &sees, &tuning, now) == ThC_Drop, "after this turn's packet: two turns on");
}

static void test_no_slap_after_a_rain_at_any_lag(void)
{
    for (int lag = 0; lag <= 12; lag++) {
        begin(8, lag);
        th_stroke_begin(&h, now);
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
    th_stroke_begin(&h, now);
    run_ms(250 + 8 * 100 + 12 * TURN_MS + 150);
    CHECK(drops == 8, "lag 12: %d drops in the time of 8 at the rate, and the lag once", drops);
    CHECK(slaps == 0, "slaps %d", slaps);
}

static void test_the_lag_may_change_under_way(void)
{
    begin(8, 3);
    th_stroke_begin(&h, now);
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
    th_stroke_begin(&h, now);
    run_ms(100);
    th_stroke_end(&h);
    run_ms(500);
    CHECK(drops == 0, "drops %d", drops);
}

static void test_a_wall_stays_a_wall(void)
{
    // With something in the hand: nothing can drop, so the ordinary gestures have it.
    begin(5, 0);
    sees.droppable = 0;
    sees.wall = 1;
    th_stroke_begin(&h, now);
    refresh();
    now += 16;
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Today, "hand: the ordinary tap, drag and long press");
    run_ms(1000);
    CHECK(drops == 0, "hand: drops %d", drops);
    // Empty.
    begin(0, 0);
    sees.droppable = 0;
    sees.wall = 1;
    th_stroke_begin(&h, now);
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
    th_stroke_begin(&h, now);
    run_ms(1000);
    CHECK(drops == 0, "drops %d", drops);
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Today, "today");
    CHECK(th_click_due(&h, &sees, &tuning, now) == ThC_None, "no click");
    // A rain that goes over the panel holds off and goes on behind it.
    begin(5, 0);
    th_stroke_begin(&h, now);
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
    th_stroke_begin(&h, now);
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
    th_stroke_begin(&h, now);
    run_ms(100);
    th_stroke_moved(&h, now);
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Today, "today");
}

static void test_the_conveyor(void)
{
    // A finger held with nothing in the hand waits. What comes falls at the next tick.
    begin(0, 0);
    th_stroke_begin(&h, now);
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
    th_stroke_begin(&h, now);
    run_ms(600);
    pick_waiting = 1;
    pick_fails = 1;
    run_ms(1000);
    CHECK((slaps == 0) && (drops == 0), "a pick that came to nothing: slaps %d drops %d", slaps, drops);
    // Under lag a pick only counts once it has landed, and then it falls.
    begin(0, 12);
    th_stroke_begin(&h, now);
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
    th_stroke_begin(&h, now);
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
    th_stroke_begin(&h, now);
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
    CHECK(th_click_due(&h, &sees, &tuning, now) == ThC_None, "the release was the first drop");
    game_turn += 2;
    refresh();
    CHECK(th_click_due(&h, &sees, &tuning, now) == ThC_Drop, "then at the rate");
}

static void test_a_long_press_on_a_wall_stays(void)
{
    begin(0, 0);
    sees.wall = 1;
    th_stroke_begin(&h, now);
    run_ms(16);
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Today, "today, and the wiring times the long press");
    begin(0, 0);
    sees.door = 1;
    th_stroke_begin(&h, now);
    run_ms(16);
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Today, "a door too");
}

static void test_a_hold_after_a_rain_is_no_slap(void)
{
    // The last one dropped is right under the finger.
    begin(2, 0);
    sees.thing = 1;
    sees.creature = CREATURE_A;
    th_stroke_begin(&h, now);
    run_ms(2000);
    CHECK(drops == 2, "drops %d", drops);
    CHECK(slaps == 0, "slaps %d", slaps);
}

static void test_a_quick_stroke_with_an_empty_hand_slaps(void)
{
    begin(0, 0);
    th_stroke_begin(&h, now);
    run_ms(64);
    th_stroke_moved(&h, now);
    run_ms(100);
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Hand, "a swipe");
    CHECK(slaps == 0, "bare floor: %d", slaps);
    sees.thing = 1;
    sees.creature = CREATURE_A;
    // Rubbing goes on for as long as the finger stays, at the game's pace.
    run_ms(2000);
    CHECK((slaps >= 8) && (slaps <= 11), "one per whip of 4 turns, %d in 2 s", slaps);
    CHECK(drops == 0, "drops %d", drops);
}

static void test_the_slap_rate_keeps_a_creature_waiting(void)
{
    const struct ThTuning slow = {250.0f, 100.0f, 500.0f, 420.0f, 4, 50.0f};
    begin(0, 0);
    th_stroke_begin(&h, now);
    th_stroke_moved(&h, now);
    now += 100;
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Hand, "a swipe");
    sees.thing = 1;
    sees.creature = CREATURE_A;
    CHECK(th_click_due(&h, &sees, &slow, now) == ThC_Slap, "the first");
    th_note_packet(&h, sees.turn, sees.lag, 1, 0);
    game_turn += 6;
    now += 300;
    refresh();
    CHECK(th_click_due(&h, &sees, &slow, now) == ThC_None, "under the rate");
    // Another one does not wait for this one's rate, only for the whip.
    sees.creature = CREATURE_B;
    CHECK(th_click_due(&h, &sees, &slow, now) == ThC_Slap, "the next creature");
    th_note_packet(&h, sees.turn, sees.lag, 1, 0);
    now += 16;
    sees.creature = CREATURE_A;
    game_turn += 1;
    now += 200;
    refresh();
    CHECK(th_click_due(&h, &sees, &slow, now) == ThC_None, "the whip is still on its way");
    game_turn += 3;
    refresh();
    CHECK(th_click_due(&h, &sees, &slow, now) == ThC_Slap, "and over");
}

static void test_a_slow_stroke_stays_what_it_is(void)
{
    begin(0, 0);
    sees.thing = 1;
    sees.creature = CREATURE_A;
    th_stroke_begin(&h, now);
    run_ms(300);
    th_stroke_moved(&h, now);
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Today, "the ordinary drag");
    CHECK(slaps == 0, "slaps %d", slaps);
}

static void test_a_swipe_counts_what_is_under_way(void)
{
    // A pick up on its way: the right click would drop what it picks.
    begin(0, 8);
    th_stroke_begin(&h, now);
    th_stroke_moved(&h, now);
    now += 100;
    refresh();
    th_note_packet(&h, sees.turn, sees.lag, 0, 1);
    sees.thing = 1;
    sees.creature = CREATURE_A;
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Hand, "it goes on as a finger that drops");
    CHECK(th_click_due(&h, &sees, &tuning, now) == ThC_None, "and sends no slap");
    // A swipe already going when the other hand's click goes out: the same.
    begin(0, 8);
    th_stroke_begin(&h, now);
    th_stroke_moved(&h, now);
    now += 100;
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Hand, "a swipe");
    th_note_packet(&h, sees.turn, sees.lag, 0, 1);
    sees.thing = 1;
    sees.creature = CREATURE_A;
    CHECK(th_click_due(&h, &sees, &tuning, now) == ThC_None, "no slap with a pick up on its way");
    game_turn += 9;
    refresh();
    CHECK(th_click_due(&h, &sees, &tuning, now) == ThC_Slap, "once that has landed or come to nothing");
    // The hand reads one and that one is on its way out: empty by the time this arrives.
    begin(1, 8);
    th_stroke_begin(&h, now);
    th_stroke_moved(&h, now);
    now += 100;
    refresh();
    th_note_packet(&h, sees.turn, sees.lag, 1, 0);
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Hand, "a swipe");
    sees.thing = 1;
    sees.creature = CREATURE_A;
    CHECK(th_click_due(&h, &sees, &tuning, now) == ThC_None, "under the whip's turns, as a right click just went");
    game_turn += 4;
    refresh();
    CHECK(th_click_due(&h, &sees, &tuning, now) == ThC_Slap, "it slaps");
}

static void test_a_quick_stroke_reaching_a_wall(void)
{
    // Nothing crossed yet: from the wall on it is the ordinary drag, so tagging is not lost.
    begin(0, 0);
    th_stroke_begin(&h, now);
    th_stroke_moved(&h, now + 30);
    now += 100;
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Hand, "a swipe");
    sees.wall = 1;
    now += 16;
    CHECK(th_click_due(&h, &sees, &tuning, now) == ThC_PressLeft, "the left button");
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Today, "and today's drag from here");
    // A creature crossed: it stays a swipe.
    begin(0, 0);
    th_stroke_begin(&h, now);
    th_stroke_moved(&h, now + 30);
    now += 100;
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Hand, "a swipe");
    sees.thing = 1;
    sees.creature = CREATURE_A;
    CHECK(th_click_due(&h, &sees, &tuning, now) == ThC_Slap, "a slap");
    sees.thing = 0;
    sees.creature = 0;
    sees.wall = 1;
    now += 16;
    CHECK(th_click_due(&h, &sees, &tuning, now) == ThC_None, "no left button on the wall after it");
    // A door on the way is no wall: the press would lock it.
    begin(0, 0);
    th_stroke_begin(&h, now);
    th_stroke_moved(&h, now + 30);
    now += 100;
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Hand, "a swipe");
    sees.door = 1;
    now += 16;
    CHECK(th_click_due(&h, &sees, &tuning, now) == ThC_None, "over a door");
}

static void test_a_swipe_that_gets_something_drops(void)
{
    begin(0, 0);
    th_stroke_begin(&h, now);
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
    th_stroke_begin(&h, now);
    run_ms(300);
    th_stroke_end(&h);
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
    th_stroke_begin(&h, now);
    th_stroke_moved(&h, now);
    now += 100;
    refresh();
    CHECK(th_decide(&h, &sees, &tuning, now) == ThV_Hand, "a swipe");
    sees.thing = 1;
    sees.creature = CREATURE_A;
    CHECK(th_click_due(&h, &sees, &tuning, now) == ThC_Slap, "not waiting for turn 5004");
    CHECK(th_drops_under_way(&h, 50, 0) == 0, "and nothing under way");
}

static void test_the_book_keeping(void)
{
    begin(0, 12);
    for (int t = 0; t < 100; t++) {
        th_note_packet(&h, 2000 + t, 12, 1, 1);
        CHECK(h.flights_count <= TH_MAX_FLIGHTS, "flights %d", h.flights_count);
    }
    CHECK(th_drops_under_way(&h, 2099, 12) == 13, "the ages 0 to 12: %d", th_drops_under_way(&h, 2099, 12));
    CHECK(th_picks_under_way(&h, 2099, 12) == 13, "%d", th_picks_under_way(&h, 2099, 12));
    CHECK(th_drops_under_way(&h, 2200, 12) == 0, "all landed: %d", th_drops_under_way(&h, 2200, 12));
}

int touch_hand_tests(int *total_checks)
{
    test_a_tap_stays_a_tap();
    test_a_held_finger_drops_at_the_rate_asked();
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
    test_a_long_press_on_a_wall_stays();
    test_a_hold_after_a_rain_is_no_slap();
    test_a_quick_stroke_with_an_empty_hand_slaps();
    test_the_slap_rate_keeps_a_creature_waiting();
    test_a_slow_stroke_stays_what_it_is();
    test_a_swipe_counts_what_is_under_way();
    test_a_quick_stroke_reaching_a_wall();
    test_a_swipe_that_gets_something_drops();
    test_the_finger_lifts_and_it_all_ends();
    test_a_new_level_starts_the_turns_over();
    test_the_book_keeping();
    *total_checks += checks;
    return failures;
}
