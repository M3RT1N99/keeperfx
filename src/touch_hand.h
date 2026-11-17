/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/** @file touch_hand.h
 *     Header file for touch_hand.c.
 * @par Purpose:
 *     The rules of the keeper's hand under a finger. No SDL and no engine in here,
 *     so tests/touch can run all of it on its own.
 * @par Comment:
 *     Just a header file - #defines, typedefs, function prototypes etc.
 * @author   KeeperFX Team
 * @date     06 Nov 2026
 * @par  Copying and copyrights:
 *     This program is free software; you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation; either version 2 of the License, or
 *     (at your option) any later version.
 */
/******************************************************************************/
#ifndef TOUCH_HAND_H
#define TOUCH_HAND_H

#ifdef __cplusplus
extern "C" {
#endif
/******************************************************************************/

/** Two flights a turn at most, for the longest input lag there is: this does not fill up. */
#define TH_MAX_FLIGHTS 32
/** Creatures one stroke keeps apart by the slap rate. */
#define TH_SLAP_MEMORY 8
/** A stroke that has travelled by this time counts as quick, and with an empty hand it swipes. */
#define TH_SWIPE_MS 250
/** The most points of one piece of a finger's path that are looked at for creatures. */
#define TH_PATH_SAMPLES 64

struct ThTuning {
    float hold_ms;       /**< How long a finger rests before the hand starts to drop. */
    float rain_ms;       /**< From one drop to the next. */
    float slap_ms;       /**< From one slap of a creature to its next. */
    float longpress_ms;  /**< The ordinary long press, which still is one where nothing can fall. */
    int whip_turns;      /**< A slap takes this long, and the game ignores another one meanwhile. */
    float turn_ms;       /**< How long a game turn is meant to take. */
    float rest_px;       /**< Travel below this is a resting finger trembling, not a finger moving. */
};

/** What the game says at this moment, about the hand and the place under the finger. */
struct ThSees {
    int in_force;              /**< The plain hand in the dungeon view, and the finger on no menu. */
    int known;                 /**< The rest of this is about the pixel under the finger. */
    int hand;                  /**< Things in the hand, as the simulation has it by now. */
    int droppable;             /**< The game would take a drop here. */
    int wall;                  /**< A left button pressed here is a pick axe. */
    int door;                  /**< A left button pressed here locks or unlocks a door. */
    int thing;                 /**< There is something here that the hand can pick up or slap. */
    unsigned long creature;    /**< That is an own creature a right click here would slap, and this one; 0 when there is none. */
    long long turn;            /**< The game turn. */
    int lag;                   /**< Input lag: what goes out now arrives this many turns later. */
    float x, y;                /**< Where the finger is. */
};

/** What is to become of a finger that has not started anything yet. */
enum ThVerdict {
    ThV_Wait = 0,    /**< Too early to say. */
    ThV_Today,       /**< The rules have nothing to say here: the ordinary tap, drag and long press. */
    ThV_LongPress,   /**< The ordinary long press, now. */
    ThV_Hand,        /**< From here on the finger drops, and a swipe slaps; it never presses a button and holds it. */
};

/** What a finger lifted before anything was decided about it was. */
enum ThLift {
    ThL_Tap = 0,     /**< The ordinary tap, a left click where it lifted. */
    ThL_Flick,       /**< It travelled: no click, only the slap of what it crossed, if anything. */
};

/** One right click or pick up on its way through the input lag. */
struct ThFlight {
    long long turn;
    unsigned char is_pick;
};

/** A slap that keeps this creature waiting. */
struct ThSlapped {
    unsigned long creature;
    long long ms;
};

/** The finger on the map. */
struct ThStroke {
    unsigned char phase;
    unsigned char looked;        /**< A frame has been drawn with the pointer where it landed. */
    unsigned char read_start;    /**< What it landed on is known. */
    unsigned char start_empty;   /**< It landed with nothing that will be in the hand. */
    unsigned char start_wall;    /**< It landed where a left button presses the pick axe. */
    unsigned char start_door;    /**< It landed on an own door. */
    unsigned char open;          /**< It landed where the rules apply. */
    unsigned char moved;         /**< It has travelled further than a tap may. */
    unsigned char swipe;         /**< It was quick, with an empty hand: it slaps what it crosses. */
    unsigned char crossed;       /**< With an empty hand its path has gone over an own creature. */
    long long down_ms;
    long long moved_ms;
    double next_drop_turn;       /**< The packet the next drop is due in, with what a rate between turns carries over. */
    unsigned char slapped_once;  /**< It has slapped, so the next slap asks for travel from where that was. */
    float slap_x, slap_y;
    struct ThSlapped slapped[TH_SLAP_MEMORY];
};

/** A creature a finger went over, waiting for its slap, and where on the screen it was found. */
struct ThAim {
    unsigned long creature;      /**< 0 when nothing waits. */
    float x, y;
};

struct ThHand {
    struct ThFlight flights[TH_MAX_FLIGHTS];
    int flights_count;
    long long last_right_turn;   /**< The turn of the last right release in a packet; negative before any. */
    long long last_packet_turn;  /**< The last turn whose packet has been made, so what a click made now goes out in. */
    struct ThStroke stroke;
    struct ThAim aim;            /**< Kept past the lift: a flick's slap goes out once the finger is gone. */
};

void th_reset(struct ThHand *h);

/**
 * What the turn's packet carries, once it is complete. Every click counts, whoever
 * made it: the count stands for what the game will have done by the time a click
 * made now arrives.
 * @param right_released It holds a right button release: a drop, or a slap with an empty hand.
 * @param picking It holds a pick up, or a left button release that may be one.
 */
void th_note_packet(struct ThHand *h, long long turn, int lag, int right_released, int picking);

/** A finger landed on the map as the first one. Anything else is never asked about. */
void th_stroke_begin(struct ThHand *h, long long ms);
/** That finger has travelled further than a tap may. Only the first time counts. */
void th_stroke_moved(struct ThHand *h, long long ms);
/** It became part of something else, or the touch went away: nothing it was going to do happens. */
void th_stroke_cancel(struct ThHand *h);
/**
 * It lifted. @param in_force The plain hand is what fingers on the map work with.
 * @return What it was, for a finger that had not started anything yet; ThL_Tap for any other.
 */
enum ThLift th_stroke_lift(struct ThHand *h, int in_force);

/**
 * A frame has been drawn with the pointer where the finger landed, and this is what the game says
 * is there. Until then the pointer is to stay there, so that this is about where it landed and not
 * about where a quick finger has got to: see th_stroke_start_unseen().
 */
void th_stroke_look(struct ThHand *h, const struct ThSees *s);
/** The finger has started nothing, and no frame has shown the pointer where it landed yet. */
int th_stroke_start_unseen(const struct ThHand *h);

/** Decides what a finger that has started nothing is going to be. Ask it again until it stops waiting. */
enum ThVerdict th_decide(struct ThHand *h, const struct ThSees *s, const struct ThTuning *t, long long now);

/**
 * Whether a finger that drops wants a drop now, at the pointer. It counts as done:
 * ask only when the game's pointer and buttons are free.
 */
int th_drop_due(struct ThHand *h, const struct ThSees *s, const struct ThTuning *t);

/**
 * The points a finger's path is looked at for creatures, from just past (x0, y0) up to and
 * including (x1, y1), in that order and no further apart than step. Only the first max of them
 * when the piece is longer than that. @return How many.
 */
int th_path_samples(float x0, float y0, float x1, float y1, float step, float *xs, float *ys, int max);

/** The finger's path is to be looked at for creatures: it has started nothing yet, or it swipes. */
int th_stroke_looks_for_crossings(const struct ThHand *h);

/**
 * The finger's path went over an own creature that a slap can reach from (x, y), with the finger
 * at s->x, s->y by now. @return true when that creature is to be slapped there: see th_slap_aim().
 */
int th_stroke_crossed(struct ThHand *h, const struct ThSees *s, const struct ThTuning *t,
    unsigned long creature, float x, float y, long long now);

/**
 * Where a slap waits to go out, once the pointer is free: a swipe's, or a flick's after the lift.
 * Nothing while it is still open whether the finger swipes.
 */
int th_slap_aim(const struct ThHand *h, float *x, float *y);

/**
 * The pointer has been at the aim for a drawn frame, and s is what the game says is under it.
 * @return true when the right click goes out there now, which only a creature to slap under the
 *     hand allows: a right click also takes a dig mark off a wall. The aim is gone either way.
 */
int th_slap_now(struct ThHand *h, const struct ThSees *s, const struct ThTuning *t);

/**
 * A long press on something with the hand empty holds the right button until the
 * lift. When the hand gets something meanwhile, that button has to give way or nothing
 * could fall. @return true when the button is to be released now, which is the first drop.
 */
int th_long_press_gives_way(struct ThHand *h, const struct ThSees *s, const struct ThTuning *t);

/** The button was released for another reason: from here on the finger drops, if it can. */
void th_long_press_ended(struct ThHand *h, const struct ThTuning *t);

/******************************************************************************/
#ifdef __cplusplus
}
#endif
#endif
