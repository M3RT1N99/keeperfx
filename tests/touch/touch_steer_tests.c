/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/** @file touch_steer_tests.c
 *     Tests of the rules of steering a possessed creature in src/touch_steer.c.
 * @par Comment:
 *     Runs without a device, an engine or SDL. What the fingers do is scripted and
 *     the only things looked at are what comes out: walking, looking and clicks.
 * @author   KeeperFX Team
 * @date     04 Nov 2026
 * @par  Copying and copyrights:
 *     This program is free software; you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation; either version 2 of the License, or
 *     (at your option) any later version.
 */
/******************************************************************************/
#include "touch_steer.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>

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

/* The phone this is tuned on. */
static const struct TsScreen phone = {1544.0f, 720.0f, 245.0f, 108.0f, 0};
static const struct TsScreen phone_flying = {1544.0f, 720.0f, 245.0f, 108.0f, 1};
static const struct TsScreen no_panel = {1544.0f, 720.0f, 0.0f, 108.0f, 0};

#define UNITS_PER_PX (0.2f * 2048.0f / 360.0f)
#define MAX_TURN 142

/* Where fingers land: the stick zone reaches to x = 829.55 on the phone. */
#define STICK_X 500.0f
#define STICK_Y 500.0f
#define LOOK_X 1100.0f
#define LOOK_Y 300.0f
#define PANEL_X 100.0f
#define PANEL_Y 500.0f

static struct TsSteer s;
static long long now;

static void begin(void)
{
    ts_reset(&s);
    now = 100000;
}

static void down(unsigned long long id, float x, float y)
{
    ts_finger_down(&s, id, x, y, now, &phone, 0);
}

static void move(unsigned long long id, float x, float y)
{
    ts_finger_move(&s, id, x, y, now, &phone);
}

static void up(unsigned long long id)
{
    ts_finger_up(&s, id, now);
}

static void cancel(unsigned long long id)
{
    ts_finger_cancel(&s, id);
}

/** Time passes the way the game sees it: a look at the clock now and then. */
static void wait_ms(int ms)
{
    for (int i = 0; i < ms; i += 10) {
        now += 10;
        ts_tick(&s, now);
    }
}

static struct TsActions taken(void)
{
    struct TsActions a;
    ts_take_actions(&s, &a);
    return a;
}

static int nothing_came_of_it(void)
{
    const struct TsActions a = taken();
    return !a.attacks && !a.right_click && !a.escape && !a.pointer_click;
}

static void look_taken(int *x, int *y)
{
    ts_take_look(&s, UNITS_PER_PX, MAX_TURN, x, y);
}

static int look_is_still(void)
{
    int x;
    int y;
    look_taken(&x, &y);
    return (x == 0) && (y == 0);
}

static void walking(int *forward, int *right)
{
    ts_walk(&s, phone.stick_radius, forward, right);
}

static int standing(void)
{
    int forward;
    int right;
    walking(&forward, &right);
    return (forward == 0) && (right == 0);
}

static enum TsRole role_of(float x, float y, const struct TsScreen *screen, int on_menu)
{
    struct TsSteer one;
    ts_reset(&one);
    ts_finger_down(&one, 1, x, y, 0, screen, on_menu);
    return one.fingers[0].role;
}

static void test_where_a_finger_lands_says_what_it_is(void)
{
    CHECK(role_of(244.0f, 300.0f, &phone, 0) == TsR_Pointer, "on the panel");
    CHECK(role_of(245.0f, 300.0f, &phone, 0) == TsR_Stick, "right beside the panel");
    CHECK(role_of(829.0f, 300.0f, &phone, 0) == TsR_Stick, "end of the zone");
    CHECK(role_of(830.0f, 300.0f, &phone, 0) == TsR_Look, "past the zone");
    CHECK(role_of(1000.0f, 300.0f, &phone, 1) == TsR_Pointer, "on a menu over the view");
    CHECK(role_of(100.0f, 300.0f, &no_panel, 0) == TsR_Stick, "no panel, the zone starts at the edge");
    CHECK(role_of(694.0f, 300.0f, &no_panel, 0) == TsR_Stick, "no panel, the zone is 45 percent of the whole width");
    CHECK(role_of(696.0f, 300.0f, &no_panel, 0) == TsR_Look, "no panel, past the zone");
}

