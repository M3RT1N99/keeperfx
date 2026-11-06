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

struct ThTuning {
    float hold_ms;       /**< How long a finger rests before the hand starts to drop. */
    float rain_ms;       /**< From one drop to the next. */
    float slap_ms;       /**< From one slap of a creature to its next. */
    float longpress_ms;  /**< The ordinary long press, which still is one where nothing can fall. */
    int whip_turns;      /**< A slap takes this long, and the game ignores another one meanwhile. */
    float turn_ms;       /**< How long a game turn is meant to take. */
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
    unsigned long creature;    /**< That is an own creature, and this one; 0 when it is not. */
    long long turn;            /**< The game turn. */
    int lag;                   /**< Input lag: what goes out now arrives this many turns later. */
};

/** What is to become of a finger that has not started anything yet. */
enum ThVerdict {
    ThV_Wait = 0,    /**< Too early to say. */
    ThV_Today,       /**< The rules have nothing to say here: the ordinary tap, drag and long press. */
    ThV_LongPress,   /**< The ordinary long press, now. */
    ThV_Hand,        /**< From here on the finger drops and slaps. */
};

/** What a finger that drops and slaps wants done at this moment. */
enum ThClick {
    ThC_None = 0,
    ThC_Drop,        /**< A right click: the first thing in the hand falls here. */
    ThC_Slap,        /**< A right click: the creature here gets a slap. */
    ThC_PressLeft,   /**< It reached a wall with nothing done yet: the ordinary drag, from here. */
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
    unsigned char read_start;    /**< What it landed on is known. */
    unsigned char open;          /**< It landed where the rules apply. */
    unsigned char moved;         /**< It has travelled further than a tap may. */
    unsigned char swipe;         /**< It was quick, with an empty hand: it slaps what it crosses. */
    unsigned char crossed;       /**< A swipe that has met a creature, so a wall does not turn it into a drag. */
    long long down_ms;
    long long moved_ms;
    double next_drop_turn;       /**< The packet the next drop is due in, with what a rate between turns carries over. */
    struct ThSlapped slapped[TH_SLAP_MEMORY];
};

struct ThHand {
    struct ThFlight flights[TH_MAX_FLIGHTS];
    int flights_count;
    long long last_right_turn;   /**< The turn of the last right release in a packet; negative before any. */
    long long last_packet_turn;  /**< The last turn whose packet has been made, so what a click made now goes out in. */
    struct ThStroke stroke;
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

/** How many of those are under way, as of this turn. */
int th_drops_under_way(struct ThHand *h, long long turn, int lag);
int th_picks_under_way(struct ThHand *h, long long turn, int lag);

/** A finger landed on the map as the first one. Anything else is never asked about. */
void th_stroke_begin(struct ThHand *h, long long ms);
/** That finger has travelled further than a tap may. Only the first time counts. */
void th_stroke_moved(struct ThHand *h, long long ms);
/** It lifted, or became part of something else. */
void th_stroke_end(struct ThHand *h);

/** Decides what a finger that has started nothing is going to be. Ask it again until it stops waiting. */
enum ThVerdict th_decide(struct ThHand *h, const struct ThSees *s, const struct ThTuning *t, long long now);

/**
 * What a finger that drops and slaps wants done now. It counts as done:
 * ask only when the game's pointer and buttons are free.
 */
enum ThClick th_click_due(struct ThHand *h, const struct ThSees *s, const struct ThTuning *t, long long now);

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
