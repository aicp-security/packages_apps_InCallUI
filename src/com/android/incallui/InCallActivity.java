/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.incallui;

import com.android.services.telephony.common.Call;
import com.android.services.telephony.common.Call.State;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.FragmentTransaction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowManagerPolicy;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.Toast;

/**
 * Phone app "in call" screen.
 */
public class InCallActivity extends Activity {

    public static final String SHOW_DIALPAD_EXTRA = "InCallActivity.show_dialpad";

    private static final int INVALID_RES_ID = -1;

    private CallButtonFragment mCallButtonFragment;
    private CallCardFragment mCallCardFragment;
    private AnswerFragment mAnswerFragment;
    private DialpadFragment mDialpadFragment;
    private ConferenceManagerFragment mConferenceManagerFragment;
    private boolean mIsForegroundActivity;
    private AlertDialog mDialog;

    /** Use to pass 'showDialpad' from {@link #onNewIntent} to {@link #onResume} */
    private boolean mShowDialpadRequested;
    private boolean mConferenceManagerShown;

    private boolean mUseFullScreenCallerPhoto;

    private ContentObserver mSettingsObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            updateSettings();
        }
    };

    private int[] mCoverWindowCoords = null;
    private BroadcastReceiver mLidStateChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (WindowManagerPolicy.ACTION_LID_STATE_CHANGED.equals(intent.getAction())) {
                boolean on = intent.getIntExtra(WindowManagerPolicy.EXTRA_LID_STATE,
                        WindowManagerPolicy.WindowManagerFuncs.LID_ABSENT)
                        == WindowManagerPolicy.WindowManagerFuncs.LID_CLOSED;
                showSmartCover(on);
            }
        }
    };

    @Override
    protected void onCreate(Bundle icicle) {
        Log.d(this, "onCreate()...  this = " + this);

        super.onCreate(icicle);

        mCoverWindowCoords = getResources().getIntArray(
                com.android.internal.R.array.config_smartCoverWindowCoords);
        if (mCoverWindowCoords != null && mCoverWindowCoords.length != 4) {
            // make sure there are exactly 4 dimensions provided, or ignore
            mCoverWindowCoords = null;
        }

        // set this flag so this activity will stay in front of the keyguard
        // Have the WindowManager filter out touch events that are "too fat".
        int flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_IGNORE_CHEEK_PRESSES;

        getWindow().addFlags(flags);

        requestWindowFeature(Window.FEATURE_NO_TITLE);

        // TODO(klp): Do we need to add this back when prox sensor is not available?
        // lp.inputFeatures |= WindowManager.LayoutParams.INPUT_FEATURE_DISABLE_USER_ACTIVITY;

        // Inflate everything in incall_screen.xml and add it to the screen.
        setContentView(R.layout.incall_screen);

        initializeInCall();

        getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.INCOMING_CALL_STYLE),
                false, mSettingsObserver);
        updateSettings();

        Log.d(this, "onCreate(): exit");
    }

    @Override
    protected void onStart() {
        Log.d(this, "onStart()...");
        super.onStart();

        // setting activity should be last thing in setup process
        InCallPresenter.getInstance().setActivity(this);
    }

    @Override
    protected void onResume() {
        Log.i(this, "onResume()...");
        super.onResume();

        mIsForegroundActivity = true;
        InCallPresenter.getInstance().onUiShowing(true);

        if (mShowDialpadRequested) {
            mCallButtonFragment.displayDialpad(true);
            mShowDialpadRequested = false;
        }
        updateSystemBarTranslucency();
        if (mCoverWindowCoords != null) {
            registerReceiver(mLidStateChangeReceiver, new IntentFilter(
                    WindowManagerPolicy.ACTION_LID_STATE_CHANGED));
        }
    }

    // onPause is guaranteed to be called when the InCallActivity goes
    // in the background.
    @Override
    protected void onPause() {
        Log.d(this, "onPause()...");
        super.onPause();
        if (mCoverWindowCoords != null) {
            unregisterReceiver(mLidStateChangeReceiver);
        }

        mIsForegroundActivity = false;

        mDialpadFragment.onDialerKeyUp(null);

        InCallPresenter.getInstance().onUiShowing(false);
    }

    @Override
    protected void onStop() {
        Log.d(this, "onStop()...");
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        Log.d(this, "onDestroy()...  this = " + this);

        InCallPresenter.getInstance().setActivity(null);

        super.onDestroy();
    }

    /**
     * Returns true when theActivity is in foreground (between onResume and onPause).
     */
    /* package */ boolean isForegroundActivity() {
        return mIsForegroundActivity;
    }

    private boolean hasPendingErrorDialog() {
        return mDialog != null;
    }
    /**
     * Dismisses the in-call screen.
     *
     * We never *really* finish() the InCallActivity, since we don't want to get destroyed and then
     * have to be re-created from scratch for the next call.  Instead, we just move ourselves to the
     * back of the activity stack.
     *
     * This also means that we'll no longer be reachable via the BACK button (since moveTaskToBack()
     * puts us behind the Home app, but the home app doesn't allow the BACK key to move you any
     * farther down in the history stack.)
     *
     * (Since the Phone app itself is never killed, this basically means that we'll keep a single
     * InCallActivity instance around for the entire uptime of the device.  This noticeably improves
     * the UI responsiveness for incoming calls.)
     */
    @Override
    public void finish() {
        Log.i(this, "finish().  Dialog showing: " + (mDialog != null));

        // skip finish if we are still showing a dialog.
        if (!hasPendingErrorDialog() && !mAnswerFragment.hasPendingDialogs()) {
            super.finish();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        Log.d(this, "onNewIntent: intent = " + intent);

        // We're being re-launched with a new Intent.  Since it's possible for a
        // single InCallActivity instance to persist indefinitely (even if we
        // finish() ourselves), this sequence can potentially happen any time
        // the InCallActivity needs to be displayed.

        // Stash away the new intent so that we can get it in the future
        // by calling getIntent().  (Otherwise getIntent() will return the
        // original Intent from when we first got created!)
        setIntent(intent);

        // Activities are always paused before receiving a new intent, so
        // we can count on our onResume() method being called next.

        // Just like in onCreate(), handle the intent.
        internalResolveIntent(intent);
    }

    @Override
    public void onBackPressed() {
        Log.d(this, "onBackPressed()...");

        if (mAnswerFragment.isVisible()) {
            // The Back key, just like the Home key, is always disabled
            // while an incoming call is ringing.  (The user *must* either
            // answer or reject the call before leaving the incoming-call
            // screen.)
            Log.d(this, "BACK key while ringing: ignored");

            // And consume this event; *don't* call super.onBackPressed().
            return;
        }

        // BACK is also used to exit out of any "special modes" of the
        // in-call UI:

        if (mDialpadFragment.isVisible()) {
            mCallButtonFragment.displayDialpad(false);  // do the "closing" animation
            return;
        } else if (mConferenceManagerFragment.isVisible()) {
            mConferenceManagerFragment.setVisible(false);
            mConferenceManagerShown = false;
            updateSystemBarTranslucency();
            return;
        }

        // Always disable the Back key while an incoming call is ringing
        final Call call = CallList.getInstance().getIncomingCall();
        if (call != null) {
            Log.d(this, "Consume Back press for an inconing call");
            return;
        }

        // Nothing special to do.  Fall back to the default behavior.
        super.onBackPressed();
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // push input to the dialer.
        if ((mDialpadFragment.isVisible()) && (mDialpadFragment.onDialerKeyUp(event))){
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_CALL) {
            // Always consume CALL to be sure the PhoneWindow won't do anything with it
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_CALL:
                boolean handled = InCallPresenter.getInstance().handleCallKey();
                if (!handled) {
                    Log.w(this, "InCallActivity should always handle KEYCODE_CALL in onKeyDown");
                }
                // Always consume CALL to be sure the PhoneWindow won't do anything with it
                return true;

            // Note there's no KeyEvent.KEYCODE_ENDCALL case here.
            // The standard system-wide handling of the ENDCALL key
            // (see PhoneWindowManager's handling of KEYCODE_ENDCALL)
            // already implements exactly what the UI spec wants,
            // namely (1) "hang up" if there's a current active call,
            // or (2) "don't answer" if there's a current ringing call.

            case KeyEvent.KEYCODE_CAMERA:
                // Disable the CAMERA button while in-call since it's too
                // easy to press accidentally.
                return true;

            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                // Ringer silencing handled by PhoneWindowManager.
                break;

            case KeyEvent.KEYCODE_MUTE:
                // toggle mute
                CallCommandClient.getInstance().mute(!AudioModeProvider.getInstance().getMute());
                return true;

            // Various testing/debugging features, enabled ONLY when VERBOSE == true.
            case KeyEvent.KEYCODE_SLASH:
                if (Log.VERBOSE) {
                    Log.v(this, "----------- InCallActivity View dump --------------");
                    // Dump starting from the top-level view of the entire activity:
                    Window w = this.getWindow();
                    View decorView = w.getDecorView();
                    decorView.debug();
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_EQUALS:
                // TODO: Dump phone state?
                break;
        }

        if (event.getRepeatCount() == 0 && handleDialerKeyDown(keyCode, event)) {
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    private boolean handleDialerKeyDown(int keyCode, KeyEvent event) {
        Log.v(this, "handleDialerKeyDown: keyCode " + keyCode + ", event " + event + "...");

        // As soon as the user starts typing valid dialable keys on the
        // keyboard (presumably to type DTMF tones) we start passing the
        // key events to the DTMFDialer's onDialerKeyDown.
        if (mDialpadFragment.isVisible()) {
            return mDialpadFragment.onDialerKeyDown(event);

            // TODO: If the dialpad isn't currently visible, maybe
            // consider automatically bringing it up right now?
            // (Just to make sure the user sees the digits widget...)
            // But this probably isn't too critical since it's awkward to
            // use the hard keyboard while in-call in the first place,
            // especially now that the in-call UI is portrait-only...
        }

        return false;
    }

    @Override
    public void onConfigurationChanged(Configuration config) {
        InCallPresenter.getInstance().getProximitySensor().onConfigurationChanged(config);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) { // On touch.
        if (InCallPresenter.getInstance().getProximitySensor().isScreenOffByProximity())
            return true; 

        return super.dispatchTouchEvent(event);
    }

    public CallButtonFragment getCallButtonFragment() {
        return mCallButtonFragment;
    }

    private void internalResolveIntent(Intent intent) {
        final String action = intent.getAction();

        if (action.equals(intent.ACTION_MAIN)) {
            // This action is the normal way to bring up the in-call UI.
            //
            // But we do check here for one extra that can come along with the
            // ACTION_MAIN intent:

            if (intent.hasExtra(SHOW_DIALPAD_EXTRA)) {
                // SHOW_DIALPAD_EXTRA can be used here to specify whether the DTMF
                // dialpad should be initially visible.  If the extra isn't
                // present at all, we just leave the dialpad in its previous state.

                final boolean showDialpad = intent.getBooleanExtra(SHOW_DIALPAD_EXTRA, false);
                Log.d(this, "- internalResolveIntent: SHOW_DIALPAD_EXTRA: " + showDialpad);

                relaunchedFromDialer(showDialpad);
            }

            return;
        }
    }

    private void relaunchedFromDialer(boolean showDialpad) {
        mShowDialpadRequested = showDialpad;

        if (mShowDialpadRequested) {
            // If there's only one line in use, AND it's on hold, then we're sure the user
            // wants to use the dialpad toward the exact line, so un-hold the holding line.
            final Call call = CallList.getInstance().getActiveOrBackgroundCall();
            if (call != null && call.getState() == State.ONHOLD) {
                CallCommandClient.getInstance().hold(call.getCallId(), false);
            }
        }
    }

    private void initializeInCall() {
        if (mCallButtonFragment == null) {
            mCallButtonFragment = (CallButtonFragment) getFragmentManager()
                    .findFragmentById(R.id.callButtonFragment);
            mCallButtonFragment.setEnabled(false, false);
        }

        if (mCallCardFragment == null) {
            mCallCardFragment = (CallCardFragment) getFragmentManager()
                    .findFragmentById(R.id.callCardFragment);
        }

        if (mAnswerFragment == null) {
            mAnswerFragment = (AnswerFragment) getFragmentManager()
                    .findFragmentById(R.id.answerFragment);
        }

        if (mDialpadFragment == null) {
            mDialpadFragment = (DialpadFragment) getFragmentManager()
                    .findFragmentById(R.id.dialpadFragment);
            getFragmentManager().beginTransaction().hide(mDialpadFragment).commit();
        }

        if (mConferenceManagerFragment == null) {
            mConferenceManagerFragment = (ConferenceManagerFragment) getFragmentManager()
                    .findFragmentById(R.id.conferenceManagerFragment);
            mConferenceManagerFragment.getView().setVisibility(View.INVISIBLE);
        }
    }

    protected void showSmartCover(boolean show) {
        mAnswerFragment.getView().setVisibility(show ? View.INVISIBLE : View.VISIBLE);

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        final int windowHeight = mCoverWindowCoords[2] - mCoverWindowCoords[0];
        final int windowWidth = metrics.widthPixels - mCoverWindowCoords[1]
                - (metrics.widthPixels - mCoverWindowCoords[3]);

        final int stretch = ViewGroup.LayoutParams.MATCH_PARENT;

        View main = findViewById(R.id.main);
        View callCard = mCallCardFragment.getView();
        if (show) {
            // clear bg color
            main.setBackground(null);

            // center
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(windowWidth, stretch);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            main.setLayoutParams(lp);

            // adjust callcard height
            ViewGroup.LayoutParams params = callCard.getLayoutParams();
            params.height = windowHeight;

            callCard.setSystemUiVisibility(callCard.getSystemUiVisibility()
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

            // disable touches
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        } else {
            // reset default parameters
            main.setBackgroundColor(R.color.incall_button_background);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(stretch, stretch);
            main.setLayoutParams(lp);

            ViewGroup.LayoutParams params = mCallCardFragment.getView().getLayoutParams();
            params.height = stretch;

            callCard.setSystemUiVisibility(callCard.getSystemUiVisibility()
                    & ~View.SYSTEM_UI_FLAG_FULLSCREEN & ~View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        }
        callCard.invalidate();
        main.requestLayout();
    }

    private void toast(String text) {
        final Toast toast = Toast.makeText(this, text, Toast.LENGTH_SHORT);

        toast.show();
    }

    /**
     * Simulates a user click to hide the dialpad. This will update the UI to show the call card,
     * update the checked state of the dialpad button, and update the proximity sensor state.
     */
    public void hideDialpadForDisconnect() {
        mCallButtonFragment.displayDialpad(false);
    }

    public void dismissKeyguard(boolean dismiss) {
        if (dismiss) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
    }

    public void displayDialpad(boolean showDialpad) {
        final FragmentTransaction ft = getFragmentManager().beginTransaction();
        if (showDialpad) {
            ft.setCustomAnimations(R.anim.incall_dialpad_slide_in, 0);
            ft.show(mDialpadFragment);
        } else {
            ft.setCustomAnimations(0, R.anim.incall_dialpad_slide_out);
            ft.hide(mDialpadFragment);
        }
        ft.commitAllowingStateLoss();

        InCallPresenter.getInstance().getProximitySensor().onDialpadVisible(showDialpad);
    }

    public boolean isDialpadVisible() {
        return mDialpadFragment.isVisible();
    }

    public void displayManageConferencePanel(boolean showPanel) {
        if (showPanel) {
            mConferenceManagerFragment.setVisible(true);
            mConferenceManagerShown = true;
            updateSystemBarTranslucency();
        }
    }

    public void onManageConferenceDoneClicked() {
        if (mConferenceManagerShown && !mConferenceManagerFragment.isVisible()) {
            mConferenceManagerShown = false;
            updateSystemBarTranslucency();
        }
    }

    public void updateSystemBarTranslucency() {
        int flags = 0;
        final Window window = getWindow();
        final InCallPresenter.InCallState inCallState =
                InCallPresenter.getInstance().getInCallState();

        if (!mConferenceManagerShown) {
            flags |= WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS;
        }
        if (mUseFullScreenCallerPhoto && inCallState == InCallPresenter.InCallState.INCOMING) {
            flags |= WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION;
        }

        window.setFlags(flags, WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS |
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        window.getDecorView().requestFitSystemWindows();
    }

    public void showPostCharWaitDialog(int callId, String chars) {
        final PostCharDialogFragment fragment = new PostCharDialogFragment(callId,  chars);
        fragment.show(getFragmentManager(), "postCharWait");
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        if (mCallCardFragment != null) {
            mCallCardFragment.dispatchPopulateAccessibilityEvent(event);
        }
        return super.dispatchPopulateAccessibilityEvent(event);
    }

    public void maybeShowErrorDialogOnDisconnect(Call.DisconnectCause cause) {
        Log.d(this, "maybeShowErrorDialogOnDisconnect");

        if (!isFinishing()) {
            final int resId = getResIdForDisconnectCause(cause);
            if (resId != INVALID_RES_ID) {
                showErrorDialog(resId);
            }
        }
    }

    public void dismissPendingDialogs() {
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }
        mAnswerFragment.dismissPendingDialogues();
    }

    /**
     * Utility function to bring up a generic "error" dialog.
     */
    private void showErrorDialog(int resId) {
        final CharSequence msg = getResources().getText(resId);
        Log.i(this, "Show Dialog: " + msg);

        dismissPendingDialogs();

        mDialog = new AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton(R.string.ok, new OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    onDialogDismissed();
                }})
            .setOnCancelListener(new OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialog) {
                    onDialogDismissed();
                }})
            .create();

        mDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        mDialog.show();
    }

    private int getResIdForDisconnectCause(Call.DisconnectCause cause) {
        int resId = INVALID_RES_ID;

        if (cause == Call.DisconnectCause.CALL_BARRED) {
            resId = R.string.callFailed_cb_enabled;
        } else if (cause == Call.DisconnectCause.FDN_BLOCKED) {
            resId = R.string.callFailed_fdn_only;
        } else if (cause == Call.DisconnectCause.CS_RESTRICTED) {
            resId = R.string.callFailed_dsac_restricted;
        } else if (cause == Call.DisconnectCause.CS_RESTRICTED_EMERGENCY) {
            resId = R.string.callFailed_dsac_restricted_emergency;
        } else if (cause == Call.DisconnectCause.CS_RESTRICTED_NORMAL) {
            resId = R.string.callFailed_dsac_restricted_normal;
        }

        return resId;
    }

    private void onDialogDismissed() {
        mDialog = null;
        InCallPresenter.getInstance().onDismissDialog();
    }

    private void updateSettings() {
        int incomingCallStyle = Settings.System.getInt(getContentResolver(),
                Settings.System.INCOMING_CALL_STYLE,
                Settings.System.INCOMING_CALL_STYLE_FULLSCREEN_PHOTO);
        mUseFullScreenCallerPhoto =
                incomingCallStyle == Settings.System.INCOMING_CALL_STYLE_FULLSCREEN_PHOTO;
        mCallButtonFragment.setHideMode(mUseFullScreenCallerPhoto ? View.GONE : View.INVISIBLE);
        mCallButtonFragment.getPresenter().setShowButtonsIfIdle(!mUseFullScreenCallerPhoto);
        updateSystemBarTranslucency();
    }

}