static void test_the_fly_buttons_are_only_there_for_a_flyer(void)
{
    struct TsCircle up_btn;
    struct TsCircle down_btn;
    ts_fly_buttons(&phone_flying, &up_btn, &down_btn);
    CHECK((up_btn.y < 360.0f) && (down_btn.y > 360.0f), "the buttons sit around the centre: %f %f", up_btn.y, down_btn.y);
    CHECK(up_btn.x + up_btn.radius < 1544.0f, "the buttons fit on the screen: %f", up_btn.x + up_btn.radius);
    CHECK(up_btn.y - up_btn.radius > 232.0f, "the button stays clear of the toggle above it: %f", up_btn.y - up_btn.radius);
    CHECK(role_of(up_btn.x, up_btn.y, &phone_flying, 0) == TsR_Ascend, "upper button");
    CHECK(role_of(down_btn.x, down_btn.y, &phone_flying, 0) == TsR_Descend, "lower button");
    CHECK(role_of(up_btn.x, up_btn.y, &phone, 0) == TsR_Look, "no flyer, an ordinary look finger");
    CHECK(role_of(down_btn.x, down_btn.y, &phone, 0) == TsR_Look, "no flyer, an ordinary look finger");
    CHECK(role_of(up_btn.x - up_btn.radius - 20.0f, up_btn.y, &phone_flying, 0) == TsR_Look, "beside the button");

    begin();
    ts_finger_down(&s, 1, up_btn.x, up_btn.y, now, &phone_flying, 0);
    CHECK(ts_holding(&s, TsR_Ascend), "held");
    wait_ms(500);
    CHECK(ts_holding(&s, TsR_Ascend), "held for as long as it is held");
    ts_finger_down(&s, 2, STICK_X, STICK_Y, now, &phone_flying, 0);
    move(2, STICK_X, STICK_Y - 80.0f);
    int forward;
    int right;
    walking(&forward, &right);
    CHECK(forward == 1, "and it combines with the stick");
    up(1);
    CHECK(!ts_holding(&s, TsR_Ascend), "let go");
    CHECK(nothing_came_of_it(), "a held button clicks nothing");
    up(2);

    begin();
    ts_finger_down(&s, 1, down_btn.x, down_btn.y, now, &phone_flying, 0);
    now += 60;
    up(1);
    CHECK(!ts_holding(&s, TsR_Descend) && nothing_came_of_it(), "a tap on a button is not an attack");
    // A quick press beside a landing thumb must not read as a two finger tap.
    begin();
    ts_finger_down(&s, 1, down_btn.x, down_btn.y, now, &phone_flying, 0);
    ts_finger_down(&s, 2, STICK_X, STICK_Y, now, &phone_flying, 0);
    now += 60;
    up(1);
    up(2);
    CHECK(nothing_came_of_it(), "button and thumb are no two finger tap");
}

static void test_the_eight_directions(void)
{
    static const struct { float dx, dy; int forward, right; } cases[] = {
        {   0.0f, -80.0f,  1,  0 },
        {  80.0f, -80.0f,  1,  1 },
        {  80.0f,   0.0f,  0,  1 },
        {  80.0f,  80.0f, -1,  1 },
        {   0.0f,  80.0f, -1,  0 },
        { -80.0f,  80.0f, -1, -1 },
        { -80.0f,   0.0f,  0, -1 },
        { -80.0f, -80.0f,  1, -1 },
    };
    for (size_t i = 0; i < sizeof(cases) / sizeof(cases[0]); i++) {
        begin();
        down(1, STICK_X, STICK_Y);
        CHECK(standing(), "landing walks nowhere");
        move(1, STICK_X + cases[i].dx, STICK_Y + cases[i].dy);
        int forward;
        int right;
        walking(&forward, &right);
        CHECK((forward == cases[i].forward) && (right == cases[i].right), "case %d: %d %d", (int)i, forward, right);
        up(1);
        CHECK(standing(), "lifting stops it");
    }
}

static void test_the_threshold(void)
{
    const float at = 0.38f * phone.stick_radius;
    begin();
    down(1, STICK_X, STICK_Y);
    move(1, STICK_X + at - 1.0f, STICK_Y - at + 1.0f);
    CHECK(standing(), "just inside the threshold on both axes");
    move(1, STICK_X + at + 1.0f, STICK_Y - at + 1.0f);
    int forward;
    int right;
    walking(&forward, &right);
    CHECK((forward == 0) && (right == 1), "only the axis past it counts: %d %d", forward, right);
    move(1, STICK_X - at + 1.0f, STICK_Y - at - 1.0f);
    walking(&forward, &right);
    CHECK((forward == 1) && (right == 0), "only the axis past it counts: %d %d", forward, right);
    // Full speed however far past: a wobble inside the threshold does nothing.
    for (int i = 0; i < 50; i++) {
        move(1, STICK_X + ((i & 1) ? 30.0f : -30.0f), STICK_Y + ((i & 2) ? 30.0f : -30.0f));
        CHECK(standing(), "wobble %d", i);
    }
    up(1);
}

