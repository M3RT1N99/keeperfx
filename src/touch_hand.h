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
    float over_px;       /**< A path that comes this near a creature's place on the screen has gone over it. */
};

/** What the game says at this moment, about the hand and the place under the finger. */
struct ThSees {
    int in_force;              /**< The plain hand in the dungeon view, and the finger on no menu. */
    int known;                 /**< The rest of this is about the pixel under the finger. */
    int hand;                  /**< Things in the hand, as the simulation has it by now. */
    int droppable;             /**< The game would take a drop here. */
    int wall;                  /**< A left button pressed here marks for digging: the pick axe, or a hand over a wall it could dig. */
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
    ThV_Area,        /**< It rested on a wall it could mark: from here on it selects an area of them, see th_area_step(). */
};

/** What a lifted finger was. */
enum ThLift {
    ThL_Tap = 0,     /**< Nothing the rules have a say in: a finger still pointing is the ordinary tap, a left click where it lifted. */
    ThL_Flick,       /**< It travelled before anything was decided: no click, only the slap of what it crossed, if anything. */
    ThL_Hand,        /**< The hand had it, dropping, slapping or holding the right button: nothing is clicked where it lifted. */
    ThL_Area,        /**< It moved with an area: the left button going up where it lifted applies that, see th_area_step(). */
};

/** What the area of walls a resting finger selects asks of the buttons, each once. */
enum ThAreaStep {
    ThAS_None = 0,
    ThAS_PressLeft,  /**< The game has had the key: the left button goes down where the finger rests, and anchors the area there. */
    ThAS_PressRight, /**< A right click while the left button is down cancels an area, as it does on a computer. */
    ThAS_Release,    /**< Both buttons go up. After a move that applies the area, after a cancel or with the key nothing. */
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
    unsigned char open;          /**< It landed where the rules apply. */
    unsigned char moved;         /**< It has travelled further than a tap may. */
    unsigned char swipe;         /**< It was quick, with an empty hand: it slaps what it crosses. */
    unsigned char crossed;       /**< With an empty hand its path has gone over an own creature itself. */
    long long down_ms;
    long long moved_ms;
    float land_x, land_y;        /**< Where it landed. */
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
    unsigned char area;          /**< Where the area of walls is, see touch_hand.c. Kept past the lift, until the game has the release. */
    long long area_ms;           /**< When its right press went down, or its buttons up after a lift. */
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

/** A finger landed on the map at (x, y) as the first one. Anything else is never asked about. */
void th_stroke_begin(struct ThHand *h, long long ms, float x, float y);
/** That finger has travelled further than a tap may. Only the first time counts. */
void th_stroke_moved(struct ThHand *h, long long ms);
/** It became part of something else, or the touch went away: nothing it was going to do happens. */
void th_stroke_cancel(struct ThHand *h);
/**
 * It lifted. @param in_force The plain hand is what fingers on the map work with.
 * @return What it was: a flick, the hand's, or nothing the rules have a say in.
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
 * including (x1, y1), in that order and no further apart than step. A piece too long for that
 * gets max of them, spread evenly over all of it and further apart. @return How many.
 */
int th_path_samples(float x0, float y0, float x1, float y1, float step, float *xs, float *ys, int max);

/** The finger's path is to be looked at for creatures: it has started nothing yet, or it swipes. */
int th_stroke_looks_for_crossings(const struct ThHand *h);

/**
 * The finger's path came within a slap's reach of an own creature, to be aimed at (x, y), with the
 * finger at s->x, s->y by now. @param piece_px How near this piece of the path came to (x, y) on the
 *     screen, where the picture shows the creature itself there; anything past t->over_px where it
 *     does not. Only a path that went over the creature makes a stroke from a wall a swipe, since
 *     zoomed in the reach spans a hundred pixels, and landing beside one is not going over it.
 * @return true when that creature is to be slapped there: see th_slap_aim().
 */
int th_stroke_crossed(struct ThHand *h, const struct ThSees *s, const struct ThTuning *t,
    unsigned long creature, float x, float y, float piece_px, long long now);

/**
 * Where a slap waits to go out, once the pointer is free: a swipe's, or a flick's after the lift.
 * Nothing while it is still open whether the finger swipes.
 */
int th_slap_aim(const struct ThHand *h, float *x, float *y);

/**
 * The pointer has been at the aim for a drawn frame, and s is what the game says is under it.
 * @return true when the right click goes out there now, which only a creature to slap under the
 *     hand allows: anywhere else it slaps nothing the finger went over, and on the top of a wall the
 *     pick axe ignores it, or with TAG_MODE_TOGGLING on it switches the dig highlight mode. The aim
 *     is gone either way.
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

/**
 * The game key that makes a left button drag an area of walls is to be held: from when a finger
 * rested on a wall to the long press time until the game has had the release, so that the release
 * is read while the area is still on, and a key left held never turns later strokes into areas.
 */
int th_area_key(const struct ThHand *h);
/** The area has a button down, or one about to go down or up: nothing else is to let go of them. */
int th_area_owns_buttons(const struct ThHand *h);
/** The pointer stays where the finger rested until a packet has carried the press that anchors the area there. */
int th_area_anchoring(const struct ThHand *h);
/**
 * What the area asks of the buttons now, each step once. @param give_up_ms A last resort: a right
 * press or a key no packet has followed for this long, while the game is meant to make one every
 * turn, is given up on, the buttons and the key together.
 */
enum ThAreaStep th_area_step(struct ThHand *h, long long now, float give_up_ms);
/**
 * What the turn's packet carries for the area, once it is complete.
 * @param area_mode It makes a left button drag an area: it carries the key's action, or the
 *     game's own default does that by itself.
 * After a right press the first packet decides: one that has not carried it with the left button
 * held never will, and the buttons and the key go up together.
 */
void th_note_area_packet(struct ThHand *h, int area_mode, int left_held, int right_click);
/**
 * The buttons were let go of for another reason, a reset of the touch layer or another click: the key
 * goes with them, so that the release applies nothing, unless a lift had asked for it already.
 */
void th_area_buttons_released(struct ThHand *h);
/**
 * The rules went out of force under the area: a pause, a menu, the level's end. A right click would
 * close a menu, and a pause carries no click at all, so where the key is held, @p keyed, the buttons
 * and the key go up together; without the key, where the drag mode is the default, only the right
 * click cancels. A release a lift asked for goes out as it would have.
 */
void th_area_give_up(struct ThHand *h, int keyed);

/******************************************************************************/
#ifdef __cplusplus
}
#endif
#endif
