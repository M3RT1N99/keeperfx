/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/**
 * @file TouchTuningActivity.java
 *     Sliders for the touch gesture constants and the frame rate limit.
 * @par Purpose:
 *     The engine exposes its gesture constants through the -touchtune startup
 *     option so they could be settled on real hardware without a rebuild.
 *     Typing option strings into the extra arguments field is no way to tune a
 *     feel, though: this screen puts a slider on each knob, stores the values,
 *     and Prefs.buildArguments() turns everything moved off its default into
 *     the -touchtune option the engine already understands.
 * @par Comment:
 *     Built in code rather than XML: the rows come from the same table Prefs
 *     uses to compose the option, so a knob added there appears here by
 *     itself.
 * @author   KeeperFX Team
 * @date     08 Aug 2026
 * @par  Copying and copyrights:
 *     This program is free software; you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation; either version 2 of the License, or
 *     (at your option) any later version.
 */
/******************************************************************************/
package net.keeperfx.android;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

public class TouchTuningActivity extends Activity {

    /** Choices for the frame rate limit; 0 is the engine's own unlimited. */
    private static final int[] FPS_CHOICES = {30, 40, 60, 90, 120, 0};

    private Prefs prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);

        final float density = getResources().getDisplayMetrics().density;
        final int pad = Math.round(18 * density);

        final ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(getColor(R.color.kfx_background));
        scroll.setFillViewport(true);

        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(pad, pad, pad, pad);
        scroll.addView(list, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView title = new TextView(this);
        title.setText(R.string.tuning_title);
        title.setTextColor(getColor(R.color.kfx_accent));
        title.setTextSize(26);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        list.addView(title);

        final TextView intro = new TextView(this);
        intro.setText(R.string.tuning_intro);
        intro.setTextColor(getColor(R.color.kfx_text_dim));
        intro.setTextSize(12);
        list.addView(intro, marginTop(list, 8));

        addFpsRow(list);
        for (Prefs.TouchTunable tunable : Prefs.TOUCH_TUNABLES) {
            addTunableRow(list, tunable);
        }

        final Button reset = new Button(this);
        reset.setText(R.string.tuning_reset);
        reset.setBackgroundResource(R.drawable.btn_slate);
        reset.setTextColor(getColor(R.color.btn_text));
        reset.setOnClickListener(v -> {
            prefs.resetTouchTuning();
            prefs.setDrawFps(60);
            // Rebuilds every row from the freshly cleared values.
            recreate();
        });
        final LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.round(48 * density));
        resetParams.topMargin = Math.round(18 * density);
        list.addView(reset, resetParams);

        setContentView(scroll);
    }

    private LinearLayout.LayoutParams marginTop(LinearLayout parent, int dp) {
        final float density = getResources().getDisplayMetrics().density;
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = Math.round(dp * density);
        return params;
    }

    /** A panel with a heading, a live value, a control and a hint. */
    private LinearLayout addPanel(LinearLayout list) {
        final float density = getResources().getDisplayMetrics().density;
        final LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.panel);
        final int inner = Math.round(10 * density);
        panel.setPadding(inner, inner, inner, inner);
        list.addView(panel, marginTop(list, 10));
        return panel;
    }

    private TextView addLabel(LinearLayout panel) {
        final TextView label = new TextView(this);
        label.setTextColor(getColor(R.color.kfx_text));
        label.setTextSize(15);
        panel.addView(label);
        return label;
    }

    private void addHint(LinearLayout panel, int stringRes) {
        final TextView hint = new TextView(this);
        hint.setText(stringRes);
        hint.setTextColor(getColor(R.color.kfx_text_dim));
        hint.setTextSize(12);
        panel.addView(hint, marginTop(panel, 4));
    }

    private void addFpsRow(LinearLayout list) {
        final LinearLayout panel = addPanel(list);
        final TextView label = addLabel(panel);
        label.setText(R.string.tuning_fps);

        final String[] names = new String[FPS_CHOICES.length];
        int selected = 2; // 60, the default
        for (int i = 0; i < FPS_CHOICES.length; i++) {
            names[i] = (FPS_CHOICES[i] == 0)
                ? getString(R.string.tuning_fps_unlimited)
                : String.valueOf(FPS_CHOICES[i]);
            if (FPS_CHOICES[i] == prefs.getDrawFps()) {
                selected = i;
            }
        }
        final Spinner spinner = new Spinner(this);
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selected);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                prefs.setDrawFps(FPS_CHOICES[position]);
            }

            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        panel.addView(spinner, marginTop(panel, 4));
        addHint(panel, R.string.tuning_fps_hint);
    }

    private void addTunableRow(LinearLayout list, Prefs.TouchTunable tunable) {
        final LinearLayout panel = addPanel(list);
        final TextView label = addLabel(panel);

        final SeekBar seek = new SeekBar(this);
        final int steps = Math.round((tunable.max - tunable.min) / tunable.step);
        seek.setMax(steps);
        seek.setProgress(progressFor(tunable, prefs.getTouchTune(tunable)));
        updateTunableLabel(label, tunable, prefs.getTouchTune(tunable));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                final float value = tunable.min + progress * tunable.step;
                updateTunableLabel(label, tunable, value);
                if (fromUser) {
                    prefs.setTouchTune(tunable, value);
                }
            }

            @Override public void onStartTrackingTouch(SeekBar bar) { }

            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        panel.addView(seek, marginTop(panel, 4));
        addHint(panel, hintFor(tunable));
    }

    private int progressFor(Prefs.TouchTunable tunable, float value) {
        final int progress = Math.round((value - tunable.min) / tunable.step);
        return Math.max(0, Math.min(progress, Math.round((tunable.max - tunable.min) / tunable.step)));
    }

    private void updateTunableLabel(TextView label, Prefs.TouchTunable tunable, float value) {
        final String shown = Prefs.formatTouchTune(tunable, value);
        final boolean isDefault = Math.abs(value - tunable.def) < tunable.step / 2.0f;
        final String text = getString(labelFor(tunable)) + " — "
            + (isDefault ? getString(R.string.tuning_value_default, shown) : shown);
        label.setText(text);
    }

    private int labelFor(Prefs.TouchTunable tunable) {
        switch (tunable.key) {
            case "panspeed":    return R.string.tuning_panspeed;
            case "longpress":   return R.string.tuning_longpress;
            case "dragslop":    return R.string.tuning_dragslop;
            case "commitpan":   return R.string.tuning_commitpan;
            case "commitpinch": return R.string.tuning_commitpinch;
            case "committwist": return R.string.tuning_committwist;
            default:            return R.string.tuning_flickdecay;
        }
    }

    private int hintFor(Prefs.TouchTunable tunable) {
        switch (tunable.key) {
            case "panspeed":    return R.string.tuning_panspeed_hint;
            case "longpress":   return R.string.tuning_longpress_hint;
            case "dragslop":    return R.string.tuning_dragslop_hint;
            case "commitpan":   return R.string.tuning_commitpan_hint;
            case "commitpinch": return R.string.tuning_commitpinch_hint;
            case "committwist": return R.string.tuning_committwist_hint;
            default:            return R.string.tuning_flickdecay_hint;
        }
    }
}