static void test_the_knob_stops_at_the_ring(void)
{
    struct TsCircle ring;
    struct TsCircle knob;
    begin();
    CHECK(!ts_stick_shape(&s, phone.stick_radius, &ring, &knob), "no thumb, no stick");
    down(1, STICK_X, STICK_Y);
    CHECK(ts_stick_shape(&s, phone.stick_radius, &ring, &knob), "a thumb, a stick");
    CHECK((ring.x == STICK_X) && (ring.y == STICK_Y) && (ring.radius == phone.stick_radius), "the ring is where it landed");
    CHECK((knob.x == STICK_X) && (knob.y == STICK_Y), "the knob starts in the middle");
    move(1, STICK_X + 30.0f, STICK_Y);
    ts_stick_shape(&s, phone.stick_radius, &ring, &knob);
    CHECK(knob.x == STICK_X + 30.0f, "the knob follows: %f", knob.x);
    move(1, STICK_X + 300.0f, STICK_Y + 400.0f);
    ts_stick_shape(&s, phone.stick_radius, &ring, &knob);
    CHECK(fabsf(hypotf(knob.x - STICK_X, knob.y - STICK_Y) - phone.stick_radius) < 0.01f, "the knob stops at the ring");
    CHECK((ring.x == STICK_X) && (ring.y == STICK_Y), "the ring does not follow");
    up(1);
    CHECK(!ts_stick_shape(&s, phone.stick_radius, &ring, &knob), "gone with the thumb");
}

static void test_only_the_first_finger_in_the_zone_is_the_stick(void)
{
    begin();
    down(1, STICK_X, STICK_Y);
    down(2, STICK_X + 100.0f, STICK_Y);
    wait_ms(300);
    CHECK(look_is_still(), "the landing of the second");
    move(2, STICK_X + 200.0f, STICK_Y);
    int x;
    int y;
    look_taken(&x, &y);
    CHECK(x > 100, "the further finger in the zone looks: %d", x);
    CHECK(standing(), "and does not walk");
    up(1);
    up(2);
    CHECK(nothing_came_of_it(), "neither was a tap");
    // Once the thumb is gone the next one is the stick again, whatever the look finger does.
    down(3, STICK_X, STICK_Y);
    down(4, STICK_X, STICK_Y - 200.0f);
    move(4, STICK_X, STICK_Y - 400.0f);
    CHECK(standing(), "the second finger is not the stick");
    move(3, STICK_X, STICK_Y - 80.0f);
    int forward;
    int right;
    walking(&forward, &right);
    CHECK(forward == 1, "the first still is");
}

static void test_a_landing_moves_nothing(void)
{
    begin();
    down(1, LOOK_X, LOOK_Y);
    CHECK(look_is_still(), "the look finger's landing");
    wait_ms(3000);
    CHECK(look_is_still(), "resting");
    CHECK(nothing_came_of_it(), "resting does not click");
    up(1);
    CHECK(look_is_still() && nothing_came_of_it(), "lifting after a rest");
}

static void test_a_tremble_is_nothing(void)
{
    begin();
    down(1, LOOK_X, LOOK_Y);
    wait_ms(300);
    for (int i = 0; i < 100; i++) {
        move(1, LOOK_X + ((i & 1) ? 3.0f : -3.0f), LOOK_Y + ((i & 2) ? 2.0f : -2.0f));
        now += 10;
        CHECK(look_is_still(), "tremble %d", i);
    }
    up(1);
    CHECK(look_is_still() && nothing_came_of_it(), "and it is no click either");
}

static void test_a_drag_loses_nothing(void)
{
    // All at once, slowly and in small events: the same travel gives the same look.
    for (int steps = 1; steps <= 300; steps *= 3) {
        begin();
        down(1, LOOK_X, LOOK_Y);
        long total = 0;
        for (int i = 1; i <= steps; i++) {
            now += 2000 / steps;
            move(1, LOOK_X - 300.0f * (float)i / (float)steps, LOOK_Y);
            int x;
            int y;
            look_taken(&x, &y);
            total += x;
        }
        up(1);
        for (int turn = 0; turn < 100; turn++) {
            int x;
            int y;
            look_taken(&x, &y);
            total += x;
        }
        const long expected = (long)(-300.0f * UNITS_PER_PX);
        CHECK(labs(total - expected) <= 1, "%d steps: %ld units against %ld", steps, total, expected);
        CHECK(nothing_came_of_it(), "%d steps: a drag does not click", steps);
    }
}

static void test_tiny_steps_add_up(void)
{
    begin();
    down(1, LOOK_X, LOOK_Y);
    wait_ms(300);
    long total = 0;
    float x = LOOK_X;
    // Far less than a unit between two looks at it, and still all of it arrives.
    for (int i = 0; i < 400; i++) {
        x += 0.25f;
        move(1, x, LOOK_Y);
        int tx;
        int ty;
        look_taken(&tx, &ty);
        total += tx;
    }
    const long expected = (long)(100.0f * UNITS_PER_PX);
    CHECK(labs(total - expected) <= 1, "%ld units against %ld", total, expected);
    up(1);
}

static void test_what_a_turn_cannot_take_stays_owed(void)
{
    begin();
    down(1, LOOK_X, LOOK_Y);
    move(1, LOOK_X - 900.0f, LOOK_Y + 900.0f);
    up(1);
    const long expected = (long)(900.0f * UNITS_PER_PX);
    long total_x = 0;
    long total_y = 0;
    int turns = 0;
    for (;;) {
        int x;
        int y;
        ts_take_look(&s, UNITS_PER_PX, 45, &x, &y);
        CHECK((abs(x) <= 45) && (abs(y) <= 45), "over the limit: %d %d", x, y);
        if ((x == 0) && (y == 0))
            break;
        total_x += x;
        total_y += y;
        turns++;
    }
    CHECK(labs(-total_x - expected) <= 1, "%ld against %ld", -total_x, expected);
    CHECK(labs(total_y - expected) <= 1, "%ld against %ld", total_y, expected);
    CHECK(turns == (int)(expected / 45) + 1, "%d turns", turns);
    ts_drop_look(&s);
    begin();
    down(1, LOOK_X, LOOK_Y);
    move(1, LOOK_X - 900.0f, LOOK_Y);
    ts_drop_look(&s);
    CHECK(look_is_still(), "dropped is gone");
    move(1, LOOK_X - 910.0f, LOOK_Y);
    int x;
    int y;
    look_taken(&x, &y);
    CHECK(x == (int)(-10.0f * UNITS_PER_PX), "and the finger goes on from where it is: %d", x);
}

static void test_a_tap_attacks_once_and_turns_nothing(void)
{
    begin();
    down(1, LOOK_X, LOOK_Y);
    now += 100;
    move(1, LOOK_X + 15.0f, LOOK_Y - 8.0f);
    CHECK(look_is_still(), "a tap's wobble is held back");
    now += 100;
    up(1);
    const struct TsActions a = taken();
    CHECK((a.attacks == 1) && !a.right_click && !a.escape && !a.pointer_click, "one attack: %d", a.attacks);
    CHECK(look_is_still(), "and the view stays where it was");
    CHECK(nothing_came_of_it(), "and that was it");
}

static void test_tap_or_drag_by_time(void)
{
    begin();
    down(1, LOOK_X, LOOK_Y);
    now += 239;
    up(1);
    CHECK(taken().attacks == 1, "just inside the tap time");
    begin();
    down(1, LOOK_X, LOOK_Y);
    now += 240;
    up(1);
    CHECK(nothing_came_of_it(), "the tap time is over, even with no tick in between");
    begin();
    down(1, LOOK_X, LOOK_Y);
    wait_ms(250);
    up(1);
    CHECK(nothing_came_of_it(), "the tap time is over");
}

static void test_tap_or_drag_by_travel(void)
{
    begin();
    down(1, LOOK_X, LOOK_Y);
    now += 50;
    move(1, LOOK_X + 19.0f, LOOK_Y);
    now += 50;
    up(1);
    CHECK(taken().attacks == 1, "just inside the slop");
    begin();
    down(1, LOOK_X, LOOK_Y);
    now += 50;
    move(1, LOOK_X + 20.0f, LOOK_Y);
    int x;
    int y;
    look_taken(&x, &y);
    CHECK(x == (int)(20.0f * UNITS_PER_PX), "past the slop all of it counts at once: %d", x);
    now += 50;
    up(1);
    CHECK(nothing_came_of_it(), "and it is no tap");
    // Out and back in time is a drag: how far it got counts, not where it ended.
    begin();
    down(1, LOOK_X, LOOK_Y);
    move(1, LOOK_X + 30.0f, LOOK_Y);
    move(1, LOOK_X, LOOK_Y);
    now += 50;
    up(1);
    CHECK(nothing_came_of_it(), "out and back is no tap");
    long total = 0;
    look_taken(&x, &y);
    total += x;
    CHECK(total == 0, "and the look went out and back: %ld", total);
}

static void test_a_slow_finger_starts_looking_without_a_jump(void)
{
    begin();
    down(1, LOOK_X, LOOK_Y);
    now += 300;
    move(1, LOOK_X + 5.0f, LOOK_Y);
    CHECK(look_is_still(), "under the slop of a resting finger");
    now += 100;
    move(1, LOOK_X + 7.0f, LOOK_Y);
    int x;
    int y;
    look_taken(&x, &y);
    CHECK(x == (int)(7.0f * UNITS_PER_PX), "from there on it counts, and from where it landed: %d", x);
    up(1);
}

static void test_a_tap_on_the_stick_does_nothing(void)
{
    begin();
    down(1, STICK_X, STICK_Y);
    now += 60;
    up(1);
    CHECK(nothing_came_of_it(), "no attack");
    CHECK(standing() && look_is_still(), "nothing else either");
}

static void test_a_tap_beside_a_resting_thumb_attacks(void)
{
    begin();
    down(1, STICK_X, STICK_Y);
    wait_ms(500);
    move(1, STICK_X, STICK_Y - 80.0f);
    down(2, LOOK_X, LOOK_Y);
    now += 60;
    up(2);
    CHECK(taken().attacks == 1, "the attack comes at once");
    int forward;
    int right;
    walking(&forward, &right);
    CHECK(forward == 1, "and the walk goes on");
    CHECK(look_is_still(), "the view stays");
    // The same with a thumb that only just landed and stays down: the attack cannot be
    // told from half a two finger tap until the thumb's tap time is over.
    begin();
    down(1, STICK_X, STICK_Y);
    now += 50;
    down(2, LOOK_X, LOOK_Y);
    now += 50;
    up(2);
    CHECK(nothing_came_of_it(), "not yet");
    wait_ms(200);
    CHECK(taken().attacks == 1, "once the thumb has stayed");
    up(1);
    CHECK(nothing_came_of_it(), "and lifting the thumb adds nothing");
}

static void test_a_drag_beside_a_thumb_looks_and_walks(void)
{
    begin();
    down(1, STICK_X, STICK_Y);
    wait_ms(300);
    move(1, STICK_X + 80.0f, STICK_Y);
    down(2, LOOK_X, LOOK_Y);
    int forward;
    int right;
    long total = 0;
    for (int i = 1; i <= 30; i++) {
        now += 16;
        move(2, LOOK_X - 10.0f * (float)i, LOOK_Y);
        walking(&forward, &right);
        CHECK(right == 1, "walks all along");
        int x;
        int y;
        look_taken(&x, &y);
        total += x;
    }
    const long expected = (long)(-300.0f * UNITS_PER_PX);
    CHECK(labs(total - expected) <= 1, "and the look is what it is without a thumb: %ld against %ld", total, expected);
    up(2);
    CHECK(nothing_came_of_it(), "and it is no click");
}

static void test_the_second_look_finger_does_nothing(void)
{
    begin();
    down(1, LOOK_X, LOOK_Y);
    wait_ms(300);
    down(2, LOOK_X + 100.0f, LOOK_Y + 100.0f);
    move(2, LOOK_X + 300.0f, LOOK_Y + 100.0f);
    CHECK(look_is_still(), "its travel turns nothing");
    up(2);
    CHECK(nothing_came_of_it(), "a drag of it clicks nothing");
    down(3, LOOK_X + 100.0f, LOOK_Y + 100.0f);
    now += 60;
    up(3);
    CHECK(nothing_came_of_it(), "nor does a tap of it");
    move(1, LOOK_X + 30.0f, LOOK_Y);
    int x;
    int y;
    look_taken(&x, &y);
    CHECK(x > 0, "the first one still looks");
    up(1);
    // With the first gone the next one down is the look finger again.
    down(4, LOOK_X, LOOK_Y);
    now += 60;
    up(4);
    CHECK(taken().attacks == 1, "the next one is the look finger");
}

/* Lift orders of a tap of n fingers: every permutation. */
static void lift_in_every_order(int n, int *order, int depth, void (*check)(const int *order))
{
    if (depth == n) {
        check(order);
        return;
    }
    for (int i = depth; i < n; i++) {
        int swap = order[depth];
        order[depth] = order[i];
        order[i] = swap;
        lift_in_every_order(n, order, depth + 1, check);
        swap = order[depth];
        order[depth] = order[i];
        order[i] = swap;
    }
}

static void two_finger_order(const int *order)
{
    static const float at[2][2] = {{STICK_X, STICK_Y}, {LOOK_X, LOOK_Y}};
    begin();
    down(1, at[0][0], at[0][1]);
    now += 100;
    down(2, at[1][0], at[1][1]);
    now += 40;
    up((unsigned long long)order[0]);
    CHECK(nothing_came_of_it(), "the first lift settles nothing");
    now += 40;
    up((unsigned long long)order[1]);
    const struct TsActions a = taken();
    CHECK(a.right_click && !a.attacks && !a.escape && !a.pointer_click, "lifted %d then %d: right %d attacks %d", order[0], order[1], a.right_click, a.attacks);
    CHECK(look_is_still(), "the view stays");
}

static void test_two_fresh_fingers_right_click(void)
{
    int order[2] = {1, 2};
    lift_in_every_order(2, order, 0, two_finger_order);
    // Both on the look side, one of them idle.
    begin();
    down(1, LOOK_X, LOOK_Y);
    down(2, LOOK_X + 100.0f, LOOK_Y);
    now += 80;
    up(2);
    up(1);
    const struct TsActions a = taken();
    CHECK(a.right_click && !a.attacks, "both on the right: right %d attacks %d", a.right_click, a.attacks);
    // Beside a resting thumb, which is not one of them.
    begin();
    down(1, STICK_X, STICK_Y);
    wait_ms(500);
    down(2, LOOK_X, LOOK_Y);
    down(3, LOOK_X + 100.0f, LOOK_Y);
    now += 80;
    up(2);
    up(3);
    const struct TsActions b = taken();
    CHECK(b.right_click && !b.attacks, "beside a thumb: right %d attacks %d", b.right_click, b.attacks);
}

static void test_fingers_that_are_not_both_fresh_make_no_right_click(void)
{
    // A landing after the other's tap time: the first is no tap any more.
    begin();
    down(1, LOOK_X, LOOK_Y);
    wait_ms(240);
    down(2, STICK_X, STICK_Y);
    now += 40;
    up(2);
    up(1);
    CHECK(nothing_came_of_it(), "resting look and a tap on the stick");
    // One moves: the other is a tap on its own.
    begin();
    down(1, LOOK_X, LOOK_Y);
    down(2, STICK_X, STICK_Y);
    now += 40;
    up(1);
    move(2, STICK_X, STICK_Y - 80.0f);
    const struct TsActions a = taken();
    CHECK((a.attacks == 1) && !a.right_click, "thumb pushes off, the tap beside it attacks");
    up(2);
    CHECK(nothing_came_of_it(), "and the thumb adds nothing");
    begin();
    down(1, LOOK_X, LOOK_Y);
    down(2, STICK_X, STICK_Y);
    now += 40;
    move(1, LOOK_X + 40.0f, LOOK_Y);
    up(2);
    up(1);
    CHECK(nothing_came_of_it(), "the look finger drags, the tap of the thumb does nothing");
    // Two taps one after the other, however fast, are two attacks.
    begin();
    down(1, LOOK_X, LOOK_Y);
    now += 40;
    up(1);
    now += 10;
    down(2, LOOK_X, LOOK_Y);
    now += 40;
    up(2);
    const struct TsActions b = taken();
    CHECK((b.attacks == 2) && !b.right_click, "%d attacks", b.attacks);
    // The first of two overlapping fingers lifts, the other outstays the tap time.
    begin();
    down(1, LOOK_X, LOOK_Y);
    now += 50;
    down(2, LOOK_X + 100.0f, LOOK_Y);
    now += 50;
    up(1);
    wait_ms(300);
    const struct TsActions c = taken();
    CHECK((c.attacks == 1) && !c.right_click, "the idle one stayed: %d attacks", c.attacks);
    up(2);
    CHECK(nothing_came_of_it(), "and adds nothing");
}

static void three_finger_order(const int *order)
{
    begin();
    down(1, STICK_X, STICK_Y);
    now += 30;
    down(2, LOOK_X, LOOK_Y);
    now += 30;
    down(3, LOOK_X + 100.0f, LOOK_Y + 100.0f);
    now += 30;
    up((unsigned long long)order[0]);
    up((unsigned long long)order[1]);
    CHECK(nothing_came_of_it(), "not yet");
    up((unsigned long long)order[2]);
    const struct TsActions a = taken();
    CHECK(a.escape && !a.right_click && !a.attacks && !a.pointer_click, "lifted %d %d %d: escape %d right %d attacks %d", order[0], order[1], order[2], a.escape, a.right_click, a.attacks);
}

static void test_three_fresh_fingers_are_escape(void)
{
    int order[3] = {1, 2, 3};
    lift_in_every_order(3, order, 0, three_finger_order);
    // One of three moved on: that is not a two finger tap, it is nothing at all.
    begin();
    down(1, LOOK_X, LOOK_Y);
    down(2, STICK_X, STICK_Y);
    down(3, LOOK_X + 100.0f, LOOK_Y);
    now += 50;
    move(2, STICK_X + 100.0f, STICK_Y);
    up(1);
    up(3);
    CHECK(nothing_came_of_it(), "sloppy three fingers");
    up(2);
    CHECK(nothing_came_of_it(), "still nothing");
}

static void test_the_panel_takes_a_press_and_never_looks(void)
{
    begin();
    down(1, PANEL_X, PANEL_Y);
    float x;
    float y;
    CHECK(ts_pointer_at(&s, &x, &y) && (x == PANEL_X) && (y == PANEL_Y), "the pointer goes to it");
    now += 80;
    move(1, PANEL_X + 3.0f, PANEL_Y);
    CHECK(ts_pointer_at(&s, &x, &y) && (x == PANEL_X + 3.0f), "and follows it");
    up(1);
    const struct TsActions a = taken();
    CHECK(a.pointer_click && !a.attacks && !a.right_click, "a click, no attack");
    CHECK((a.click_x == PANEL_X + 3.0f) && (a.click_y == PANEL_Y), "where it lifted: %f %f", a.click_x, a.click_y);
    CHECK(!ts_pointer_at(&s, &x, &y), "the pointer is free again");
    CHECK(look_is_still(), "the view stays");
    // Slower than a tap: the panel does not care.
    begin();
    down(1, PANEL_X, PANEL_Y);
    wait_ms(1000);
    CHECK(nothing_came_of_it(), "resting does nothing");
    up(1);
    CHECK(taken().pointer_click, "lifting clicks");
    // Dragged away: a change of mind.
    begin();
    down(1, PANEL_X, PANEL_Y);
    move(1, PANEL_X + 300.0f, PANEL_Y);
    CHECK(look_is_still(), "it does not turn into a look on leaving the panel");
    up(1);
    CHECK(nothing_came_of_it(), "and clicks nothing");
    // With a thumb on the stick, walking on.
    begin();
    down(1, STICK_X, STICK_Y);
    wait_ms(300);
    move(1, STICK_X, STICK_Y - 80.0f);
    down(2, PANEL_X, PANEL_Y);
    now += 80;
    up(2);
    CHECK(taken().pointer_click, "a click beside a thumb");
    int forward;
    int right;
    walking(&forward, &right);
    CHECK(forward == 1, "and the walk goes on");
    // A second finger on the panel has no pointer to take.
    begin();
    down(1, PANEL_X, PANEL_Y);
    wait_ms(300);
    down(2, PANEL_X + 50.0f, PANEL_Y);
    CHECK(ts_pointer_at(&s, &x, &y) && (x == PANEL_X), "the first keeps the pointer");
    now += 50;
    up(2);
    CHECK(nothing_came_of_it(), "and the second clicks nothing");
    up(1);
    CHECK(taken().pointer_click, "the first still clicks");
}

static void test_a_cancel_leaves_nothing(void)
{
    // A tap the system took away.
    begin();
    down(1, LOOK_X, LOOK_Y);
    now += 50;
    cancel(1);
    CHECK(nothing_came_of_it(), "no attack");
    // One of two fingers of a tap: neither is a tap any more.
    begin();
    down(1, LOOK_X, LOOK_Y);
    down(2, STICK_X, STICK_Y);
    now += 50;
    up(1);
    cancel(2);
    CHECK(nothing_came_of_it(), "the lifted one is no attack either");
    begin();
    down(1, LOOK_X, LOOK_Y);
    down(2, STICK_X, STICK_Y);
    now += 50;
    cancel(1);
    up(2);
    CHECK(nothing_came_of_it(), "nor the one lifted after the cancel");
    // A walking thumb stops.
    begin();
    down(1, STICK_X, STICK_Y);
    move(1, STICK_X, STICK_Y - 80.0f);
    CHECK(!standing(), "walking");
    cancel(1);
    CHECK(standing(), "stopped");
    // On the panel.
    begin();
    down(1, PANEL_X, PANEL_Y);
    wait_ms(300);
    cancel(1);
    float x;
    float y;
    CHECK(nothing_came_of_it() && !ts_pointer_at(&s, &x, &y), "no click, and the pointer is free");
    // A held fly button.
    begin();
    struct TsCircle up_btn;
    struct TsCircle down_btn;
    ts_fly_buttons(&phone_flying, &up_btn, &down_btn);
    ts_finger_down(&s, 1, up_btn.x, up_btn.y, now, &phone_flying, 0);
    cancel(1);
    CHECK(!ts_holding(&s, TsR_Ascend), "let go");
}

static void test_no_direction_gets_stuck(void)
{
    begin();
    down(1, STICK_X, STICK_Y);
    move(1, STICK_X + 90.0f, STICK_Y - 90.0f);
    CHECK(!standing(), "walking");
    ts_reset(&s);
    CHECK(standing(), "a reset stops it");
    // Fingers that were down at the reset are nobody: they move and lift unheard.
    move(1, STICK_X + 200.0f, STICK_Y);
    CHECK(standing() && look_is_still(), "its motion is not heard");
    up(1);
    CHECK(nothing_came_of_it(), "nor its lift");
    down(2, STICK_X, STICK_Y);
    CHECK(standing(), "and the next thumb starts from nothing");
    up(2);
    // An unknown finger lifting is no event at all.
    begin();
    up(99);
    cancel(99);
    move(99, 10.0f, 10.0f);
    CHECK(nothing_came_of_it() && standing() && look_is_still(), "nobody's finger");
}

static void test_more_fingers_than_slots_are_ignored(void)
{
    begin();
    for (unsigned long long id = 1; id <= TS_MAX_FINGERS + 3; id++)
        down(id, 900.0f + (float)id * 20.0f, 300.0f);
    now += 50;
    for (unsigned long long id = 1; id <= TS_MAX_FINGERS + 3; id++)
        up(id);
    const struct TsActions a = taken();
    CHECK(a.escape && !a.attacks && !a.right_click, "a tap of very many fingers is still Escape");
    CHECK(standing() && look_is_still(), "and leaves nothing");
    // And every slot is free again.
    down(1, STICK_X, STICK_Y);
    move(1, STICK_X, STICK_Y - 80.0f);
    CHECK(!standing(), "fingers still work afterwards");
}

int touch_steer_tests(int *total_checks)
{
    test_where_a_finger_lands_says_what_it_is();
    test_the_fly_buttons_are_only_there_for_a_flyer();
    test_the_eight_directions();
    test_the_threshold();
    test_the_knob_stops_at_the_ring();
    test_only_the_first_finger_in_the_zone_is_the_stick();
    test_a_landing_moves_nothing();
    test_a_tremble_is_nothing();
    test_a_drag_loses_nothing();
    test_tiny_steps_add_up();
    test_what_a_turn_cannot_take_stays_owed();
    test_a_tap_attacks_once_and_turns_nothing();
    test_tap_or_drag_by_time();
    test_tap_or_drag_by_travel();
    test_a_slow_finger_starts_looking_without_a_jump();
    test_a_tap_on_the_stick_does_nothing();
    test_a_tap_beside_a_resting_thumb_attacks();
    test_a_drag_beside_a_thumb_looks_and_walks();
    test_the_second_look_finger_does_nothing();
    test_two_fresh_fingers_right_click();
    test_fingers_that_are_not_both_fresh_make_no_right_click();
    test_three_fresh_fingers_are_escape();
    test_the_panel_takes_a_press_and_never_looks();
    test_a_cancel_leaves_nothing();
    test_no_direction_gets_stuck();
    test_more_fingers_than_slots_are_ignored();
    *total_checks += checks;
    return failures;
}
